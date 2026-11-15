package com.example.carbridge

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared entry point. Protocol logos are supplied assets, fitted without distortion. */
internal class ConnectionPickerView(context: Context, choose: (String)->Unit) : ScrollView(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val note=TextView(context).apply { textSize=16f; setPadding(0,dp(6),0,dp(18)) }
    init {
        isFillViewport=true
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(14),dp(18),dp(24)) }
        body.addView(TextView(context).apply { text="Выберите подключение"; textSize=26f; setTypeface(null,1) })
        body.addView(note)
        val wide=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        val cards=LinearLayout(context).apply { orientation=if(wide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        listOf(Triple("carlife","CarLife","USB · кабель к ГУ"),Triple("carbit","CarbitLink","Wi-Fi · планшет или ГУ"),Triple("aa","Android Auto","Без проводов · через адаптер")).forEach { (id,title,description)->
            val card=LinearLayout(context).apply {
                orientation=if(wide)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                minimumHeight=dp(if(wide)190 else 108)
                setPadding(dp(16),dp(14),dp(16),dp(14))
                background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(27,53,83),Color.rgb(15,30,49))).apply { cornerRadius=dp(20).toFloat() }
                isFocusable=true; isClickable=true
                contentDescription="$title. $description"
                setOnClickListener { choose(id) }
            }
            card.addView(ProtocolBadge(context,id),LinearLayout.LayoutParams(dp(54),dp(54)).apply { rightMargin=dp(16); bottomMargin=if(wide)dp(12) else 0 })
            card.addView(LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                addView(TextView(context).apply { text=title; textSize=22f; setTextColor(Color.WHITE); setTypeface(null,1) })
                addView(TextView(context).apply { text=description; textSize=14f; setTextColor(Color.rgb(187,213,238)); setPadding(0,dp(5),0,0) })
            },LinearLayout.LayoutParams(if(wide)-1 else 0,-2,if(wide)0f else 1f))
            cards.addView(card,LinearLayout.LayoutParams(if(wide)0 else -1,if(wide)-1 else -2,if(wide)1f else 0f).apply {
                if(wide){leftMargin=dp(4);rightMargin=dp(4)} else bottomMargin=dp(12)
            })
        }
        body.addView(cards,LinearLayout.LayoutParams(-1,if(wide)dp(210) else -2))
        body.addView(TextView(context).apply { text="Настройки доступны сверху. Для CarbitLink и Android Auto способ подключения выбирается на следующем шаге.";textSize=14f;setPadding(0,dp(12),0,0) })
        addView(body)
        setSessionActive(false)
    }
    fun setSessionActive(active:Boolean) {
        note.text=if(active)"Подключение запущено. Для смены протокола сначала нажмите «Стоп»." else "● Телефон подготовлен. Выберите протокол вашего приёмника."
    }
}

private class ProtocolBadge(context:Context,protocol:String):android.widget.ImageView(context) {
    init {
        scaleType=ScaleType.FIT_CENTER
        if(protocol=="carlife") {
            outlineProvider=object:android.view.ViewOutlineProvider() {
                override fun getOutline(view:android.view.View,outline:android.graphics.Outline) {
                    outline.setOval(0,0,view.width,view.height)
                }
            }
            clipToOutline=true
        }
        setImageResource(when(protocol) {
            "carlife" -> R.drawable.protocol_carlife
            "carbit" -> R.drawable.protocol_carbit
            else -> R.drawable.protocol_aa
        })
    }
}

internal object ConnectionScreens {
    const val EXTRA_SETTINGS="carbridge.open_settings"
    fun settings(context:Context) = context.startActivity(Intent(context,MainActivity::class.java)
        .putExtra(EXTRA_SETTINGS,true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}
