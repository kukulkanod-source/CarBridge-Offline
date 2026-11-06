package com.example.carbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

/** Owns preparation while Settings is foreground; no Activity or code is persisted. */
class ConnectionPreparationService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wizard: ConnectionWizard
    private lateinit var notifications: NotificationManager
    private var deadline = 0L
    private var lastEnvironment = ""
    private var lastNotification = ""
    private var lastCodeNotification = ""
    private var finished = false
    private var pendingCode: String? = null
    private var pendingCodeUntil = 0L
    private val observer = Observer<ConnectionWizard.State> { state -> render(state) }
    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                wizard.cancelPreparation("Время подготовки истекло. Нажмите «Проверить и продолжить», чтобы начать снова.")
                finish("Подготовка остановлена по времени")
                return
            }
            val state = wizard.current()
            if (pendingCode != null && SystemClock.elapsedRealtime() >= pendingCodeUntil) {
                pendingCode = null
                if (state.action == ConnectionWizard.Action.PAIR) PairingPrompt.show(this@ConnectionPreparationService, "Код не был отправлен: предыдущая проверка заняла слишком долго. Введите текущий код ещё раз.")
            }
            if (!state.busy && state.action == ConnectionWizard.Action.PAIR && pendingCode != null) {
                val code = pendingCode!!
                pendingCode = null
                wizard.pair(code)
            } else if (!state.busy && !state.phoneReady && !state.connected) {
                val signature = wizard.environmentSignature()
                if (signature != lastEnvironment) {
                    lastEnvironment = signature
                    wizard.check()
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        running = true
        requestedAt = 0L
        wizard = ConnectionWizard.get(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Подготовка подключения", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification("Проверяю готовность телефона…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        deadline = SystemClock.elapsedRealtime() + 8 * 60_000L
        lastEnvironment = wizard.environmentSignature()
        wizard.state.observeForever(observer)
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                wizard.cancelPreparation("Подготовка отменена. Для повтора нажмите «Проверить и продолжить».")
                finish("Подготовка отменена")
            }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingPrompt.CODE)?.toString()?.trim()
                if (!code.isNullOrEmpty() && !wizard.current().phoneReady && !wizard.current().connected) {
                    if (!wizard.current().busy && wizard.current().action == ConnectionWizard.Action.PAIR) {
                        PairingPrompt.hide(this)
                        lastCodeNotification = ""
                        wizard.pair(code)
                    } else {
                        pendingCode = code
                        pendingCodeUntil = SystemClock.elapsedRealtime() + 20000L
                        if (!wizard.current().busy) { wizard.resumePreparation(); wizard.check() }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun render(state: ConnectionWizard.State) {
        if (finished) return
        if (!state.busy && wizard.preparationIsCancelled()) { finish(state.message); return }
        val text = listOf(state.message, state.preparationNote).filter { it.isNotBlank() }.joinToString("\n")
        if (text != lastNotification) {
            lastNotification = text
            notifications.notify(ID, notification(text))
        }
        if (state.action == ConnectionWizard.Action.PAIR && !state.busy) {
            if (text != lastCodeNotification) { lastCodeNotification = text; PairingPrompt.show(this, text) }
        } else { PairingPrompt.hide(this); lastCodeNotification = "" }
        // Wait for the bounded runner to release its slot before relinquishing process priority.
        if (!state.busy && (state.phoneReady || state.connected)) finish(text)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, ID, Intent(this, javaClass).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Подготовка CarBridge").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отменить", cancel).build()).build()
    }

    private fun finish(text: String) {
        if (finished) return
        finished = true
        pendingCode = null
        PairingPrompt.hide(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(RESULT_ID, Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Подготовка CarBridge").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        stopSelf()
    }

    override fun onDestroy() {
        pendingCode = null
        main.removeCallbacksAndMessages(null)
        wizard.state.removeObserver(observer)
        if (!finished && com.example.carbridge.capture.AppDisplayState.displayId <= 0) wizard.cancelPreparation("Сервис подготовки остановлен. Повторите текущий шаг.")
        running = false
        requestedAt = 0L
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "carbridge_preparation"
        private const val ID = 9208
        private const val RESULT_ID = 9209
        const val ACTION_CODE = "com.example.carbridge.PREPARE_CODE"
        const val ACTION_CANCEL = "com.example.carbridge.PREPARE_CANCEL"
        @Volatile var running = false
            private set
        private var requestedAt = 0L
        fun ensureStarted(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (running || (requestedAt != 0L && now - requestedAt < 5000L)) return
            requestedAt = now
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionPreparationService::class.java)) }
                .onFailure { requestedAt = 0L; android.util.Log.w("CarBridge/Preparation", "Foreground preparation unavailable: ${it.javaClass.simpleName}") }
        }
    }
}
