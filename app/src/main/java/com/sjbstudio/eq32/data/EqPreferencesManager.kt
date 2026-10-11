package com.sjbstudio.eq32.data

import android.content.Context
import android.content.SharedPreferences
import com.sjbstudio.eq32.state.EqState32WithMDRC
import org.json.JSONArray
import org.json.JSONObject

class EqPreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "sjb_settings"
        private const val KEY_ENABLED = "master_enabled"
        private const val KEY_FIXED_GAINS = "fixed_gains"
        private const val KEY_TONE_GAINS = "tone_gains"
        private const val KEY_MDRC_ENABLED = "mdrc_enabled"
        private const val KEY_MDRC_THRESHOLD = "mdrc_threshold"
        private const val KEY_MDRC_RATIO = "mdrc_ratio"
        private const val KEY_MDRC_ATTACK = "mdrc_attack"
        private const val KEY_MDRC_RELEASE = "mdrc_release"
        private const val KEY_MDRC_MAKEUP = "mdrc_makeup"
        private const val KEY_BASS_BOOST = "bass_boost_db"
        private const val KEY_BASS_BOOST_HZ = "bass_boost_hz"
        private const val KEY_PREAMP = "preamp_db"
        private const val KEY_SMOOTH = "smooth_curve"
        private const val KEY_DYN_BASS = "dynamic_bass"
        private const val KEY_LIM_THR = "limiter_threshold_db"
        private const val KEY_LIM_REL = "limiter_release_ms"
        private const val KEY_AUTO_HEAD = "auto_headroom"
        private const val KEY_BASS_DETAIL = "bass_detail"
        private const val KEY_HIGH_PRECISION = "high_precision"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveCurrentState(state: EqState32WithMDRC) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, state.isEnabled)
            putString(KEY_FIXED_GAINS, floatArrayToJson(state.fixedGains))
            putString(KEY_TONE_GAINS, floatArrayToJson(state.toneGains))
            putBoolean(KEY_MDRC_ENABLED, state.mdrcEnabled)
            putString(KEY_MDRC_THRESHOLD, floatArrayToJson(state.mdrcThreshold))
            putString(KEY_MDRC_RATIO, floatArrayToJson(state.mdrcRatio))
            putString(KEY_MDRC_ATTACK, floatArrayToJson(state.mdrcAttack))
            putString(KEY_MDRC_RELEASE, floatArrayToJson(state.mdrcRelease))
            putString(KEY_MDRC_MAKEUP, floatArrayToJson(state.mdrcMakeup))
            putFloat(KEY_BASS_BOOST, state.bassBoostDb)
            putFloat(KEY_BASS_BOOST_HZ, state.bassBoostHz)
            putFloat(KEY_PREAMP, state.preampDb)
            putBoolean(KEY_SMOOTH, state.smoothCurve)
            putBoolean(KEY_DYN_BASS, state.dynamicBass)
            putFloat(KEY_LIM_THR, state.limiterThresholdDb)
            putFloat(KEY_LIM_REL, state.limiterReleaseMs)
            putBoolean(KEY_AUTO_HEAD, state.autoHeadroom)
            putBoolean(KEY_BASS_DETAIL, state.bassDetail)
            putBoolean(KEY_HIGH_PRECISION, state.highPrecision)
            apply()
        }
    }

    fun loadCurrentState(): EqState32WithMDRC {
        val isEnabled = prefs.getBoolean(KEY_ENABLED, true)
        val fixed = jsonToFloatArray(prefs.getString(KEY_FIXED_GAINS, null), 32, 0.0f).mapValuesSafe(-12f, 12f, 0f)
        val tone = jsonToFloatArray(prefs.getString(KEY_TONE_GAINS, null), 3, 0.0f).mapValuesSafe(-12f, 12f, 0f)
        val mdrcEnabled = prefs.getBoolean(KEY_MDRC_ENABLED, false)
        val mdrcThreshold = jsonToFloatArray(prefs.getString(KEY_MDRC_THRESHOLD, null), 4, -20.0f).mapValuesSafe(-40f, 0f, -20f)
        val mdrcRatio = jsonToFloatArray(prefs.getString(KEY_MDRC_RATIO, null), 4, 2.0f).mapValuesSafe(1f, 20f, 2f)
        val mdrcAttack = jsonToFloatArray(prefs.getString(KEY_MDRC_ATTACK, null), 4, 20.0f).mapValuesSafe(1f, 100f, 20f)
        val mdrcRelease = jsonToFloatArray(prefs.getString(KEY_MDRC_RELEASE, null), 4, 200.0f).mapValuesSafe(10f, 500f, 200f)
        val mdrcMakeup = jsonToFloatArray(prefs.getString(KEY_MDRC_MAKEUP, null), 4, 0.0f).mapValuesSafe(0f, 18f, 0f)

        val bassBoost = prefs.getFloat(KEY_BASS_BOOST, 0f).let { if (it.isFinite()) it.coerceIn(0f, EqState32WithMDRC.BASS_BOOST_MAX_DB) else 0f }

        val bassBoostHz = prefs.getFloat(KEY_BASS_BOOST_HZ, EqState32WithMDRC.BASS_BOOST_HZ.toFloat())
            .let { if (it.isFinite()) it.coerceIn(EqState32WithMDRC.BASS_BOOST_MIN_HZ, EqState32WithMDRC.BASS_BOOST_MAX_HZ) else EqState32WithMDRC.BASS_BOOST_HZ.toFloat() }
        val preamp = prefs.getFloat(KEY_PREAMP, 0f)
            .let { if (it.isFinite()) it.coerceIn(EqState32WithMDRC.PREAMP_MIN_DB, EqState32WithMDRC.PREAMP_MAX_DB) else 0f }

        return EqState32WithMDRC(
            fixedGains = fixed,
            toneGains = tone,
            mdrcEnabled = mdrcEnabled,
            mdrcThreshold = mdrcThreshold,
            mdrcRatio = mdrcRatio,
            mdrcAttack = mdrcAttack,
            mdrcRelease = mdrcRelease,
            mdrcMakeup = mdrcMakeup,
            isEnabled = isEnabled,
            bassBoostDb = bassBoost,
            bassBoostHz = bassBoostHz,
            preampDb = preamp,
            smoothCurve = prefs.getBoolean(KEY_SMOOTH, false),
            dynamicBass = prefs.getBoolean(KEY_DYN_BASS, false),
            limiterThresholdDb = prefs.getFloat(KEY_LIM_THR, -0.5f)
                .let { if (it.isFinite()) it.coerceIn(EqState32WithMDRC.LIMITER_MIN_DB, EqState32WithMDRC.LIMITER_MAX_DB) else -0.5f },
            limiterReleaseMs = prefs.getFloat(KEY_LIM_REL, 50f)
                .let { if (it.isFinite()) it.coerceIn(EqState32WithMDRC.LIMITER_RELEASE_MIN_MS, EqState32WithMDRC.LIMITER_RELEASE_MAX_MS) else 50f },
            autoHeadroom = prefs.getBoolean(KEY_AUTO_HEAD, false),
            bassDetail = prefs.getBoolean(KEY_BASS_DETAIL, false),
            highPrecision = prefs.getBoolean(KEY_HIGH_PRECISION, true)
        )
    }

    private fun floatArrayToJson(arr: FloatArray): String {
        val json = JSONArray()
        for (f in arr) json.put(f.toDouble())
        return json.toString()
    }

    private fun FloatArray.mapValuesSafe(min: Float, max: Float, fallback: Float): FloatArray = FloatArray(size) { index ->
        val value = this[index]
        if (value.isFinite()) value.coerceIn(min, max) else fallback.coerceIn(min, max)
    }

    private fun jsonToFloatArray(jsonStr: String?, expectedSize: Int, defaultVal: Float): FloatArray {
        val result = FloatArray(expectedSize) { defaultVal }
        if (jsonStr.isNullOrEmpty()) return result
        try {
            val json = JSONArray(jsonStr)
            for (i in 0 until minOf(json.length(), expectedSize)) {
                result[i] = json.getDouble(i).toFloat()
            }
        } catch (_: Exception) {}
        return result
    }
}
