package com.example.carbridge

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** One current instruction; later checks remain grey until the blocked step is fixed. */
internal class ConnectionWizardView(
    context: Context,
    private val action: (ConnectionWizard.Action) -> Unit,
    private val submit: (String) -> Unit,
    private val manual: () -> Unit,
    private val manualConnect: () -> Unit,
    private val allowPairNotification: () -> Unit,
    private val stop: () -> Unit,
) : ScrollView(context) {
    private val rows = linkedMapOf<ConnectionWizard.Step, TextView>()
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setPadding(0, 10, 0, 14)
    }
    private val heading = text("Подключение к ГУ", 25f)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
    private val instruction = text("", 18f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val primary = Button(context)
    private val pairControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val code = EditText(context).apply {
        hint = "6 цифр из окна сопряжения Android"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        isSaveEnabled = false
        setSingleLine()
        filters = arrayOf(android.text.InputFilter.LengthFilter(6))
    }
    private val pairButton = Button(context).apply {
        text = "Подтвердить код"
        setOnClickListener { val secret = code.text.toString().trim(); code.text.clear(); submit(secret) }
    }
    private val ports = text("", 13f)
    private val notificationAccess = Button(context).apply {
        text = "Разрешить уведомление для ввода кода"
        setOnClickListener { allowPairNotification() }
    }
    private val connectPortButton = Button(context).apply {
        text = "Указать порт подключения, если поиск не сработал"
        setOnClickListener { manualConnect() }
    }
    private var currentAction = ConnectionWizard.Action.RETRY
    private val stopButton = Button(context).apply { text = "Остановить соединение"; setOnClickListener { stop() } }
    init {
        body.addView(heading)
        body.addView(text("CarBridge проверяет пункты по очереди. Выполните подсказку текущего шага — после возврата проверка продолжится сама.", 15f))
        ConnectionWizard.Step.entries.forEach { step ->
            rows[step] = text("○  ${step.label}", 17f)
        }
        body.addView(progress)
        body.addView(instruction)
        primary.setOnClickListener { action(currentAction) }
        body.addView(primary)
        body.addView(connectPortButton)
        pairControls.addView(code)
        pairControls.addView(pairButton)
        pairControls.addView(ports)
        pairControls.addView(notificationAccess)
        pairControls.addView(text("Если окно Android закрывается при переходе сюда, используйте кнопку «Ввести код» в уведомлении CarBridge. Для этого разрешите уведомления CarBridge на телефоне.", 14f))
        pairControls.addView(Button(context).apply { text = "Указать адрес и порты вручную"; setOnClickListener { manual() } })
        body.addView(pairControls)
        body.addView(text("Готовность телефона", 18f))
        rows.values.forEach { body.addView(it) }
        body.addView(text("Дополнительно: доступ к уведомлениям включает музыку и уведомления на ГУ. Он настраивается во вкладке «Настройки» и не блокирует подключение карт.", 14f))
        body.addView(stopButton)
        addView(body)
    }
    fun render(state: ConnectionWizard.State) {
        rows.forEach { (step, view) ->
            view.visibility = if (step in ConnectionWizard.PREPARATION_STEPS && step !in state.rows) View.GONE else View.VISIBLE
            val mark = state.rows[step] ?: ConnectionWizard.Mark.WAITING
            val glyph = when (mark) {
                ConnectionWizard.Mark.PASSED -> "●"
                ConnectionWizard.Mark.BLOCKED -> "●"
                ConnectionWizard.Mark.CHECKING -> "◉"
                else -> "○"
            }
            view.text = "$glyph  ${step.label}"
            view.setTextColor(when (mark) {
                ConnectionWizard.Mark.PASSED -> Color.rgb(33, 150, 83)
                ConnectionWizard.Mark.BLOCKED -> Color.rgb(210, 48, 48)
                ConnectionWizard.Mark.CHECKING -> Color.rgb(40, 130, 220)
                else -> Color.GRAY
            })
        }
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        instruction.text = state.message
        currentAction = state.action
        primary.text = when (state.action) {
            ConnectionWizard.Action.DEVICE_SETTINGS -> "Открыть сведения о телефоне"
            ConnectionWizard.Action.DEBUG_SETTINGS -> "Открыть настройки разработчика"
            ConnectionWizard.Action.WIFI -> "Соединиться с Wi-Fi"
            ConnectionWizard.Action.WIRELESS, ConnectionWizard.Action.PAIR -> "Открыть беспроводную отладку"
            ConnectionWizard.Action.OVERLAY -> "Разрешить панель управления"
            ConnectionWizard.Action.APP -> "Выбрать приложение"
            ConnectionWizard.Action.USB_PERMISSION -> "Разрешить USB-подключение"
            else -> if (state.ready) "Подключиться к ГУ" else "Проверить и продолжить"
        }
        primary.visibility = if (state.connected || state.action == ConnectionWizard.Action.NONE) View.GONE else View.VISIBLE
        primary.isEnabled = !state.busy
        connectPortButton.visibility = if (state.action in setOf(ConnectionWizard.Action.WIRELESS, ConnectionWizard.Action.PAIR) && !state.busy) View.VISIBLE else View.GONE
        pairControls.visibility = if (state.action == ConnectionWizard.Action.PAIR) View.VISIBLE else View.GONE
        pairButton.isEnabled = !state.busy
        notificationAccess.visibility = if (PairingPrompt.available(context)) View.GONE else View.VISIBLE
        ports.text = if (state.pairPort > 0) "Порт сопряжения найден автоматически: ${state.pairPort}" else "Ищу порт этого телефона. Откройте в Android окно с кодом сопряжения."
        stopButton.isEnabled = !state.busy
    }
}
