package com.example.carbridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import kotlin.math.roundToInt

/** One window owns both the drawer and its outside-tap area. Editing never launches an app. */
internal class HuFavoritesPanel(private val context: Context, private val wm: WindowManager,
    private val width: Int, private val height: Int, private val bottom: Int, private val density: Float,
    private val launch: (String) -> Unit, private val beforeOpen: () -> Unit) {
    private val prefs = context.getSharedPreferences("hu_favorites", Context.MODE_PRIVATE)
    private var right = prefs.getBoolean("right", true)
    private val selected = prefs.getString("packages", "").orEmpty().split('|').filter { it.isNotBlank() }.toMutableList()
    private var handle: View? = null
    private var window: FrameLayout? = null
    private var pane: LinearLayout? = null
    private var body: LinearLayout? = null
    private var heading: TextView? = null
    private var editButton: TextView? = null
    private var sideButton: TextView? = null
    private var editing = false
    private var apps = emptyList<HuAppCatalog.App>()
    private var loaded = false
    private var revision = 0
    val isOpen: Boolean get() = window != null
    private fun px(n: Int) = (n * density).roundToInt()
    private fun params(w: Int, h: Int, gravity: Int) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT).apply { this.gravity = gravity; title = "CarBridge favorites" }
    fun showHandle() {
        handle?.let { runCatching { wm.removeViewImmediate(it) } }; handle = null
        var start = 0f
        val edge = TextView(context).apply {
            text = "★"; gravity = Gravity.CENTER; setTextColor(Color.rgb(137,198,255))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f*density)
            setBackgroundColor(Color.rgb(25,39,56)); contentDescription = "Избранные приложения"
            setOnClickListener { if (isOpen) dismiss("handle") else open(false) }
            setOnTouchListener { v,e ->
                when(e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { start=e.rawX; true }
                    MotionEvent.ACTION_UP -> {
                        if ((this@HuFavoritesPanel.right && start-e.rawX>px(20)) || (!this@HuFavoritesPanel.right && e.rawX-start>px(20))) open(false)
                        else v.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        runCatching { wm.addView(edge, params(px(25),px(52),Gravity.TOP or if(right) Gravity.END else Gravity.START).apply {
            y = (height*0.70f).roundToInt().coerceAtMost((height-bottom-px(52)).coerceAtLeast(0))
        }) }.onSuccess { handle=edge; edge.visibility=if(isOpen) View.GONE else View.VISIBLE }
    }
    private fun save() { prefs.edit().putString("packages",selected.joinToString("|")).apply() }
    private fun text(value: String, size: Float=16f) = TextView(context).apply {
        text=value; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX,size*density)
        gravity=Gravity.CENTER_VERTICAL; setPadding(px(10),px(8),px(10),px(8))
    }
    private fun action(value: String, block: () -> Unit) = text(value).apply {
        setTextColor(Color.rgb(137,198,255)); setOnClickListener { block() }
    }
    private fun paneLayout() = FrameLayout.LayoutParams((width*0.44f).roundToInt(),-1,
        Gravity.TOP or if(right) Gravity.END else Gravity.START)
    fun open(edit: Boolean) {
        if (isOpen) { editing=edit; render(); return }
        beforeOpen()
        editing=edit; loaded=false
        val frame=FrameLayout(context)
        frame.addView(View(context).apply {
            setBackgroundColor(0x44000000); contentDescription="Закрыть избранное"
            setOnClickListener { dismiss("outside") }
        },FrameLayout.LayoutParams(-1,-1))
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(22,32,46))
            isClickable=true // Blank space inside the drawer must not reach the outside-tap area.
        }
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        heading=text("Избранное",18f)
        header.addView(heading,LinearLayout.LayoutParams(0,px(48),1f))
        header.addView(action("×") { dismiss("close_button") },LinearLayout.LayoutParams(px(42),px(48)))
        panel.addView(header)
        val actions=LinearLayout(context)
        editButton=action("") {
            editing=!editing; render()
            SessionDiagnostics.event("HU favorites edit mode=$editing")
        }
        actions.addView(editButton,LinearLayout.LayoutParams(0,px(50),1f))
        sideButton=action("") {
            right=!right; prefs.edit().putBoolean("right",right).apply()
            pane?.layoutParams=paneLayout(); showHandle(); render()
        }
        actions.addView(sideButton,LinearLayout.LayoutParams(px(112),px(50)))
        panel.addView(actions)
        body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        panel.addView(ScrollView(context).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        frame.addView(panel,paneLayout())
        try {
            wm.addView(frame,params(width,height-bottom,Gravity.TOP or Gravity.START))
            window=frame; pane=panel; handle?.visibility=View.GONE
            val current=++revision
            render()
            HuAppCatalog.load(context) { entries ->
                if(current!=revision || window!==frame) return@load
                apps=entries.sortedBy { it.label.lowercase() }; loaded=true; render()
            }
            SessionDiagnostics.event("HU favorites opened; editing=$editing; right=$right; selected=${selected.size}")
        } catch(error: Exception) {
            dismiss("open_failed")
            SessionDiagnostics.event("HU favorites failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }
    private fun render() {
        val target=body ?: return
        heading?.text=if(editing) "Выберите приложения" else "Избранное"
        editButton?.text=if(editing) "✓ Готово" else "+ Добавить / убрать"
        sideButton?.text=if(right) "← Влево" else "Вправо →"
        target.removeAllViews()
        if(!loaded) { target.addView(text("Загружаю приложения…")); return }
        val shown=if(editing) apps else selected.mapNotNull { pkg -> apps.firstOrNull { it.component.packageName==pkg } }
        if(shown.isEmpty()) target.addView(text(if(editing) "Приложения не найдены" else "Нажмите «Добавить / убрать», чтобы выбрать приложения"))
        shown.forEach { app ->
            val pkg=app.component.packageName
            val row=LinearLayout(context).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(px(8),px(4),px(8),px(4))
            }
            row.addView(ImageView(context).apply { setImageDrawable(app.icon?.newDrawable(context.resources)) },LinearLayout.LayoutParams(px(36),px(36)))
            row.addView(text(app.label).apply { maxLines=2 },LinearLayout.LayoutParams(0,px(58),1f))
            if(editing) {
                val check=CheckBox(context).apply {
                    isChecked=pkg in selected
                    contentDescription="${app.label}: в избранном"
                    buttonTintList=android.content.res.ColorStateList.valueOf(Color.rgb(137,198,255))
                    setOnCheckedChangeListener { _, checked ->
                        if(checked && pkg !in selected) selected.add(pkg)
                        else if(!checked) selected.remove(pkg)
                        save()
                        SessionDiagnostics.event("HU favorite selection changed; package=$pkg; selected=$checked; editing=$editing")
                    }
                }
                row.addView(check,LinearLayout.LayoutParams(px(48),px(48)))
                row.setOnClickListener { if(editing) check.isChecked=!check.isChecked }
            } else {
                row.contentDescription="Открыть ${app.label}"
                row.setOnClickListener { if(!editing) { dismiss("launch:$pkg"); launch(pkg) } }
                row.setOnLongClickListener {
                    if(!editing) { selected.remove(pkg); save(); render() }
                    true
                }
            }
            target.addView(row)
        }
    }
    fun dismiss(reason: String = "other_ui_or_session") {
        if(window!=null) SessionDiagnostics.event("HU favorites dismissed; reason=$reason; editing=$editing")
        revision++
        window?.let { runCatching { wm.removeViewImmediate(it) } }; window=null
        pane=null; body=null; heading=null; editButton=null; sideButton=null
        handle?.visibility=View.VISIBLE
    }
    fun close() { dismiss("session_close"); handle?.let { runCatching { wm.removeViewImmediate(it) } }; handle=null }
}
