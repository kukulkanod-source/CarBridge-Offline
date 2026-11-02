package com.example.carbridge

import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Icon desktop rendered only on the CarBridge virtual display. */
class HuHomePresentation(
    outerContext: Context,
    display: Display,
    private val interfaceDpi: Int = 200,
    private val outputWidth: Int = 0,
    private val reserveDock: Boolean = true,
    private val onLaunchApp: (ComponentName, Int) -> Unit = { _, _ -> }
) : Presentation(outerContext, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {
    private val main = Handler(Looper.getMainLooper())
    private var grid: GridView? = null
    private var status: TextView? = null
    private var density = 1f
    private var revision = 0
    private var content: LinearLayout? = null
    private var musicVisible = false
    private var musicOnRight = true
    fun setMusicLayout(visible: Boolean, onRight: Boolean) {
        musicVisible = visible; musicOnRight = onRight
        val width = context.resources.displayMetrics.widthPixels
        val available = if (visible) (width * 0.70f).toInt() else width
        val inset = if (reserveDock) px(HuDockDimensions.HEIGHT_DP) else 0
        val height = (context.resources.displayMetrics.heightPixels - inset).coerceAtLeast(1)
        content?.layoutParams = android.widget.FrameLayout.LayoutParams(available, height).apply {
            leftMargin = if (visible && !onRight) width - available else 0
        }
        grid?.numColumns = ((available / density - 40) / 120).toInt().coerceIn(3, 8)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val width = context.resources.displayMetrics.widthPixels
        val scale = if (outputWidth > 0) width.toFloat() / outputWidth else 1f
        density = (interfaceDpi.coerceIn(80, 320) / 160f * scale).coerceAtMost(width / 700f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(12, 19, 30))
                cornerRadius = HuWindowCorners.radiusPx(density).toFloat()
            }
            clipToOutline = true
        }
        root.addView(text("Приложения", 23f).apply { setPadding(px(8), 0, 0, px(8)) })
        grid = GridView(context).apply {
            numColumns = ((width / density - 40) / 120).toInt().coerceIn(4, 8)
            horizontalSpacing = px(12)
            verticalSpacing = px(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(0, px(6), 0, px(6))
            clipToPadding = false
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        status = text("Загрузка приложений…", 14f).also { root.addView(it) }
        content = root
        val frame = android.widget.FrameLayout(context)
        frame.addView(root, android.widget.FrameLayout.LayoutParams(width, -1))
        setContentView(frame)
        setMusicLayout(musicVisible, musicOnRight)
        val token = ++revision
        HuAppCatalog.load(context) { catalog ->
            if (revision != token || !isShowing) return@load
            val recent = HuLauncherStore.recent(context)
            val apps = catalog.sortedWith(compareBy<HuAppCatalog.App> {
                recent.indexOf(it.component.packageName).let { index -> if (index < 0) Int.MAX_VALUE else index }
            }.thenBy { it.label.lowercase() })
                grid?.adapter = object : BaseAdapter() {
                    override fun getCount() = apps.size
                    override fun getItem(position: Int) = apps[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, recycled: View?, parent: ViewGroup?): View {
                        val app = apps[position]
                        return LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(px(6), px(10), px(6), px(8))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(Color.rgb(24, 35, 50)); cornerRadius = px(16).toFloat()
                            }
                            addView(ImageView(context).apply { setImageDrawable(app.icon?.newDrawable(context.resources)?.mutate()) }, LinearLayout.LayoutParams(px(58), px(58)))
                            addView(text(app.label, 15f).apply {
                                gravity = Gravity.CENTER
                                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                                setPadding(0, px(8), 0, 0)
                            }, LinearLayout.LayoutParams(-1, px(48)))
                            contentDescription = "Открыть ${app.label}"
                            setOnClickListener {
                                showStatus("Открываю ${app.label}…")
                                runCatching { onLaunchApp(app.component, display.displayId) }
                                    .onFailure { showStatus("Не удалось открыть ${app.label}") }
                            }
                        }
                    }
                }
                showStatus(if (apps.isEmpty()) "Приложения не найдены" else "Нажмите значок, чтобы открыть приложение")
        }
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(-1, -1)
    }
    override fun onStop() {
        revision++
        super.onStop()
    }
    fun showStatus(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { showStatus(message) }; return }
        status?.text = message
    }
    fun showHome() { grid?.smoothScrollToPosition(0) }
    fun handleBack(): Boolean { showHome(); return true }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { handleBack() }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density)
    }
    private fun px(value: Int) = (value * density).roundToInt()
}
