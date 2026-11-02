package com.example.carbridge

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.carbridge.capture.AppDisplayState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран, который рисуется на дисплее ГУ. Пока это диагностика:
 *  - часы обновляются раз в 500 мс, так что видео на ГУ идёт постоянно;
 *  - строка «Касание» показывает, доходит ли тач до дисплея;
 *  - список приложений; тап пытается запустить приложение на этом же дисплее
 *    и показывает результат (успех или текст ошибки).
 * Позже превращается в полноценный лаунчер.
 */
class HuHomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var info: TextView
    private var apps: List<ResolveInfo> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(32, 24, 32, 24)
        }
        clock = TextView(this).apply { textSize = 44f; setTextColor(Color.WHITE) }
        info = TextView(this).apply {
            textSize = 20f; setTextColor(Color.parseColor("#9FD0FF"))
            text = "Касание: нет. Дисплей ${currentDisplayId()}"
        }
        root.addView(clock)
        root.addView(info)

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val labels = apps.map { it.loadLabel(packageManager).toString() }

        val list = ListView(this)
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE); v.textSize = 22f
                return v
            }
        }
        list.setOnItemClickListener { _, _, position, _ -> launchApp(apps[position]) }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        Log.i(TAG, "создан на дисплее ${currentDisplayId()}, приложений: ${apps.size}")
    }

    private fun currentDisplayId(): Int = display?.displayId ?: AppDisplayState.displayId

    private fun launchApp(ri: ResolveInfo) {
        val pkg = ri.activityInfo.packageName
        val id = currentDisplayId()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) { info.text = "Нет launch-интента: $pkg"; return }
        try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
            info.text = "Запуск $pkg на дисплее $id: отправлен"
            Log.i(TAG, "запуск $pkg на дисплее $id отправлен")
        } catch (e: Exception) {
            info.text = "Ошибка запуска $pkg: ${e.message?.take(160)}"
            Log.e(TAG, "запуск $pkg на дисплее $id не удался", e)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        info.text = "Касание: action=${ev.actionMasked} x=${ev.x.toInt()} y=${ev.y.toInt()} дисплей ${currentDisplayId()}"
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private companion object { const val TAG = "CarBridge/Home" }
}
