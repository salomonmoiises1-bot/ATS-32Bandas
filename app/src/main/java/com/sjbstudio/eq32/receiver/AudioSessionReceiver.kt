package com.sjbstudio.eq32.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.audiofx.AudioEffect
import android.util.Log
import com.sjbstudio.eq32.service.EqService

class AudioSessionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AudioSessionReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        val audioSessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Unknown"

        Log.d(TAG, "Received AudioSession intent: $action for session $audioSessionId from $packageName")

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val serviceIntent = Intent(context, EqService::class.java).apply {
                    this.action = EqService.ACTION_ATTACH_SESSION
                    putExtra(EqService.EXTRA_AUDIO_SESSION, audioSessionId)
                    putExtra(EqService.EXTRA_PACKAGE_NAME, packageName)
                }
                startServiceSafely(context, serviceIntent)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                val serviceIntent = Intent(context, EqService::class.java).apply {
                    this.action = EqService.ACTION_DETACH_SESSION
                    putExtra(EqService.EXTRA_AUDIO_SESSION, audioSessionId)
                    putExtra(EqService.EXTRA_PACKAGE_NAME, packageName)
                }
                startServiceSafely(context, serviceIntent)
            }
        }
    }
    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            // Android can reject background starts. The foreground service remains
            // started from the app/notification path; don't crash the broadcast.
            Log.w(TAG, "Android rejected background DSP service start", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to start DSP service", e)
        }
    }

}
