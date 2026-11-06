package com.example.carbridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/** A stable window: notification refreshes only replace its displayed content. */
internal class HuNavigationCue(private val context: Context, private val wm: WindowManager,
    private val width: Int, private val density: Float, resizeForMusic: Boolean,
    private val open: (StatusBarNotification) -> Unit) {
    private var root: FrameLayout? = null
    private var scene: HuNavigationSceneView? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var signImage: ImageView? = null
    private var speed: TextView? = null
    private var signContainer: FrameLayout? = null
    private var current: StatusBarNotification? = null
    private var boundContent: HuNotificationContent.Content? = null
    private var layout: WindowManager.LayoutParams? = null
    var visible = false
        private set
    private fun px(n: Int)=(n*density).roundToInt()
    private val geometry = HuTopCardLayout(width, density, resizeForMusic)
    private var onRight = false
    private var cardHeight = geometry.navigationHeight
    private var cardInset = geometry.inset
    fun setPlacement(right: Boolean, height: Int, inset: Int) {
        if(onRight==right && cardHeight==height && cardInset==inset) return
        onRight=right; cardHeight=height.coerceAtLeast(1); cardInset=inset.coerceAtLeast(0)
        val params=layout ?: return
        params.gravity=Gravity.TOP or if(onRight) Gravity.END else Gravity.START
        params.height=cardHeight; params.x=cardInset; params.y=cardInset
        root?.let { runCatching { wm.updateViewLayout(it,params) } }
    }
    private fun label(size: Float)=TextView(context).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX,size*density)
        maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END
        gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false
    }
    private fun create(): FrameLayout {
        val card=FrameLayout(context).apply {
            background=GradientDrawable().apply {
                orientation=GradientDrawable.Orientation.TOP_BOTTOM
                setColors(intArrayOf(Color.argb(217,55,113,162),Color.argb(217,55,113,162),
                    Color.argb(217,76,82,90),Color.argb(217,76,82,90)),
                    floatArrayOf(0f,1f/3f,1f/3f,1f))
                cornerRadius=geometry.radius
            }
            clipToOutline=true
            setOnClickListener { current?.let(open) }
        }
        scene=HuNavigationSceneView(context)
        card.addView(scene,FrameLayout.LayoutParams(-1,-1).apply {
            topMargin=px(40); bottomMargin=px(36); leftMargin=px(8); rightMargin=px(8)
        })
        title=label(28f).apply {
            gravity=Gravity.CENTER; maxLines=1
            typeface=Typeface.create("sans-serif-rounded",Typeface.BOLD)
            setShadowLayer(px(3).toFloat(),0f,px(1).toFloat(),Color.BLACK)
        }
        card.addView(title,FrameLayout.LayoutParams(-1,px(36),Gravity.TOP).apply {
            topMargin=px(4); leftMargin=px(50); rightMargin=px(50)
        })
        detail=label(13f).apply {
            gravity=Gravity.CENTER; maxLines=2
            setShadowLayer(px(3).toFloat(),0f,px(1).toFloat(),Color.BLACK)
        }
        card.addView(detail,FrameLayout.LayoutParams(-1,px(32),Gravity.BOTTOM).apply {
            bottomMargin=px(4); leftMargin=px(10); rightMargin=px(10)
        })
        val sign=FrameLayout(context)
        signContainer=sign
        signImage=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER; visibility=View.INVISIBLE }
        sign.addView(signImage,FrameLayout.LayoutParams(-1,-1))
        speed=label(20f).apply {
            gravity=Gravity.CENTER; setTextColor(Color.BLACK); maxLines=1; visibility=View.INVISIBLE
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(px(4),Color.rgb(214,45,51)) }
        }
        sign.addView(speed,FrameLayout.LayoutParams(-1,-1))
        card.addView(sign,FrameLayout.LayoutParams(px(42),px(42),Gravity.TOP or Gravity.END).apply {
            topMargin=px(6); rightMargin=px(6)
        })
        return card
    }
    fun show(item: StatusBarNotification, content: HuNotificationContent.Content) {
        current=item
        if(root!=null && visible && boundContent===content) {
            scene?.setActive(true)
            return
        }
        val card=root ?: create()
        boundContent=content
        // Drawable copies avoid sharing mutable bounds with the notification shade.
        fun copy(icon: android.graphics.drawable.Drawable?)=icon?.constantState?.newDrawable(context.resources)?.mutate() ?: icon
        val icon=content.icon ?: runCatching { item.notification.smallIcon?.loadDrawable(context) }.getOrNull()
        val presentation=HuNavigationSceneContent.read(content)
        scene?.bind(copy(icon))
        val heading=presentation.distance ?: content.title
        title?.let { if(it.text.toString()!=heading) it.text=heading }
        val caption=(listOf(content.title.takeUnless { it==heading }.orEmpty())+content.text.lines())
            .filter { it.isNotBlank() && it!=heading }.distinct().joinToString(" · ")
        detail?.let { if(it.text.toString()!=caption) it.text=caption }
        val road=content.roadSign
        signImage?.setImageDrawable(copy(road)); signImage?.visibility=if(road!=null) View.VISIBLE else View.INVISIBLE
        val limit=content.speedLimitKmh?.takeIf { road==null }
        speed?.text=limit?.toString().orEmpty(); speed?.visibility=if(limit!=null) View.VISIBLE else View.INVISIBLE
        signContainer?.visibility=if(road!=null || limit!=null) View.VISIBLE else View.GONE
        speed?.contentDescription=limit?.let { "Ограничение скорости $it км/ч" }
        signImage?.contentDescription="Дорожный знак из уведомления навигатора"
        card.contentDescription="Навигационная подсказка: ${content.title} ${content.text}" +
            (limit?.let { ". Ограничение скорости $it км/ч" } ?: "")
        if(root==null) {
            val params=WindowManager.LayoutParams(geometry.navigationWidth,cardHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                    gravity=Gravity.TOP or if(onRight) Gravity.END else Gravity.START
                    x=cardInset; y=cardInset; title="CarBridge navigation cue"
                }
            try { wm.addView(card,params); root=card; layout=params }
            catch(error: Exception) { SessionDiagnostics.event("HU navigation cue unavailable: ${error.javaClass.simpleName}"); return }
        } else if(!visible) {
            layout?.let { params ->
                params.flags=params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(card,params) }
            }
        }
        card.visibility=View.VISIBLE; visible=true
        scene?.setActive(true)
    }
    fun hide() {
        scene?.setActive(false)
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
        scene?.setActive(false)
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root=null; scene=null; title=null; detail=null; signImage=null; speed=null; signContainer=null; current=null; layout=null; boundContent=null; visible=false
    }
}
