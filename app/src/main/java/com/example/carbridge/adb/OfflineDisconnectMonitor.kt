package com.example.carbridge.adb

import android.content.Context
import android.content.Intent
import com.example.carbridge.AdbSetupViewModel
import com.example.carbridge.capture.CaptureService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Short post-USB check. Owns no Service/Activity and never changes activation preferences. */
object OfflineDisconnectMonitor {
    private val epoch = AtomicLong()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "adb-after-usb") }

    fun cancel() { epoch.incrementAndGet() }

    fun start(context: Context, port: Int, record: (String) -> Unit) {
        val app = context.applicationContext
        val token = epoch.incrementAndGet()
        worker.execute {
            fun cancelled() = epoch.get() != token || AdbSetupViewModel.isOperationBusy()
            try {
                record("USB disconnected; waiting 2 seconds before local ADB check")
                repeat(20) {
                    if (cancelled()) { record("Post-USB ADB check cancelled by a new session/operation"); return@execute }
                    Thread.sleep(100L)
                }
                val result = OfflineAdbHealth.checkLocal(app, port, cancelled = ::cancelled, report = record)
                record(result.summary)
                if (!cancelled()) {
                    app.sendBroadcast(Intent(CaptureService.ACTION_DISPLAY_STATUS).setPackage(app.packageName)
                        .putExtra(CaptureService.EXTRA_STATUS, result.summary))
                }
            } catch (error: Exception) {
                record("Post-USB ADB check failed: ${error.javaClass.simpleName}: ${error.message?.take(200)}")
            }
        }
    }
}
