package com.example.carbridge

import android.content.ActivityNotFoundException
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.example.carbridge.adb.AdbConnectionMode
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.capture.CaptureService

/** Home TLS setup and explicit transition to Android's classic local ADB endpoint. */
class AdbSetupActivity : AppCompatActivity() {
    private lateinit var hostInput: EditText
    private lateinit var connectPortInput: EditText
    private lateinit var pairPortInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var offlinePortInput: EditText
    private lateinit var status: TextView
    private lateinit var connectionState: TextView
    private val checklistRows = mutableMapOf<String, Pair<TextView, TextView>>()
    private lateinit var progress: ProgressBar
    private lateinit var operations: AdbSetupViewModel
    private val operationButtons = mutableListOf<Button>()
    private var lastCompletedOperation = 0L
    private var pendingDialogId = 0L
    private var autoPrepareAfterResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        operations = ViewModelProvider(this)[AdbSetupViewModel::class.java]
        lastCompletedOperation = savedInstanceState?.getLong("last_completed_operation") ?: 0L
        autoPrepareAfterResume = savedInstanceState?.getBoolean("auto_prepare_after_resume")
            ?: (savedInstanceState == null)
        title = "Офлайн-подключение"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val config = BridgeSettings.read(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun text(value: String): TextView = TextView(this).apply {
            text = value
            setPadding(0, 8, 0, 12)
            layout.addView(this)
        }
        fun field(label: String, initial: String = "", numeric: Boolean = false): EditText =
            EditText(this).apply {
                hint = label
                setText(initial)
                setSingleLine()
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                layout.addView(this)
            }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            layout.addView(this)
        }

