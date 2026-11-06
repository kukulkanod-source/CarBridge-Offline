package com.example.carbridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View

/** Original navigation graphics are laid onto a decorative road; only its centre marks move. */
internal class HuNavigationSceneView(context: Context) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val car=BitmapFactory.decodeResource(resources,R.drawable.hu_navigation_car,
        BitmapFactory.Options().apply { inSampleSize=4; inScaled=false })
    private val rect=RectF()
    private val road=Path()
    private val perspective=Matrix()
    private val sourceCorners=FloatArray(8)
    private val targetCorners=FloatArray(8)
    private var original: Drawable?=null
    private var active=false
    private var scheduled=false
    private var startedAt=0L
    private var roadPhase=0f
    private val frame=object: Runnable {
        override fun run() {
            scheduled=false
            if(!canAnimate()) { roadPhase=0f; invalidate(); return }
            roadPhase=((SystemClock.uptimeMillis()-startedAt)%1800L)/1800f
            invalidate(); scheduleFrame()
        }
    }
    fun bind(icon: Drawable?) { original=icon; invalidate(); scheduleFrame() }
    fun setActive(value: Boolean) {
        if(active==value) { scheduleFrame(); return }
        active=value
        if(value) startedAt=SystemClock.uptimeMillis()
        else { removeCallbacks(frame); scheduled=false; roadPhase=0f; invalidate() }
        scheduleFrame()
    }
    private fun canAnimate()=active && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
    private fun scheduleFrame() {
        if(!scheduled && canAnimate()) { scheduled=true; postDelayed(frame,50L) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleFrame() }
    override fun onDetachedFromWindow() {
        removeCallbacks(frame); scheduled=false; super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if(isVisible) scheduleFrame() else { removeCallbacks(frame); scheduled=false }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(width<=0 || height<=0) return
        val scale=minOf(width/240f,height/264f)
        val originY=(height-264f*scale)/2f
        // Match the card background's actual one-third horizon, including compact stacked cards.
        val cardHeight=(parent as? View)?.height ?: height
        val horizon=((cardHeight/3f-top-originY)/scale).coerceIn(0f,130f)
        canvas.save()
        canvas.translate((width-240f*scale)/2f,originY)
        canvas.scale(scale,scale)
        canvas.save()
        canvas.clipRect(0f,horizon,240f,264f)
        road.reset()
        road.moveTo(117f,horizon); road.lineTo(123f,horizon)
        road.lineTo(218f,264f); road.lineTo(22f,264f); road.close()
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(47,53,61)
        canvas.drawPath(road,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f
        paint.color=Color.rgb(172,185,191)
        canvas.drawLine(117f,horizon,22f,264f,paint)
        canvas.drawLine(123f,horizon,218f,264f,paint)
        paint.style=Paint.Style.FILL
        for(i in 0..5) {
            val t=(i/6f+roadPhase)%1f
            val end=(t+0.07f).coerceAtMost(1f)
            val y=horizon+t*t*(264f-horizon)
            val bottom=horizon+end*end*(264f-horizon)
            val halfWidth=0.5f+2.2f*t
            rect.set(120f-halfWidth,y,120f+halfWidth,bottom)
            paint.color=Color.argb((110+t*145).toInt(),235,239,235)
            canvas.drawRoundRect(rect,0.7f,0.7f,paint)
        }
        canvas.restore()
        original?.let { icon ->
            val iw=icon.intrinsicWidth.coerceAtLeast(1)
            val ih=icon.intrinsicHeight.coerceAtLeast(1)
            val s=minOf(132f/iw,116f/ih)
            val w=(iw*s).toInt().coerceAtLeast(1)
            val h=(ih*s).toInt().coerceAtLeast(1)
            val arrowTop=horizon+5f
            val arrowHeight=minOf(72f,(162f-arrowTop).coerceAtLeast(20f))
            val halfWidth=w/2f
            sourceCorners[0]=0f; sourceCorners[1]=0f
            sourceCorners[2]=w.toFloat(); sourceCorners[3]=0f
            sourceCorners[4]=w.toFloat(); sourceCorners[5]=h.toFloat()
            sourceCorners[6]=0f; sourceCorners[7]=h.toFloat()
            targetCorners[0]=120f-halfWidth*0.72f; targetCorners[1]=arrowTop
            targetCorners[2]=120f+halfWidth*0.72f; targetCorners[3]=arrowTop
            targetCorners[4]=120f+halfWidth; targetCorners[5]=arrowTop+minOf(arrowHeight,h*0.7f)
            targetCorners[6]=120f-halfWidth; targetCorners[7]=targetCorners[5]
            perspective.setPolyToPoly(sourceCorners,0,targetCorners,0,4)
            icon.setBounds(0,0,w,h)
            canvas.save(); canvas.concat(perspective)
            icon.draw(canvas)
            canvas.restore()
        }
        // A lower-looking viewpoint: keep its width, reduce the overhead silhouette's height.
        paint.color=Color.argb(95,0,0,0)
        rect.set(78f,239f,162f,261f); canvas.drawOval(rect,paint)
        car?.let {
            rect.set(84.3f,171f,155.7f,257.1f)
            canvas.drawBitmap(it,null,rect,paint.apply { color=Color.WHITE })
        }
        canvas.restore()
    }
}
