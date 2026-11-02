package com.example.carbridge.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque

/**
 * Тач через dispatchGesture. Работает без root/Shizuku, но только на основном дисплее
 * и с задержкой ~50-100 мс на сегмент. Поддерживается один палец.
 */
class AccessibilityTouchService : AccessibilityService() {

    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var busy = false
    private val main = Handler(Looper.getMainLooper())
    private data class Touch(val action: Int, val x: Float, val y: Float)
    private val pending = ArrayDeque<Touch>()
    private var generation = 0

    /** Дисплей, на который идёт тач (Android 11+). По умолчанию основной экран. */
    @Volatile var targetDisplayId: Int = Display.DEFAULT_DISPLAY

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        reset()
        return super.onUnbind(intent)
    }

    /** action: 0 = DOWN, 1 = UP, 2 = MOVE. x/y в пикселях экрана телефона. */
    fun onHuTouch(action: Int, x: Float, y: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onHuTouch(action, x, y) }
            return
        }
        if (action !in 0..2) return
        // Preserve DOWN/UP while a previous segment is running. Dispatching UP
        // immediately would cancel the still-running DOWN/continued stroke.
        if (action == 2 && pending.peekLast()?.action == 2) pending.removeLast()
        pending.addLast(Touch(action, x, y))
        drain()
    }

    private fun drain() {
        if (busy) return
        while (pending.isNotEmpty()) {
            val event = pending.removeFirst()
            val previous = stroke
            if (event.action != 0 && previous == null) continue
            val x = if (event.x < 0) lastX else event.x
            val y = if (event.y < 0) lastY else event.y
            val path = Path()
            val next = if (event.action == 0) {
                path.moveTo(x, y)
                GestureDescription.StrokeDescription(path, 0, 50, true)
            } else {
                path.moveTo(lastX, lastY)
                path.lineTo(x, y)
                previous!!.continueStroke(path, 0, if (event.action == 1) 10 else 30,
                    event.action != 1)
            }
            val submittedGeneration = generation
            busy = true
            stroke = if (event.action == 1) null else next
            lastX = x
            lastY = y
            val gesture = GestureDescription.Builder().apply {
                if (Build.VERSION.SDK_INT >= 30) setDisplayId(targetDisplayId)
            }.addStroke(next).build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (generation != submittedGeneration) return
                        busy = false
                        drain()
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (generation != submittedGeneration) return
                        busy = false
                        stroke = null
                        Log.w(TAG, "touch gesture cancelled")
                        drain()
                    }
                }, main
            )
            if (accepted) return
            busy = false
            stroke = null
            Log.w(TAG, "dispatchGesture rejected touch")
        }
    }

    private fun reset() {
        generation++
        pending.clear()
        stroke = null
        busy = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { reset() }

    companion object {
        private const val TAG = "CarBridge/Touch"
        @Volatile var instance: AccessibilityTouchService? = null
    }
}
