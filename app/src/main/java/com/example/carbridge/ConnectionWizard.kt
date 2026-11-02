package com.example.carbridge

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.carbridge.adb.AdbConnectionMode
import com.example.carbridge.adb.AdbServiceDiscovery
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.adb.OfflineAdbHealth
import com.example.carbridge.capture.AppDisplayState
import java.net.InetAddress

/** Process-owned, bounded preparation; no Activity, view, pairing code or password is retained. */
internal class ConnectionWizard private constructor(context: Context) {
    enum class Step(val label: String) {
        ANDROID("Android 11 или новее"), DEVELOPER("Режим разработчика"),
        DEBUG("Отладка по USB"), WIFI("Wi-Fi для подготовки"), WIRELESS("Беспроводная отладка"),
        PAIRING("Сопряжение ADB"), ACCESS("Локальный доступ к телефону"),
        OVERLAY("Панель управления на ГУ"), APP("Приложение для запуска"), USB("USB / CarLife")
    }
    enum class Mark { WAITING, CHECKING, PASSED, BLOCKED }
    enum class Action { RETRY, DEVICE_SETTINGS, DEBUG_SETTINGS, WIFI, WIRELESS, PAIR, OVERLAY, APP, USB_PERMISSION, NONE }
    data class State(
        val rows: Map<Step, Mark> = emptyMap(),
        val message: String = "Проверю готовность телефона. Подключите USB к ГУ или начните подготовку.",
        val action: Action = Action.RETRY,
        val busy: Boolean = false,
        val phoneReady: Boolean = false,
        val ready: Boolean = false,
        val connected: Boolean = false,
        val pairPort: Int = 0,
        val connectPort: Int = 0,
    )
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableLiveData(State())
    private val stateLock = Any()
    val state: LiveData<State> get() = mutable
    @Volatile private var snapshot = State()
    @Volatile private var connectRequested = false
    @Volatile var awaitingHeadUnit = false
        private set
    private var connectionEpoch = 0
    var automaticDiscoveryChecks: Boolean = true
    @Volatile private var discoveryPorts = ""
    private val discovery: AdbServiceDiscovery = AdbServiceDiscovery(app) {
        val pairing = discoveryEndpoints(AdbServiceDiscovery.PAIR).firstOrNull()?.port ?: 0
        val connect = discoveryEndpoints(AdbServiceDiscovery.CONNECT).firstOrNull()?.port ?: 0
        update { it.copy(pairPort = pairing, connectPort = connect) }
        val ports = "$pairing:$connect"
        if (ports != discoveryPorts) {
            discoveryPorts = ports
            if (automaticDiscoveryChecks && connect != 0 && !snapshot.busy && snapshot.action in setOf(Action.PAIR, Action.WIRELESS, Action.RETRY)) check()
        }
    }
    private val runner: AdbOperationRunner = AdbOperationRunner(
        cancelTransport = { AdbSession.disconnect() },
        onState = { operation ->
            update { old ->
                val rows = if (!operation.busy) old.rows.mapValues { (_, mark) ->
                    if (mark == Mark.CHECKING) Mark.BLOCKED else mark
                } else old.rows
                old.copy(rows = rows, busy = operation.busy, message = operation.message,
                    action = if (!operation.busy && old.action == Action.NONE && !old.ready) Action.RETRY else old.action)
            }
        },
    )
    private fun discoveryEndpoints(kind: String): List<AdbServiceDiscovery.Endpoint> = discovery.endpoints(kind)
    private fun publish(value: State) {
        update { value }
    }
    private fun update(change: (State) -> State) {
        synchronized(stateLock) { snapshot = change(snapshot) }
        if (Looper.myLooper() == Looper.getMainLooper()) mutable.value = snapshot
        else main.post { mutable.value = snapshot }
    }
    private fun mark(step: Step, value: Mark, message: String? = null, action: Action = Action.NONE) {
        update { old -> old.copy(rows = old.rows + (step to value), message = message ?: old.message, action = action) }
        android.util.Log.i("CarBridge/Wizard", "step=$step state=$value action=$action")
    }
    private fun blocked(step: Step, message: String, action: Action): String {
        mark(step, Mark.BLOCKED, message, action)
        if (action == Action.PAIR) main.post { PairingPrompt.show(app, message) }
        return message
    }
    fun current(): State = snapshot
    fun cancelConnectionRequest() { connectRequested = false; awaitingHeadUnit = false; connectionEpoch++ }
    fun takeConnectionRequest(): Boolean {
        if (snapshot.busy || !snapshot.ready || snapshot.connected || !connectRequested) return false
        connectRequested = false
        return true
    }
    fun connected() {
        awaitingHeadUnit = false; connectionEpoch++
        discovery.stop(); PairingPrompt.hide(app); connectRequested = false
        publish(State(Step.entries.filter { it !in PREPARATION_STEPS }.associateWith { Mark.PASSED }, "ГУ подключено. Можно пользоваться приложениями.",
            Action.NONE, phoneReady = true, connected = true))
    }
    fun waitingForHeadUnit() {
        awaitingHeadUnit = true
        val epoch = ++connectionEpoch
        publish(snapshot.copy(ready = false, action = Action.NONE, message = "Запускаю CarLife. Ожидаю ответ ГУ…"))
        main.postDelayed({
            if (awaitingHeadUnit && epoch == connectionEpoch) {
                if (AppDisplayState.displayId > 0) connected()
                else connectionFailed("Ответ ГУ не подтверждён. Проверьте USB-кабель, выберите CarLife на ГУ и повторите подключение.")
            }
        }, 30_000)
    }
    fun connectionFailed(message: String) {
        awaitingHeadUnit = false; connectionEpoch++
        mark(Step.USB, Mark.BLOCKED, message, Action.RETRY)
        publish(snapshot.copy(ready = false, connected = false))
    }
    fun check(autoConnect: Boolean = false) {
        if (autoConnect) connectRequested = true
        if (awaitingHeadUnit) return
        if (snapshot.busy) return
        if (AppDisplayState.displayId > 0) { connected(); return }
        if (AdbSetupViewModel.isOperationBusy()) {
            publish(snapshot.copy(ready = false, message = "Дождитесь завершения операции в настройках ADB.", action = Action.RETRY))
            return
        }
        publish(State(pairPort = snapshot.pairPort, connectPort = snapshot.connectPort))
        runner.start("wizard", "Проверяю готовность телефона…", 90_000L,
            "Проверка не завершилась за 90 секунд. Повторите текущий шаг.",
            "Повторите проверку текущего шага.") { operation ->
            fun step(s: Step, description: String, check: () -> Boolean, failure: String, action: Action): Boolean {
                operation.ensureActive(); mark(s, Mark.CHECKING, description, action)
                if (!check()) { blocked(s, failure, action); return false }
                mark(s, Mark.PASSED)
                return true
            }
            if (!step(Step.ANDROID, "Проверяю версию Android…", { Build.VERSION.SDK_INT >= 30 },
                    "Для отдельного дисплея нужен Android 11 или новее.", Action.NONE)) return@start snapshot.message
            if (!step(Step.DEVELOPER, "Проверяю режим разработчика…", {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
                }, "Откройте сведения о телефоне → сведения о ПО. Нажмите «Номер сборки» 7 раз и подтвердите действие. Вернитесь в CarBridge.",
                    Action.DEVICE_SETTINGS)) return@start snapshot.message
            if (!step(Step.DEBUG, "Проверяю отладку по USB…", {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
                }, "В разделе «Для разработчиков» включите «Отладку по USB», подтвердите запрос Android и вернитесь в CarBridge.",
                    Action.DEBUG_SETTINGS)) return@start snapshot.message
            mark(Step.ACCESS, Mark.CHECKING, "Проверяю сохранённый локальный доступ…")
            var config = BridgeSettings.read(app)
            var localReady = false
            val tlsLive = config.connectPort in 1..65535 &&
                AdbSession.isConnectedFor(config.host, config.connectPort, AdbConnectionMode.TLS)
            if (!tlsLive && config.offlinePort in 1..65535) {
                localReady = OfflineAdbHealth.checkLocal(app, config.offlinePort, budgetMs = 12_000L,
                    cancelled = { runCatching { operation.ensureActive() }.isFailure }).connected
                operation.ensureActive()
            }
            if (!localReady) {
                mark(Step.ACCESS, Mark.WAITING)
                var connected = tlsLive && AdbSession.probeConnected(config.host, config.connectPort, AdbConnectionMode.TLS, 2000)
                if (!connected) mark(Step.WIFI, Mark.CHECKING, "Проверяю Wi-Fi для подготовки…")
                if (!connected && !wifiConnected()) return@start blocked(Step.WIFI,
                    "Соединитесь с любой сетью Wi-Fi и вернитесь в CarBridge. Интернет не требуется. Wi-Fi нужен для подготовки доступа.", Action.WIFI)
                if (!connected) mark(Step.WIFI, Mark.PASSED)
                discovery.start()
                val wireless = Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", -1)
                if (!connected && wireless == 0) return@start blocked(Step.WIRELESS,
                    "В разделе «Для разработчиков» включите «Беспроводную отладку» и разрешите её для текущей сети Wi-Fi. Затем вернитесь в CarBridge.", Action.WIRELESS)
                if (wireless > 0 || connected) mark(Step.WIRELESS, Mark.PASSED)
                mark(Step.PAIRING, Mark.CHECKING, "Проверяю сохранённый ключ ADB…")
                val discovered = discoveryEndpoints(AdbServiceDiscovery.CONNECT).map { "127.0.0.1" to it.port }
                val saved = if (config.connectPort in 1..65535) listOf(config.host to config.connectPort) else emptyList()
                for ((host, port) in (discovered + saved).distinct().take(3)) {
                    if (connected) break
                    operation.ensureActive()
                    mark(Step.PAIRING, Mark.CHECKING, "Восстанавливаю доступ по сохранённому ключу…")
                    connected = runCatching { AdbSession.connect(app, host, port, AdbConnectionMode.TLS, 4000) }.getOrDefault(false)
                    if (connected) {
                        config = config.copy(host = host, connectPort = port)
                        operation.commit { BridgeSettings.save(app, config) }
                    }
                }
                if (!connected) {
                    if (discovered.isEmpty() && saved.isEmpty() && app.getSharedPreferences("connection_wizard", 0).getBoolean("paired", false)) {
                        return@start blocked(Step.PAIRING,
                            "Сопряжение сохранено. Вернитесь на главный экран беспроводной отладки: ищу порт подключения. Если поиск не сработает, укажите этот порт вручную.", Action.WIRELESS)
                    }
                    main.post { PairingPrompt.show(app) }
                    return@start blocked(Step.PAIRING,
                        "Откройте «Беспроводная отладка» → «Подключить устройство с помощью кода сопряжения». Введите 6 цифр в уведомлении CarBridge, не закрывая окно Android. Порты ищутся автоматически. Ввод в приложении и ручной ввод портов доступны ниже.", Action.PAIR)
                }
                mark(Step.WIRELESS, Mark.PASSED)
                mark(Step.PAIRING, Mark.PASSED)
                operation.ensureActive()
                mark(Step.ACCESS, Mark.CHECKING, "Активирую локальный доступ и проверяю настоящий ответ телефона…")
                val port = AdbSession.activateOffline(app, 0)
                operation.commit { BridgeSettings.save(app, BridgeSettings.read(app).copy(
                    connectionMode = BridgeSettings.MODE_LOCAL_TCP, offlinePort = port)) }
            } else operation.commit { BridgeSettings.save(app, config.copy(connectionMode = BridgeSettings.MODE_LOCAL_TCP)) }
            discovery.stop(); main.post { PairingPrompt.hide(app) }
            mark(Step.ACCESS, Mark.PASSED)
            if (!step(Step.OVERLAY, "Проверяю разрешение панели…", { Settings.canDrawOverlays(app) },
                    "Разрешите CarBridge отображение поверх других приложений. Это нужно для нижней панели управления на ГУ. Вернитесь после выдачи разрешения.", Action.OVERLAY)) return@start snapshot.message
            if (!step(Step.APP, "Проверяю приложение для запуска…", {
                    val selected = BridgeSettings.read(app).packageNameLaunch
                    selected != app.packageName && app.packageManager.getLaunchIntentForPackage(selected) != null
                }, "Выберите установленное приложение, которое нужно открыть на ГУ.", Action.APP)) return@start snapshot.message
            publish(snapshot.copy(phoneReady = true))
            val usb = app.getSystemService(UsbManager::class.java)
            val accessory = usb.accessoryList?.firstOrNull()
            if (accessory == null) return@start blocked(Step.USB,
                "Телефон подготовлен. Подключите его кабелем передачи данных к ГУ и выберите CarLife на ГУ. При обнаружении подключения мастер продолжит автоматически.", Action.RETRY)
            if (!usb.hasPermission(accessory)) return@start blocked(Step.USB,
                "Разрешите Android передать USB-подключение CarBridge. Нажмите кнопку ниже и подтвердите системный запрос.", Action.USB_PERMISSION)
            mark(Step.USB, Mark.PASSED)
            publish(snapshot.copy(ready = true, action = Action.RETRY))
            "Все проверки выполнены. Можно подключаться к ГУ."
        }
    }

