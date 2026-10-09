package com.sjbstudio.eq32.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.sjbstudio.eq32.service.EqService

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        if (Intent.ACTION_BOOT_COMPLETED == intent?.action ||
            "android.intent.action.QUICKBOOT_POWERON" == intent?.action) {
            Log.d(TAG, "Boot completed event received. Restarting SJBStudio DSP engine.")

            try {
                val serviceIntent = Intent(context, EqService::class.java).apply {
                    action = EqService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "Foreground service launch request dispatched.")
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException is thrown on Android 12+ if system restricts background start
                Log.e(TAG, "Caught ForegroundServiceStartNotAllowedException or launch restriction: ${e.message}")
            }
        }
    }
}
