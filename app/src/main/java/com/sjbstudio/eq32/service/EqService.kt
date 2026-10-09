package com.sjbstudio.eq32.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Binder
import android.os.Handler
import android.os.Looper
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
        const val ACTION_REFRESH_ACTIVE_SESSION = "com.sjbstudio.eq32.ACTION_REFRESH_ACTIVE_SESSION"
        const val ACTION_DETECTED_ATTACH_SESSION = "com.sjbstudio.eq32.ACTION_DETECTED_ATTACH_SESSION"
        const val ACTION_DETECTED_DETACH_SESSION = "com.sjbstudio.eq32.ACTION_DETECTED_DETACH_SESSION"
        const val EXTRA_AUDIO_SESSION = "EXTRA_AUDIO_SESSION"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        private val TARGET_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.spotify.music",
            "com.aimp.player"
        )

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
    // Keep only explicitly targeted app sessions; never attach session 0/global output.
    // Keep broadcast-announced sessions separate from sessions discovered by the EQ314-style listener.
    private val announcedSessions = mutableMapOf<Int, String>()
    private val detectedSessions = mutableMapOf<Int, String>()
    private val sessionHandler = Handler(Looper.getMainLooper())
    private var playbackCallbackRegistered = false
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            refreshActiveSession()
        }
    }
    private val sessionRefresh = object : Runnable {
        override fun run() {
            if (!serviceDestroyed) {
                refreshActiveSession()
                sessionHandler.postDelayed(this, 2000L)
            }
        }
    }
    @Volatile private var serviceDestroyed = false

    inner class LocalBinder : Binder() {
        fun getService(): EqService = this@EqService
    }

    override fun onCreate() {
        super.onCreate()
        prefsManager = EqPreferencesManager(this)
        currentState = prefsManager.loadCurrentState()
        createNotificationChannel()
        val audioManager = getSystemService(AudioManager::class.java)
        try {
            audioManager?.registerAudioPlaybackCallback(playbackCallback, sessionHandler)
            playbackCallbackRegistered = audioManager != null
        } catch (e: Exception) {
            Log.w(TAG, "Playback callback registration unavailable", e)
        }
        sessionHandler.post(sessionRefresh)
        refreshActiveSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

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
            ACTION_ATTACH_SESSION, ACTION_DETECTED_ATTACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION, 0)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                if (isTargetPackage(packageName) && sessionId > 0) {
                    if (intent.action == ACTION_DETECTED_ATTACH_SESSION) detectedSessions[sessionId] = packageName
                    else announcedSessions[sessionId] = packageName
                    attachSessionSafely(sessionId, packageName)
                } else {
                    Log.d(TAG, "Ignoring non-target/invalid audio session: package=$packageName session=$sessionId")
                }
            }
            ACTION_DETACH_SESSION, ACTION_DETECTED_DETACH_SESSION -> {
                val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION, 0)
                if (sessionId > 0) {
                    if (intent.action == ACTION_DETECTED_DETACH_SESSION) detectedSessions.remove(sessionId)
                    else announcedSessions.remove(sessionId)
                }
                refreshActiveSession()
            }
            ACTION_REFRESH_ACTIVE_SESSION -> {
                refreshActiveSession()
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

        val statusText = when {
            !currentState.isEnabled -> "DSP BYPASSED"
            dynamicsManagers.isNotEmpty() -> "DSP activo · sesión de app objetivo conectada"
            else -> "Esperando sesión de YouTube, Spotify o AIMP"
        }

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
    private fun refreshActiveSession() {
        val audioManager = getSystemService(AudioManager::class.java)
        val discovered = mutableMapOf<Int, String>()
        try {
            audioManager?.activePlaybackConfigurations?.forEach { config ->
                val active = readPlaybackConfigValue(config, "isActive") as? Boolean
                    ?: ((readPlaybackConfigValue(config, "getPlayerState") as? Number)?.toInt() == 2)
                if (!active) return@forEach

                val sessionId = ((readPlaybackConfigValue(config, "getAudioSessionId") as? Number)?.toInt()
                    ?: (readPlaybackConfigValue(config, "getClientAudioSessionId") as? Number)?.toInt())
                    ?: return@forEach
                if (sessionId <= 0) return@forEach

                val uid = (readPlaybackConfigValue(config, "getClientUid") as? Number)?.toInt()
                    ?: return@forEach
                val packageName = try {
                    packageManager.getPackagesForUid(uid)
                        ?.firstOrNull { isTargetPackage(it) }
                } catch (_: Exception) { null }
                if (packageName != null) discovered[sessionId] = packageName
            }
        } catch (e: Exception) {
            Log.d(TAG, "Playback session discovery unavailable; relying on target-app session broadcasts", e)
        }

        // The broadcast route is retained because some players expose sessions this way.
        val desired = LinkedHashMap<Int, String>().apply {
            putAll(discovered)
            putAll(announcedSessions)
            putAll(detectedSessions)
        }

        val staleSessions = dynamicsManagers.keys.filter { it !in desired.keys }
        staleSessions.forEach { detachSession(it) }
        desired.forEach { (sessionId, packageName) -> attachSessionSafely(sessionId, packageName) }
        updateNotification()
    }

    private fun isTargetPackage(packageName: String): Boolean = packageName in TARGET_PACKAGES

    private fun readPlaybackConfigValue(config: AudioPlaybackConfiguration, methodName: String): Any? {
        return try {
            config.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }?.invoke(config)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    @Synchronized
    private fun attachSessionSafely(sessionId: Int, packageName: String) {
        if (sessionId <= 0 || !isTargetPackage(packageName)) return

        val existing = dynamicsManagers[sessionId]
        if (existing != null) {
            existing.applyState(currentState)
            return
        }

        val manager = DynamicsProcessingManager()
        if (manager.attachToSession(sessionId, currentState)) {
            dynamicsManagers[sessionId] = manager
            Log.i(TAG, "Attached DSP to target app=$packageName session=$sessionId")
        } else {
            manager.release()
            Log.w(TAG, "Unable to attach DSP to target app=$packageName session=$sessionId; no global fallback is used")
        }
    }

    @Synchronized
    private fun detachSession(sessionId: Int) {
        dynamicsManagers.remove(sessionId)?.release()
        Log.d(TAG, "Detached DSP from session=$sessionId")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceDestroyed = true
        sessionHandler.removeCallbacks(sessionRefresh)
        try {
            if (playbackCallbackRegistered) {
                getSystemService(AudioManager::class.java)?.unregisterAudioPlaybackCallback(playbackCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback callback unregister failed", e)
        }
        super.onDestroy()
        Log.d(TAG, "SJBStudio DSP Service onDestroy")
        dynamicsManagers.values.forEach { it.release() }
        dynamicsManagers.clear()
    }
}