    fun pair(code: String, manualHost: String? = null, manualPairPort: Int = 0, manualConnectPort: Int = 0) {
        if (snapshot.busy || AdbSetupViewModel.isOperationBusy() || AppDisplayState.displayId > 0) return
        if (code.length != 6 || code.any { it !in '0'..'9' }) {
            blocked(Step.PAIRING, "Введите шестизначный код из текущего окна сопряжения Android.", Action.PAIR)
            return
        }
        val host = manualHost?.trim()?.ifBlank { null } ?: "127.0.0.1"
        val own = runCatching { AdbServiceDiscovery.isOwnAddress(InetAddress.getByName(host)) }.getOrDefault(false)
        val port = manualPairPort.takeIf { it in 1..65535 }
            ?: discoveryEndpoints(AdbServiceDiscovery.PAIR).firstOrNull()?.port ?: 0
        if (!own || port !in 1..65535) {
            blocked(Step.PAIRING, if (!own) "Укажите адрес этого телефона, например 127.0.0.1." else
                "Порт сопряжения пока не найден. Оставьте окно с кодом Android открытым и повторите ввод через уведомление. Если поиск не сработает, укажите порт вручную.", Action.PAIR)
            return
        }
        runner.start("wizard_pair", "Выполняю сопряжение…", 45_000L,
            "Сопряжение не завершилось. Откройте новое окно с кодом и повторите.",
            "Проверьте текущий код и порт сопряжения.", secret = code) { operation ->
            if (!AdbSession.pair(app, host, port, code)) return@start blocked(Step.PAIRING,
                "Сопряжение не подтверждено. Откройте новое окно с кодом и повторите.", Action.PAIR)
            operation.ensureActive()
            val connectPort = manualConnectPort.takeIf { it in 1..65535 }
                ?: discoveryEndpoints(AdbServiceDiscovery.CONNECT).firstOrNull()?.port
                ?: BridgeSettings.read(app).connectPort
            operation.commit {
                BridgeSettings.save(app, BridgeSettings.read(app).copy(host = host, connectPort = connectPort))
                app.getSharedPreferences("connection_wizard", 0).edit().putBoolean("paired", true).apply()
            }
            mark(Step.PAIRING, Mark.PASSED, "Ключ принят. Продолжаю подготовку…")
            // Run after the bounded pairing operation has released its runner slot.
            main.postDelayed({ check() }, 400)
            "Сопряжение выполнено. Продолжаю подготовку автоматически…"
        }
    }

