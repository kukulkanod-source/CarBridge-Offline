package com.example.carbridge

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.carbridge.adb.AdbConnectionMode
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.adb.OfflineAdbHealth

/** All long-running closures hold only the Application, never an Activity or its views. */
class AdbSetupViewModel(application: Application) : AndroidViewModel(application) {
    internal val state: LiveData<AdbOperationRunner.State> get() = Shared.state
    val busy: Boolean get() = Shared.runner.state().busy

    fun feedback(message: String, checkResult: Boolean = false) {
        Shared.runner.feedback(message, if (checkResult) CHECK_TITLE else "")
        Log.i(TAG, "ADB setup feedback: $message")
    }

    fun acknowledgeDialog(id: Long) = Shared.runner.acknowledgeDialog(id)

    fun reportBusy(): Boolean {
        if (!busy) return false
        val message = Shared.runner.state().message.removePrefix("Операция ADB уже выполняется. ")
        feedback("Операция ADB уже выполняется. $message")
        return true
    }

    fun connectTls(config: BridgeSettings.Config) {
        val app = getApplication<Application>()
        Shared.runner.start("connect_tls", "Подключаю TLS ADB… Ожидание не более 45 секунд.",
            OTHER_TIMEOUT_MS, "Время подключения истекло (45 секунд). Проверьте подключение к Wi-Fi, беспроводную отладку и её порт.",
            "Проверьте подключение к Wi-Fi, адрес, порты и беспроводную отладку.") { operation ->
            if (AdbSession.connect(app, config.host, config.connectPort)) {
                operation.commit { saveTlsConnection(app, config) }
                "TLS ADB подключён. Теперь активируйте офлайн-режим; после этого Wi-Fi в машине не потребуется."
            } else "TLS ADB не подключён. Проверьте подключение к Wi-Fi, беспроводную отладку и её порт подключения."
        }
    }

    fun pair(config: BridgeSettings.Config, pairPort: Int, code: String) {
        val app = getApplication<Application>()
        Shared.runner.start("pair", "Выполняю сопряжение… Ожидание не более 45 секунд.", OTHER_TIMEOUT_MS,
            "Время сопряжения истекло (45 секунд). Откройте новое окно с кодом и повторите попытку.",
            "Проверьте подключение к Wi-Fi, адрес и порт сопряжения.", secret = code) { operation ->
            if (!AdbSession.pair(app, config.host, pairPort, code)) {
                "Сопряжение не выполнено. Откройте новое окно с кодом и проверьте порт сопряжения."
            } else if (config.connectPort == 0) {
                "Сопряжение выполнено. Укажите TLS-порт с главного экрана отладки и подключитесь."
            } else {
                operation.ensureActive()
                val connected = runCatching { AdbSession.connect(app, config.host, config.connectPort) }
                    .getOrDefault(false)
                if (connected) {
                    operation.commit { saveTlsConnection(app, config) }
                    "Сопряжение выполнено, TLS ADB подключён. Теперь активируйте офлайн-режим."
                } else "Сопряжение выполнено, но TLS ADB не подключён. Проверьте порт подключения."
            }
        }
    }

