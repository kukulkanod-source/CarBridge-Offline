package com.example.carbridge

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback

/** System binding/configuration is always a visible, user-initiated phone flow. */
class WeatherWidgetSetupActivity: AppCompatActivity() {
    private var pending=AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancel() }
        })
        title="Яндекс Погода для CarBridge"
        setContentView(TextView(this).apply {
            text="Разрешите добавление виджета Яндекс Погоды и выберите город в его настройках. После завершения вернитесь на ГУ."
            setPadding(24,24,24,24); textSize=18f
        })
        pending=state?.getInt("pending",AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if(state!=null && pending!=AppWidgetManager.INVALID_APPWIDGET_ID) return
        val orphan=WeatherWidgetStore.prefs(this).getInt("pending_id",AppWidgetManager.INVALID_APPWIDGET_ID)
        WeatherWidgetStore.discard(this,orphan)
        WeatherWidgetStore.prefs(this).edit().remove("pending_id").apply()
        val providers=WeatherWidgetStore.providers(this)
        if(providers.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Виджеты не найдены")
                .setMessage("Установите и один раз откройте Яндекс Погоду. Эта версия приложения должна предоставлять виджет рабочего стола в том же профиле Android, где установлен CarBridge.")
                .setPositiveButton("Закрыть") { _,_ -> finish() }.setOnCancelListener { finish() }.show()
        } else AlertDialog.Builder(this).setTitle("Выберите виджет погоды")
            .setItems(providers.map { "${it.loadLabel(packageManager)} (${it.minWidth}×${it.minHeight})" }.toTypedArray()) { _,which -> bind(providers[which]) }
            .setNegativeButton("Отмена") { _,_ -> finish() }.setOnCancelListener { finish() }.show()
    }
    private fun bind(info: AppWidgetProviderInfo) {
        try {
            pending=WeatherWidgetStore.host(this).allocateAppWidgetId()
            WeatherWidgetStore.prefs(this).edit().putInt("pending_id",pending).apply()
            val options=Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }
            if(AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(pending,info.profile,info.provider,options)) configure()
            else startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,pending)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,info.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,info.profile)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS,options)
            },1)
        } catch(error: Exception) { fail(error) }
    }
    private fun configure() {
        try {
            val info=AppWidgetManager.getInstance(this).getAppWidgetInfo(pending) ?: error("Виджет не привязан")
            if(!WeatherWidgetStore.isYandexWeather(info)) error("Неверный поставщик виджета")
            if(info.configure!=null) WeatherWidgetStore.host(this).startAppWidgetConfigureActivityForResult(this,pending,0,2,null)
            else complete()
        } catch(error: Exception) { fail(error) }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode !in 1..2) return
        if(resultCode!=RESULT_OK) { cancel(); return }
        if(requestCode==1) configure() else complete()
    }
    private fun complete() {
        try {
            WeatherWidgetStore.commit(this,pending)
            pending=AppWidgetManager.INVALID_APPWIDGET_ID
            finish()
        } catch(error: Exception) { fail(error) }
    }
    private fun cancel() {
        WeatherWidgetStore.discard(this,pending)
        WeatherWidgetStore.prefs(this).edit().remove("pending_id").apply()
        pending=AppWidgetManager.INVALID_APPWIDGET_ID; finish()
    }
    private fun fail(error: Exception) {
        SessionDiagnostics.event("Weather widget setup unavailable: ${error.javaClass.simpleName}")
        AlertDialog.Builder(this).setMessage("Не удалось настроить виджет: ${error.message ?: error.javaClass.simpleName}")
            .setPositiveButton("Закрыть") { _,_ -> cancel() }.setOnCancelListener { cancel() }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("pending",pending); super.onSaveInstanceState(outState) }
}
