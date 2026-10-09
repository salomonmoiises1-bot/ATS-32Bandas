package com.sjbstudio.eq32.service

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Detection bridge adapted from Equalizer314. It asks Android for playback-change callbacks,
 * uses MediaSessionManager to trigger rescans, and tries to recover real positive audio session
 * IDs from audioserver. Synthetic IDs are intentionally never sent to the DSP engine.
 * The user must explicitly enable Notification access in Android Settings.
 */
class PlaybackListenerService : NotificationListenerService() {
    private var detectorThread: HandlerThread? = null
    private var handler: Handler? = null
    private var previous = mutableMapOf<Int, String>()
    private val snapshot = Runnable { scanAndDispatch() }
    private val heartbeat = object : Runnable {
        override fun run() {
            scheduleScan()
            handler?.postDelayed(this, HEARTBEAT_MS)
        }
    }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = scheduleScan()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { _: List<MediaController>? -> scheduleScan() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val thread = HandlerThread("ATSPlaybackDetector").also { it.start() }
        detectorThread = thread
        val h = Handler(thread.looper)
        handler = h
        h.post {
            try { getSystemService(AudioManager::class.java)?.registerAudioPlaybackCallback(playbackCallback, h) }
            catch (t: Throwable) { Log.w(TAG, "Audio playback callback registration failed", t) }
            try {
                getSystemService(MediaSessionManager::class.java)?.addOnActiveSessionsChangedListener(
                    sessionsListener, ComponentName(this, PlaybackListenerService::class.java), h
                )
            } catch (t: Throwable) { Log.w(TAG, "Media session listener registration failed", t) }
            scheduleScan()
            h.postDelayed(heartbeat, HEARTBEAT_MS)
        }
    }

    override fun onListenerDisconnected() {
        val h = handler
        val t = detectorThread
        handler = null
        detectorThread = null
        if (h != null) {
            h.removeCallbacks(snapshot)
            h.removeCallbacks(heartbeat)
            h.post {
                unregisterCallbacks()
                detachAll()
                t?.quitSafely()
            }
        } else {
            unregisterCallbacks()
            detachAll()
            t?.quitSafely()
        }
        super.onListenerDisconnected()
    }

    private fun scheduleScan() {
        val h = handler ?: return
        h.removeCallbacks(snapshot)
        h.postDelayed(snapshot, DEBOUNCE_MS)
    }

    private fun scanAndDispatch() {
        val targetPackages = setOf("com.google.android.youtube", "com.spotify.music", "com.aimp.player")
        val found = mutableMapOf<Int, String>()
        try {
            AudioPolicyDumpParser.dump(applicationContext).forEach { (pkg, ids) ->
                if (pkg in targetPackages) ids.filter { it > 0 }.forEach { id -> found[id] = pkg }
            }
        } catch (t: Throwable) { Log.w(TAG, "Session scan failed", t) }

        // Public APIs can show the active app but do not expose its usable session ID on many builds.
        // Deliberately do not manufacture an ID or attach the global session 0 in that case.
        val stale = previous.keys - found.keys
        stale.forEach { dispatch(EqService.ACTION_DETECTED_DETACH_SESSION, it, previous[it].orEmpty()) }
        found.forEach { (id, pkg) ->
            if (previous[id] != pkg) dispatch(EqService.ACTION_DETECTED_ATTACH_SESSION, id, pkg)
        }
        previous = found
        Log.d(TAG, "Target app sessions recovered=${found.size}; no synthetic/global sessions attached")
    }

    private fun dispatch(action: String, sessionId: Int, pkg: String) {
        if (sessionId <= 0) return
        val intent = Intent(this, EqService::class.java).apply {
            this.action = action
            putExtra(EqService.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(EqService.EXTRA_PACKAGE_NAME, pkg)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (t: Throwable) { Log.w(TAG, "Could not dispatch $action for session=$sessionId", t) }
    }

    private fun detachAll() {
        previous.toMap().forEach { (id, pkg) -> dispatch(EqService.ACTION_DETACH_SESSION, id, pkg) }
        previous.clear()
    }

    private fun unregisterCallbacks() {
        try { getSystemService(AudioManager::class.java)?.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Throwable) {}
        try { getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { /* no notification contents are read */ }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no notification contents are read */ }

    companion object {
        private const val TAG = "ATSPlaybackListener"
        private const val DEBOUNCE_MS = 100L
        private const val HEARTBEAT_MS = 3000L
    }
}
