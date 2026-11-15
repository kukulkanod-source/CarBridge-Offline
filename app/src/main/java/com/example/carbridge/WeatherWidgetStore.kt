package com.example.carbridge

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

/** One independent widget instance; never reuses or deletes a launcher widget. */
internal object WeatherWidgetStore {
    private var instance: AppWidgetHost?=null
    fun host(context: Context): AppWidgetHost = instance ?: AppWidgetHost(context.applicationContext,6801).also { instance=it }
    fun prefs(context: Context)=context.getSharedPreferences("weather_widget",0)
    fun id(context: Context)=prefs(context).getInt("active_id",AppWidgetManager.INVALID_APPWIDGET_ID)
    fun isYandexWeather(info: AppWidgetProviderInfo): Boolean {
        val name=info.provider.packageName.lowercase()
        return name.contains("yandex") && (name.contains("weather") || name.contains("pogoda"))
    }
    fun info(context: Context): AppWidgetProviderInfo? = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetInfo(id(context))?.takeIf(::isYandexWeather)
    }.getOrNull()
    fun providers(context: Context)=AppWidgetManager.getInstance(context).installedProviders.filter(::isYandexWeather)
    fun discard(context: Context, id: Int) {
        if(id!=AppWidgetManager.INVALID_APPWIDGET_ID && id!=this.id(context)) runCatching { host(context).deleteAppWidgetId(id) }
    }
    fun commit(context: Context, id: Int) {
        val info=AppWidgetManager.getInstance(context).getAppWidgetInfo(id)
        require(info!=null && isYandexWeather(info))
        val old=this.id(context)
        prefs(context).edit().putInt("active_id",id).remove("pending_id").apply()
        discard(context,old)
    }
}
