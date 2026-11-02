package com.example.carbridge

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/** Android grants MediaSessionManager access after the user enables notification access. */
class HuMediaNotificationListener : NotificationListenerService() {
    private val snapshotRevision = java.util.concurrent.atomic.AtomicLong()
    private val refreshPending = java.util.concurrent.atomic.AtomicBoolean()
    private var refreshAt = -10000L
    private val diagnosticAt = HashMap<String,Long>()
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        live = this
        snapshotRevision.incrementAndGet()
        notifications.clear()
        runCatching { activeNotifications?.forEach { notifications[it.key] = it } }
        SessionDiagnostics.event("Notification listener connected; activeCount=${notifications.size}; strelka=${notifications.values.count { isStrelka(it.packageName) }}")
        notifyNotifications()
        notifySessionChange()
    }

    override fun onListenerDisconnected() {
        connected = false
        if (live === this) live = null
        notifications.clear()
        notifyNotifications()
        notifySessionChange()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (live === this) {
            live = null
            connected = false
            notifications.clear()
            notifyNotifications()
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null) {
            snapshotRevision.incrementAndGet()
            notifications[sbn.key] = sbn
            if(isStrelka(sbn.packageName) || sbn.packageName in HuNotificationContent.navigationPackages) {
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-(diagnosticAt[sbn.packageName] ?: -10000L)>=5000) {
                    diagnosticAt[sbn.packageName]=now
                    val n=sbn.notification
                    SessionDiagnostics.event("HU notification source=${sbn.packageName}; category=${n.category}; ongoing=${sbn.isOngoing}; title=${n.extras.containsKey(Notification.EXTRA_TITLE)}; text=${n.extras.containsKey(Notification.EXTRA_TEXT)}; lines=${n.extras.containsKey(Notification.EXTRA_TEXT_LINES)}; custom=${n.contentView!=null || n.bigContentView!=null}; largeIcon=${n.getLargeIcon()!=null}; groupSummary=${n.flags and Notification.FLAG_GROUP_SUMMARY != 0}; flags=${n.flags}; activeForSource=${notifications.values.count { it.packageName == sbn.packageName }}")
                }
            }
            notifyNotifications(sbn.key)
        }
        if (isMediaNotification(sbn)) notifySessionChange()
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null) {
            snapshotRevision.incrementAndGet()
            notifications.computeIfPresent(sbn.key) { _, current -> if (current.postTime > sbn.postTime) current else null }
            if (sbn.packageName in HuNotificationContent.navigationPackages) {
                SessionDiagnostics.event("HU navigation notification removed; source=${sbn.packageName}; summary=${sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0}; activeForSource=${notifications.values.count { it.packageName == sbn.packageName }}")
            }
        }
        notifyNotifications()
        if (isMediaNotification(sbn)) notifySessionChange()
    }

    private fun refreshSnapshot() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - refreshAt < 10000 || !refreshPending.compareAndSet(false, true)) return
        refreshAt = now
        val revision = snapshotRevision.get()
        Thread({
            val current = runCatching { activeNotifications?.toList() }.getOrNull()
            android.os.Handler(mainLooper).post {
                refreshPending.set(false)
                if (live !== this || !connected || revision != snapshotRevision.get() || current == null) return@post
                notifications.clear()
                current.forEach { notifications[it.key] = it }
                val nav = current.filter { it.packageName in HuNotificationContent.navigationPackages }
                SessionDiagnostics.event("HU notification snapshot reconciled; total=${current.size}; navigation=${nav.size}; summaries=${nav.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }}")
                notifyNotifications()
            }
        }, "carbridge-notification-refresh").apply { isDaemon = true }.start()
    }

    private fun isMediaNotification(sbn: android.service.notification.StatusBarNotification?): Boolean {
        val notification = sbn?.notification ?: return false
        return notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun notifySessionChange() {
        sendBroadcast(Intent(ACTION_MEDIA_SESSIONS_CHANGED).setPackage(packageName))
    }

    private fun notifyNotifications(postedKey: String? = null) {
        sendBroadcast(Intent(ACTION_NOTIFICATIONS_CHANGED).setPackage(packageName)
            .putExtra("posted_key", postedKey))
    }

    companion object {
        const val ACTION_MEDIA_SESSIONS_CHANGED = "com.example.carbridge.MEDIA_SESSIONS_CHANGED"
        const val ACTION_NOTIFICATIONS_CHANGED = "com.example.carbridge.NOTIFICATIONS_CHANGED"
        @Volatile var connected = false
            private set
        private val notifications = ConcurrentHashMap<String, StatusBarNotification>()
        @Volatile private var live: HuMediaNotificationListener? = null
        fun isStrelka(packageName: String): Boolean =
            packageName.startsWith("com.ivolk.strelka", ignoreCase = true)
        fun diagnoseStrelka(context: android.content.Context): String {
            val items = snapshot().filter { isStrelka(it.packageName) }
            val enabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { android.content.ComponentName.unflattenFromString(it)?.packageName == context.packageName } == true
            val message = "Strelka diagnostics; access=$enabled; listener=$connected; active=${items.size}; ongoing=${items.count { it.isOngoing }}; custom=${items.count { it.notification.contentView != null || it.notification.bigContentView != null }}"
            SessionDiagnostics.event(message)
            return "Доступ к уведомлениям: ${if (enabled) "включён" else "выключен"}\n" +
                "Служба: ${if (connected) "подключена" else "не подключена"}\nУведомлений Стрелки: ${items.size}\n\n" +
                "Запустите Стрелку и включите её уведомления. Отдельное плавающее окно телефона не является уведомлением и автоматически на ГУ не переносится."
        }
        fun requestSnapshotRefresh() { live?.takeIf { connected }?.refreshSnapshot() }
        fun navigationSnapshot(): List<StatusBarNotification> {
            val all = snapshot().filter { it.packageName in HuNotificationContent.navigationPackages }
            return all.filter { item ->
                item.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 || all.none {
                    it.packageName == item.packageName && it.groupKey == item.groupKey &&
                        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
                }
            }
        }
        fun snapshot(): List<StatusBarNotification> = notifications.values.sortedByDescending { it.postTime }
        fun dismiss(key: String): Boolean {
            val listener = live?.takeIf { connected } ?: return false
            if (notifications[key]?.isClearable != true) return false
            return runCatching { listener.cancelNotification(key); true }.getOrDefault(false)
        }
    }
}
