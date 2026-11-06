package com.example.carbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Inline code entry keeps Android's short-lived pairing dialog open. */
internal object PairingPrompt {
    private const val CHANNEL = "carbridge_adb_pairing"
    private const val ID = 9207
    const val CODE = "pairing_code"
    fun available(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun show(context: Context, message: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Подготовка подключения", NotificationManager.IMPORTANCE_HIGH))
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val reply = PendingIntent.getForegroundService(context, ID,
            Intent(context, ConnectionPreparationService::class.java).setAction(ConnectionPreparationService.ACTION_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(android.R.drawable.ic_lock_lock, "Ввести код", reply)
            .addRemoteInput(RemoteInput.Builder(CODE).setLabel("6 цифр из окна Android").build()).build()
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Сопряжение CarBridge")
            .setContentText(message ?: "Откройте окно с кодом Android и введите код здесь")
            .setStyle(Notification.BigTextStyle().bigText(message ?: "В разделе «Беспроводная отладка» откройте окно с кодом. Нажмите «Ввести код» в этом уведомлении, оставив окно Android открытым."))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).addAction(action).build()
        runCatching { manager.notify(ID, notification) }
    }
    fun hide(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(ID) }
}

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.PAIR_CODE") return
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingPrompt.CODE)?.toString()?.trim() ?: return
        val wizard = ConnectionWizard.get(context)
        if (wizard.current().action != ConnectionWizard.Action.PAIR) return
        wizard.pair(code)
    }
}
