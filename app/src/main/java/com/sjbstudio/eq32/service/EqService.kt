package com.sjbstudio.eq32.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sjbstudio.eq32.MainActivity
import com.sjbstudio.eq32.R
import com.sjbstudio.eq32.core.DynamicsProcessingManager
import com.sjbstudio.eq32.data.EqPreferencesManager
import com.sjbstudio.eq32.state.EqState32WithMDRC

class EqService : Service() {

    companion object {
        private const val TAG = "EqService"
        const val CHANNEL_ID = "sjb_dsp_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.sjbstudio.eq32.ACTION_START"
        const val ACTION_STOP = "com.sjbstudio.eq32.ACTION_STOP"
        const val ACTION_TOGGLE = "com.sjbstudio.eq32.ACTION_TOGGLE"
        const val ACTION_UPDATE_STATE = "com.sjbstudio.eq32.ACTION_UPDATE_STATE"
        const val ACTION_ATTACH_SESSION = "com.sjbstudio.eq32.ACTION_ATTACH_SESSION"
        const val ACTION_DETACH_SESSION = "com.sjbstudio.eq32.ACTION_DETACH_SESSION"
        const val EXTRA_AUDIO_SESSION = "EXTRA_AUDIO_SESSION"

        fun startService(context: Context) {
            val intent = Intent(context, EqService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val binder = LocalBinder()
    private val dynamicsManagers = mutableMapOf<Int, DynamicsProcessingManager>()
    private lateinit var prefsManager: EqPreferencesManager
    private var currentState = EqState32WithMDRC()

    inner class LocalBinder : Binder() {
        fun getService(): EqService = this@EqService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SJBStudio DSP Service onCreate")
        prefsManager = EqPreferencesManager(this)
        currentState = prefsManager.loadCurrentState()
        createNotificationChannel()

        // Session 0 is only a fallback until Android reports real playback sessions.
        attachSession(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_TOGGLE -> {
                val newState = currentState.copy(isEnabled = !currentState.isEnabled)
                updateState(newState)
            }
            ACTION_UPDATE_STATE -> {
                currentState = prefsManager.loadCurrentState()
                dynamicsManagers.values.forEach { it.applyState(currentState) }
                updateNotification()
            }
            ACTION_ATTACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION, 0)
                if (sessionId != 0) {
                    // Avoid processing the same output through both global and app session effects.
                    dynamicsManagers.remove(0)?.release()
                    attachSession(sessionId)
                }
            }
            ACTION_DETACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION, 0)
                dynamicsManagers.remove(sessionId)?.release()
                if (dynamicsManagers.keys.none { it != 0 }) attachSession(0)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    fun updateState(newState: EqState32WithMDRC) {
        currentState = newState
        prefsManager.saveCurrentState(newState)
        dynamicsManagers.values.forEach { it.applyState(newState) }
        updateNotification()
    }

    fun getCurrentState(): EqState32WithMDRC = currentState

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SJBStudio DSP Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zero-Root 32-Band EQ & 4-Band MDRC Audio Processing"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, EqService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingToggle = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (currentState.isEnabled) "DSP ACTIVE · EQ32 + Tone + MDRC" else "DSP BYPASSED"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SJBStudio EQ32")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_tile_eq)
            .setContentIntent(pendingOpen)
            .addAction(
                0,
                if (currentState.isEnabled) "Bypass" else "Enable",
                pendingToggle
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    @Synchronized
    private fun attachSession(sessionId: Int) {
        val existing = dynamicsManagers[sessionId]
        if (existing != null) {
            existing.applyState(currentState)
            return
        }
        val manager = DynamicsProcessingManager()
        if (manager.attachToSession(sessionId, currentState)) {
            dynamicsManagers[sessionId] = manager
        } else {
            manager.release()
            Log.w(TAG, "Unable to attach DSP to audio session $sessionId")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SJBStudio DSP Service onDestroy")
        dynamicsManagers.values.forEach { it.release() }
        dynamicsManagers.clear()
    }
}
