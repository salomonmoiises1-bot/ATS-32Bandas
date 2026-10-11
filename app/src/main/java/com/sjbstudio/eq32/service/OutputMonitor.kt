package com.sjbstudio.eq32.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.util.Log

/** Kind of audio output the sound is leaving through. [key] is stable and used to store a profile per output. */
enum class OutputKind(val key: String, val label: String) {
    BLUETOOTH("bluetooth", "Bluetooth"),
    WIRED("cable", "Auriculares con cable"),
    USB("usb", "USB"),
    SPEAKER("parlante", "Parlante del teléfono"),
    OTHER("otro", "Otra salida")
}

data class OutputInfo(val kind: OutputKind, val name: String?) {
    fun displayText(): String = if (name.isNullOrBlank()) kind.label else "${kind.label} · $name"
}

/**
 * Detects where audio is going (Bluetooth speaker, wired headphones, USB, phone speaker) and reports changes.
 * Uses only AudioManager (device callback + route query): no runtime permission is required.
 */
class OutputMonitor(
    context: Context,
    private val handler: Handler,
    private val onChange: (OutputInfo) -> Unit
) {
    companion object {
        private const val TAG = "OutputMonitor"
        // Newer AudioDeviceInfo types, hard-coded so this compiles and runs on every API level.
        private const val TYPE_HEARING_AID = 23
        private const val TYPE_BUILTIN_SPEAKER_SAFE = 24
        private const val TYPE_BLE_HEADSET = 26
        private const val TYPE_BLE_SPEAKER = 27
        private const val TYPE_BLE_BROADCAST = 30
        private const val SETTLE_MS = 700L
    }

    private val audioManager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
    private var registered = false
    private val recentlyAdded = ArrayList<Int>()

    @Volatile
    var current: OutputInfo? = null
        private set

    private val settle = Runnable { evaluate() }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            for (d in addedDevices) {
                if (d.isSink && classify(d) != null) {
                    recentlyAdded.remove(d.id)
                    recentlyAdded.add(d.id)
                }
            }
            evaluate()
            // The audio policy may need a moment to move the route: look once more shortly after.
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, SETTLE_MS)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (d in removedDevices) recentlyAdded.remove(d.id)
            evaluate()
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, SETTLE_MS)
        }
    }

    fun start() {
        if (registered) return
        try {
            audioManager?.registerAudioDeviceCallback(callback, handler)
            registered = audioManager != null
        } catch (e: Exception) {
            Log.w(TAG, "Audio device callback unavailable", e)
        }
        evaluate()
    }

    fun stop() {
        handler.removeCallbacks(settle)
        if (!registered) return
        try {
            audioManager?.unregisterAudioDeviceCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Audio device callback unregister failed", e)
        }
        registered = false
    }

    private fun evaluate() {
        val info = resolve()
        if (info != current) {
            current = info
            onChange(info)
        }
    }

    private fun resolve(): OutputInfo {
        val am = audioManager ?: return OutputInfo(OutputKind.OTHER, null)
        // Android 13+: ask the audio policy which device music would really use right now.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { d ->
                    classify(d)?.let { return OutputInfo(it, cleanName(d, it)) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Route query unavailable; using connected-device heuristic", e)
            }
        }
        val outputs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { classify(it) != null }
        } catch (_: Exception) {
            emptyList()
        }
        if (outputs.isEmpty()) return OutputInfo(OutputKind.OTHER, null)
        // The most recently connected external output usually wins the route.
        val byId = outputs.associateBy { it.id }
        for (id in recentlyAdded.asReversed()) {
            val d = byId[id] ?: continue
            val kind = classify(d) ?: continue
            if (kind != OutputKind.SPEAKER) return OutputInfo(kind, cleanName(d, kind))
        }
        val best = outputs.minByOrNull { priority(classify(it)!!) }!!
        val kind = classify(best)!!
        return OutputInfo(kind, cleanName(best, kind))
    }

    private fun priority(kind: OutputKind): Int = when (kind) {
        OutputKind.BLUETOOTH -> 0
        OutputKind.USB -> 1
        OutputKind.WIRED -> 2
        OutputKind.SPEAKER -> 3
        OutputKind.OTHER -> 4
    }

    private fun classify(d: AudioDeviceInfo): OutputKind? = when (d.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST,
        TYPE_HEARING_AID -> OutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_SPEAKER_SAFE -> OutputKind.SPEAKER
        else -> null
    }

    /** Product name without junk (the phone's own model name, generic placeholders). */
    private fun cleanName(d: AudioDeviceInfo, kind: OutputKind): String? {
        if (kind == OutputKind.SPEAKER) return null
        val s = d.productName?.toString()?.trim()
        if (s.isNullOrEmpty() || s == Build.MODEL || s == "boot_headset" || s == "h2w") return null
        return s
    }
}
