package com.example.carbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Shared visible corner size, scaled by the same HU chrome density as the dock. */
internal object HuWindowCorners {
    fun radiusPx(density: Float): Int = (18f * density).roundToInt().coerceAtLeast(1)

    /** SurfaceView has its own Surface. Four small sibling caps cover its square corners
     * without replacing it with a TextureView or copying video frames into a bitmap. */
    fun addSurfaceCaps(frame: FrameLayout, radius: Int) {
        listOf(
            (Gravity.TOP or Gravity.LEFT) to 0f,
            (Gravity.TOP or Gravity.RIGHT) to 90f,
            (Gravity.BOTTOM or Gravity.RIGHT) to 180f,
            (Gravity.BOTTOM or Gravity.LEFT) to 270f
        ).forEach { (gravity, angle) ->
            frame.addView(CornerCap(frame.context, angle), FrameLayout.LayoutParams(radius, radius, gravity))
        }
    }

    private class CornerCap(context: Context, private val angle: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val cap = Path()
        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            cap.reset()
            cap.fillType = Path.FillType.EVEN_ODD
            cap.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
            cap.addCircle(w.toFloat(), h.toFloat(), minOf(w, h).toFloat(), Path.Direction.CW)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val save = canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.rotate(angle, width / 2f, height / 2f)
            canvas.drawPath(cap, paint)
            canvas.restoreToCount(save)
        }
    }
}
