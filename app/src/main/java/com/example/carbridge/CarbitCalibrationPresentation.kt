package com.example.carbridge

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.example.carbridge.carbit.TouchCalibration

/** Raw transport points are collected before coordinate mapping, including letterbox margins. */
class CarbitCalibrationPresentation(context: Context, display: Display,
    private val outputWidth:Int, private val outputHeight:Int,
    private val success:(DoubleArray)->Unit, private val failed:(String)->Unit
):Presentation(context,display,android.R.style.Theme_Material_NoActionBar_Fullscreen) {
    private val fractions=arrayOf(.18 to .22,.82 to .22,.18 to .78,.82 to .78,.50 to .50)
    private val target=Array(5){i->doubleArrayOf(fractions[i].first*outputWidth,fractions[i].second*outputHeight)}
    private val raw=Array(5){DoubleArray(2)}
    private var index=0
    private var pending:DoubleArray?=null
    private var finished=false
    private lateinit var scene:View
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);setCancelable(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scene=object:View(context){
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas) {
                c.drawColor(Color.rgb(15,24,38))
                p.color=Color.WHITE;p.textAlign=Paint.Align.CENTER;p.textSize=(width*.024f).coerceAtLeast(20f)
                c.drawText("Нажмите и отпустите метку: ${index+1} / 5",width/2f,height*.09f,p)
                if(index<5) {
                    val x=(fractions[index].first*width).toFloat();val y=(fractions[index].second*height).toFloat()
                    val r=(height*.045f).coerceAtLeast(12f)
                    p.color=Color.rgb(70,225,130);p.style=Paint.Style.STROKE;p.strokeWidth=3f
                    c.drawCircle(x,y,r,p);c.drawLine(x-r*1.6f,y,x+r*1.6f,y,p);c.drawLine(x,y-r*1.6f,x,y+r*1.6f,p)
                    p.style=Paint.Style.FILL
                }
                p.color=Color.LTGRAY;p.textSize=(width*.017f).coerceAtLeast(15f)
                c.drawText("Отмена — кнопкой на телефоне",width/2f,height*.96f,p)
            }
        };setContentView(scene)
    }
    fun rawTouch(event:Int,x:Int,y:Int,slot:Int) {
        if(finished||slot!=0)return
        if(event==2)pending=doubleArrayOf(x.toDouble(),y.toDouble())
        if(event!=1)return
        val point=pending ?: return;pending=null
        raw[index]=point;index++
        if(index==5) {
            finished=true
            runCatching{TouchCalibration.fit(raw,target,maxOf(18.0,outputWidth*.015))}
                .onSuccess(success).onFailure{failed("Метки не совпали: повторите калибровку, точно нажимая центры")}
        }else scene.invalidate()
    }
    override fun onStart(){super.onStart();window?.setLayout(-1,-1)}
    fun close(){finished=true;runCatching{dismiss()}}
}
