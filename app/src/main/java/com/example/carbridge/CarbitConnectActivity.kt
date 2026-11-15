package com.example.carbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.*
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.carbridge.adb.AdbConnectionMode
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.capture.CaptureService
import java.net.Inet4Address
import java.util.concurrent.Executors

class CarbitConnectActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var discovery:NsdManager.DiscoveryListener?=null
    private var multicast:WifiManager.MulticastLock?=null
    private val queue=java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving=false
    private var epoch=0
    private var connecting=false
    private var connectEpoch=0
    private val setupTrace=java.util.ArrayDeque<String>()
    private var checkTimeout:Runnable?=null
    private val nsd by lazy { getSystemService(NsdManager::class.java) }
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(c:Context,i:Intent){showStatus(i.getStringExtra(CaptureService.EXTRA_STATUS).orEmpty())}
    }
    private fun showStatus(message:String) {
        if(message.isBlank())return
        status.text=message
        val item="${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(java.util.Date())} ${message.take(400)}"
        if(setupTrace.peekLast()!=item)setupTrace.addLast(item)
        while(setupTrace.size>24)setupTrace.removeFirst()
        SessionDiagnostics.event("Carbit setup: ${message.take(300)}")
    }
    private fun finishCheck() {
        connecting=false
        checkTimeout?.let{main.removeCallbacks(it)};checkTimeout=null
        connectButton.isEnabled=true;connectButton.text="Подключить виртуальный дисплей"
    }
    override fun onCreate(saved:Bundle?){
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
        WindowCompat.setDecorFitsSystemWindows(window,false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root=LinearLayout(this).apply{orientation=1}
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            status.maxLines=if(insets.isVisible(WindowInsetsCompat.Type.ime()))2 else 4
            view.setPadding(dp(12)+bars.left,dp(8)+bars.top,dp(12)+bars.right,dp(8)+maxOf(bars.bottom,keyboard))
            insets
        }
        root.addView(TextView(this).apply{text="CarbitLink • Wi-Fi";textSize=18f})
        status=TextView(this).apply{
            textSize=16f;maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(8),0,dp(8));setTextIsSelectable(true)
        }
        root.addView(status)
        val box=LinearLayout(this).apply{orientation=1;setPadding(0,0,0,dp(20))}
        val scroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=true;addView(box)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(this).apply{orientation=0;setPadding(0,dp(4),0,0)}
        footer.addView(Button(this).apply{
            text="Журнал";textSize=16f
            setOnClickListener{
                val info=packageManager.getPackageInfo(packageName,0)
                val config=BridgeSettings.read(this@CarbitConnectActivity)
                val report="CarBridge ${info.versionName} • CarbitLink: экран подключения\nСтатус: ${status.text}\n"+
                    "Приёмник=${host.text}:${port.text}; режим ADB=${config.connectionMode}; офлайн-порт=${config.offlinePort}\n"+
                    setupTrace.joinToString("\n")
                SessionDiagnostics.shareLatest(this@CarbitConnectActivity,report)
            }
        },LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(Button(this).apply{
            text="Стоп";textSize=16f
            setOnClickListener{
                connectEpoch++;finishCheck();stopDiscovery()
                startService(Intent(this@CarbitConnectActivity,CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                showStatus("Подключение остановлено")
            }
        },LinearLayout.LayoutParams(0,-2,1f))
        root.addView(footer)
        setContentView(root);ViewCompat.requestApplyInsets(root)
        fun label(s:String){box.addView(TextView(this).apply{text=s;textSize=17f})}
        fun button(s:String, action:()->Unit):Button = Button(this).apply{
            text=s;setOnClickListener{action()};box.addView(this)
        }
        button("⚙ Настройки CarBridge"){ConnectionScreens.settings(this)}
        label("Используется виртуальный дисплей CarBridge. Подключите Wi-Fi ГУ, отключите VPN и остановите Carbit Probe и штатный CarbitLink на телефоне. На ГУ откройте CarbitLink.")
        val prefs=getSharedPreferences("carbit_connection",0)
        host=EditText(this).apply{hint="IPv4 ГУ";setSingleLine();setText(saved?.getString("host") ?: prefs.getString("host",""));inputType=3};box.addView(host)
        port=EditText(this).apply{hint="Порт";setSingleLine();setText(saved?.getString("port") ?: prefs.getInt("port",10930).toString());inputType=2};box.addView(port)
        button("Найти ГУ"){discover()}
        connectButton=button("Подключить виртуальный дисплей"){connect()}
        box.addView(CheckBox(this).apply {
            text="Сетевой звук CarbitLink · эксперимент"
            isChecked=prefs.getBoolean("audio",true)
            setOnCheckedChangeListener{_,checked->
                prefs.edit().putBoolean("audio",checked).apply()
                startService(Intent(this@CarbitConnectActivity,CaptureService::class.java).setAction(CaptureService.ACTION_AUDIO_MODE))
            }
        })
        button("Калибровать касания на планшете / ГУ"){
            startService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_CARBIT_CALIBRATE))
        }
        button("Восстановить звук без переподключения видео"){
            startService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_RESTART_AUDIO))
        }
        button("Отменить калибровку"){
            startService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_CARBIT_CALIBRATE_CANCEL))
        }
        button("Сбросить калибровку этого подключения"){
            startService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_CARBIT_CALIBRATE_RESET))
        }
        label("Сетевой звук включается переключателем выше и не зависит от настройки USB-звука CarLife. Звонки остаются через штатный Bluetooth. USB CarbitLink пока не реализован. Калибровка проводится после подключения: нажмите пять меток на экране приёмника.")
        saved?.getStringArrayList("setup_trace")?.forEach{setupTrace.addLast(it)}
        status.text=saved?.getString("status") ?: "Найдите приёмник или укажите его IP и нажмите «Подключить виртуальный дисплей»"
        androidx.core.content.ContextCompat.registerReceiver(this,receiver,IntentFilter(CaptureService.ACTION_DISPLAY_STATUS),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun connect(){
        if(connecting){showStatus("Проверка ADB ещё выполняется…");return}
        val address=host.text.toString().trim()
        val number=port.text.toString().toIntOrNull()
        if(!address.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))||address.split('.').any{it.toInt()>255}||number==null||number !in 1..65535){
            showStatus("Укажите IPv4 и порт ГУ");return
        }
        val config=BridgeSettings.read(this)
        if(config.connectionMode!=BridgeSettings.MODE_LOCAL_TCP||config.offlinePort !in 1..65535){
            showStatus("Сначала подготовьте офлайн ADB в основном меню CarBridge");return
        }
        stopDiscovery();connecting=true
        val token=++connectEpoch
        connectButton.isEnabled=false;connectButton.text="Проверяю ADB…"
        showStatus("Проверяю локальный ADB… Не более 15 секунд.")
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(host.windowToken,0)
        host.clearFocus();port.clearFocus()
        checkTimeout=Runnable{
            if(connecting&&connectEpoch==token){
                connectEpoch++;finishCheck()
                showStatus("Проверка ADB не завершилась вовремя. Повторите подключение или отправьте журнал кнопкой внизу.")
            }
        }.also{main.postDelayed(it,15000L)}
        getSharedPreferences("carbit_connection",0).edit().putString("host",address).putInt("port",number).apply()
        worker.execute{
            val result=runCatching{AdbSession.connect(applicationContext,"127.0.0.1",config.offlinePort,AdbConnectionMode.LOCAL_TCP,10_000L)}
            main.post{
                if(isDestroyed||isFinishing||connectEpoch!=token)return@post
                finishCheck()
                if(result.getOrDefault(false)){
                    runCatching{
                        startForegroundService(Intent(this,CaptureService::class.java)
                            .putExtra(CaptureService.EXTRA_MODE,CaptureService.MODE_ADB)
                            .putExtra(CaptureService.EXTRA_TRANSPORT,CaptureService.TRANSPORT_CARBIT)
                            .putExtra(CaptureService.EXTRA_CARBIT_HOST,address)
                            .putExtra(CaptureService.EXTRA_CARBIT_PORT,number)
                            .putExtra(CaptureService.EXTRA_PACKAGE,config.packageNameLaunch)
                            .putExtra(CaptureService.EXTRA_SCREEN_OFF,config.screenOff))
                    }.onSuccess{showStatus("ADB подключён. Запускаю CarbitLink, ожидаю ответ приёмника…")}
                     .onFailure{showStatus("Не удалось запустить: "+it.message)}
                }else showStatus("Локальный ADB недоступен: "+(result.exceptionOrNull()?.message?:AdbSession.lastError))
            }
        }
    }
    private fun discover(){
        stopDiscovery()
        val token=epoch
        multicast=getSystemService(WifiManager::class.java).createMulticastLock("CarBridgeCarbit").also{it.setReferenceCounted(false);it.acquire()}
        val d=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(t:String){main.post{if(token==epoch)showStatus("Поиск ГУ…")}}
            override fun onServiceFound(info:NsdServiceInfo){main.post{if(token==epoch){queue.add(info);resolve(token)}}}
            override fun onServiceLost(info:NsdServiceInfo){}
            override fun onDiscoveryStopped(t:String){}
            override fun onStartDiscoveryFailed(t:String,e:Int){main.post{if(token==epoch){showStatus("Ошибка поиска: "+e);stopDiscovery()}}}
            override fun onStopDiscoveryFailed(t:String,e:Int){}
        }
        discovery=d
        runCatching{nsd.discoverServices("_EasyConn._tcp.",NsdManager.PROTOCOL_DNS_SD,d)}
            .onFailure{showStatus("Поиск недоступен: "+it.message);stopDiscovery()}
        main.postDelayed({if(token==epoch){stopDiscovery();showStatus("Поиск завершён. Можно ввести IP вручную.")}},20_000)
    }
    @Suppress("DEPRECATION")
    private fun resolve(token:Int){
        if(resolving||queue.isEmpty()||token!=epoch)return
        resolving=true
        nsd.resolveService(queue.removeFirst(),object:NsdManager.ResolveListener{
            override fun onResolveFailed(i:NsdServiceInfo,e:Int){main.post{if(token==epoch){resolving=false;resolve(token)}}}
            override fun onServiceResolved(i:NsdServiceInfo){main.post{
                if(token!=epoch||isDestroyed)return@post
                resolving=false
                if(i.host is Inet4Address)AlertDialog.Builder(this@CarbitConnectActivity).setTitle(i.serviceName)
                    .setMessage(i.host?.hostAddress+":"+i.port).setPositiveButton("Выбрать"){_,_->
                        host.setText(i.host?.hostAddress.orEmpty());port.setText(i.port.toString())
                        showStatus("Приёмник выбран: ${i.host?.hostAddress}:${i.port}. Нажмите «Подключить виртуальный дисплей».")
                    }
                    .setNegativeButton("Пропустить",null).show()
                resolve(token)
            }}
        })
    }
    private fun stopDiscovery(){
        epoch++;queue.clear();resolving=false
        discovery?.let{runCatching{nsd.stopServiceDiscovery(it)}};discovery=null
        multicast?.let{if(it.isHeld)it.release()};multicast=null
    }
    override fun onDestroy(){
        connectEpoch++
        stopDiscovery();worker.shutdownNow();main.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver);super.onDestroy()
    }
    override fun onSaveInstanceState(out:Bundle){
        out.putString("host",host.text.toString());out.putString("port",port.text.toString())
        out.putString("status",if(connecting)"Проверка прервана при пересоздании экрана. Повторите подключение." else status.text.toString())
        out.putStringArrayList("setup_trace",ArrayList(setupTrace))
        super.onSaveInstanceState(out)
    }
}
