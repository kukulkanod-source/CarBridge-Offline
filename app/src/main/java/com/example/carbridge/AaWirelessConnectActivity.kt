package com.example.carbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import com.example.carbridge.adb.AdbConnectionMode
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.capture.CaptureService
import java.util.concurrent.Executors

/** Explicit adapter selection only; the stable USB path never starts AA discovery. */
class AaWirelessConnectActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var checking = false
    private var epoch = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            status.text = intent.getStringExtra(CaptureService.EXTRA_STATUS).orEmpty()
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val body = LinearLayout(this).apply { orientation = 1; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(body) })
        fun text(s: String) { body.addView(TextView(this).apply { text = s; textSize = 17f; setPadding(0, 8, 0, 8) }) }
        fun button(s: String, action: () -> Unit) { body.addView(Button(this).apply { text = s; setOnClickListener { action() } }) }
        text("Адаптер CarPlay → Android Auto · эксперимент")
        button("⚙ Настройки CarBridge") { ConnectionScreens.settings(this) }
        button("Сертификат телефона для AA") {
            val prefs=getSharedPreferences("aa_identity",0)
            android.app.AlertDialog.Builder(this).setTitle("Идентификация телефона AA")
                .setMessage("Сейчас: ${if(prefs.getString("android_alias",null).isNullOrBlank()) "собственный сертификат CarBridge" else "выбранный сертификат Android"}. Некоторые адаптеры отклоняют самоподписанную идентификацию. Можно выбрать уже установленный RSA-сертификат с закрытым ключом. Его совместимость с адаптером не гарантирована. Выбор применяется при следующем подключении.")
                .setPositiveButton("Выбрать в Android") { _,_->
                    android.security.KeyChain.choosePrivateKeyAlias(this,{ alias ->
                        runOnUiThread {
                            if(!alias.isNullOrBlank()) {
                                prefs.edit().putString("android_alias",alias).apply()
                                if(!isDestroyed)status.text="Сертификат Android выбран. Остановите текущий сеанс и подключитесь заново."
                            }
                        }
                    },arrayOf("RSA"),null,null,-1,prefs.getString("android_alias",null))
                }.setNeutralButton("Собственный") { _,_->
                    prefs.edit().remove("android_alias").apply()
                    status.text="Выбран прежний собственный сертификат CarBridge. Применится при новом подключении."
                }.setNegativeButton("Отмена",null).show()
        }
        text("Подключите адаптер к USB CarPlay автомобиля. В настройках Bluetooth телефона выполните сопряжение именно с адаптером. Включите Wi-Fi и выберите адаптер ниже. Подтвердите запрос Android на подключение к его Wi-Fi, если появится.")
        text("На ГУ откройте CarPlay. Показывается рабочий стол CarBridge. Встроенный Android Auto на ГУ не нужен. Адрес, порт и пароль вручную вводить не требуется.")
        button("Настройки Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("Настройки Wi-Fi") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        button("Обновить список адаптеров") { refresh() }
        devices = LinearLayout(this).apply { orientation = 1 }; body.addView(devices)
        button("Остановить подключение CarBridge") {
            epoch++; checking = false
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            status.text = "Подключение остановлено"
        }
        button("Поделиться журналом") { SessionDiagnostics.shareLatest(this) }
        text("Если адаптер уже занят другим приложением или телефоном, сначала отключите то соединение. Для виртуального дисплея требуется подготовленный офлайн ADB. Звук использует настройку «Звук через USB» и требует PCM 48 кГц / 16 бит / стерео от адаптера. Звонки пока остаются через штатный Bluetooth.")
        status = TextView(this); body.addView(status)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(CaptureService.ACTION_DISPLAY_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }
    private fun permissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    @SuppressLint("MissingPermission")
    private fun refresh() {
        val missing = permissions()
        if (missing.isNotEmpty()) { requestPermissions(missing, 701); return }
        devices.removeAllViews()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { status.text = "Включите Bluetooth и обновите список"; return }
        val bonded = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        for (device in bonded) devices.addView(Button(this).apply {
            text = "Подключить: ${device.name ?: "Bluetooth-устройство"}\n${device.address}"
            setOnClickListener { connect(device.address) }
        })
        status.text = if (bonded.isEmpty()) "Нет сопряжённых устройств. Добавьте адаптер в настройках Bluetooth."
            else "Выберите ваш адаптер. Другие устройства Bluetooth в списке могут не поддерживать AA."
    }
    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, p, grants)
        if (code == 701) {
            if (permissions().isEmpty()) refresh()
            else status.text = "Для соединения нужны разрешения Bluetooth и Wi-Fi / местоположения. Разрешите их в настройках приложения."
        }
    }
    private fun connect(address: String) {
        if (checking) return
        if (permissions().isNotEmpty()) { refresh(); return }
        val cfg = BridgeSettings.read(this)
        if (cfg.connectionMode != BridgeSettings.MODE_LOCAL_TCP || cfg.offlinePort !in 1..65535) {
            status.text = "Сначала подготовьте офлайн ADB в основном меню CarBridge"; return
        }
        checking = true; val token = ++epoch
        status.text = "Проверяю локальный ADB…"
        worker.execute {
            val result = runCatching { AdbSession.connect(applicationContext, "127.0.0.1", cfg.offlinePort, AdbConnectionMode.LOCAL_TCP, 10_000L) }
            main.post {
                if (isDestroyed || isFinishing || token != epoch) return@post
                checking = false
                if (!result.getOrDefault(false)) { status.text = "Локальный ADB недоступен: ${result.exceptionOrNull()?.message ?: AdbSession.lastError}"; return@post }
                runCatching {
                    startForegroundService(Intent(this, CaptureService::class.java)
                        .putExtra(CaptureService.EXTRA_MODE, CaptureService.MODE_ADB)
                        .putExtra(CaptureService.EXTRA_TRANSPORT, CaptureService.TRANSPORT_AA_WIRELESS)
                        .putExtra(CaptureService.EXTRA_AA_ADAPTER, address)
                        .putExtra(CaptureService.EXTRA_PACKAGE, cfg.packageNameLaunch)
                        .putExtra(CaptureService.EXTRA_SCREEN_OFF, cfg.screenOff))
                }.onSuccess { status.text = "Подключаюсь к адаптеру. Не закрывайте экран до подтверждения Wi-Fi." }
                    .onFailure { status.text = "Не удалось запустить: ${it.message}" }
            }
        }
    }
    override fun onDestroy() {
        epoch++; worker.shutdownNow(); main.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver); super.onDestroy()
    }
}
