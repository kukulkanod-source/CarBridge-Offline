package com.example.carbridge

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Bevelled silver/blue star matching the dock's metallic control palette. */
internal class HuFavoriteIcon : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val star=Path().apply {
        for(i in 0 until 10) {
            val a=-Math.PI/2+i*Math.PI/5
            val r=if(i%2==0) 43f else 20f
            val x=50f+(cos(a)*r).toFloat(); val y=49f+(sin(a)*r).toFloat()
            if(i==0) moveTo(x,y) else lineTo(x,y)
        }; close()
    }
    private val face=LinearGradient(15f,8f,77f,92f,
        intArrayOf(Color.WHITE,Color.rgb(186,226,255),Color.rgb(33,118,230)),null,Shader.TileMode.CLAMP)
    override fun draw(canvas: Canvas) {
        val scale=minOf(bounds.width(),bounds.height())/100f
        canvas.save(); canvas.translate(bounds.exactCenterX()-50*scale,bounds.exactCenterY()-50*scale); canvas.scale(scale,scale)
        paint.shader=null; paint.style=Paint.Style.FILL; paint.color=Color.rgb(10,58,128)
        canvas.save(); canvas.translate(0f,5f); canvas.drawPath(star,paint); canvas.restore()
        paint.shader=face; canvas.drawPath(star,paint); paint.shader=null
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.strokeJoin=Paint.Join.ROUND; paint.color=Color.rgb(208,242,255)
        canvas.drawPath(star,paint); canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
