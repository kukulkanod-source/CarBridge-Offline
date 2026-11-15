package com.example.carbridge

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.view.View
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.adb.AdbConnectionMode
import com.example.carbridge.capture.CaptureService
import com.example.carbridge.capture.AppDisplayState
import com.example.carbridge.scrcpy.SystemDensityCommand
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var selectedApp: TextView
    private lateinit var adbStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var wifiConnectButton: Button
    private lateinit var dockModeButton: Button
    private lateinit var scaleButton: Button
    private lateinit var logicalDisplayButton: Button
    private lateinit var videoProfileButton: Button
    private lateinit var mediaAccessButton: Button
    private lateinit var overlayPermissionStatus: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var connecting = false
    private var waitingUsbPermission = false
    private var pendingUsbAfterConnect = false
    private var pendingAutoUsbConnect = false
    private var resumed = false
    private var lastAutoUsbConnectAt = 0L
    private var connectGeneration = 0
    private var wifiStartAfterPermission = false
    private val wizard by lazy { ConnectionWizard.get(applicationContext) }
    private lateinit var wizardView: ConnectionWizardView
    private lateinit var settingsView: ScrollView
    private lateinit var protocolPicker: ConnectionPickerView
    private lateinit var connectionFrame: android.widget.FrameLayout
    private var pendingProtocol: String? = null
    private var carLifeAttemptAt = 0L
    private lateinit var settingsTab: Button
    private lateinit var connectionTab: Button
    private var selectedTab = 0
    private val wizardMain = Handler(Looper.getMainLooper())
    private var environment = ""
    private val wizardPoll = object : Runnable {
        override fun run() {
            if (!resumed) return
            returnAfterCarLifeAttempt()
            if (selectedTab == 0 && !wizard.current().busy && !wizard.awaitingHeadUnit) {
                if (AppDisplayState.displayId > 0) {
                    if (!wizard.current().connected) wizard.connected()
                } else if(!CaptureService.hasActiveSession) {
                    val signature = wizard.environmentSignature()
                    if (signature != environment || wizard.current().connected) { environment = signature; wizard.check() }
                }
                maybeConnectFromWizard()
            }
            wizardMain.postDelayed(this, 1500)
        }
    }

    private val usbPermissionAction get() = "$packageName.USB_PERMISSION"
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction || !waitingUsbPermission) return
            waitingUsbPermission = false
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val accessory = manager.accessoryList?.firstOrNull()
            if (accessory != null && manager.hasPermission(accessory)) startConnection()
            else if (accessory == null) showStatus("USB-аксессуар отключился до выдачи разрешения. Подключите кабель и включите CarLife на ГУ.")
            else showStatus("Автоподключение остановлено: Android не выдал CarBridge разрешение на USB-аксессуар. Разрешите доступ в системном запросе и подключите заново.")
        }
    }
    private val displayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CaptureService.ACTION_DISPLAY_STATUS) {
                showStatus(intent.getStringExtra(CaptureService.EXTRA_STATUS) ?: return)
            } else if (intent.action == CaptureService.ACTION_DISPLAY_READY) {
                wizard.connected()
                updateNavigationMotion()
                showStatus("Отдельный дисплей ГУ готов. Можно пользоваться телефоном независимо.")
            }
            updateAdbStatus()
        }
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val navigationLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted=HuNavigationMotion.permitted(this)
            HuNavigationMotion.setEnabled(this,granted)
            updateNavigationMotion()
            showStatus(if(granted) "Дорога движется по скорости геолокации. Нужен работающий навигатор; без свежей скорости разметка неподвижна."
                else "Для скорости нужна точная геолокация. Разметка останется неподвижной.")
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (wifiStartAfterPermission) {
                wifiStartAfterPermission = false
                if (result.values.all { it } && hasWirelessPermissions()) connectWifiAfterPermission()
                else showStatus("Для CarLife по Bluetooth и Wi-Fi Direct разрешите CarBridge доступ к Bluetooth и ближайшим устройствам")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "CarBridge Offline"
        selectedTab = savedInstanceState?.getInt("selected_tab", 0) ?: 0
        pendingProtocol = savedInstanceState?.getString("pending_protocol")
        carLifeAttemptAt = savedInstanceState?.getLong("carlife_attempt_at",0L) ?: 0L
        wizard.phonePreparationOnly = pendingProtocol != "carlife"
        if (savedInstanceState == null && intent.getBooleanExtra(ConnectionScreens.EXTRA_SETTINGS, false)) selectedTab = 1
        connectButton = Button(this)
        wifiConnectButton = Button(this)
        pendingAutoUsbConnect = intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        waitingUsbPermission = savedInstanceState?.getBoolean("waiting_usb", false) ?: false
        pendingUsbAfterConnect = savedInstanceState?.getBoolean("pending_usb", false) ?: false
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String): TextView = TextView(this).apply {
            text = value
            setPadding(0, 8, 0, 12)
            layout.addView(this)
        }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            layout.addView(this)
        }
        text("Приложения запускаются на отдельном дисплее ГУ. Телефон остаётся свободным. " +
            "Для поездки без Wi-Fi сначала активируйте офлайн-подключение. Требуется Android 11+.")
        adbStatus = text("")
        button("Подробная настройка ADB") { startActivity(Intent(this, AdbSetupActivity::class.java)) }
        selectedApp = text("")
        button("Выбрать приложение для ГУ") { chooseApplication() }
        layout.addView(CheckBox(this).apply {
            text = "Гасить экран телефона во время сессии"
            isChecked = BridgeSettings.read(this@MainActivity).screenOff
            setOnCheckedChangeListener { _, checked ->
                BridgeSettings.setScreenOff(this@MainActivity, checked)
                sendCommand(Intent(this@MainActivity, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_SCREEN_POWER)
                    .putExtra(CaptureService.EXTRA_SCREEN_OFF, checked))
            }
        })
        button("Включить экран телефона") {
            sendCommand(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_PHONE_WAKE))
            showStatus("Запрос включения экрана отправлен. Если телефон заблокирован, разблокируйте его обычным способом.")
        }
        text("Экран погаснет после появления видео на ГУ. Кнопка «Включить экран телефона» позволяет вернуться " +
            "к телефону во время подключения. При остановке сессии экран включается автоматически.")
        logicalDisplayButton = button("") { chooseLogicalDisplayMode() }
        scaleButton = button("") { chooseControlsScale() }
        button("Настроить виджет Яндекс Погоды") { startActivity(Intent(this,WeatherWidgetSetupActivity::class.java)) }
        button("Движение дороги по скорости GPS") {
            AlertDialog.Builder(this).setTitle("Движение дороги")
                .setMessage("Сейчас: ${if(HuNavigationMotion.enabled(this)) "включено" else "выключено"}. CarBridge использует скорость из геолокации, которую уже получает навигатор. На стоянке и без свежей скорости разметка замирает. Нужна точная геолокация; отдельный постоянный запрос GPS не запускается.")
                .setPositiveButton("Включить") { _,_ ->
                    if(HuNavigationMotion.permitted(this)) {
                        HuNavigationMotion.setEnabled(this,true); updateNavigationMotion()
                        showStatus("Движение дороги включено. Откройте навигацию и начните маршрут.")
                    } else navigationLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                }.setNeutralButton("Выключить") { _,_ ->
                    HuNavigationMotion.setEnabled(this,false); updateNavigationMotion()
                    showStatus("Движение дороги выключено")
                }.setNegativeButton("Отмена",null).show()
        }
        dockModeButton = button("") {
            val current = BridgeSettings.read(this)
            AlertDialog.Builder(this).setTitle("Область приложения и панель")
                .setSingleChoiceItems(arrayOf("Постоянная панель — приложение над ней", "Полный экран — скрывающаяся панель"),
                    if (current.dockAutoHide) 1 else 0) { dialog, which ->
                    BridgeSettings.save(this, BridgeSettings.read(this).copy(dockAutoHide = which == 1))
                    refreshDisplaySettings()
                    showStatus(if (which == 1) "Выбран полный экран. Переподключите CarBridge: панель скроется через 6 секунд. Вернуть её — голубой язычок внизу."
                        else "Выбрана постоянная панель. Переподключите CarBridge: приложение займёт область над ней.")
                    dialog.dismiss()
                }.setNegativeButton("Отмена", null).show()
        }
        text("Два варианта окна выбираются кнопкой выше. Полноэкранный режим скрывает панель через 6 секунд; " +
            "возврат — голубой язычок снизу. Смена режима применяется после переподключения.")
        button("Диагностика Стрелки") {
            AlertDialog.Builder(this).setTitle("Стрелка на ГУ")
                .setMessage(HuMediaNotificationListener.diagnoseStrelka(this))
                .setPositiveButton("Понятно", null)
                .setNeutralButton("Доступ к уведомлениям") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.show()
        }
        layout.addView(CheckBox(this).apply {
            text = "Уменьшать приложение при открытии музыки (70/30)"
            isChecked = BridgeSettings.read(this@MainActivity).resizeForMusic
            setOnCheckedChangeListener { _, checked ->
                BridgeSettings.save(this@MainActivity, BridgeSettings.read(this@MainActivity).copy(resizeForMusic = checked))
                showStatus("Режим музыки сохранён. Переподключите CarBridge для применения.")
            }
        })
        layout.addView(CheckBox(this).apply {
            text = "Звук через USB CarLife — эксперимент"
            isChecked = BridgeSettings.read(this@MainActivity).usbAudio
            isEnabled = android.os.Build.VERSION.SDK_INT >= 31
            setOnCheckedChangeListener { _, checked ->
                BridgeSettings.save(this@MainActivity, BridgeSettings.read(this@MainActivity).copy(usbAudio = checked))
                sendCommand(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_AUDIO_MODE))
                showStatus(if (checked) "USB-звук включён. Подключите USB CarLife и запустите музыку. " +
                    "Звук перенаправляется с телефона в CarLife; если на ГУ тишина, выключите этот пункт." else
                    "USB-звук выключен. Используется обычный выход телефона или Bluetooth.")
            }
        })
        button("Восстановить звук без переподключения видео") {
            sendCommand(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_RESTART_AUDIO))
        }
        text("USB-звук: Android 12+, отдельный PCM-канал, только при воспроизведении. " +
            "Включение и выключение во время сессии не требует перезапуска дисплея. " +
            "Это первая проверка общего звука; отдельный канал навигационных подсказок ещё не подключён.")
        layout.addView(CheckBox(this).apply {
            text = "Новый сеанс Алисы — эксперимент"
            isChecked = BridgeSettings.read(this@MainActivity).restartAliceSession
            setOnCheckedChangeListener { _, checked ->
                BridgeSettings.save(this@MainActivity, BridgeSettings.read(this@MainActivity).copy(restartAliceSession = checked))
                showStatus(if (checked) "При следующем вызове голосовой кнопкой CarBridge попробует заново открыть Алису. " +
                    "Если стало хуже, выключите этот пункт — вернётся обычный способ вызова." else
                    "Возвращён обычный способ вызова ассистента. Применяется со следующего нажатия голосовой кнопки.")
            }
        })
        text("Для проверки повторных запросов: при вызове заново открывает подтверждённое окно Алисы на ГУ. " +
            "Предыдущий диалог при этом завершается. По умолчанию выключено; переподключение не требуется.")
        button("Проверить микрофон ГУ — 10 секунд") {
            sendCommand(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_MICROPHONE_PROBE))
        }
        button("Остановить проверку микрофона ГУ") {
            sendCommand(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_MICROPHONE_PROBE_STOP))
        }
        text("Проверяйте на стоянке при подключённом CarLife. Произнесите несколько слов: результат появится в статусе и журнале. " +
            "Звук не сохраняется в файл. Проверка не подключает микрофон к Алисе и автоматически заканчивается через 10 секунд.")
        layout.addView(CheckBox(this).apply {
            text = "Навигационные данные CarLife — эксперимент"
            isChecked = BridgeSettings.read(this@MainActivity).carLifeNavigation
            setOnCheckedChangeListener { _, checked ->
                BridgeSettings.save(this@MainActivity, BridgeSettings.read(this@MainActivity).copy(carLifeNavigation = checked))
                sendCommand(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_METADATA_MODE))
                showStatus(if (checked) "Отправка манёвров включена. Запустите маршрут; появление подсказок на приборке зависит от ГУ."
                    else "Отправка манёвров выключена; отправлен запрос очистки предыдущей подсказки.")
            }
        })
        layout.addView(CheckBox(this).apply {
            text = "Обложка CarLife — эксперимент"
            isChecked = BridgeSettings.read(this@MainActivity).carLifeArtwork
            setOnCheckedChangeListener { _, checked ->
                BridgeSettings.save(this@MainActivity, BridgeSettings.read(this@MainActivity).copy(carLifeArtwork = checked))
                sendCommand(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_METADATA_MODE))
                showStatus(if (checked) "Передача обложки включена. Запустите музыку; вывод на ГУ и приборку зависит от прошивки."
                    else "Передача обложки выключена.")
            }
        })
        text("Эксперименты применяются сразу и не требуют USB-звука. Нужен доступ к уведомлениям. " +
            "Манёвры берутся из опубликованных подсказок навигатора: если направление или расстояние не определены, отправки не будет. " +
            "Это данные для ГУ, не отдельный звуковой канал подсказок. Обложка — та, которую предоставляет плеер. " +
            "Поддержка приборной панели пока не проверена.")
        videoProfileButton = button("") { chooseVideoProfile() }
        text("Профиль 1920×1080 / 300 dpi подходит для проверенной компоновки карты. " +
            "Размер виртуального экрана применяется после переподключения. " +
            "Размер кнопок CarBridge и управления музыкой настраивается отдельно.")
        text("Приложения запускаются на ГУ в альбомной ориентации. " +
            "Некоторые приложения могут сохранять портретную компоновку с полями. " +
            "При нагреве частота видео снижается до 15 или 6 кадров/с.")
        if (BridgeSettings.hasSystemDpiBaseline(this)) {
            button("Восстановить исходный DPI телефона после версии 0.26") { restoreSystemDensity() }
        }
        mediaAccessButton = button("") { requestMediaSessionAccess() }
        text("Медиапанель показывает обложку и управляет активным плеером телефона: музыкой, подкастом и другими источниками.")
        overlayPermissionStatus = text("")
        button("Разрешить панель управления на ГУ") { requestOverlayPermission() }
        text("Для кнопок «Назад», «Домой» и выбора приложений поверх карт на ГУ разрешите CarBridge отображение поверх других приложений.")
        text("Выберите USB CarLife для передачи звука по кабелю. " +
            "Если USB-звук выключен, используется выход телефона или Bluetooth.")
        button("Запустить выбранное приложение на ГУ") {
            sendCommand(Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_LAUNCH_DISPLAY_APP)
                .putExtra(CaptureService.EXTRA_PACKAGE, BridgeSettings.read(this).packageNameLaunch))
        }
        button("Поделиться журналом последнего подключения") {
            SessionDiagnostics.shareLatest(this)
        }
        button("Поддержка") {
            val image = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.support_qr)
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = "QR-код поддержки · Олег"
            }
            android.app.AlertDialog.Builder(this).setTitle("Поддержка")
                .setView(ScrollView(this).apply { addView(image) })
                .setPositiveButton("Закрыть", null).show()
        }
        status = TextView(this).apply {
            maxLines=3
            ellipsize=android.text.TextUtils.TruncateAt.END
            text = savedInstanceState?.getString("status") ?: "Мастер проверит готовность телефона и подскажет следующий шаг."
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        settingsView = ScrollView(this).apply { addView(layout) }
        wizardView = ConnectionWizardView(this,
            action = { performWizardAction(it) },
            submit = { wizard.pair(it) },
            manual = { showManualPairing() },
            manualConnect = { showManualConnectPort() },
            allowPairNotification = {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            },
            stop = {
                stopConnection()
            })
        protocolPicker = ConnectionPickerView(this, choose = ::chooseProtocol)
        connectionFrame=android.widget.FrameLayout(this).apply { addView(wizardView); addView(protocolPicker) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectionTab = Button(this).apply { text = "Подключение"; setOnClickListener {
            if (!wizard.current().busy && !wizard.awaitingHeadUnit) {
                pendingProtocol = null; wizard.phonePreparationOnly = true; wizard.cancelConnectionRequest()
                wizard.resumePreparation()
            }
            selectTab(0)
        } }
        settingsTab = Button(this).apply { text = "Настройки"; setOnClickListener { selectTab(1) } }
        tabs.addView(connectionTab, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(settingsTab, LinearLayout.LayoutParams(0, -2, 1f))
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows=true
            addView(tabs)
            addView(status)
            addView(connectionFrame, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(settingsView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                addView(Button(this@MainActivity).apply { text="Журнал"; setOnClickListener { SessionDiagnostics.shareLatest(this@MainActivity) } }, LinearLayout.LayoutParams(0,-2,1f))
                addView(Button(this@MainActivity).apply { text="Стоп"; setOnClickListener {
                    stopConnection()
                } }, LinearLayout.LayoutParams(0,-2,1f))
            })
        })
        selectTab(selectedTab)
        wizard.state.observe(this) { next ->
            wizardView.render(next)
            renderConnectionPage()
            if(AppDisplayState.displayId<=0 && !next.connected)showStatus(next.message)
            settingsTab.isEnabled = !next.busy
            if (resumed) maybeConnectFromWizard()
        }
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(usbPermissionAction),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, displayReceiver,
            IntentFilter(CaptureService.ACTION_DISPLAY_READY).apply {
                addAction(CaptureService.ACTION_DISPLAY_STATUS)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshSelection()
        refreshDisplaySettings()
        updateAdbStatus()
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.getBooleanExtra(ConnectionScreens.EXTRA_SETTINGS,false)) { selectTab(1); return }
        if(intent.action==Intent.ACTION_MAIN) {
            pendingProtocol=null;carLifeAttemptAt=0L
            wizard.phonePreparationOnly=true;wizard.cancelConnectionRequest();wizard.resumePreparation()
            selectTab(0);return
        }
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            pendingAutoUsbConnect = true
            if (resumed) runPendingAutoUsbConnect()
        }
    }

    @Deprecated("Uses the existing Activity back handler")
    override fun onBackPressed() {
        if(selectedTab==1) { selectTab(0); return }
        if(pendingProtocol!=null && !wizard.current().busy && !wizard.awaitingHeadUnit) {
            pendingProtocol=null; wizard.phonePreparationOnly=true; wizard.cancelConnectionRequest(); selectTab(0); return
        }
        super.onBackPressed()
    }

    private fun runPendingAutoUsbConnect() {
        if (!pendingAutoUsbConnect || !resumed) return
        pendingAutoUsbConnect = false
        val now = SystemClock.elapsedRealtime()
        if (lastAutoUsbConnectAt != 0L && now - lastAutoUsbConnectAt < 2_000L) return
        lastAutoUsbConnectAt = now
        if(wizard.awaitingHeadUnit || CaptureService.hasActiveSession || AppDisplayState.displayId>0) { selectTab(0); return }
        if(pendingProtocol=="carlife") {
            // A choice made before plugging the cable remains explicit user authorization.
            selectTab(0);wizard.check(autoConnect=true);return
        }
        pendingProtocol=null; wizard.phonePreparationOnly=true; wizard.cancelConnectionRequest()
        selectTab(0)
        showStatus("USB подключён. Проверяю телефон; затем выберите протокол подключения.")
        // Give UsbManager a moment to publish the accessory before the normal validation path.
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (resumed) wizard.check() else pendingAutoUsbConnect = true
        }, 300L)
    }

    private fun selectTab(index: Int) {
        selectedTab = if (index == 1 && wizard.current().busy) 0 else index
        wizard.automaticDiscoveryChecks = selectedTab == 0
        if (!::wizardView.isInitialized) return
        renderConnectionPage()
        settingsView.visibility = if (selectedTab == 1) View.VISIBLE else View.GONE
        connectionTab.isSelected = selectedTab == 0
        settingsTab.isSelected = selectedTab == 1
        connectionTab.alpha = if (selectedTab == 0) 1f else 0.6f
        settingsTab.alpha = if (selectedTab == 1) 1f else 0.6f
        if (resumed && selectedTab == 0) { wizard.check(); maybeConnectFromWizard() }
    }

    private fun maybeConnectFromWizard() {
        if(CaptureService.hasActiveSession)return
        if (!resumed || selectedTab != 0 || isFinishing || isDestroyed || wizard.awaitingHeadUnit || AdbSetupViewModel.isOperationBusy()) return
        if (pendingProtocol == "carlife" && wizard.takeConnectionRequest()) startPreparedConnection()
    }

    private fun renderConnectionPage() {
        if(!::protocolPicker.isInitialized)return
        connectionFrame.visibility=if(selectedTab==0)View.VISIBLE else View.GONE
        val state=wizard.current()
        val picker=selectedTab==0 && !state.busy && (state.connected || (state.phoneReady && pendingProtocol==null))
        protocolPicker.visibility=if(picker)View.VISIBLE else View.GONE
        wizardView.visibility=if(selectedTab==0 && !picker)View.VISIBLE else View.GONE
        protocolPicker.setSessionActive(CaptureService.hasActiveSession || AppDisplayState.displayId>0)
    }

    private fun stopConnection() {
        carLifeAttemptAt=0L
        wizard.cancelPreparation("Подготовка и запрос подключения остановлены.")
        sendCommand(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        pendingProtocol=null; wizard.phonePreparationOnly=true
        wizard.connectionFailed("Остановлено. Для нового подключения нажмите «Проверить и продолжить».")
    }

    private fun returnAfterCarLifeAttempt() {
        if(carLifeAttemptAt==0L || CaptureService.hasActiveSession || wizard.current().busy ||
            SystemClock.elapsedRealtime()-carLifeAttemptAt in 0L..1500L)return
        carLifeAttemptAt=0L
        pendingProtocol=null;wizard.phonePreparationOnly=true;wizard.cancelConnectionRequest()
        if(!wizard.preparationIsCancelled())wizard.check()
        renderConnectionPage()
    }

    private fun chooseProtocol(protocol: String) {
        if(CaptureService.hasActiveSession || AppDisplayState.displayId>0 || wizard.awaitingHeadUnit) {
            showStatus("Сеанс уже работает. Для смены подключения нажмите «Стоп»."); return
        }
        if(!wizard.current().phoneReady || wizard.current().busy) { wizard.check(); return }
        if(protocol=="carlife") { startConnection(); return }
        val carbit=protocol=="carbit"
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        body.addView(TextView(this).apply { text="Выберите способ подключения"; textSize=18f })
        body.addView(Button(this).apply { text="USB · пока недоступно"; isEnabled=false })
        body.addView(TextView(this).apply { text="USB для этого протокола ещё не реализован. Доступно беспроводное подключение."; setPadding(8,4,8,16) })
        val dialog=AlertDialog.Builder(this).setTitle(if(carbit)"CarbitLink" else "Android Auto")
            .setView(body).setNegativeButton("Назад",null).setNeutralButton("Настройки") { _,_->selectTab(1) }.create()
        body.addView(Button(this).apply { text=if(carbit)"Беспроводное · Wi-Fi" else "Беспроводное · через адаптер"
            setOnClickListener { dialog.dismiss(); startActivity(Intent(this@MainActivity,if(carbit)CarbitConnectActivity::class.java else AaWirelessConnectActivity::class.java)) } })
        dialog.show()
    }

    private fun performWizardAction(action: ConnectionWizard.Action) {
        when (action) {
            ConnectionWizard.Action.RETRY -> if(pendingProtocol=="carlife")startConnection() else { wizard.resumePreparation(); wizard.check() }
            ConnectionWizard.Action.DEVICE_SETTINGS -> openWizardSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
            ConnectionWizard.Action.DEBUG_SETTINGS -> openWizardSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            ConnectionWizard.Action.WIFI -> openWizardSettings(Settings.ACTION_WIFI_SETTINGS)
            ConnectionWizard.Action.WIRELESS, ConnectionWizard.Action.PAIR -> {
                wizard.resumePreparation()
                ConnectionPreparationService.ensureStarted(applicationContext)
                if (action == ConnectionWizard.Action.PAIR) PairingPrompt.show(applicationContext)
                openWizardSettings("android.settings.WIRELESS_DEBUGGING_SETTINGS", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            }
            ConnectionWizard.Action.OVERLAY -> {
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { openWizardSettings(Settings.ACTION_SETTINGS) }
            }
            ConnectionWizard.Action.APP -> chooseApplication()
            ConnectionWizard.Action.USB_PERMISSION -> if (wizard.current().phoneReady) requestUsb()
            ConnectionWizard.Action.NONE -> Unit
        }
    }

    private fun openWizardSettings(vararg actions: String) {
        for (action in actions.toList() + Settings.ACTION_SETTINGS) {
            try { startActivity(Intent(action)); return }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        showStatus("Откройте настройки телефона вручную и выполните подсказку мастера.")
    }

    private fun showManualPairing() {
        val config = BridgeSettings.read(this)
        val data = wizard.current()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun field(label: String, value: String, numeric: Boolean = false) = EditText(this).apply {
            hint = label; setText(value); setSingleLine(); isSaveEnabled = false
            inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
            form.addView(this)
        }
        val host = field("Адрес этого телефона", "127.0.0.1")
        val pairPort = field("Порт из окна с кодом", data.pairPort.takeIf { it > 0 }?.toString().orEmpty(), true)
        val connectPort = field("Порт подключения: главный экран отладки", (data.connectPort.takeIf { it > 0 }
            ?: config.connectPort.takeIf { it > 0 })?.toString().orEmpty(), true)
        val code = field("Код сопряжения: 6 цифр", "", true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Ручной ввод, если поиск не сработал").setView(form)
            .setPositiveButton("Сопрячь") { _, _ ->
                val secret = code.text.toString().trim(); code.text.clear()
                wizard.pair(secret, host.text.toString(), pairPort.text.toString().toIntOrNull() ?: 0,
                    connectPort.text.toString().toIntOrNull() ?: 0)
            }.setNegativeButton("Отмена") { _, _ -> code.text.clear() }.show()
    }

    private fun showManualConnectPort() {
        val field = EditText(this).apply {
            hint = "Порт с главного экрана беспроводной отладки"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSaveEnabled = false
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("Порт подключения ADB")
            .setMessage("Откройте «Беспроводная отладка». В строке «IP-адрес и порт» возьмите число после двоеточия. Это не порт из окна с кодом сопряжения.")
            .setView(field).setPositiveButton("Продолжить") { _, _ ->
                wizard.setConnectionPort(field.text.toString().trim().toIntOrNull() ?: 0)
            }.setNegativeButton("Отмена", null).show()
    }

    private fun startConnection() {
        pendingProtocol="carlife"; wizard.phonePreparationOnly=false
        selectTab(0)
        wizard.check(autoConnect = true)
    }

    private fun startPreparedConnection() {
        if (AdbSetupViewModel.isOperationBusy()) {
            showStatus("В настройке ещё выполняется операция ADB. Дождитесь результата проверки или завершения подключения.")
            return
        }
        if (connecting || waitingUsbPermission) return
        if (Build.VERSION.SDK_INT < 30) {
            showStatus("Этот режим требует Android 11 или новее")
            return
        }
        val config = BridgeSettings.read(this)
        if (packageManager.getLaunchIntentForPackage(config.packageNameLaunch) == null) {
            showStatus("Выберите установленное приложение для запуска на ГУ")
            chooseApplication()
            return
        }
        val offline = config.connectionMode == BridgeSettings.MODE_LOCAL_TCP
        val port = if (offline) config.offlinePort else config.connectPort
        val connectionMode = if (offline) AdbConnectionMode.LOCAL_TCP else AdbConnectionMode.TLS
        if (port !in 1..65535) {
            showStatus(if (offline) "Офлайн-порт не активирован. Подготовьте подключение через Wi-Fi."
                else "Укажите TLS-порт в настройках ADB или активируйте офлайн-режим")
            startActivity(Intent(this, AdbSetupActivity::class.java))
            return
        }
        if (offline) {
            showStatus("Сначала подключаю USB. Локальный ADB будет проверен в фоне после переключения USB и ответа ГУ.")
            requestUsb()
            return
        }
        if (AdbSession.isConnectedFor(config.host, port, connectionMode)) { requestUsb(); return }
        connecting = true
        connectButton.isEnabled = false
        val generation = ++connectGeneration
        showStatus("Подключаю TLS ADB…")
        worker.execute {
            var failure: String? = null
            val connected = try {
                AdbSession.connect(applicationContext, config.host, port)
            } catch (error: Exception) {
                failure = error.message?.take(180)
                false
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing && generation == connectGeneration) {
                    connecting = false
                    connectButton.isEnabled = true
                    updateAdbStatus()
                    if (connected) {
                        if (resumed) requestUsb() else pendingUsbAfterConnect = true
                    } else showStatus("TLS ADB не подключён${failure?.let { ": $it" }.orEmpty()}. " +
                        "проверьте Wi-Fi, беспроводную отладку и порт. Для поездки без сети сначала активируйте офлайн-режим.")
                }
            }
        }
    }

    private fun requestUsb() {
        try {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val accessory = manager.accessoryList?.firstOrNull()
            if (accessory == null) {
                showStatus("USB подключён, но Android не видит CarLife-аксессуар. Включите CarLife на ГУ и подключите телефон кабелем передачи данных.")
                return
            }
            if (manager.hasPermission(accessory)) beginSession()
            else {
                waitingUsbPermission = true
                showStatus("Разрешите доступ к USB-аксессуару")
                val permissionIntent = PendingIntent.getBroadcast(this, 102,
                    Intent(usbPermissionAction).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
                manager.requestPermission(accessory, permissionIntent)
            }
        } catch (error: Exception) {
            waitingUsbPermission = false
            showStatus("Не удалось запросить доступ к USB: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun startWifiConnection() {
        if (AdbSetupViewModel.isOperationBusy()) {
            showStatus("Сначала дождитесь завершения подготовки офлайн-подключения ADB")
            return
        }
        val config = BridgeSettings.read(this)
        if (config.connectionMode != BridgeSettings.MODE_LOCAL_TCP || config.offlinePort !in 1..65535) {
            showStatus("Wi-Fi CarLife требует подготовленный офлайн ADB. Сначала выполните пункт 1.")
            startActivity(Intent(this, AdbSetupActivity::class.java))
            return
        }
        if (packageManager.getLaunchIntentForPackage(config.packageNameLaunch) == null) {
            showStatus("Выберите установленное приложение для запуска на ГУ")
            chooseApplication()
            return
        }
        val missing = requiredWirelessPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            wifiStartAfterPermission = true
            wirelessPermissions.launch(missing.toTypedArray())
            return
        }
        connectWifiAfterPermission()
    }

    private fun requiredWirelessPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun hasWirelessPermissions(): Boolean = requiredWirelessPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun connectWifiAfterPermission() {
        if (connecting) return
        val config = BridgeSettings.read(this)
        if (config.offlinePort !in 1..65535) {
            showStatus("Офлайн ADB отключён. Повторите подготовку подключения.")
            return
        }
        connecting = true
        wifiConnectButton.isEnabled = false
        connectButton.isEnabled = false
        val generation = ++connectGeneration
        showStatus("Подключаю локальный ADB и запускаю Bluetooth-согласование CarLife…")
        worker.execute {
            val result = runCatching {
                AdbSession.connect(applicationContext, "127.0.0.1", config.offlinePort,
                    AdbConnectionMode.LOCAL_TCP, 10_000L)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != connectGeneration) return@runOnUiThread
                connecting = false
                wifiConnectButton.isEnabled = true
                connectButton.isEnabled = true
                updateAdbStatus()
                val connected = result.getOrDefault(false)
                if (!connected) {
                    showStatus("Локальный ADB не подключён: ${result.exceptionOrNull()?.message ?: AdbSession.lastError ?: "проверьте офлайн-подключение"}")
                    return@runOnUiThread
                }
                try {
                    startForegroundService(Intent(this, CaptureService::class.java)
                        .putExtra(CaptureService.EXTRA_NAVIGATION_MOTION,resumed && HuNavigationMotion.enabled(this) && HuNavigationMotion.permitted(this))
                        .putExtra(CaptureService.EXTRA_MODE, CaptureService.MODE_ADB)
                        .putExtra(CaptureService.EXTRA_TRANSPORT, CaptureService.TRANSPORT_WIFI)
                        .putExtra(CaptureService.EXTRA_STUB, false)
                        .putExtra(CaptureService.EXTRA_PACKAGE, config.packageNameLaunch)
                        .putExtra(CaptureService.EXTRA_SCREEN_OFF, config.screenOff))
                    showStatus("CarBridge запущен. Запустите беспроводной CarLife на ГУ.")
                } catch (error: Exception) {
                    showStatus("Не удалось запустить Wi-Fi сессию: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun beginSession() {
        if (wizard.awaitingHeadUnit || AppDisplayState.displayId > 0) return
        if (AdbSetupViewModel.isOperationBusy()) {
            showStatus("Операция настройки ADB ещё не завершена. После её результата повторите подключение к ГУ.")
            return
        }
        val config = BridgeSettings.read(this)
        val offline = config.connectionMode == BridgeSettings.MODE_LOCAL_TCP
        val port = if (offline) config.offlinePort else config.connectPort
        val connectionMode = if (offline) AdbConnectionMode.LOCAL_TCP else AdbConnectionMode.TLS
        if (port !in 1..65535 || (!offline && !AdbSession.isConnectedFor(config.host, port, connectionMode))) {
            showStatus(if (offline) "Офлайн-порт не активирован. Повторите активацию."
                else "TLS ADB отключился. Подключитесь заново через настройки ADB.")
            return
        }
        try {
            wizard.waitingForHeadUnit()
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_NAVIGATION_MOTION,resumed && HuNavigationMotion.enabled(this) && HuNavigationMotion.permitted(this))
                .putExtra(CaptureService.EXTRA_MODE, CaptureService.MODE_ADB)
                .putExtra(CaptureService.EXTRA_STUB, false)
                .putExtra(CaptureService.EXTRA_PACKAGE, config.packageNameLaunch)
                .putExtra(CaptureService.EXTRA_SCREEN_OFF, config.screenOff))
            carLifeAttemptAt=SystemClock.elapsedRealtime()
            showStatus(if (offline) "USB: ожидаю CarLife от ГУ. В эмуляторе Windows нажмите «Подключить» или «Переподключить». После ответа проверю локальный ADB."
                else "USB: запускаю CarLife и отдельный дисплей через ADB…")
        } catch (error: Exception) {
            wizard.connectionFailed("Не удалось запустить сессию. Повторите подключение.")
            showStatus("Не удалось запустить сессию: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun sendCommand(intent: Intent) {
        try { startService(intent) }
        catch (error: Exception) { showStatus("Команда не выполнена: ${error.message ?: error.javaClass.simpleName}") }
    }

    private data class LaunchApp(val name: String, val packageName: String) {
        override fun toString() = "$name\n$packageName"
    }

    private fun chooseApplication() {
        val loading = AlertDialog.Builder(this).setMessage("Читаю список приложений…").create()
        loading.show()
        worker.execute {
            val result = runCatching {
                val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(query, 0)
                    .filter { it.activityInfo.packageName != packageName && it.activityInfo.enabled && it.activityInfo.exported }
                    .map { LaunchApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
                    .distinctBy { it.packageName }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loading.dismiss()
                result.fold(onSuccess = { showAppPicker(it) },
                    onFailure = { showStatus("Не удалось прочитать приложения: ${it.message}") })
            }
        }
    }

    private fun showAppPicker(apps: List<LaunchApp>) {
        if (apps.isEmpty()) { showStatus("Android не вернул приложений со значком запуска"); return }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val search = EditText(this).apply {
            hint = "Поиск по названию или пакету"
            setSingleLine()
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, apps.toMutableList())
        val list = ListView(this).apply { this.adapter = adapter }
        container.addView(search)
        container.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (360 * resources.displayMetrics.density).toInt()))
        val dialog = AlertDialog.Builder(this).setTitle("Приложение для ГУ").setView(container)
            .setNegativeButton("Отмена", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                adapter.clear()
                adapter.addAll(apps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) })
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let {
                BridgeSettings.choosePackage(this, it.packageName)
                refreshSelection()
                showStatus("Выбрано: ${it.name}.")
                if (selectedTab == 0) wizard.check()
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun chooseControlsScale() {
        val sizes = listOf(160, 200, 240, 280, 320)
        val labels = arrayOf("Компактный", "Обычный", "Крупный", "Очень крупный", "Максимальный")
        val selected = sizes.indexOf(BridgeSettings.read(this).displayDpi)
        AlertDialog.Builder(this).setTitle("Размер панели и кнопок музыки")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                BridgeSettings.setDisplayDpi(this, sizes[index])
                refreshDisplaySettings()
                sendCommand(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH_NAV))
                showStatus("Размер панели и управления музыкой сохранён. Масштаб карты не меняется.")
                dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun restoreSystemDensity() {
        if (!BridgeSettings.hasSystemDpiBaseline(this)) {
            showStatus("Исходный DPI телефона не сохранён старой версией CarBridge.")
            return
        }
        if (AdbSetupViewModel.isOperationBusy()) {
            showStatus("Дождитесь завершения текущей операции ADB и повторите восстановление DPI телефона.")
            return
        }
        showStatus("Подключаю ADB и восстанавливаю сохранённый исходный DPI телефона…")
        worker.execute {
            val result = runCatching { restoreLegacyPhoneDensityIfNeeded() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                refreshDisplaySettings()
                result.onSuccess { state ->
                    showStatus("Исходный DPI телефона восстановлен: ${state?.effectiveDpi ?: "без переопределения"}.")
                }.onFailure { error ->
                    showStatus("Не удалось восстановить исходный DPI: ${error.message ?: error.javaClass.simpleName}. " +
                        "Сохранённое значение останется доступно для повтора.")
                }
            }
        }
    }

    private fun restoreLegacyPhoneDensityIfNeeded(): SystemDensityCommand.State? {
        if (!BridgeSettings.hasSystemDpiBaseline(this)) return null
        val originalOverride = BridgeSettings.systemDpiBaselineOverride(this)
        ensureAdbForSystemDensity()
        val state = AdbSession.open(SystemDensityCommand.restoreDestination(originalOverride), 3000L).use {
            SystemDensityCommand.readState(it.input)
        }
        SystemDensityCommand.requireApplied(state, originalOverride)
        BridgeSettings.clearSystemDpiExperiment(this)
        return state
    }
    private fun ensureAdbForSystemDensity() {
        if (AdbSession.isConnected()) return
        val config = BridgeSettings.read(this)
        val connected = when {
            config.connectionMode == BridgeSettings.MODE_LOCAL_TCP && config.offlinePort in 1..65535 ->
                AdbSession.connect(applicationContext, "127.0.0.1", config.offlinePort,
                    AdbConnectionMode.LOCAL_TCP, 10_000L)
            config.connectionMode == BridgeSettings.MODE_TLS && config.connectPort in 1..65535 ->
                AdbSession.connect(applicationContext, config.host, config.connectPort,
                    AdbConnectionMode.TLS, 10_000L)
            config.offlinePort in 1..65535 ->
                AdbSession.connect(applicationContext, "127.0.0.1", config.offlinePort,
                    AdbConnectionMode.LOCAL_TCP, 10_000L)
            else -> false
        }
        if (!connected) throw java.io.IOException("ADB не подключён или не отвечает")
    }

    private fun chooseVideoProfile() {
        val profiles = VideoProfile.values()
        val current = VideoProfile.fromPreference(BridgeSettings.read(this).videoProfile)
        AlertDialog.Builder(this).setTitle("Видеопрофиль")
            .setSingleChoiceItems(profiles.map { it.label }.toTypedArray(), profiles.indexOf(current)) { dialog, index ->
                BridgeSettings.setVideoProfile(this, profiles[index])
                refreshDisplaySettings()
                showStatus("Профиль «${profiles[index].label}» сохранён. Остановите сессию и повторно подключитесь к ГУ, чтобы применить его.")
                dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun chooseLogicalDisplayMode() {
        val modes = listOf(
            BridgeSettings.DISPLAY_MODE_HU,
            BridgeSettings.DISPLAY_MODE_1600_900,
            BridgeSettings.DISPLAY_MODE_1920_1080,
            BridgeSettings.DISPLAY_MODE_1920_960,
            BridgeSettings.DISPLAY_MODE_1920_720,
        )
        val labels = modes.map(::logicalDisplayModeLabel).toTypedArray()
        val selected = modes.indexOf(BridgeSettings.read(this).logicalDisplayMode).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Размер виртуального экрана ГУ")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                val mode = modes[index]
                BridgeSettings.setLogicalDisplayMode(this, mode)
                refreshDisplaySettings()
                val statusMessage = when (mode) {
                    BridgeSettings.DISPLAY_MODE_1920_720 -> "Выбран экран 1920×720 / 300 DPI. Для CarbitLink размер согласуется при подключении; для CarLife требуется видеорежим ГУ 8:3. Остановите сеанс и переподключитесь."
                    BridgeSettings.DISPLAY_MODE_1600_900 ->
                        "Выбран виртуальный экран 1600×900. Остановите сессию и переподключитесь."
                    BridgeSettings.DISPLAY_MODE_1920_1080 ->
                        "Включён эксперимент 1920×1080 при 300 dpi. Видео для ГУ останется 1280×720; остановите сессию и переподключитесь."
                    BridgeSettings.DISPLAY_MODE_1920_960 ->
                        "Выбран экран 1920×960 / 300 DPI для ГУ 1280×640 (2:1). Остановите сессию и переподключитесь: текущее окно само не меняется."
                    else -> "Экспериментальный размер выключен. Остановите сессию и переподключитесь, чтобы применить обычный размер ГУ."
                }
                showStatus(statusMessage)
                dialog.dismiss()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun logicalDisplayModeLabel(mode: String): String = when (mode) {
        BridgeSettings.DISPLAY_MODE_1920_720 -> "1920×720 / 300 dpi — широкий экран"
        BridgeSettings.DISPLAY_MODE_1600_900 -> "1600×900 / 250 dpi"
        BridgeSettings.DISPLAY_MODE_1920_1080 -> "1920×1080 / 300 dpi — рекомендуемый"
        BridgeSettings.DISPLAY_MODE_1920_960 -> "1920×960 / 300 dpi — для ГУ 1280×640 (2:1)"
        else -> "Размер ГУ / 200 dpi — меньшая нагрузка"
    }

    private fun scaleLabel(dpi: Int): String = when (dpi) {
        80 -> "80 dpi — минимальный размер элементов"
        100 -> "100 dpi — мелко"
        120 -> "120 dpi — очень компактно"
        140 -> "140 dpi — средний масштаб"
        160 -> "160 dpi — компактно"
        180 -> "180 dpi — немного мельче"
        200 -> "200 dpi — текущий базовый масштаб"
        220 -> "220 dpi — немного крупнее"
        240 -> "240 dpi — крупнее"
        250 -> "250 dpi — расчётный эквивалент 200 dpi для 1600×900"
        300 -> "300 dpi — эксперимент для 1920×1080"
        else -> "$dpi dpi"
    }

    private fun refreshDisplaySettings() {
        val config = BridgeSettings.read(this)
        dockModeButton.text = if (config.dockAutoHide) "Окно: полный экран, панель скрывается" else "Окно: над постоянной панелью"
        scaleButton.text = "Размер панели и кнопок музыки: ${config.displayDpi * 100 / 200}%"
        logicalDisplayButton.text = "Размер виртуального экрана: ${logicalDisplayModeLabel(config.logicalDisplayMode)}"
        videoProfileButton.text = "Видео: ${VideoProfile.fromPreference(config.videoProfile).label}"
        mediaAccessButton.text = if (hasMediaSessionAccess())
            "Управление музыкой на ГУ: доступ разрешён"
        else "Разрешить обложки и управление музыкой на ГУ"
        if (::overlayPermissionStatus.isInitialized) {
            overlayPermissionStatus.text = if (Settings.canDrawOverlays(this))
                "Панель управления на ГУ: разрешение выдано."
            else "Панель управления на ГУ: нужно разрешение на отображение поверх приложений."
        }
    }

    private fun hasMediaSessionAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val component = android.content.ComponentName(this, HuMediaNotificationListener::class.java)
        return enabled.split(':').mapNotNull { android.content.ComponentName.unflattenFromString(it) }
            .contains(component)
    }

    private fun requestMediaSessionAccess() {
        if (hasMediaSessionAccess()) {
            showStatus("Доступ к медиасеансам разрешён. Запустите любой плеер, чтобы вывести его обложку на ГУ.")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            showStatus("В настройках включите доступ CarBridge к уведомлениям, чтобы читать обложки и управлять плеером.")
        } catch (error: Exception) {
            showStatus("Не удалось открыть доступ к уведомлениям: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            showStatus("Разрешение для панели управления уже выдано. Панель появится во время подключения к ГУ.")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            showStatus("Разрешите CarBridge отображение поверх других приложений, затем вернитесь назад.")
        } catch (error: Exception) {
            showStatus("Не удалось открыть разрешение панели: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun refreshSelection() {
        val pkg = BridgeSettings.read(this).packageNameLaunch
        val label = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        selectedApp.text = if (label == null) "Приложение не найдено: $pkg. Выберите установленное приложение."
        else "На ГУ: $label\n$pkg"
    }

    private fun updateAdbStatus() {
        val config = BridgeSettings.read(this)
        val offline = config.connectionMode == BridgeSettings.MODE_LOCAL_TCP
        val port = if (offline) config.offlinePort else config.connectPort
        if (offline) {
            adbStatus.text = if (port in 1..65535) "Офлайн-порт сохранён: 127.0.0.1:$port\nЛокальный ADB нужен и для USB, и для Wi-Fi CarLife. Wi-Fi подключение к ГУ запускается отдельно."
                else "Офлайн-порт не активирован. Подготовьте подключение."
        } else {
            val connected = port in 1..65535 && AdbSession.isConnectedFor(config.host, port, AdbConnectionMode.TLS)
            adbStatus.text = "Подготовка: TLS ADB\n${if (connected) "ADB подключён" else "ADB не подключён"}"
        }
    }

    private fun showStatus(message: String) {
        if (::status.isInitialized) status.text = message
        Log.i("CarBridge/Activity", message)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateNavigationMotion()
        if (::selectedApp.isInitialized) {
            refreshSelection()
            refreshDisplaySettings()
            updateAdbStatus()
        }
        if (pendingUsbAfterConnect) {
            pendingUsbAfterConnect = false
            requestUsb()
        }
        runPendingAutoUsbConnect()
        returnAfterCarLifeAttempt()
        if (::wizardView.isInitialized && selectedTab == 0) {
            if(pendingProtocol==null && !CaptureService.hasActiveSession && !wizard.current().busy && !wizard.awaitingHeadUnit)
                wizard.resumePreparation()
            environment = wizard.environmentSignature()
            wizard.check()
            maybeConnectFromWizard()
        }
        wizardMain.removeCallbacks(wizardPoll)
        wizardMain.postDelayed(wizardPoll, 1500)
    }

    override fun onPause() {
        resumed = false
        wizardMain.removeCallbacks(wizardPoll)
        super.onPause()
    }

    private fun updateNavigationMotion() {
        // A location FGS is enabled only while this Activity is visible, never from a USB receiver.
        if(!resumed || AppDisplayState.displayId<=0) return
        sendCommand(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_NAVIGATION_MOTION)
            .putExtra(CaptureService.EXTRA_NAVIGATION_MOTION,HuNavigationMotion.enabled(this) && HuNavigationMotion.permitted(this)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", selectedTab)
        outState.putString("pending_protocol",pendingProtocol)
        outState.putLong("carlife_attempt_at",carLifeAttemptAt)
        outState.putBoolean("waiting_usb", waitingUsbPermission)
        outState.putBoolean("pending_usb", pendingUsbAfterConnect)
        if (::status.isInitialized) outState.putString("status", status.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        wizardMain.removeCallbacksAndMessages(null)
        if (isFinishing) wizard.cancelConnectionRequest()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(displayReceiver)
        worker.shutdown()
        super.onDestroy()
    }
}