    fun setConnectionPort(port: Int) {
        if (snapshot.busy || AdbSetupViewModel.isOperationBusy()) return
        if (port !in 1..65535) {
            blocked(Step.WIRELESS, "Введите порт подключения от 1 до 65535 с главного экрана беспроводной отладки.", Action.WIRELESS)
            return
        }
        BridgeSettings.save(app, BridgeSettings.read(app).copy(host = "127.0.0.1", connectPort = port))
        check()
    }

    fun wifiConnected(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)
    private fun global(name: String) = runCatching { Settings.Global.getInt(app.contentResolver, name, -1) }.getOrDefault(-1)
    fun environmentSignature(): String = listOf(
        global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), global(Settings.Global.ADB_ENABLED),
        global("adb_wifi_enabled"), wifiConnected(), AppDisplayState.displayId, AdbSetupViewModel.isOperationBusy(),
        discoveryEndpoints(AdbServiceDiscovery.CONNECT),
        Settings.canDrawOverlays(app), BridgeSettings.read(app),
        app.getSystemService(UsbManager::class.java).let { manager -> manager.accessoryList?.firstOrNull()?.let { "${it.model}:${manager.hasPermission(it)}" } },
    ).joinToString("|")

    companion object {
        val PREPARATION_STEPS = setOf(Step.WIFI, Step.WIRELESS, Step.PAIRING)
        @Volatile private var instance: ConnectionWizard? = null
        fun get(context: Context): ConnectionWizard = instance ?: synchronized(this) {
            instance ?: ConnectionWizard(context.applicationContext).also { instance = it }
        }
    }
}
