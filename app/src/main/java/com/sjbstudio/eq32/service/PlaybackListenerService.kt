package com.sjbstudio.eq32.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class PlaybackListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "PlaybackListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        // Check if posted notification belongs to a MediaStyle audio player
        val isMedia = sbn.notification.extras.containsKey("android.mediaSession")
        if (isMedia) {
            val pkg = sbn.packageName
            Log.d(TAG, "Detected active media playback player notification from: $pkg")
            // Ensure EqService is actively alive and attached
            EqService.startService(applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
