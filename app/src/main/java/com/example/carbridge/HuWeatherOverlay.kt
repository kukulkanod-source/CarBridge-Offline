package com.example.carbridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Hosts the provider's real RemoteViews, not scraped forecasts or notification text. */
internal class HuWeatherOverlay(private val context: Context,private val onClose: () -> Unit,
    private val onSwap: () -> Unit,private val onSetup: () -> Unit,private val onOpen: (String) -> Unit) {
    private val main=Handler(Looper.getMainLooper())
    private var manager: WindowManager?=null
    private var window: Context?=null
    private var root: LinearLayout?=null
    private var body: FrameLayout?=null
    private var params: WindowManager.LayoutParams?=null
    private var hosted: android.appwidget.AppWidgetHostView?=null
    private var hostedId=Int.MIN_VALUE
    private var requestedSize=""
    private var displayId=-1
    private var visible=false
    private val refresh=object: Runnable {
        override fun run() { if(!visible) return; refreshWidget(); main.postDelayed(this,5000L) }
    }
    fun isVisible()=visible
    fun place(id: Int,g: HuSidebarGeometry,height: Int,top: Int): Boolean {
        if(displayId!=id) close()
        if(root==null) {
            val display=context.getSystemService(DisplayManager::class.java).getDisplay(id)?.takeIf { it.isValid } ?: return false
            val wc=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            val wm=wc.getSystemService(WindowManager::class.java)
            window=wc; manager=wm; displayId=id
            fun px(n: Int)=(n*g.density).roundToInt().coerceAtLeast(1)
            val panel=LinearLayout(wc).apply {
                orientation=LinearLayout.VERTICAL
                background=GradientDrawable().apply { setColor(Color.rgb(23,30,38)); cornerRadius=px(10).toFloat() }
                clipToOutline=true
            }
            val header=LinearLayout(wc).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(px(8),0,px(4),0) }
            header.addView(TextView(wc).apply {
                text="ЯНДЕКС ПОГОДА"; setTextColor(Color.WHITE); textSize=12f
            },LinearLayout.LayoutParams(0,-1,1f))
            fun button(text: String,description: String,click: () -> Unit) = Button(wc).apply {
                this.text=text; contentDescription=description; setTextColor(Color.WHITE); setPadding(0,0,0,0)
                minimumWidth=0; minimumHeight=0; setBackgroundColor(Color.TRANSPARENT); setOnClickListener { click() }
            }
            header.addView(button("⚙","Настроить виджет погоды",onSetup),LinearLayout.LayoutParams(px(36),-1))
            header.addView(button("↔","Сменить сторону виджетов",onSwap),LinearLayout.LayoutParams(px(36),-1))
            header.addView(button("×","Скрыть погоду",onClose),LinearLayout.LayoutParams(px(36),-1))
            panel.addView(header,LinearLayout.LayoutParams(-1,px(40)))
            body=FrameLayout(wc)
            panel.addView(body,LinearLayout.LayoutParams(-1,0,1f))
            val layout=WindowManager.LayoutParams((g.width-g.inset*2).coerceAtLeast(1),height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT)
            try { wm.addView(panel,layout); root=panel; params=layout }
            catch(error: Exception) { SessionDiagnostics.event("HU weather window failed: ${error.javaClass.simpleName}"); close(); return false }
        }
        val p=params ?: return false
        p.width=(g.width-g.inset*2).coerceAtLeast(1); p.height=height
        p.gravity=Gravity.TOP or if(g.right) Gravity.END else Gravity.START
        p.x=g.inset; p.y=top; p.title="CarBridge weather widget"
        return runCatching {
            manager?.updateViewLayout(root,p)
            root?.visibility=View.VISIBLE
            if(!visible) {
                visible=true
                runCatching { WeatherWidgetStore.host(context).startListening() }
                    .onFailure { SessionDiagnostics.event("Weather widget updates unavailable: ${it.javaClass.simpleName}") }
                main.removeCallbacks(refresh); main.post(refresh)
            }
            root?.post { if(visible) updateSize() }
            true
        }.getOrElse { SessionDiagnostics.event("Weather widget placement failed: ${it.javaClass.simpleName}"); false }
    }
    private fun refreshWidget() {
        val wc=window ?: return
        val container=body ?: return
        val info=WeatherWidgetStore.info(context)
        val id=if(info==null) -1 else WeatherWidgetStore.id(context)
        if(id==hostedId) { updateSize(); return }
        container.removeAllViews(); hosted=null; hostedId=id; requestedSize=""
        if(info==null) {
            container.addView(Button(wc).apply {
                text="Добавить виджет Яндекс Погоды\nНастройка на телефоне"
                setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { onSetup() }
            },FrameLayout.LayoutParams(-1,-1))
            return
        }
        try {
            val hostView=WeatherWidgetStore.host(context).createView(wc,id,info)
            hosted=hostView
            // Provider PendingIntents may default to the phone. A card tap explicitly opens on HU.
            val touchSurface=object: FrameLayout(wc) {
                private var x=0f; private var y=0f
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { x=event.x; y=event.y }
                        MotionEvent.ACTION_UP -> {
                            val slop=ViewConfiguration.get(context).scaledTouchSlop
                            if(abs(event.x-x)<=slop && abs(event.y-y)<=slop) { performClick(); onOpen(info.provider.packageName) }
                        }
                    }
                    return true
                }
                override fun performClick(): Boolean { super.performClick(); return true }
            }
            touchSurface.addView(hostView,FrameLayout.LayoutParams(-1,-1))
            container.addView(touchSurface,FrameLayout.LayoutParams(-1,-1))
            container.post { updateSize() }
            SessionDiagnostics.event("HU weather widget attached; provider=${info.provider.flattenToShortString()}; id=$id")
        } catch(error: Exception) {
            hostedId=Int.MIN_VALUE
            container.addView(TextView(wc).apply { text="Не удалось показать виджет. Откройте настройку погоды на телефоне."; setTextColor(Color.WHITE) })
            SessionDiagnostics.event("HU weather widget unavailable: ${error.javaClass.simpleName}")
        }
    }
    private fun updateSize() {
        val container=body ?: return
        val view=hosted ?: return
        if(container.width<=0 || container.height<=0) return
        val density=window?.resources?.displayMetrics?.density ?: return
        val w=(container.width/density).toInt().coerceAtLeast(1)
        val h=(container.height/density).toInt().coerceAtLeast(1)
        val size="$w:$h"
        if(size!=requestedSize) runCatching { view.updateAppWidgetSize(null,w,h,w,h) }.onSuccess { requestedSize=size }
    }
    fun hide() {
        visible=false; root?.visibility=View.GONE; main.removeCallbacks(refresh)
        runCatching { WeatherWidgetStore.host(context).stopListening() }
    }
    fun close() {
        hide()
        root?.let { runCatching { manager?.removeViewImmediate(it) } }
        root=null; body=null; hosted=null; params=null; window=null; manager=null
        hostedId=Int.MIN_VALUE; requestedSize=""; displayId=-1
    }
}