        text("Соединитесь с любой сетью Wi-Fi, выполните сопряжение и TLS-подключение, затем активируйте офлайн-режим. " +
            "В машине Wi-Fi и точка доступа не нужны: CarBridge подключится к этому же телефону через 127.0.0.1. " +
            "Требуется Android 11+. Root и Shizuku не требуются.")
        connectionState = text("")
        text("Проверка подключений")
        fun checkRow(key: String, label: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val dot = TextView(this).apply {
                text = "○"
                textSize = 20f
                setPadding(0, 0, (10 * resources.displayMetrics.density).toInt(), 0)
            }
            val detail = TextView(this).apply { text = label }
            row.addView(dot)
            row.addView(detail, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            layout.addView(row)
            checklistRows[key] = dot to detail
        }
        checkRow("android", "Android 11 или новее")
        checkRow("developer", "Режим разработчика")
        checkRow("usb", "Отладка по USB")
        checkRow("wireless", "Беспроводная отладка")
        checkRow("adb", "ADB-подключение")
        val autoButton = button("Проверить и подготовить автоматически") { startAutomaticPreparation() }
        operationButtons += autoButton
        text("CarBridge проверит пункты по очереди, подключится к сохранённому TLS ADB и активирует локальный ADB. " +
            "Защищённые переключатели Android приложение меняет только через системные настройки.")
        text("После перезагрузки телефона или остановки отладки активацию может потребоваться повторить. " +
            "После остановки процесса приложения также проверьте подключение до поездки: если локальный порт недоступен, нужна повторная активация через Wi-Fi. " +
            "Переключение режима останавливает текущую сессию ГУ; после настройки нажмите «Подключиться к ГУ» заново.")
        button("Открыть настройки разработчика") { openDebugSettings(false) }
        text("Для офлайн-режима включите «Отладку по USB» и оставляйте этот переключатель включённым. " +
            "Кабель к компьютеру не нужен; USB-кабель используется только для CarLife с ГУ.")
        text("1. Подключение ADB через Wi-Fi")
        button("Открыть беспроводную отладку") { openDebugSettings(true) }
        text("Включите «Беспроводную отладку». IP и порт подключения указаны на её главном экране. " +
            "Для этого же телефона можно попробовать 127.0.0.1; если не работает, введите IP из Android. " +
            "Во время подготовки телефон должен оставаться в той же сети Wi-Fi.")
        hostInput = field("IP телефона для TLS", savedInstanceState?.getString("host") ?: config.host)
        connectPortInput = field("TLS-порт подключения (главный экран отладки)",
            savedInstanceState?.getString("connect_port") ?: config.connectPort.takeIf { it > 0 }?.toString().orEmpty(), true)
        operationButtons += button("Подключить TLS ADB") { connectTls() }
        text("При первом запуске Android: «Подключить устройство с помощью кода сопряжения». " +
            "Порт из окна с кодом отличается от порта подключения. Оставьте это окно открытым; на телефоне удобно использовать разделённый экран.")
        pairPortInput = field("Порт сопряжения (окно с кодом)", numeric = true)
        codeInput = field("Шестизначный код", numeric = true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        operationButtons += button("Сопрячь по коду") { pair() }
        text("Код не сохраняется и не записывается в журнал. После сопряжения TLS-подключение выполняется автоматически, " +
            "если его порт заполнен. Иначе узнайте порт на главном экране отладки и нажмите кнопку подключения.")
        text("2. Активировать поездку без Wi-Fi")
        offlinePortInput = field("TCP-порт: 0 — автоматически; вручную 1024–65535",
            savedInstanceState?.getString("offline_port") ?: config.offlinePort.takeIf { it > 0 }?.toString().orEmpty().ifEmpty { "0" }, true)
        text("Используется стандартный ADB Android, а не отдельный демон CarBridge. Он переводится в классический TCP-режим. " +
            "CarBridge всегда подключается к нему через 127.0.0.1, но сам порт ADB может слушать и сетевые интерфейсы телефона. " +
            "Доступ требует разрешённого ADB-ключа. Активируйте его в доверенной сети. После успешной проверки приложение сохранит порт.")
        operationButtons += button("Активировать офлайн-режим") { activateOffline() }
        operationButtons += button("Проверить локальный ADB") { checkOfflineConnection() }
        text("Для проверки до поездки выключите Wi-Fi, затем нажмите «Проверить локальный ADB». ГУ и USB-кабель для этой проверки не нужны.")
        operationButtons += button("Отключить офлайн-порт ADB") { deactivateOffline() }
        text("Отключение считается успешным только после ответа ADB. Если подтверждения нет, приложение сохранит известный порт. " +
            "Для ручного закрытия отключите оба переключателя: «Отладка по USB» и «Беспроводная отладка» в настройках разработчика, либо перезагрузите телефон; " +
            "перед следующей поездкой снова подготовьте подключение и активируйте режим.")
        button("Готово") { finish() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(layout) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val feedback = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Выполняется операция ADB"
        }
        status = TextView(this).apply {
            textSize = 15f
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 8, 0, 8)
            // A long validation or activation explanation can always be opened in full.
            setOnClickListener {
                if (pendingDialogId == 0L && !supportFragmentManager.isStateSaved &&
                    supportFragmentManager.findFragmentByTag(RESULT_DIALOG) == null) {
                    pendingDialogId = -1L
                    AdbSetupResultDialog.create("Состояние ADB", text.toString(), 0)
                        .show(supportFragmentManager, RESULT_DIALOG)
                }
            }
        }
        feedback.addView(progress)
        feedback.addView(status)
        root.addView(feedback)
        setContentView(root)
        operations.state.observe(this) { state ->
            if (state.dialogId == 0L && pendingDialogId > 0L) pendingDialogId = 0L
            status.text = state.message
            progress.visibility = if (state.busy) View.VISIBLE else View.GONE
            operationButtons.forEach { it.isEnabled = !state.busy && Build.VERSION.SDK_INT >= 30 }
            if (state.busy) {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(status.windowToken, 0)
            } else if (state.operationId > lastCompletedOperation) {
                lastCompletedOperation = state.operationId
                offlinePortInput.setText(BridgeSettings.read(this).offlinePort.takeIf { it > 0 }?.toString() ?: "0")
            }
            refreshConnectionState()
            refreshChecklist()
            if (!state.busy && autoPrepareAfterResume) {
                autoPrepareAfterResume = false
                window.decorView.post { startAutomaticPreparation() }
            }
            showPendingResult()
        }
        refreshConnectionState()
        refreshChecklist()
        if (Build.VERSION.SDK_INT < 30) {
            showFeedback("Этот режим требует Android 11 или новее")
            operationButtons.forEach { it.isEnabled = false }
        }
    }

    private fun endpoint(requirePort: Boolean): BridgeSettings.Config? {
        val host = hostInput.text.toString().trim()
        if (host.isEmpty() || host.any { it.isWhitespace() } || host.contains('/')) {
            showFeedback("Введите IP телефона без схемы и порта")
            return null
        }
        val raw = connectPortInput.text.toString().trim()
        val port = raw.toIntOrNull() ?: 0
        if ((requirePort || raw.isNotEmpty()) && port !in 1..65535) {
            showFeedback("Введите TLS-порт подключения от 1 до 65535 с главного экрана отладки")
            return null
        }
        return BridgeSettings.read(this).copy(host = host, connectPort = port)
    }

    private fun stopHuSession() {
        stopService(Intent(this, CaptureService::class.java))
    }

    private fun connectTls() {
        if (operations.reportBusy()) return
        val config = endpoint(true) ?: return
        stopHuSession()
        operations.connectTls(config)
    }

