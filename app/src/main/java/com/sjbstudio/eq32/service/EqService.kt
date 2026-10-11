package com.sjbstudio.eq32.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sjbstudio.eq32.MainActivity
import com.sjbstudio.eq32.R
import com.sjbstudio.eq32.core.DynamicsProcessingManager
import com.sjbstudio.eq32.data.EqPreferencesManager
import com.sjbstudio.eq32.data.PresetStore
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
        // Route B: request Android's global output-mix audio session first. This can affect
        // all system audio and is only effective on Android builds that permit session 0.
        // If the system rejects it, the service falls back at runtime to per-app sessions.
        private const val TRY_GLOBAL_OUTPUT_SESSION = true

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

    @Volatile private var useGlobal = TRY_GLOBAL_OUTPUT_SESSION
    private lateinit var presetStore: PresetStore
    private var outputMonitor: OutputMonitor? = null
    private var lastOutputKind: OutputKind? = null
    /** Where the sound is going right now (Bluetooth speaker, headphones, ...); null until detected. */
    @Volatile var currentOutput: OutputInfo? = null
        private set
    /** Called on the main thread when the audio output changes. */
    var outputListener: ((OutputInfo) -> Unit)? = null
    private var globalRetryDelayMs = 5000L
    /** Called on the main thread when state changes from outside the UI (notification, tile). */
    var stateListener: ((EqState32WithMDRC) -> Unit)? = null
    private val binder = LocalBinder()
    private val dynamicsManagers = mutableMapOf<Int, DynamicsProcessingManager>()
    private lateinit var prefsManager: EqPreferencesManager
    private var currentState = EqState32WithMDRC()
    // Global output mode uses key 0 for the output-mix DynamicsProcessing instance.
    // Per-app session maps remain for compatibility with the existing receiver code.
    private val announcedSessions = mutableMapOf<Int, String>()
    private val detectedSessions = mutableMapOf<Int, String>()
    private val sessionHandler = Handler(Looper.getMainLooper())
    private var recoveryThread: HandlerThread? = null
    private var recoveryHandler: Handler? = null
    private var playbackCallbackRegistered = false
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // Event-driven: bursts of callbacks collapse into one cheap check.
            sessionHandler.removeCallbacks(eventRefresh)
            sessionHandler.postDelayed(eventRefresh, 250L)
        }
    }
    private val eventRefresh = Runnable {
        if (serviceDestroyed) return@Runnable
        refreshActiveSession()
        if (!useGlobal) kickSessionRecovery()
        scheduleNextRefresh()
    }
    // Safety net only: global mode needs no polling while attached; otherwise it retries with back-off.
    private val sessionRefresh = Runnable {
        if (!serviceDestroyed) {
            refreshActiveSession()
            scheduleNextRefresh()
        }
    }
    private val sessionRecovery = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return
            try {
                val recovered = AudioPolicyDumpParser.dump(applicationContext)
                if (recovered != null) {
                    val recoveredTargets = mutableMapOf<Int, String>()
                    recovered.forEach { (pkg, ids) ->
                        if (isTargetPackage(pkg)) ids.filter { it > 0 }.forEach { id -> recoveredTargets[id] = pkg }
                    }
                    synchronized(this@EqService) {
                        detectedSessions.clear()
                        detectedSessions.putAll(recoveredTargets)
                    }
                    sessionHandler.post { if (!serviceDestroyed) refreshActiveSession() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio policy session recovery failed; retaining previously detected sessions", e)
            }
            // The dump is expensive: keep repeating it only while something is actually playing.
            // New sessions are caught by the playback callback, which kicks a single dump.
            if (isAnyPlaybackActive()) recoveryHandler?.postDelayed(this, 6000L)
        }
    }
    @Volatile private var serviceDestroyed = false

    inner class LocalBinder : Binder() {
        fun getService(): EqService = this@EqService
    }

    override fun onCreate() {
        super.onCreate()
        prefsManager = EqPreferencesManager(this)
        presetStore = PresetStore(this)
        currentState = prefsManager.loadCurrentState()
        createNotificationChannel()
        if (useGlobal) {
            ensureGlobalOutputEffect()
            if (dynamicsManagers.containsKey(0)) {
                scheduleNextRefresh()
            } else {
                // The system rejected the global output-mix effect: fall back to per-app sessions.
                Log.w(TAG, "Global DSP rejected; falling back to per-app session mode")
                useGlobal = false
                startPerAppMode()
            }
        } else {
            startPerAppMode()
        }
        registerPlaybackCallback()
        outputMonitor = OutputMonitor(this, sessionHandler) { info -> handleOutputChange(info) }.also { it.start() }
    }

    private fun registerPlaybackCallback() {
        if (playbackCallbackRegistered) return
        val audioManager = getSystemService(AudioManager::class.java)
        try {
            audioManager?.registerAudioPlaybackCallback(playbackCallback, sessionHandler)
            playbackCallbackRegistered = audioManager != null
        } catch (e: Exception) {
            Log.w(TAG, "Playback callback registration unavailable", e)
        }
    }

    private fun scheduleNextRefresh() {
        sessionHandler.removeCallbacks(sessionRefresh)
        val delay = when {
            useGlobal && dynamicsManagers.containsKey(0) -> return // attached: events only, no polling
            useGlobal -> globalRetryDelayMs.also { globalRetryDelayMs = (it * 2).coerceAtMost(60_000L) }
            else -> 10_000L
        }
        sessionHandler.postDelayed(sessionRefresh, delay)
    }

    private fun isAnyPlaybackActive(): Boolean = try {
        getSystemService(AudioManager::class.java)?.activePlaybackConfigurations?.isNotEmpty() == true
    } catch (_: Exception) {
        true
    }

    private fun kickSessionRecovery() {
        recoveryHandler?.removeCallbacks(sessionRecovery)
        recoveryHandler?.postDelayed(sessionRecovery, 200L)
    }

    /** A different audio output became active: apply the profile saved for it, if there is one. */
    private fun handleOutputChange(info: OutputInfo) {
        if (serviceDestroyed) return
        currentOutput = info
        val kindChanged = lastOutputKind != info.kind
        lastOutputKind = info.kind
        if (kindChanged) {
            val profile = presetStore.loadOutputProfile(info.kind.key)
            if (profile != null) {
                // A profile holds sound settings only; power and engine options stay as the user set them.
                val merged = profile.copy(
                    isEnabled = currentState.isEnabled,
                    bassDetail = currentState.bassDetail,
                    highPrecision = currentState.highPrecision
                )
                if (merged != currentState) {
                    updateState(merged)
                    stateListener?.invoke(currentState)
                }
            }
        }
        outputListener?.invoke(info)
        updateNotification()
    }

    private fun startPerAppMode() {
        val thread = HandlerThread("ATSSessionRecovery").also { it.start() }
        recoveryThread = thread
        recoveryHandler = Handler(thread.looper)
        recoveryHandler?.post(sessionRecovery)
        scheduleNextRefresh()
        refreshActiveSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        when (intent?.action) {
            ACTION_TOGGLE -> {
                val newState = currentState.copy(isEnabled = !currentState.isEnabled)
                updateState(newState)
                stateListener?.invoke(currentState)
            }
            ACTION_UPDATE_STATE -> {
                currentState = prefsManager.loadCurrentState()
                dynamicsManagers.values.forEach { it.applyState(currentState) }
                updateNotification()
                stateListener?.invoke(currentState)
            }
            ACTION_ATTACH_SESSION, ACTION_DETECTED_ATTACH_SESSION -> {
                if (useGlobal) {
                    ensureGlobalOutputEffect()
                    return START_STICKY
                }
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
                if (useGlobal) {
                    // Playback-session changes must not detach the global output effect.
                    ensureGlobalOutputEffect()
                    return START_STICKY
                }
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

    fun updateState(newState: EqState32WithMDRC, persist: Boolean = true) {
        currentState = newState
        if (persist) prefsManager.saveCurrentState(newState)
        dynamicsManagers.values.forEach { it.applyState(newState) }
        updateNotification()
    }

    fun getCurrentState(): EqState32WithMDRC = currentState

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Motor DSP SJBStudio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ecualizador de 32 bandas y compresor MDRC de 4 bandas, sin root"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, EqService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 2, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val enabled = currentState.isEnabled
        val statusText = currentStatusText()
        val output = currentOutput?.displayText()
        val summary = summaryText()
        val big = buildString {
            append(statusText)
            if (output != null) append("\nSalida: ").append(output)
            if (summary.isNotEmpty()) append("\n").append(summary)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (enabled) "SJBStudio EQ32 · Activo" else "SJBStudio EQ32 · Bypass")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setSmallIcon(R.drawable.ic_tile_eq)
            .setColor(0xFF06B6D4.toInt())
            .setContentIntent(pendingOpen)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (enabled) "Bypass" else "Activar", servicePendingIntent(1, ACTION_TOGGLE))
            .addAction(0, "Abrir", pendingOpen)
            .addAction(0, "Detener", servicePendingIntent(3, ACTION_STOP))
        if (output != null) builder.setSubText(output)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** One line with the settings that matter, shown when the notification is expanded. */
    private fun summaryText(): String {
        val s = currentState
        val parts = ArrayList<String>()
        if (s.preampDb != 0f) parts += "Preamp %+.1f dB".format(s.preampDb)
        if (s.bassBoostDb > 0f) parts += "Refuerzo +%.1f dB a %d Hz".format(s.bassBoostDb, Math.round(s.bassBoostHz))
        if (s.mdrcEnabled) parts += "MDRC"
        if (s.dynamicBass) parts += "Graves dinámicos"
        if (s.smoothCurve) parts += "Curva suave"
        parts += "Límite %.1f dB".format(s.limiterThresholdDb)
        return parts.joinToString(" · ")
    }

    private fun currentStatusText(): String = when {
        !currentState.isEnabled -> "DSP en bypass"
        useGlobal && dynamicsManagers.containsKey(0) -> "DSP global conectado · salida del sistema"
        useGlobal -> "Intentando conectar DSP global"
        dynamicsManagers.isNotEmpty() -> "DSP activo · sesión de app objetivo conectada"
        else -> "Esperando sesión de YouTube, Spotify o AIMP"
    }

    private var lastNotificationKey: String? = null

    private fun updateNotification() {
        // Only re-post when what the user sees changed.
        val key = "${currentState.isEnabled}|${currentStatusText()}|${currentOutput?.displayText()}|${summaryText()}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    @Synchronized
    private fun refreshActiveSession() {
        if (useGlobal) {
            ensureGlobalOutputEffect()
            updateNotification()
            return
        }
        val audioManager = getSystemService(AudioManager::class.java)
        val discovered = mutableMapOf<Int, String>()

        // Real session-ID recovery runs on a dedicated HandlerThread, not the main thread.
        // Its results are merged into detectedSessions and then reconciled here.

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

    /**
     * Route B: request DynamicsProcessing on session 0 (global output mix).
     * Android/OEM audio policy may reject this for ordinary apps. In that case the
     * failure is logged explicitly; we do not pretend the EQ is active.
     */
    @Synchronized
    private fun ensureGlobalOutputEffect() {
        if (!useGlobal || serviceDestroyed) return
        val existing = dynamicsManagers[0]
        if (existing != null) {
            if (existing.isAlive()) {
                existing.applyState(currentState)
                return
            }
            // The system dropped or took over the effect (e.g. audio server restart): rebuild it.
            Log.w(TAG, "Global DSP lost; re-attaching")
            dynamicsManagers.remove(0)
            existing.release()
        }
        val manager = DynamicsProcessingManager()
        if (manager.attachToSession(0, currentState)) {
            globalRetryDelayMs = 5000L
            dynamicsManagers[0] = manager
            Log.i(TAG, "GLOBAL_OUTPUT_DSP_ATTACHED session=0 enabled=${currentState.isEnabled}")
        } else {
            manager.release()
            Log.e(TAG, "GLOBAL_OUTPUT_DSP_FAILED session=0; Android audio policy rejected or does not support global DynamicsProcessing")
        }
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
        stateListener = null
        outputListener = null
        outputMonitor?.stop()
        outputMonitor = null
        sessionHandler.removeCallbacks(sessionRefresh)
        sessionHandler.removeCallbacks(eventRefresh)
        recoveryHandler?.removeCallbacks(sessionRecovery)
        recoveryThread?.quitSafely()
        recoveryHandler = null
        recoveryThread = null
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
