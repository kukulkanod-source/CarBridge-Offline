package com.example.carbridge

import android.os.SystemClock
import android.service.notification.StatusBarNotification

/** One source for the card and the car metadata. A second navigator is never force-stopped. */
internal object HuGuidanceSource {
    private var preferred: String? = null
    private var selected: String? = null
    private var missingSince = 0L
    @Synchronized fun reset() { preferred=null; selected=null; missingSince=0L }
    @Synchronized fun foreground(pkg: String?) {
        if(pkg in HuNotificationContent.navigationPackages && pkg!=preferred) {
            preferred=pkg; selected=pkg; missingSince=0L
            SessionDiagnostics.event("Navigation guidance source selected; package=$pkg; reason=foreground_navigator")
        }
    }
    @Synchronized fun filter(items: List<StatusBarNotification>): List<StatusBarNotification> {
        val owner=selected ?: preferred
        val current=items.filter { it.packageName==owner }
        if(current.isNotEmpty()) { missingSince=0L; return current }
        val now=SystemClock.elapsedRealtime()
        if(owner!=null) {
            if(missingSince==0L) missingSince=now
            // Notification replacement must not briefly show another route's turn.
            if(now-missingSince<3000L) return emptyList()
        }
        val next=items.firstOrNull()?.packageName ?: return emptyList()
        if(next!=selected) SessionDiagnostics.event("Navigation guidance source selected; package=$next; reason=active_notification")
        selected=next; missingSince=0L
        return items.filter { it.packageName==next }
    }
}
