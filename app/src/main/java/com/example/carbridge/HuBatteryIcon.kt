package com.example.carbridge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Live battery gauge: fill follows the phone's reported level, bolt means external power. */
internal class HuBatteryIcon(private val percent: Int?, private val powered: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        val scale = minOf(bounds.width() / 28f, bounds.height() / 24f)
        canvas.translate(bounds.exactCenterX() - 14f * scale, bounds.exactCenterY() - 12f * scale)
        canvas.scale(scale, scale)
        paint.alpha = opacity
        paint.colorFilter = filter
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 5f, 0f, 19f,
            Color.rgb(236, 246, 255), Color.rgb(112, 138, 160), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(1f, 5f, 25f, 19f), 2.5f, 2.5f, paint)
        paint.shader = null
        paint.color = Color.rgb(156, 184, 205)
        canvas.drawRoundRect(RectF(25f, 9f, 27f, 15f), 0.8f, 0.8f, paint)
        paint.color = Color.rgb(16, 28, 43)
        canvas.drawRoundRect(RectF(3f, 7f, 23f, 17f), 1f, 1f, paint)
        if (percent != null && percent > 0) {
            val low = percent <= 15 && !powered
            paint.shader = LinearGradient(0f, 7f, 0f, 17f,
                if (low) Color.rgb(255, 143, 143) else Color.rgb(119, 230, 255),
                if (low) Color.rgb(203, 52, 65) else Color.rgb(22, 126, 224), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(4f, 8f, 4f + 18f * percent.coerceIn(0, 100) / 100f, 16f), 0.6f, 0.6f, paint)
            paint.shader = null
        }
        if (powered) {
            val bolt = Path().apply {
                moveTo(15f, 6f); lineTo(9f, 13f); lineTo(13f, 13f)
                lineTo(11f, 18f); lineTo(18f, 10f); lineTo(14f, 10f); close()
            }
            paint.color = Color.rgb(18, 39, 64)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            canvas.drawPath(bolt, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawPath(bolt, paint)
        } else if (percent == null) {
            paint.color = Color.WHITE
            paint.textSize = 10f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("?", 13f, 15.5f, paint)
        }
        canvas.restoreToCount(checkpoint)
    }
    override fun getIntrinsicWidth() = 28
    override fun getIntrinsicHeight() = 24
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
