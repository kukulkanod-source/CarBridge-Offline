package com.example.carbridge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Resolution independent symbols shared by the dock and shade. */
internal class HuStatusIcon(private val kind: String, tint: Int = Color.WHITE) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; strokeWidth = 1.8f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (kind) {
            "keyboard" -> {
                canvas.drawRoundRect(RectF(1f, 5f, 23f, 20f), 2f, 2f, paint)
                for (y in 0..1) for (x in 0..4) canvas.drawPoint(4f + x * 4, 9f + y * 4, paint)
                canvas.drawLine(7f, 17f, 17f, 17f, paint)
            }
            "back" -> canvas.drawPath(Path().apply {
                moveTo(14f, 4f); lineTo(6f, 12f); lineTo(14f, 20f)
                moveTo(6f, 12f); lineTo(21f, 12f)
            }, paint)
            "home" -> canvas.drawPath(Path().apply {
                moveTo(2f, 11f); lineTo(12f, 3f); lineTo(22f, 11f)
                moveTo(5f, 9f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 14f)
                lineTo(14f, 14f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 9f)
            }, paint)
            "apps" -> for (y in 0..1) for (x in 0..1)
                canvas.drawRoundRect(RectF(3f + x * 11, 3f + y * 11, 10f + x * 11, 10f + y * 11), 1.5f, 1.5f, paint)
            "music" -> {
                canvas.drawPath(Path().apply {
                    moveTo(9f, 18f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 16f)
                    moveTo(9f, 9f); lineTo(20f, 7f)
                }, paint)
                canvas.drawOval(RectF(3f, 16f, 9f, 21f), paint)
                canvas.drawOval(RectF(14f, 14f, 20f, 19f), paint)
            }
            "phone_on", "phone_off" -> {
                canvas.drawRoundRect(RectF(6f, 2f, 18f, 22f), 2f, 2f, paint)
                canvas.drawLine(10f, 19f, 14f, 19f, paint)
                if (kind == "phone_off") canvas.drawLine(3f, 3f, 21f, 21f, paint)
                else canvas.drawLine(10f, 5f, 14f, 5f, paint)
            }
            "wifi" -> {
                canvas.drawArc(RectF(1f, 2f, 23f, 24f), 225f, 90f, false, paint)
                canvas.drawArc(RectF(5f, 8f, 19f, 22f), 225f, 90f, false, paint)
                canvas.drawArc(RectF(9f, 14f, 15f, 20f), 225f, 90f, false, paint)
                canvas.drawCircle(12f, 20f, 0.8f, paint)
            }
            "bluetooth" -> canvas.drawPath(Path().apply {
                moveTo(12f, 2f); lineTo(12f, 22f); lineTo(18f, 16f); lineTo(6f, 5f)
                moveTo(6f, 19f); lineTo(18f, 8f); lineTo(12f, 2f)
            }, paint)
            "cellular" -> {
                paint.style = Paint.Style.FILL
                for (i in 0..3) canvas.drawRoundRect(RectF(3f + i * 5, 18f - i * 4, 6f + i * 5, 22f), 0.6f, 0.6f, paint)
                paint.style = Paint.Style.STROKE
            }
            "location" -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 22f); cubicTo(9f, 17f, 5f, 13f, 5f, 9f)
                    cubicTo(5f, 0f, 19f, 0f, 19f, 9f); cubicTo(19f, 13f, 15f, 17f, 12f, 22f)
                }, paint)
                canvas.drawCircle(12f, 9f, 2.5f, paint)
            }
            "vpn" -> {
                canvas.drawRoundRect(RectF(4f, 10f, 20f, 22f), 2f, 2f, paint)
                canvas.drawArc(RectF(7f, 1f, 17f, 17f), 180f, 180f, false, paint)
                canvas.drawCircle(12f, 16f, 1f, paint)
            }
            "display" -> {
                canvas.drawRoundRect(RectF(2f, 3f, 22f, 18f), 2f, 2f, paint)
                canvas.drawLine(12f, 18f, 12f, 22f, paint); canvas.drawLine(7f, 22f, 17f, 22f, paint)
            }
            "sound" -> canvas.drawPath(Path().apply {
                moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 4f); lineTo(12f, 20f)
                lineTo(7f, 15f); lineTo(3f, 15f); close()
                moveTo(16f, 7f); cubicTo(21f, 9f, 21f, 15f, 16f, 17f)
            }, paint)
            else -> {
                canvas.drawCircle(12f, 12f, 6f, paint); canvas.drawCircle(12f, 12f, 2f, paint)
                for (i in 0..7) {
                    val angle = i * Math.PI / 4
                    canvas.drawLine(12f + cos(angle).toFloat() * 8, 12f + sin(angle).toFloat() * 8,
                        12f + cos(angle).toFloat() * 10, 12f + sin(angle).toFloat() * 10, paint)
                }
            }
        }
        canvas.restoreToCount(checkpoint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