    /** Automatically verifies known prerequisites, reuses a working local ADB, or prepares it over TLS. */
    fun prepareAutomatically(config: BridgeSettings.Config, requestedPort: Int) {
        val app = getApplication<Application>()
        Shared.runner.start("prepare_automatic",
            "Автоматически проверяю настройки и подключение ADB…", AUTO_TIMEOUT_MS,
            "Автоматическая подготовка не завершилась за 90 секунд. Откройте шаги ниже и повторите проверку.",
            "Автоматическая подготовка остановлена. Проверьте отмеченные пункты и выполните указанный шаг вручную.") { operation ->
            operation.reportProgress("Проверка 1/5: версия Android…")
            if (android.os.Build.VERSION.SDK_INT < 30) return@start "Нужен Android 11 или новее. Офлайн-ADB на этой версии недоступен."

            operation.reportProgress("Проверка 2/5: режим разработчика…")
            val developerOptions = runCatching {
                Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
            }.getOrDefault(false)
            if (!developerOptions) return@start "Включите «Для разработчиков» в настройках телефона. После возврата CarBridge повторит проверку автоматически."

            operation.reportProgress("Проверка 3/5: отладка по USB…")
            val usbDebugging = runCatching {
                Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
            }.getOrDefault(false)
            if (!usbDebugging) return@start "Включите «Отладку по USB» в настройках разработчика. После возврата CarBridge повторит проверку автоматически."

            val localPort = config.offlinePort.takeIf { it in 1..65535 }
            val tlsConnected = config.connectPort in 1..65535 &&
                AdbSession.isConnectedFor(config.host, config.connectPort, AdbConnectionMode.TLS)
            val localConnected = localPort?.let {
                AdbSession.isConnectedFor("127.0.0.1", it, AdbConnectionMode.LOCAL_TCP)
            } ?: false
            if (localConnected) {
                operation.commit {
                    BridgeSettings.save(app, config.copy(connectionMode = BridgeSettings.MODE_LOCAL_TCP,
                        offlinePort = localPort!!))
                }
                operation.reportProgress("Локальный ADB уже подключён; проверка завершена.")
                return@start "Локальный ADB отвечает на 127.0.0.1:$localPort. Офлайн-режим готов; Wi-Fi не требуется."
            }

            if (!tlsConnected && localPort != null) {
                operation.reportProgress("Проверка 4/5: ищу сохранённый локальный ADB на 127.0.0.1:$localPort…")
                val local = OfflineAdbHealth.checkLocal(app, localPort, budgetMs = 7_000L,
                    cancelled = { runCatching { operation.ensureActive() }.isFailure },
                    report = { Log.i(TAG, it) })
                operation.ensureActive()
                if (local.connected) {
                    operation.commit {
                        BridgeSettings.save(app, config.copy(connectionMode = BridgeSettings.MODE_LOCAL_TCP,
                            offlinePort = localPort))
                    }
                    return@start local.summary
                }
            }

            val wifiDebugging = runCatching {
                Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", -1)
            }.getOrDefault(-1)
            if (!tlsConnected) {
                if (wifiDebugging == 0) return@start "Включите «Беспроводную отладку» и подключите телефон к любой сети Wi-Fi. После возврата CarBridge повторит проверку."
                if (config.connectPort !in 1..65535) {
                    return@start "Сначала выполните сопряжение и укажите TLS-порт из настроек беспроводной отладки. Затем подготовка продолжится автоматически."
                }
                operation.reportProgress("Проверка 4/5: подключаю TLS ADB ${config.host}:${config.connectPort}…")
                if (!AdbSession.connect(app, config.host, config.connectPort, AdbConnectionMode.TLS)) {
                    return@start "TLS ADB не подключился. Проверьте подключение к Wi-Fi, сопряжение и TLS-порт; затем повторите подготовку."
                }
                operation.commit { saveTlsConnection(app, config) }
            }

            operation.ensureActive()
            operation.reportProgress("Проверка 5/5: активирую офлайн-ADB и проверяю ответ телефона…")
            val activatedPort = AdbSession.activateOffline(app, requestedPort)
            operation.commit {
                BridgeSettings.save(app, BridgeSettings.read(app).copy(
                    connectionMode = BridgeSettings.MODE_LOCAL_TCP, offlinePort = activatedPort))
            }
            "Офлайн-подключение проверено: 127.0.0.1:$activatedPort. Порт сохранён. Wi-Fi в машине не нужен; оставьте «Отладку по USB» включённой."
        }
    }

    fun activateOffline(port: Int) {
        val app = getApplication<Application>()
        Shared.runner.start("activate_offline", "Переключаю стандартный ADB и проверяю локальное подключение… Ожидание не более 45 секунд.",
            OTHER_TIMEOUT_MS,
            "Время активации истекло (45 секунд). Порт мог открыться, но успешная активация не подтверждена. Повторите подготовку через Wi-Fi.",
            "Офлайн-активация не подтверждена. Порт мог открыться, но режим не сохранён. Повторите подготовку; " +
                "для ручного закрытия отключите и «Отладку по USB», и «Беспроводную отладку».") { operation ->
            val activatedPort = AdbSession.activateOffline(app, port)
            check(activatedPort in 1024..65535) { "ADB не вернул проверенный локальный порт" }
            operation.commit {
                BridgeSettings.save(app, BridgeSettings.read(app).copy(
                    connectionMode = BridgeSettings.MODE_LOCAL_TCP, offlinePort = activatedPort))
            }
            "Офлайн-подключение проверено: 127.0.0.1:$activatedPort. Порт сохранён. " +
                "Wi-Fi можно отключить; оставьте «Отладку по USB» включённой. Вернитесь и подключитесь к ГУ."
        }
    }

