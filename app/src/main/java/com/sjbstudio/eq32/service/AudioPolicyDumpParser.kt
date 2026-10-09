package com.sjbstudio.eq32.service

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.util.regex.Pattern

/**
 * Best-effort recovery of real AudioPlaybackConfiguration session IDs.
 * This mirrors Equalizer314's audioserver-dump strategy. Android/OEM builds may deny it;
 * an empty result is not treated as a valid session and never triggers global processing.
 */
internal object AudioPolicyDumpParser {
    private const val TAG = "AudioPolicyDumpParser"
    private val waveletLine = Pattern.compile("Session\\sID:\\s(\\d+);?\\sUID:?\\s(\\d+)")
    private val uidPid = Pattern.compile("u/pid:(\\d+)/(\\d+)")
    private val sessionPattern = Pattern.compile("(?i)session\\s?id:\\s*(\\d+)")
    @Volatile private var cachedBinder: IBinder? = null

    fun dump(context: Context, timeoutMs: Long = 1200L): Map<String, Set<Int>>? = try {
        dumpInternal(context, timeoutMs)
    } catch (t: Throwable) {
        // null means the dump failed; an empty map means a successful dump had no recognized
        // target sessions. Keeping those states distinct prevents transient Binder/API failures
        // from detaching a DSP session while the user's music is still playing.
        Log.w(TAG, "Could not recover audio session IDs; retaining previously detected sessions", t)
        null
    }

    private fun dumpInternal(context: Context, timeoutMs: Long): Map<String, Set<Int>>? {
        val binder = obtainAudioBinder() ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        try {
            val method = binder.javaClass.getMethod("dumpAsync", java.io.FileDescriptor::class.java, Array<String>::class.java)
            method.invoke(binder, writeFd.fileDescriptor, emptyArray<String>())
        } finally {
            try { writeFd.close() } catch (_: Throwable) {}
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val uidSessions = mutableMapOf<Int, MutableSet<Int>>()
        try {
            BufferedReader(FileReader(readFd.fileDescriptor)).use { reader ->
                while (System.currentTimeMillis() <= deadline) {
                    val line = reader.readLine() ?: break
                    if (parsePoweramp(line, uidSessions)) continue
                    parseWavelet(line, uidSessions)
                }
            }
        } finally {
            try { readFd.close() } catch (_: Throwable) {}
        }
        val out = mutableMapOf<String, MutableSet<Int>>()
        val pm = context.packageManager
        for ((uid, ids) in uidSessions) {
            if (uid == context.applicationInfo.uid) continue
            // Map every package for a shared UID, so the caller can filter target apps accurately.
            for (pkg in pm.getPackagesForUid(uid).orEmpty()) {
                out.getOrPut(pkg) { mutableSetOf() }.addAll(ids)
            }
        }
        return out
    }

    private fun parsePoweramp(line: String, out: MutableMap<Int, MutableSet<Int>>): Boolean {
        val prefix = line.startsWith("  AudioPlaybackConfiguration ") ||
            line.startsWith("AudioPlaybackConfiguration ") || line.startsWith("  ID:") || line.startsWith("ID:")
        if (!prefix) return false
        if (line.contains("type:android.media.SoundPool") ||
            (!line.contains("USAGE_MEDIA") && !line.contains("USAGE_UNKNOWN"))) return true
        val uidMatch = uidPid.matcher(line)
        if (!uidMatch.find()) return true
        val uid = uidMatch.group(1)?.toIntOrNull() ?: return true
        val sidMatch = sessionPattern.matcher(line)
        if (!sidMatch.find()) return true
        val sid = sidMatch.group(1)?.toIntOrNull() ?: return true
        if (sid > 0) out.getOrPut(uid) { mutableSetOf() }.add(sid)
        return true
    }

    private fun parseWavelet(line: String, out: MutableMap<Int, MutableSet<Int>>): Boolean {
        val match = waveletLine.matcher(line)
        if (!match.find()) return false
        val sid = match.group(1)?.toIntOrNull() ?: return false
        val uid = match.group(2)?.toIntOrNull() ?: return false
        if (sid > 0) out.getOrPut(uid) { mutableSetOf() }.add(sid)
        return true
    }

    private fun obtainAudioBinder(): IBinder? {
        cachedBinder?.takeIf { it.isBinderAlive }?.let { return it }
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "audio") as? IBinder ?: return null
        cachedBinder = binder
        return binder
    }
}
