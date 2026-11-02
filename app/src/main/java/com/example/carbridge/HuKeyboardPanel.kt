package com.example.carbridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Explicit text-entry pad. Leaves the target application's editor focused. */
internal class HuKeyboardPanel(private val context: Context, private val manager: WindowManager,
    private val width: Int, private val height: Int, private val bottom: Int, private val density: Float,
    private val paste: (String) -> Boolean, private val key: (Int) -> Unit, private val onClose: () -> Unit) {
    private var root: FrameLayout? = null
    private var pad: LinearLayout? = null
    private var preview: TextView? = null
    private val buffer = StringBuilder()
    private var russian = true
    private var upper = false
    private var symbols = false
    private var moreSymbols = false
    fun show() {
        if (root != null) return
        val frame = FrameLayout(context).apply { setOnClickListener { close(); onClose() } }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(6), px(8), px(6))
            setBackgroundColor(Color.rgb(22, 31, 43))
            isClickable = true
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("Текст для выбранного поля · ввод через вставку", 12f), LinearLayout.LayoutParams(0, px(28), 1f))
        top.addView(button("×", "Закрыть клавиатуру") { close(); onClose() }, LinearLayout.LayoutParams(px(44), px(28)))
        panel.addView(top)
        val entry = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        preview = label("", 18f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        entry.addView(preview, LinearLayout.LayoutParams(0, px(38), 1f))
        entry.addView(button("Вставить", "Вставить текст в выбранное поле") {
            if (buffer.isNotEmpty() && paste(buffer.toString())) { buffer.clear(); close(); onClose() }
        }, LinearLayout.LayoutParams(px(100), px(38)))
        panel.addView(entry)
        pad = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(pad)
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = bottom })
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; title = "CarBridge text input" }
        root = frame
        try { manager.addView(frame, params); drawKeys(); render() }
        catch (error: Exception) { root = null; buffer.clear(); onClose(); SessionDiagnostics.event("HU keyboard unavailable: ${error.javaClass.simpleName}") }
    }
    private fun drawKeys() {
        val target = pad ?: return
        target.removeAllViews()
        fun row(keys: List<Pair<String, () -> Unit>>) {
            val line = LinearLayout(context)
            keys.forEach { (value, action) -> line.addView(button(value, value, action),
                LinearLayout.LayoutParams(0, px(34), 1f).apply { setMargins(px(2), px(2), px(2), 0) }) }
            target.addView(line)
        }
        val letters = if (symbols) {
            if (moreSymbols) listOf("[]{}<>\\|", "~`^_=+*%", "€£¥₽$¢", "©®™°•…")
            else listOf("1234567890", "+-*/=_%@#&", "!?:;,.", "()\"\'₽$")
        } else if (russian) listOf("1234567890", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё")
            else listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
        letters.forEach { chars -> row(chars.map { c ->
            val value = if (upper) c.uppercaseChar().toString() else c.toString()
            value to { append(value) }
        }) }
        row(listOf((if (symbols) "АБВ" else "?123") to { symbols = !symbols; drawKeys() },
            (if (russian) "EN" else "РУ") to { russian = !russian; symbols = false; drawKeys() },
            (if (symbols) "#+=" else "⇧") to { if (symbols) moreSymbols = !moreSymbols else upper = !upper; drawKeys() }, "." to { append(".") }, "," to { append(",") },
            "@" to { append("@") }, "/" to { append("/") }, "?" to { append("?") }, "-" to { append("-") },
            "Пробел" to { append(" ") }, "⌫" to { backspace() }, "↵" to {
                if (buffer.isNotEmpty()) { if (paste(buffer.toString())) { buffer.clear(); render() } }
                else key(KeyEvent.KEYCODE_ENTER)
            }))
    }
    private fun append(value: String) { if (buffer.length + value.length <= 2000) { buffer.append(value); render() } }
    private fun backspace() {
        if (buffer.isEmpty()) key(KeyEvent.KEYCODE_DEL)
        else { val last = buffer.offsetByCodePoints(buffer.length, -1); buffer.delete(last, buffer.length); render() }
    }
    private fun render() { preview?.text = buffer.toString().ifBlank { "Наберите текст, затем нажмите «Вставить»" } }
    fun close() {
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root = null; pad = null; preview = null; buffer.clear()
    }
    private fun button(value: String, description: String, action: () -> Unit) = Button(context).apply {
        text = value; contentDescription = description; isAllCaps = false
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * density)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(43, 59, 78)); cornerRadius = px(5).toFloat() }
        setOnClickListener { action() }
    }
    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density)
        setPadding(px(5), 0, px(5), 0); gravity = Gravity.CENTER_VERTICAL
    }
    private fun px(value: Int) = (value * density).roundToInt()
}
