package com.example.carbridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Bottom favourites strip and in-window picker, without a side handle or Presentation. */
internal class HuFavoritesPanel(private val context: Context, private val wm: WindowManager,
    private val width: Int, private val height: Int, private val bottom: Int, private val density: Float,
    private val launch: (String) -> Unit, private val beforeOpen: () -> Unit) {
    private val prefs=context.getSharedPreferences("hu_favorites",Context.MODE_PRIVATE)
    private val slots=prefs.getString("packages","").orEmpty().split('|').toMutableList()
    private var window: FrameLayout?=null
    private var apps=emptyList<HuAppCatalog.App>()
    private var loaded=false
    private var pickingSlot: Int?=null
    private var revision=0
    val isOpen: Boolean get()=window!=null
    private fun px(n: Int)=(n*density).roundToInt().coerceAtLeast(1)
    private fun label(value: String, size: Float)=TextView(context).apply {
        text=value; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX,size*density)
        gravity=Gravity.CENTER; includeFontPadding=false; maxLines=1; ellipsize=TextUtils.TruncateAt.END
    }
    private fun background()=GradientDrawable().apply { setColor(Color.rgb(22,32,46)); cornerRadius=px(10).toFloat() }
    fun open() {
        if(isOpen) { dismiss(); return }
        beforeOpen(); pickingSlot=null; loaded=false
        val frame=FrameLayout(context)
        val params=WindowManager.LayoutParams(width,(height-bottom).coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; title="CarBridge favorites strip" }
        try {
            wm.addView(frame,params); window=frame; render()
            val token=++revision
            HuAppCatalog.load(context) { entries ->
                if(token!=revision || window!==frame) return@load
                apps=entries.sortedBy { it.label.lowercase() }; loaded=true; render()
            }
            SessionDiagnostics.event("HU favorites bottom strip opened; selected=${slots.count { it.isNotBlank() }}")
        } catch(error: Exception) {
            dismiss(); SessionDiagnostics.event("HU favorites strip failed: ${error.javaClass.simpleName}")
        }
    }
    private fun save() {
        while(slots.isNotEmpty() && slots.last().isBlank()) slots.removeAt(slots.lastIndex)
        prefs.edit().putString("packages",slots.joinToString("|")).apply()
    }
    private fun render() {
        val frame=window ?: return
        frame.removeAllViews()
        frame.addView(View(context).apply {
            contentDescription="Закрыть избранное"; setOnClickListener { dismiss() }
        },FrameLayout.LayoutParams(-1,-1))
        val count=((width-px(12))/px(72)).coerceIn(4,24)
        val cellWidth=((width-px(12))/count).coerceAtLeast(1)
        val itemCount=maxOf(count,slots.indexOfLast { it.isNotBlank() }+2)
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        repeat(itemCount) { index ->
            val pkg=slots.getOrNull(index).orEmpty()
            val app=apps.firstOrNull { it.component.packageName==pkg }
            val cell=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
                setPadding(px(3),px(3),px(3),px(3))
            }
            if(pkg.isBlank()) {
                cell.addView(label("+",30f),LinearLayout.LayoutParams(-1,px(42)))
                cell.contentDescription="Добавить приложение в ячейку ${index+1}"
                cell.setOnClickListener { pickingSlot=index; render() }
            } else {
                cell.addView(ImageView(context).apply {
                    scaleType=ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(app?.icon?.newDrawable(context.resources)
                        ?: runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull())
                },LinearLayout.LayoutParams(px(34),px(34)))
                cell.addView(label(app?.label ?: pkg.substringAfterLast('.'),10f),LinearLayout.LayoutParams(-1,px(17)))
                cell.contentDescription="Открыть ${app?.label ?: pkg}; удерживайте для замены или удаления"
                cell.setOnClickListener { dismiss(); launch(pkg) }
                cell.setOnLongClickListener { pickingSlot=index; render(); true }
            }
            row.addView(cell,LinearLayout.LayoutParams(cellWidth,-1))
        }
        val strip=HorizontalScrollView(context).apply {
            background=background(); clipToOutline=true; isHorizontalScrollBarEnabled=false
            addView(row,FrameLayout.LayoutParams(-2,-1))
        }
        frame.addView(strip,FrameLayout.LayoutParams(width-px(12),px(64),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin=px(4)
        })
        pickingSlot?.let { renderPicker(frame,it) }
    }
    private fun renderPicker(frame: FrameLayout, slot: Int) {
        frame.addView(View(context).apply {
            setBackgroundColor(0x66000000); setOnClickListener { pickingSlot=null; render() }
        },FrameLayout.LayoutParams(-1,-1))
        val picker=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; background=background(); clipToOutline=true; isClickable=true
        }
        val heading=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        heading.addView(label("Выбрать приложение",17f),LinearLayout.LayoutParams(0,px(44),1f))
        heading.addView(label("×",26f).apply { setOnClickListener { pickingSlot=null; render() } },LinearLayout.LayoutParams(px(44),px(44)))
        picker.addView(heading)
        if(!slots.getOrNull(slot).isNullOrBlank()) picker.addView(label("Удалить из избранного",15f).apply {
            setTextColor(Color.rgb(255,153,153))
            setOnClickListener { slots[slot]=""; save(); pickingSlot=null; render() }
        },LinearLayout.LayoutParams(-1,px(42)))
        val choices=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        if(!loaded) choices.addView(label("Загружаю приложения…",16f))
        else if(apps.isEmpty()) choices.addView(label("Приложения не найдены",16f))
        apps.forEach { app ->
            val pkg=app.component.packageName
            val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(px(10),px(4),px(10),px(4)) }
            row.addView(ImageView(context).apply { setImageDrawable(app.icon?.newDrawable(context.resources)) },LinearLayout.LayoutParams(px(34),px(34)))
            row.addView(label(app.label,16f),LinearLayout.LayoutParams(0,px(44),1f))
            row.setOnClickListener {
                while(slots.size<=slot) slots.add("")
                for(i in slots.indices) if(slots[i]==pkg) slots[i]=""
                slots[slot]=pkg; save(); pickingSlot=null; render()
                SessionDiagnostics.event("HU favorite slot assigned; slot=$slot; package=$pkg")
            }
            choices.addView(row)
        }
        picker.addView(ScrollView(context).apply { addView(choices) },LinearLayout.LayoutParams(-1,0,1f))
        frame.addView(picker,FrameLayout.LayoutParams(minOf(px(560),width-px(24)),
            ((height-bottom)*0.78f).roundToInt().coerceAtLeast(1),Gravity.CENTER))
    }
    fun dismiss(reason: String="other_ui_or_session") {
        revision++; pickingSlot=null
        window?.let { runCatching { wm.removeViewImmediate(it) } }; window=null
    }
    fun close()=dismiss("session_close")
}
