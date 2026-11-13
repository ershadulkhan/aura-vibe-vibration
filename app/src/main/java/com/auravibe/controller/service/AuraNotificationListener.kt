package com.auravibe.controller.service

import android.app.Notification
import android.media.AudioManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.auravibe.controller.AuraVibeApp

/**
 * Event-driven engine. The system binds this service and calls onNotificationPosted;
 * between notifications it does nothing — no wake locks, no polling, no timers.
 */
class AuraNotificationListener : NotificationListenerService() {
    private val lastFire = HashMap<String, Long>()
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = AuraVibeApp.instance
        val s = app.repo.settings.value
        if (!s.enabled) return
        val rule = app.repo.rules.value[sbn.packageName] ?: return   // O(1) lookup
        if (!rule.active) return

        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        if (s.onlyInQuietModes) {
            val mode = audio.ringerMode
            if (mode != AudioManager.RINGER_MODE_VIBRATE && mode != AudioManager.RINGER_MODE_SILENT) return
        }

        val now = SystemClock.elapsedRealtime()
        val prev = lastFire[sbn.packageName]
        if (prev != null && now - prev < s.cooldownMs) return
        lastFire[sbn.packageName] = now

        if (rule.vibrate && s.vibe.enabled) {
            app.vibes.vibrate(s.vibe, s.usage, if (rule.custom) rule.vibeStyle else null)
        }
        // Don't hijack a light the user turned on manually.
        if (rule.aura && !app.lightOn) {
            app.notificationFlash(if (rule.custom) s.light.copy(temp = rule.temp) else s.light)
        }
    }

    override fun onListenerDisconnected() {
        lastFire.clear()
    }
}
