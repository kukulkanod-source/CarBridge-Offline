package com.example.carbridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Process cache, warmed before the user opens the HU launcher. Main-thread state. */
internal object HuAppCatalog {
    data class App(val component: ComponentName, val label: String, val icon: Drawable.ConstantState?)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "hu-app-catalog").apply { isDaemon = true } }
    private var registered = false
    private var loaded = false
    private var loading = false
    private var dirty = true
    private var entries = emptyList<App>()
    private val callbacks = ArrayList<(List<App>) -> Unit>()
    fun warm(context: Context) = load(context, null)
    fun load(context: Context, callback: ((List<App>) -> Unit)?) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { load(app, callback) }; return }
        if (!registered) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) { dirty = true; warm(app) }
            }
            app.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
            }, Context.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        if (loaded && callback != null) { val snapshot = entries; main.post { callback(snapshot) } }
        if (!dirty && !loading) return
        if (callback != null) callbacks.add(callback)
        if (loading) return
        dirty = false; loading = true
        val started = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            val result = runCatching {
                val pm = app.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != app.packageName && it.activityInfo.enabled && it.activityInfo.exported }
                    .distinctBy { it.activityInfo.packageName }
                    .map { App(ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadLabel(pm).toString(),
                        runCatching { it.loadIcon(pm).constantState }.getOrNull()) }
            }
            main.post {
                loading = false
                if (result.isSuccess) { entries = result.getOrThrow(); loaded = true }
                val pending = callbacks.toList(); callbacks.clear()
                pending.forEach { it(entries) }
                SessionDiagnostics.event("HU app catalog ready; count=${entries.size}; elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}; success=${result.isSuccess}")
                if (dirty) warm(app)
            }
        }
    }
}