    private fun startAutomaticPreparation() {
        if (operations.reportBusy()) return
        val config = endpoint(false) ?: return
        val port = offlinePortInput.text.toString().trim().ifEmpty { "0" }.toIntOrNull()
        if (port == null || (port != 0 && port !in 1024..65535)) {
            showFeedback("Введите 0 для автоматического порта или номер от 1024 до 65535")
            return
        }
        autoPrepareAfterResume = false
        BridgeSettings.save(this, config)
        stopHuSession()
        operations.prepareAutomatically(config, port)
    }

    private fun pair() {
        if (operations.reportBusy()) return
        val config = endpoint(false) ?: return
        val pairPort = pairPortInput.text.toString().trim().toIntOrNull()
        if (pairPort == null || pairPort !in 1..65535) {
            showFeedback("Введите порт сопряжения из окна с кодом")
            return
        }
        if (pairPort == config.connectPort) {
            showFeedback("Порт сопряжения и TLS-порт подключения должны отличаться")
            return
        }
        val code = codeInput.text.toString().filterNot { it.isWhitespace() }
        if (!code.matches(Regex("[0-9]{6}"))) {
            showFeedback("Введите шестизначный код сопряжения")
            return
        }
        BridgeSettings.save(this, config)
        codeInput.text.clear()
        stopHuSession()
        operations.pair(config, pairPort, code)
    }

