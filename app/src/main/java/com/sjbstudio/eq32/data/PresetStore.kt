package com.sjbstudio.eq32.data

import android.content.Context
import com.sjbstudio.eq32.state.EqState32WithMDRC
import org.json.JSONArray
import org.json.JSONObject

/**
 * User presets, stored as a JSON array in SharedPreferences (insertion order kept).
 * A preset holds every sound setting; the master on/off switch is NOT part of a preset.
 */
class PresetStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "sjb_user_presets"
        private const val KEY_PRESETS = "presets"
        const val MAX_PRESETS = 100
        const val MAX_NAME_LENGTH = 40
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun names(): List<String> = read().map { it.first }

    fun exists(name: String): Boolean = read().any { it.first.equals(name.trim(), ignoreCase = true) }

    fun load(name: String): EqState32WithMDRC? =
        read().firstOrNull { it.first.equals(name, ignoreCase = true) }?.second?.let(::fromJson)

    /** Saves or overwrites (case-insensitive). Returns false when the name is invalid or the list is full. */
    fun save(rawName: String, state: EqState32WithMDRC): Boolean {
        val name = rawName.trim().take(MAX_NAME_LENGTH)
        if (name.isEmpty()) return false
        val list = read().toMutableList()
        val index = list.indexOfFirst { it.first.equals(name, ignoreCase = true) }
        val json = toJson(state)
        if (index >= 0) list[index] = name to json
        else {
            if (list.size >= MAX_PRESETS) return false
            list.add(name to json)
        }
        write(list)
        return true
    }

    fun delete(name: String) {
        write(read().filterNot { it.first.equals(name, ignoreCase = true) })
    }

    private fun read(): List<Pair<String, JSONObject>> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                val data = o.optJSONObject("data")
                if (name.isEmpty() || data == null) null else name to data
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(list: List<Pair<String, JSONObject>>) {
        val arr = JSONArray()
        list.forEach { (name, data) -> arr.put(JSONObject().put("name", name).put("data", data)) }
        prefs.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    private fun toJson(s: EqState32WithMDRC): JSONObject = JSONObject().apply {
        put("fixed", arr(s.fixedGains)); put("tone", arr(s.toneGains))
        put("boostDb", s.bassBoostDb.toDouble()); put("boostHz", s.bassBoostHz.toDouble())
        put("preamp", s.preampDb.toDouble())
        put("mdrcOn", s.mdrcEnabled)
        put("thr", arr(s.mdrcThreshold)); put("ratio", arr(s.mdrcRatio))
        put("atk", arr(s.mdrcAttack)); put("rel", arr(s.mdrcRelease)); put("mk", arr(s.mdrcMakeup))
    }

    private fun fromJson(o: JSONObject): EqState32WithMDRC = EqState32WithMDRC(
        fixedGains = floats(o, "fixed", 32, 0f, -12f, 12f),
        toneGains = floats(o, "tone", 3, 0f, -12f, 12f),
        bassBoostDb = scalar(o, "boostDb", 0f, 0f, EqState32WithMDRC.BASS_BOOST_MAX_DB),
        bassBoostHz = scalar(o, "boostHz", EqState32WithMDRC.BASS_BOOST_HZ.toFloat(),
            EqState32WithMDRC.BASS_BOOST_MIN_HZ, EqState32WithMDRC.BASS_BOOST_MAX_HZ),
        preampDb = scalar(o, "preamp", 0f, EqState32WithMDRC.PREAMP_MIN_DB, EqState32WithMDRC.PREAMP_MAX_DB),
        mdrcEnabled = o.optBoolean("mdrcOn", false),
        mdrcThreshold = floats(o, "thr", 4, -20f, -40f, 0f),
        mdrcRatio = floats(o, "ratio", 4, 2f, 1f, 20f),
        mdrcAttack = floats(o, "atk", 4, 20f, 1f, 100f),
        mdrcRelease = floats(o, "rel", 4, 200f, 10f, 500f),
        mdrcMakeup = floats(o, "mk", 4, 0f, 0f, 18f)
    )

    private fun arr(a: FloatArray) = JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }

    private fun scalar(o: JSONObject, key: String, def: Float, min: Float, max: Float): Float {
        val v = o.optDouble(key, def.toDouble()).toFloat()
        return if (v.isFinite()) v.coerceIn(min, max) else def
    }

    private fun floats(o: JSONObject, key: String, size: Int, def: Float, min: Float, max: Float): FloatArray {
        val j = o.optJSONArray(key)
        return FloatArray(size) { i ->
            val v = j?.optDouble(i, def.toDouble())?.toFloat() ?: def
            if (v.isFinite()) v.coerceIn(min, max) else def
        }
    }
}
