package com.example.carbridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** A stable window: notification refreshes only replace its displayed content. */
internal class HuNavigationCue(private val context: Context, private val wm: WindowManager,
    private val width: Int, private val density: Float, private val open: (StatusBarNotification) -> Unit) {
    private var root: LinearLayout? = null
    private var turn: ImageView? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var signImage: ImageView? = null
    private var speed: TextView? = null
    private var current: StatusBarNotification? = null
    private var boundContent: HuNotificationContent.Content? = null
    private var layout: WindowManager.LayoutParams? = null
    var visible = false
        private set
    private fun px(n: Int)=(n*density).roundToInt()
    private fun label(size: Float)=TextView(context).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX,size*density)
        maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END
        gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false
    }
    private fun create(): LinearLayout {
        val card=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL; setPadding(px(10),px(8),px(10),px(8))
            background=GradientDrawable().apply { setColor(Color.rgb(23,39,56)); cornerRadius=px(10).toFloat() }
            setOnClickListener { current?.let(open) }
        }
        turn=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
        card.addView(turn,LinearLayout.LayoutParams(px(52),px(52)))
        val words=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; setPadding(px(8),0,px(8),0) }
        title=label(20f); detail=label(15f)
        words.addView(title,LinearLayout.LayoutParams(-1,px(47)))
        words.addView(detail,LinearLayout.LayoutParams(-1,px(35)))
        card.addView(words,LinearLayout.LayoutParams(0,-1,1f))
        val sign=FrameLayout(context)
        signImage=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER; visibility=View.INVISIBLE }
        sign.addView(signImage,FrameLayout.LayoutParams(-1,-1))
        speed=label(26f).apply {
            gravity=Gravity.CENTER; setTextColor(Color.BLACK); maxLines=1; visibility=View.INVISIBLE
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(px(4),Color.rgb(214,45,51)) }
        }
        sign.addView(speed,FrameLayout.LayoutParams(-1,-1))
        card.addView(sign,LinearLayout.LayoutParams(px(60),px(60)))
        return card
    }
    fun show(item: StatusBarNotification, content: HuNotificationContent.Content) {
        current=item
        if(root!=null && visible && boundContent===content) return
        val card=root ?: create()
        boundContent=content
        // Drawable copies avoid sharing mutable bounds with the notification shade.
        fun copy(icon: android.graphics.drawable.Drawable?)=icon?.constantState?.newDrawable(context.resources)?.mutate() ?: icon
        val icon=content.icon ?: runCatching { item.notification.smallIcon?.loadDrawable(context) }.getOrNull()
        turn?.setImageDrawable(copy(icon)); turn?.visibility=if(icon==null) View.INVISIBLE else View.VISIBLE
        title?.let { if(it.text.toString()!=content.title) it.text=content.title }
        detail?.let { if(it.text.toString()!=content.text) it.text=content.text }
        val road=content.roadSign
        signImage?.setImageDrawable(copy(road)); signImage?.visibility=if(road!=null) View.VISIBLE else View.INVISIBLE
        val limit=content.speedLimitKmh?.takeIf { road==null }
        speed?.text=limit?.toString().orEmpty(); speed?.visibility=if(limit!=null) View.VISIBLE else View.INVISIBLE
        speed?.contentDescription=limit?.let { "Ограничение скорости $it км/ч" }
        signImage?.contentDescription="Дорожный знак из уведомления навигатора"
        card.contentDescription="Навигационная подсказка: ${content.title} ${content.text}" +
            (limit?.let { ". Ограничение скорости $it км/ч" } ?: "")
        if(root==null) {
            val params=WindowManager.LayoutParams((width*0.40f).roundToInt(),px(100),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=px(5); y=px(5); title="CarBridge navigation cue" }
            try { wm.addView(card,params); root=card; layout=params }
            catch(error: Exception) { SessionDiagnostics.event("HU navigation cue unavailable: ${error.javaClass.simpleName}"); return }
        } else if(!visible) {
            layout?.let { params ->
                params.flags=params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(card,params) }
            }
        }
        card.visibility=View.VISIBLE; visible=true
    }
    fun hide() {
        if(!visible) return
        visible=false; current=null
        root?.let { card ->
            card.visibility=View.GONE
            layout?.let { params ->
                params.flags=params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(card,params) }
            }
        }
    }
    fun close() {
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root=null; turn=null; title=null; detail=null; signImage=null; speed=null; current=null; layout=null; boundContent=null; visible=false
    }
}