    private fun activateOffline() {
        if (operations.reportBusy()) return
        val port = offlinePortInput.text.toString().trim().ifEmpty { "0" }.toIntOrNull()
        if (port == null || (port != 0 && port !in 1024..65535)) {
            showFeedback("Введите 0 для автоматического порта или номер от 1024 до 65535")
            return
        }
        val usbDebug = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!usbDebug) {
            showFeedback("Включите «Отладку по USB» в настройках разработчика и оставьте её включённой. Затем повторите активацию.")
            return
        }
        if (!AdbSession.isConnected() || AdbSession.connectionMode != AdbConnectionMode.TLS) {
            showFeedback(if (AdbSession.connectionMode == AdbConnectionMode.LOCAL_TCP)
                "Локальное подключение уже подготовлено. Для новой активации или смены порта сначала подключите TLS ADB через Wi-Fi."
            else "Сначала подключите TLS ADB через Wi-Fi. Затем повторите активацию офлайн-режима.")
            return
        }
        stopHuSession()
        operations.activateOffline(port)
    }

    private fun checkOfflineConnection() {
        if (operations.reportBusy()) return
        val config = BridgeSettings.read(this)
        if (config.connectionMode != BridgeSettings.MODE_LOCAL_TCP || config.offlinePort !in 1024..65535) {
            showFeedback("Сначала выполните успешную активацию через Wi-Fi офлайн-режима. Wi-Fi нужен для активации; после её успеха для проверки выключите Wi-Fi и оставьте его выключенным.", checkResult = true)
            return
        }
        stopHuSession()
        operations.checkOfflineConnection(config)
    }

    private fun deactivateOffline() {
        if (operations.reportBusy()) return
        val entered = offlinePortInput.text.toString().trim().toIntOrNull()
        val port = entered?.takeIf { it in 1..65535 } ?: BridgeSettings.read(this).offlinePort
        if (port !in 1..65535) {
            showFeedback("Нет известного TCP-порта. Если активация не завершилась, закройте ADB вручную: выключите и «Отладку по USB», и «Беспроводную отладку» в настройках разработчика.")
            return
        }
        stopHuSession()
        operations.deactivateOffline(port)
    }

    private fun showFeedback(message: String, checkResult: Boolean = false) =
        operations.feedback(message, checkResult)

    private fun showPendingResult() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val state = operations.state.value ?: return
        if (state.dialogId == 0L || pendingDialogId != 0L ||
            supportFragmentManager.findFragmentByTag(RESULT_DIALOG) != null) return
        pendingDialogId = state.dialogId
        AdbSetupResultDialog.create(state.dialogTitle, state.message, state.dialogId)
            .show(supportFragmentManager, RESULT_DIALOG)
    }

    internal fun onResultDialogDismissed() {
        pendingDialogId = 0L
        status.post { showPendingResult() }
    }

    private fun refreshConnectionState() {
        val config = BridgeSettings.read(this)
        val saved = if (config.connectionMode == BridgeSettings.MODE_LOCAL_TCP)
            "Сохранён офлайн-порт: 127.0.0.1:${config.offlinePort}" else "Выбран режим ADB через Wi-Fi"
        val live = when {
            !AdbSession.isConnected() -> "ADB не подключён"
            AdbSession.connectionMode == AdbConnectionMode.LOCAL_TCP -> "Локальный TCP ADB подключён"
            else -> "TLS ADB подключён"
        }
        val knownPort = config.offlinePort.takeIf { it > 0 }?.let { "\nИзвестный офлайн-порт: $it" }.orEmpty()
        connectionState.text = "$saved\n$live$knownPort"
    }

    private fun refreshChecklist() {
        if (checklistRows.isEmpty()) return
        val config = BridgeSettings.read(this)
        val developer = globalFlag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        val usbDebug = globalFlag(Settings.Global.ADB_ENABLED)
        val wifiDebug = runCatching {
            Settings.Global.getString(contentResolver, "adb_wifi_enabled")?.toIntOrNull()?.let { it != 0 }
        }.getOrNull()
        val tlsConnected = config.connectPort in 1..65535 &&
            AdbSession.isConnectedFor(config.host, config.connectPort, AdbConnectionMode.TLS)
        val localConnected = config.offlinePort in 1..65535 &&
            AdbSession.isConnectedFor("127.0.0.1", config.offlinePort, AdbConnectionMode.LOCAL_TCP)
        val connected = tlsConnected || localConnected

        setChecklistRow("android", Build.VERSION.SDK_INT >= 30,
            if (Build.VERSION.SDK_INT >= 30) "Android 11 или новее" else "Нужен Android 11 или новее")
        setChecklistRow("developer", developer, if (developer == true) "Режим разработчика включён"
            else "Режим разработчика: включите в настройках")
        setChecklistRow("usb", usbDebug, if (usbDebug == true) "Отладка по USB включена"
            else "Отладка по USB: включите в настройках разработчика")
        setChecklistRow("wireless", if (tlsConnected) true else wifiDebug,
            when {
                tlsConnected -> "TLS ADB подключён"
                wifiDebug == true -> "Беспроводная отладка включена"
                wifiDebug == false -> "Беспроводная отладка: включите по Wi-Fi"
                else -> "Беспроводная отладка: проверю при подключении"
            })
        setChecklistRow("adb", connected, when {
            localConnected -> "Локальный ADB отвечает на 127.0.0.1:${config.offlinePort}"
            tlsConnected -> "TLS ADB подключён; офлайн-режим можно активировать"
            config.offlinePort > 0 -> "Проверяю сохранённый локальный ADB…"
            else -> "Локальный ADB появится после активации"
        })
    }

    private fun globalFlag(key: String): Boolean? = runCatching {
        Settings.Global.getInt(contentResolver, key, -1).takeIf { it >= 0 }?.let { it != 0 }
    }.getOrNull()

    private fun setChecklistRow(key: String, complete: Boolean?, label: String) {
        val (dot, detail) = checklistRows[key] ?: return
        dot.text = if (complete == true) "●" else if (complete == false) "○" else "●"
        dot.setTextColor(when (complete) {
            true -> Color.rgb(34, 168, 83)
            false -> Color.rgb(140, 148, 156)
            null -> Color.rgb(218, 160, 35)
        })
        detail.text = label
    }

    private fun openDebugSettings(wireless: Boolean) {
        autoPrepareAfterResume = true
        val actions = if (wireless) listOf("android.settings.WIRELESS_DEBUGGING_SETTINGS",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS)
        else listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS)
        for (action in actions) {
            try { startActivity(Intent(action)); return }
            catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        showFeedback("Откройте настройки телефона вручную: Для разработчиков → Отладка")
    }

    override fun onResume() {
        super.onResume()
        if (::connectionState.isInitialized) {
            refreshConnectionState()
            refreshChecklist()
            if (autoPrepareAfterResume && !operations.busy) {
                autoPrepareAfterResume = false
                window.decorView.post { startAutomaticPreparation() }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        showPendingResult()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("host", hostInput.text.toString())
        outState.putString("connect_port", connectPortInput.text.toString())
        outState.putString("offline_port", offlinePortInput.text.toString())
        outState.putLong("last_completed_operation", lastCompletedOperation)
        outState.putBoolean("auto_prepare_after_resume", autoPrepareAfterResume)
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object { private const val RESULT_DIALOG = "adb_setup_result" }
}

/** FragmentManager restores an unread check result if the phone rotates. */
class AdbSetupResultDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AlertDialog.Builder(requireContext())
        .setTitle(requireArguments().getString("title"))
        .setMessage(requireArguments().getString("message"))
        .setPositiveButton("Понятно") { _, _ -> acknowledge() }
        .create()

    override fun onCancel(dialog: android.content.DialogInterface) {
        acknowledge()
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (activity as? AdbSetupActivity)?.onResultDialogDismissed()
    }

    private fun acknowledge() {
        val id = requireArguments().getLong("id")
        if (id != 0L) ViewModelProvider(requireActivity())[AdbSetupViewModel::class.java].acknowledgeDialog(id)
    }

    companion object {
        fun create(title: String, message: String, id: Long) = AdbSetupResultDialog().apply {
            arguments = Bundle().apply {
                putString("title", title)
                putString("message", message)
                putLong("id", id)
            }
        }
    }
}