    fun checkOfflineConnection(config: BridgeSettings.Config) {
        val app = getApplication<Application>()
        Shared.runner.start("check_local", "Проверяю локальный ADB: 127.0.0.1:${config.offlinePort}. Wi-Fi включать не нужно. Ожидание не более 20 секунд.",
            CHECK_TIMEOUT_MS,
            "Локальный ADB не подтвердил подключение за 20 секунд. Wi-Fi для этой проверки не требуется. " +
                "Проверьте «Отладку по USB»; если ADB остановлен или телефон перезагружен, повторите активацию через Wi-Fi.",
            "Локальное подключение не подтверждено. Wi-Fi включать для проверки не нужно. " +
                "Проверьте «Отладку по USB»; после перезагрузки нужна активация.", dialogTitle = CHECK_TITLE) { operation ->
            val tail = SessionDiagnostics.captureTailRecorder()
            val result = OfflineAdbHealth.checkLocal(app, config.offlinePort,
                cancelled = { runCatching { operation.ensureActive() }.isFailure },
                report = { detail -> Log.i(TAG, detail); tail(detail) })
            operation.ensureActive()
            result.summary
        }
    }

    fun deactivateOffline(port: Int) {
        val app = getApplication<Application>()
        Shared.runner.start("deactivate_offline", "Запрашиваю отключение офлайн-порта ADB… Ожидание не более 45 секунд.",
            OTHER_TIMEOUT_MS,
            "Время отключения истекло (45 секунд). Закрытие порта не подтверждено; сохранённый режим оставлен. " +
                "Повторите попытку или выключите оба переключателя отладки вручную.",
            "Отключение порта не подтверждено. Сохранённый режим и порт оставлены; закрытым порт не считаем. " +
                "Повторите попытку или выключите и «Отладку по USB», и «Беспроводную отладку» вручную.") { operation ->
            if (AdbSession.deactivateOffline(app, port)) {
                operation.commit {
                    BridgeSettings.save(app, BridgeSettings.read(app).copy(
                        connectionMode = BridgeSettings.MODE_TLS, offlinePort = 0))
                }
                "ADB подтвердил отключение TCP-режима. Для новой офлайн-поездки повторите активацию через Wi-Fi."
            } else "Отключение порта не подтверждено. Сохранённый режим и порт оставлены. " +
                "Закройте порт вручную, отключив и «Отладку по USB», и «Беспроводную отладку», или повторите попытку."
        }
    }

    private fun saveTlsConnection(app: Application, config: BridgeSettings.Config) {
        BridgeSettings.save(app, BridgeSettings.read(app).copy(
            host = config.host, connectPort = config.connectPort, connectionMode = BridgeSettings.MODE_TLS))
    }

    /** The queue outlives a finished screen but its tasks are always bounded and contain no views. */
    private object Shared {
        private val main = Handler(Looper.getMainLooper())
        val state = MutableLiveData(AdbOperationRunner.State())
        val runner = AdbOperationRunner(
            cancelTransport = { AdbSession.disconnect() },
            onState = { next ->
                val publish = Runnable {
                    if (next.revision >= (state.value?.revision ?: -1)) state.value = next
                }
                if (Looper.myLooper() == Looper.getMainLooper()) publish.run() else main.post(publish)
            },
            log = { Log.i(TAG, it) },
        )
    }

    companion object {
        fun isOperationBusy(): Boolean = Shared.runner.state().busy

        private const val TAG = "CarBridge/AdbSetup"
        private const val CHECK_TITLE = "Проверка локального ADB"
        private const val CHECK_TIMEOUT_MS = 20_000L
        private const val OTHER_TIMEOUT_MS = 45_000L
        private const val AUTO_TIMEOUT_MS = 90_000L
    }
}
