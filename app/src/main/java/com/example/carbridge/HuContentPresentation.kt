package com.example.carbridge

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.carbridge.scrcpy.ScrcpyInputSession

/** Application display rendered into a Surface, with real space reserved for the dock.
 * SurfaceFlinger composes it before the existing scrcpy encoder. No decode/re-encode. */
class HuContentPresentation(outer: Context, display: Display,
    private val appDpi: Int, private val bottomInset: Int, private val cornerRadius: Int,
    private val onReady: (Int, ScrcpyInputSession) -> Unit,
    private val onFailure: (String) -> Unit
) : Presentation(outer, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {
    private var virtual: VirtualDisplay? = null
    private var input: ScrcpyInputSession? = null
    private var disposed = false
    private var surfaceView: SurfaceView? = null
    private var contentFrame: FrameLayout? = null
    private var musicVisible = false
    private var musicOnRight = true
    private var fullWidth = 0
    private var contentHeight = 0
    private var targetWidth = 0
    private var surfaceReady = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val resizeTask = Runnable { applyMusicLayout() }

    fun setMusicLayout(visible: Boolean, onRight: Boolean) {
        musicVisible = visible
        musicOnRight = onRight
        handler.removeCallbacks(resizeTask)
        if (!disposed) handler.postDelayed(resizeTask, 120)
    }

    private fun applyMusicLayout() {
        val surface = surfaceView ?: return
        if (disposed) return
        val width = if (musicVisible) (fullWidth * 0.70f).toInt() else fullWidth
        val left = if (musicVisible && !musicOnRight) fullWidth - width else 0
        if (targetWidth != width) {
            surfaceReady = false
            targetWidth = width
            // Resize the existing display: its ID and running task survive.
            try {
                virtual?.resize(width, contentHeight, appDpi)
                input?.resize(width, contentHeight)
                surface.holder.setFixedSize(width, contentHeight)
            } catch (e: Exception) { onFailure("Изменение области приложения: ${e.message}"); return }
            SessionDiagnostics.event("HU content resized; app=${virtual?.display?.displayId}; size=${width}x$contentHeight; dpi=$appDpi; music=$musicVisible; right=$musicOnRight")
        }
        contentFrame?.layoutParams = FrameLayout.LayoutParams(width, contentHeight).apply { leftMargin = left }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setCancelable(false)
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels
        val h = (metrics.heightPixels - bottomInset).coerceAtLeast(240)
        fullWidth = w; contentHeight = h; targetWidth = w
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(context)
        surfaceView = surface
        surface.holder.setFixedSize(w, h)
        val frame = FrameLayout(context)
        contentFrame = frame
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1))
        HuWindowCorners.addSurfaceCaps(frame, cornerRadius)
        root.addView(frame, FrameLayout.LayoutParams(w, h))
        setContentView(root)
        var multiLogged=false
        surface.setOnTouchListener { _, event ->
            if(event.pointerCount>1 && !multiLogged) {
                multiLogged=true
                SessionDiagnostics.event("HU content surface received multitouch; pointers=${event.pointerCount}")
            }
            if (surfaceReady) input?.touch(event, surface.width, surface.height)
            true
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (disposed) return
                try {
                    val existing = virtual
                    if (existing != null) { existing.surface = holder.surface; return }
                    val dm = context.getSystemService(DisplayManager::class.java)
                    val vd = dm.createVirtualDisplay("CarBridge application", targetWidth, contentHeight, appDpi, holder.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                        ?: error("Android не создал область приложения")
                    virtual = vd
                    val id = vd.display.displayId
                    val controller = ScrcpyInputSession(context.applicationContext, id, targetWidth, contentHeight, onFailure)
                    input = controller
                    controller.start { if (!disposed) onReady(id, controller) }
                    SessionDiagnostics.event("HU content surface created; shell=${display.displayId}; app=$id; size=${w}x$h; dpi=$appDpi; inset=$bottomInset")
                } catch (e: Exception) { onFailure(e.message ?: e.javaClass.simpleName) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceReady = width == targetWidth && height == contentHeight
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; virtual?.surface = null }
        })
        applyMusicLayout()
    }
    fun close() {
        disposed = true
        handler.removeCallbacks(resizeTask)
        surfaceView = null
        contentFrame = null
        input?.close(); input = null
        virtual?.release(); virtual = null
        runCatching { dismiss() }
    }
}
