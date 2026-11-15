package com.example.carbridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
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
    private val mappedBase=FloatArray(2)
    private val brakeShape=Path().apply {
        moveTo(142f,1042f); lineTo(320f,1065f); lineTo(282f,1086f); lineTo(151f,1068f); close()
        moveTo(704f,1065f); lineTo(881f,1042f); lineTo(873f,1068f); lineTo(742f,1086f); close()
    }
    private val leftBrakeGlow=RadialGradient(225f,1060f,140f,intArrayOf(Color.argb(200,255,25,10),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
    private val rightBrakeGlow=RadialGradient(799f,1060f,140f,intArrayOf(Color.argb(200,255,25,10),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
    private var original: Bitmap?=null
    private var baseX=0f
    private var baseY=0f
    private var active=false
    private var scheduled=false
    private var lastFrameAt=0L
    private var visualSpeed=0f
    private var observing=false
    private val motionChanged: () -> Unit = {
        if(HuNavigationMotion.metersPerSecond()<=0f) visualSpeed=0f
        invalidate(); scheduleFrame()
    }
    private var roadPhase=0f
    private val frame=object: Runnable {
        override fun run() {
            scheduled=false
            if(!canAnimate()) { visualSpeed=0f; invalidate(); return }
            val now=SystemClock.uptimeMillis()
            val dt=((now-lastFrameAt)/1000f).coerceIn(0f,0.1f)
            lastFrameAt=now
            val target=HuNavigationMotion.metersPerSecond()
            visualSpeed += (target-visualSpeed)*minOf(1f,dt*3f)
            // At 50 km/h the old 1.8-second cycle is retained; integrate phase without jumps.
            roadPhase=(roadPhase+dt*visualSpeed/25f)%1f
            invalidate(); scheduleFrame()
        }
    }
    fun bind(icon: Drawable?, alignBase: Boolean) {
        original=icon?.let {
            val iw=it.intrinsicWidth.coerceAtLeast(1); val ih=it.intrinsicHeight.coerceAtLeast(1)
            val s=minOf(132f/iw,116f/ih)
            val bitmap=Bitmap.createBitmap((iw*s).toInt().coerceAtLeast(1),(ih*s).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            val hardware=(it as? BitmapDrawable)?.bitmap?.takeIf { source -> source.config==Bitmap.Config.HARDWARE }
            val software=hardware?.copy(Bitmap.Config.ARGB_8888,false)
            if(software!=null) {
                Canvas(bitmap).drawBitmap(software,null,RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat()),paint.apply { color=Color.WHITE })
                software.recycle()
            } else {
                it.setBounds(0,0,bitmap.width,bitmap.height); it.draw(Canvas(bitmap))
            }
            bitmap
        }
        original?.let { bitmap ->
            val w=bitmap.width; val h=bitmap.height
            baseX=w/2f; baseY=h.toFloat()
            if(alignBase) {
                // Find the bottom visible stem, rather than the centre of the entire turning arrow.
                var bottom=-1
                for(y in h-1 downTo 0) {
                    var count=0
                    for(x in 0 until w) if(Color.alpha(bitmap.getPixel(x,y))>=64) count++
                    if(count>=maxOf(2,w/40)) { bottom=y; break }
                }
                if(bottom>=0) {
                    var sum=0L; var weight=0L
                    for(y in maxOf(0,bottom-maxOf(1,h/16))..bottom) for(x in 0 until w) {
                        val alpha=Color.alpha(bitmap.getPixel(x,y))
                        if(alpha>=64) { sum+=x.toLong()*alpha; weight+=alpha }
                    }
                    if(weight>0) { baseX=sum.toFloat()/weight+0.5f; baseY=bottom+1f }
                }
            }
        }
        invalidate(); scheduleFrame()
    }
    fun setActive(value: Boolean) {
        if(active==value) { scheduleFrame(); return }
        active=value
        if(value) lastFrameAt=SystemClock.uptimeMillis()
        else { removeCallbacks(frame); scheduled=false; visualSpeed=0f; invalidate() }
        syncMotionObserver()
        scheduleFrame()
    }
    private fun canAnimate()=active && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled() && HuNavigationMotion.metersPerSecond()>0f
    private fun scheduleFrame() {
        if(!scheduled && canAnimate()) { lastFrameAt=SystemClock.uptimeMillis(); scheduled=true; postDelayed(frame,50L) }
    }
    private fun syncMotionObserver() {
        val wanted=active && isAttachedToWindow && isShown
        if(wanted && !observing) { observing=true; HuNavigationMotion.observe(context,motionChanged) }
        else if(!wanted && observing) { observing=false; HuNavigationMotion.remove(motionChanged) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncMotionObserver(); scheduleFrame() }
    override fun onDetachedFromWindow() {
        removeCallbacks(frame); scheduled=false; super.onDetachedFromWindow()
        if(observing) { observing=false; HuNavigationMotion.remove(motionChanged) }
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncMotionObserver()
        if(isVisible) scheduleFrame() else { removeCallbacks(frame); scheduled=false }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(width<=0 || height<=0) return
        val scale=width/240f
        val designHeight=height/scale
        val objectScale=(designHeight/264f).coerceIn(0.65f,1.5f)
        val carBottom=designHeight-7f
        val carTop=carBottom-86.1f*objectScale
        val originY=0f
        // Match the card background's actual one-third horizon, including compact stacked cards.
        val cardHeight=(parent as? View)?.height ?: height
        val horizon=((cardHeight/3f-top-originY)/scale).coerceIn(0f,designHeight.coerceAtLeast(0f))
        canvas.save()
        canvas.translate((width-240f*scale)/2f,originY)
        canvas.scale(scale,scale)
        canvas.save()
        canvas.clipRect(0f,horizon,240f,designHeight)
        road.reset()
        road.moveTo(117f,horizon); road.lineTo(123f,horizon)
        road.lineTo(218f,designHeight); road.lineTo(22f,designHeight); road.close()
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(47,53,61)
        canvas.drawPath(road,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f
        paint.color=Color.rgb(172,185,191)
        canvas.drawLine(117f,horizon,22f,designHeight,paint)
        canvas.drawLine(123f,horizon,218f,designHeight,paint)
        paint.style=Paint.Style.FILL
        for(i in 0..5) {
            val t=(i/6f+roadPhase)%1f
            val end=(t+0.07f).coerceAtMost(1f)
            val y=horizon+t*t*(designHeight-horizon)
            val bottom=horizon+end*end*(designHeight-horizon)
            val halfWidth=0.5f+2.2f*t
            rect.set(120f-halfWidth,y,120f+halfWidth,bottom)
            paint.color=Color.argb((110+t*145).toInt(),235,239,235)
            canvas.drawRoundRect(rect,0.7f,0.7f,paint)
        }
        canvas.restore()
        original?.let { icon ->
            val w=icon.width
            val h=icon.height
            val arrowTop=horizon+5f*objectScale
            val arrowHeight=minOf(72f*objectScale,(carTop-12f*objectScale-arrowTop).coerceAtLeast(8f))
            val halfWidth=minOf(90f,w/2f*objectScale)
            sourceCorners[0]=0f; sourceCorners[1]=0f
            sourceCorners[2]=w.toFloat(); sourceCorners[3]=0f
            sourceCorners[4]=w.toFloat(); sourceCorners[5]=h.toFloat()
            sourceCorners[6]=0f; sourceCorners[7]=h.toFloat()
            targetCorners[0]=120f-halfWidth*0.72f; targetCorners[1]=arrowTop
            targetCorners[2]=120f+halfWidth*0.72f; targetCorners[3]=arrowTop
            targetCorners[4]=120f+halfWidth; targetCorners[5]=arrowTop+minOf(arrowHeight,h*0.7f*objectScale)
            targetCorners[6]=120f-halfWidth; targetCorners[7]=targetCorners[5]
            perspective.setPolyToPoly(sourceCorners,0,targetCorners,0,4)
            mappedBase[0]=baseX; mappedBase[1]=baseY
            perspective.mapPoints(mappedBase)
            canvas.save()
            canvas.translate(120f-mappedBase[0],targetCorners[5]-mappedBase[1])
            canvas.concat(perspective)
            canvas.drawBitmap(icon,0f,0f,paint.apply { color=Color.WHITE })
            canvas.restore()
        }
        // A lower-looking viewpoint: keep its width, reduce the overhead silhouette's height.
        paint.color=Color.argb(95,0,0,0)
        rect.set(120f-42f*objectScale,carBottom-18f*objectScale,120f+42f*objectScale,carBottom+4f*objectScale); canvas.drawOval(rect,paint)
        car?.let {
            rect.set(120f-35.7f*objectScale,carTop,120f+35.7f*objectScale,carBottom)
            canvas.drawBitmap(it,null,rect,paint.apply { color=Color.WHITE })
            if(HuNavigationMotion.isStopped()) {
                canvas.save()
                canvas.translate(rect.left,rect.top); canvas.scale(rect.width()/1024f,rect.height()/1536f)
                paint.shader=leftBrakeGlow; canvas.drawCircle(225f,1060f,140f,paint)
                paint.shader=rightBrakeGlow; canvas.drawCircle(799f,1060f,140f,paint)
                paint.shader=null; paint.color=Color.rgb(255,45,26); canvas.drawPath(brakeShape,paint)
                paint.strokeWidth=8f; paint.strokeCap=Paint.Cap.ROUND
                paint.color=Color.rgb(255,115,70)
                canvas.drawLine(155f,1049f,303f,1068f,paint)
                canvas.drawLine(721f,1068f,869f,1049f,paint)
                paint.color=Color.rgb(255,35,20); paint.strokeWidth=9f
                canvas.drawLine(414f,783f,608f,783f,paint)
                canvas.restore()
            }
        }
        canvas.restore()
    }
}
