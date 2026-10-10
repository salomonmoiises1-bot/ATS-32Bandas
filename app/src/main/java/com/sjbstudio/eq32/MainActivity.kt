package com.sjbstudio.eq32

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.InputFilter
import android.widget.EditText
import android.widget.FrameLayout
import com.sjbstudio.eq32.data.EqPreferencesManager
import com.sjbstudio.eq32.data.PresetStore
import com.sjbstudio.eq32.databinding.ActivityMainBinding
import com.sjbstudio.eq32.service.EqService
import com.sjbstudio.eq32.state.EqState32WithMDRC
import com.sjbstudio.eq32.ui.EqSlidersAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: EqPreferencesManager
    private lateinit var slidersAdapter: EqSlidersAdapter
    private lateinit var presetStore: PresetStore

    private var eqService: EqService? = null
    private var isBound: Boolean = false
    private var currentState = EqState32WithMDRC()

    // Choreographer 16ms frame-rate limiter to debounce high-frequency touch updates
    private var isFrameScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    // True only while a debounced save is outstanding, so onPause never overwrites
    // a newer state written by the notification or the tile with a stale one.
    private var persistPending = false
    private val persistStateRunnable = Runnable {
        persistPending = false
        prefsManager.saveCurrentState(currentState)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Re-post the foreground notification now that it may be visible.
            if (granted) EqService.startService(this)
        }
    private val frameCallback = Choreographer.FrameCallback {
        isFrameScheduled = false
        dispatchRealtimeStateUpdate()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? EqService.LocalBinder
            eqService = localBinder?.getService()
            isBound = true

            // Sync with service state
            eqService?.getCurrentState()?.let { serviceState ->
                currentState = serviceState.deepCopy()
                updateUiFromState(currentState)
            }
            // Follow changes made from the notification action or the quick-settings tile.
            eqService?.stateListener = { external ->
                currentState = external.deepCopy()
                updateUiFromState(currentState)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = EqPreferencesManager(this)
        presetStore = PresetStore(this)
        currentState = prefsManager.loadCurrentState()

        // Android 13+: the foreground notification (and its Bypass action) needs this runtime permission.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Start & bind DSP service
        EqService.startService(this)
        bindService(Intent(this, EqService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        setupTopToolbar()
        setupKnobs()
        setupPreampAndBoostFreq()
        setupEqGraph()
        setupSlidersRecyclerView()
        setupMdrcSection()

        updateUiFromState(currentState)
    }

    private fun setupTopToolbar() {
        // Master Bypass / Power Switch
        binding.switchMasterPower.isChecked = currentState.isEnabled
        binding.switchMasterPower.setOnCheckedChangeListener { _, isChecked ->
            currentState = currentState.copy(isEnabled = isChecked)
            scheduleStateDispatch()
        }

        // Preset selector button
        binding.btnPresets.setOnClickListener {
            showPresetsDialog()
        }

        // Reset to Flat button
        binding.btnResetFlat.setOnClickListener {
            resetAllToFlat()
        }
    }

    private fun setupKnobs() {
        // Bass Knob (100 Hz LowShelf)
        binding.knobBass.setValue(currentState.toneGains[0])
        binding.knobBass.onValueChangedListener = { gain ->
            currentState.toneGains[0] = gain
            scheduleStateDispatch()
        }

        // Mid Knob (1000 Hz Peaking)
        binding.knobMid.setValue(currentState.toneGains[1])
        binding.knobMid.onValueChangedListener = { gain ->
            currentState.toneGains[1] = gain
            scheduleStateDispatch()
        }

        // Bass Boost Knob (60 Hz LowShelf, 0..12 dB) - same biquad path as the tone knobs
        binding.knobBoost.minValue = 0f
        binding.knobBoost.maxValue = EqState32WithMDRC.BASS_BOOST_MAX_DB
        binding.knobBoost.setValue(currentState.bassBoostDb)
        binding.knobBoost.onValueChangedListener = { gain ->
            currentState = currentState.copy(bassBoostDb = gain)
            scheduleStateDispatch()
        }

        // Treble Knob (8000 Hz HighShelf)
        binding.knobTreble.setValue(currentState.toneGains[2])
        binding.knobTreble.onValueChangedListener = { gain ->
            currentState.toneGains[2] = gain
            scheduleStateDispatch()
        }
    }

    private fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        val clamped = value.coerceIn(from, to)
        return (from + Math.round((clamped - from) / step) * step).coerceIn(from, to)
    }

    private fun setPreampUi(db: Float) {
        binding.sliderPreamp.value = snap(db, EqState32WithMDRC.PREAMP_MIN_DB, EqState32WithMDRC.PREAMP_MAX_DB, 0.5f)
        binding.tvPreampVal.text = "%+.1f dB".format(db)
    }

    private fun setBoostFreqUi(hz: Float) {
        binding.sliderBoostFreq.value = snap(hz, EqState32WithMDRC.BASS_BOOST_MIN_HZ, EqState32WithMDRC.BASS_BOOST_MAX_HZ, 5f)
        binding.tvBoostFreqVal.text = "%d Hz".format(Math.round(hz))
    }

    private fun setupPreampAndBoostFreq() {
        setPreampUi(currentState.preampDb)
        binding.sliderPreamp.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentState = currentState.copy(preampDb = value)
                binding.tvPreampVal.text = "%+.1f dB".format(value)
                scheduleStateDispatch()
            }
        }
        setBoostFreqUi(currentState.bassBoostHz)
        binding.sliderBoostFreq.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentState = currentState.copy(bassBoostHz = value)
                binding.tvBoostFreqVal.text = "%d Hz".format(Math.round(value))
                scheduleStateDispatch()
            }
        }
    }

    private fun setupEqGraph() {
        binding.eqGraphView.updateCurve(currentState)
    }

    private fun setupSlidersRecyclerView() {
        slidersAdapter = EqSlidersAdapter(
            frequencies = EqState32WithMDRC.FREQS,
            gains = currentState.fixedGains,
            onGainChanged = { index, gain ->
                currentState.fixedGains[index] = gain
                scheduleStateDispatch()
            }
        )

        binding.rvEqSliders.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = slidersAdapter
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
            itemAnimator = null // eliminate jank on rapid slide
        }
    }

    private fun setupMdrcSection() {
        binding.mdrcView.setState(currentState)
        binding.mdrcView.onMdrcChangedListener = { updatedState ->
            currentState = updatedState
            scheduleStateDispatch()
        }
    }

    private fun scheduleStateDispatch() {
        binding.eqGraphView.updateCurve(currentState)
        if (!isFrameScheduled) {
            isFrameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    // During a drag, update the audio engine at display-frame cadence but do
    // not write SharedPreferences 60 times per second. Persistence is debounced.
    private fun dispatchRealtimeStateUpdate() {
        eqService?.updateState(currentState, persist = false)
        mainHandler.removeCallbacks(persistStateRunnable)
        persistPending = true
        mainHandler.postDelayed(persistStateRunnable, 250L)
    }

    // Used for explicit actions (preset, reset) where an immediate save is desired.
    private fun dispatchStateUpdate() {
        mainHandler.removeCallbacks(persistStateRunnable)
        persistPending = false
        prefsManager.saveCurrentState(currentState)
        eqService?.updateState(currentState, persist = false)
    }

    private fun updateUiFromState(state: EqState32WithMDRC) {
        binding.switchMasterPower.isChecked = state.isEnabled
        binding.knobBass.setValue(state.toneGains[0])
        binding.knobMid.setValue(state.toneGains[1])
        binding.knobTreble.setValue(state.toneGains[2])
        binding.knobBoost.setValue(state.bassBoostDb)
        setPreampUi(state.preampDb)
        setBoostFreqUi(state.bassBoostHz)
        binding.eqGraphView.updateCurve(state)
        slidersAdapter.updateGains(state.fixedGains)
        binding.mdrcView.setState(state)
    }

    private fun resetAllToFlat() {
        currentState = EqState32WithMDRC()
        updateUiFromState(currentState)
        dispatchStateUpdate()
        Toast.makeText(this, "Reset all bands to flat reference (0 dB)", Toast.LENGTH_SHORT).show()
    }

    private class MdrcPreset(
        val threshold: FloatArray, val ratio: FloatArray, val attack: FloatArray,
        val release: FloatArray, val makeup: FloatArray
    )

    /** Piecewise-linear curve over log-frequency, sampled at the 32 band centres, rounded to 0.1 dB. */
    private fun curve(vararg pts: Pair<Double, Float>): FloatArray = FloatArray(32) { i ->
        val f = EqState32WithMDRC.FREQS[i]
        val v = when {
            f <= pts.first().first -> pts.first().second
            f >= pts.last().first -> pts.last().second
            else -> {
                var j = 0
                while (pts[j + 1].first < f) j++
                val (f0, g0) = pts[j]
                val (f1, g1) = pts[j + 1]
                val t = (Math.log(f / f0) / Math.log(f1 / f0)).toFloat()
                g0 + (g1 - g0) * t
            }
        }
        Math.round(v * 10f) / 10f
    }

    /** Applies a complete sound preset (EQ, tone, bass boost, preamp, MDRC). Master power is untouched. */
    private fun applyFullPreset(
        gains: FloatArray, tone: FloatArray, boostDb: Float, boostHz: Float, preampDb: Float, mdrc: MdrcPreset?
    ) {
        val base = EqState32WithMDRC()
        currentState = currentState.copy(
            fixedGains = gains,
            toneGains = tone,
            bassBoostDb = boostDb,
            bassBoostHz = boostHz,
            preampDb = preampDb,
            mdrcEnabled = mdrc != null,
            mdrcThreshold = mdrc?.threshold ?: base.mdrcThreshold,
            mdrcRatio = mdrc?.ratio ?: base.mdrcRatio,
            mdrcAttack = mdrc?.attack ?: base.mdrcAttack,
            mdrcRelease = mdrc?.release ?: base.mdrcRelease,
            mdrcMakeup = mdrc?.makeup ?: base.mdrcMakeup
        )
        updateUiFromState(currentState)
        dispatchStateUpdate()
    }

    private fun builtInPresets(): List<Pair<String, () -> Unit>> = listOf(
        "Pure Flat Reference" to { resetAllToFlat() },
        "Harman Target Over-Ear" to { applyHarmanTarget() },
        "Sub-Bass Thunder & Punch" to { applySubBass() },
        "Crystal Vocal & Intelligibility" to { applyCrystalVocal() },
        "V-Shape Energy" to { applyVShape() },
        "Acoustic Warmth & Air" to { applyAcoustic() },

        "Graves suaves" to {
            applyFullPreset(
                curve(20.0 to 3f, 60.0 to 3.5f, 120.0 to 2f, 250.0 to 0f, 16000.0 to 0f),
                floatArrayOf(1.5f, 0f, 0f), 3f, 60f, -3f, null
            )
        },
        "Loudness (volumen bajo)" to {
            applyFullPreset(
                curve(20.0 to 5f, 60.0 to 5f, 120.0 to 3f, 250.0 to 1f, 500.0 to 0f, 1000.0 to 0f,
                    3000.0 to 1.5f, 6000.0 to 3f, 10000.0 to 4.5f, 16000.0 to 4f),
                floatArrayOf(1f, -0.5f, 1.5f), 2f, 70f, -3f,
                MdrcPreset(
                    floatArrayOf(-26f, -24f, -24f, -26f), floatArrayOf(2f, 2f, 2f, 2f),
                    floatArrayOf(20f, 20f, 15f, 10f), floatArrayOf(200f, 180f, 150f, 120f),
                    floatArrayOf(1f, 1f, 1f, 1f)
                )
            )
        },
        "Noche (dinámica reducida)" to {
            applyFullPreset(
                curve(20.0 to 0f, 60.0 to 1f, 250.0 to 0f, 3000.0 to 1f, 10000.0 to 0f, 16000.0 to 0f),
                floatArrayOf(0f, 0f, 0f), 0f, 60f, -2f,
                MdrcPreset(
                    floatArrayOf(-30f, -32f, -32f, -30f), floatArrayOf(4f, 4f, 4f, 3f),
                    floatArrayOf(15f, 10f, 8f, 5f), floatArrayOf(250f, 220f, 200f, 150f),
                    floatArrayOf(4f, 5f, 5f, 4f)
                )
            )
        },
        "Voz / Podcast" to {
            applyFullPreset(
                curve(20.0 to -8f, 60.0 to -6f, 120.0 to -3f, 200.0 to 0f, 500.0 to 1f, 1000.0 to 2.5f,
                    2500.0 to 4f, 4000.0 to 3.5f, 6000.0 to 1.5f, 10000.0 to 0f, 16000.0 to -1f),
                floatArrayOf(-2f, 2f, 0.5f), 0f, 60f, -2f,
                MdrcPreset(
                    floatArrayOf(-28f, -26f, -26f, -28f), floatArrayOf(3f, 3f, 3f, 2f),
                    floatArrayOf(15f, 10f, 8f, 5f), floatArrayOf(200f, 150f, 120f, 100f),
                    floatArrayOf(0f, 3f, 3f, 1f)
                )
            )
        },
        "Rock / Pop" to {
            applyFullPreset(
                curve(20.0 to 2f, 60.0 to 3f, 120.0 to 2f, 250.0 to 0f, 500.0 to -1f, 1000.0 to 0f,
                    2500.0 to 2f, 5000.0 to 3f, 10000.0 to 3f, 16000.0 to 2f),
                floatArrayOf(1.5f, -0.5f, 1.5f), 2f, 60f, -3f,
                MdrcPreset(
                    floatArrayOf(-22f, -20f, -20f, -22f), floatArrayOf(2.5f, 2f, 2f, 2f),
                    floatArrayOf(20f, 20f, 15f, 10f), floatArrayOf(180f, 150f, 120f, 100f),
                    floatArrayOf(1f, 1f, 1f, 1f)
                )
            )
        },
        "EDM / Electrónica" to {
            applyFullPreset(
                curve(20.0 to 3.5f, 50.0 to 4.5f, 100.0 to 3.5f, 200.0 to 1f, 500.0 to -1f, 1000.0 to -1.5f,
                    3000.0 to 1f, 6000.0 to 4f, 10000.0 to 6f, 16000.0 to 5f),
                floatArrayOf(2f, -1.5f, 3f), 4f, 50f, -6f,
                MdrcPreset(
                    floatArrayOf(-18f, -20f, -20f, -22f), floatArrayOf(4f, 3f, 3f, 3f),
                    floatArrayOf(10f, 15f, 10f, 5f), floatArrayOf(120f, 150f, 120f, 100f),
                    floatArrayOf(2f, 1f, 1f, 1f)
                )
            )
        },
        "Hip-Hop / Trap" to {
            applyFullPreset(
                curve(20.0 to 3.5f, 50.0 to 4f, 100.0 to 3.5f, 200.0 to 0f, 400.0 to -1f, 1000.0 to 0f,
                    2500.0 to 1.5f, 5000.0 to 2f, 10000.0 to 3f, 16000.0 to 2.5f),
                floatArrayOf(2f, -1f, 1.5f), 3f, 45f, -5f,
                MdrcPreset(
                    floatArrayOf(-20f, -22f, -22f, -24f), floatArrayOf(4f, 2.5f, 2.5f, 2f),
                    floatArrayOf(10f, 20f, 15f, 8f), floatArrayOf(150f, 150f, 120f, 100f),
                    floatArrayOf(2f, 1f, 1f, 0f)
                )
            )
        },
        "Jazz / Clásica" to {
            applyFullPreset(
                curve(20.0 to 0f, 60.0 to 1f, 120.0 to 1f, 250.0 to 0f, 1000.0 to 0f, 4000.0 to 0.5f,
                    8000.0 to 1.5f, 12000.0 to 2f, 16000.0 to 1.5f),
                floatArrayOf(0.5f, 0f, 1f), 0f, 60f, 0f, null
            )
        },
        "Parlante pequeño (protege graves)" to {
            // Cuts the deepest bass a small driver cannot reproduce and lifts the 80-180 Hz range instead.
            applyFullPreset(
                curve(20.0 to -9f, 30.0 to -7f, 40.0 to -5f, 55.0 to -2f, 80.0 to 2f, 120.0 to 3.5f,
                    180.0 to 2f, 300.0 to 0f, 1000.0 to 0f, 4000.0 to 1f, 10000.0 to 1.5f, 16000.0 to 1f),
                floatArrayOf(0f, 0f, 1f), 3f, 110f, -3f,
                MdrcPreset(
                    floatArrayOf(-24f, -24f, -24f, -26f), floatArrayOf(6f, 2f, 2f, 2f),
                    floatArrayOf(5f, 20f, 15f, 10f), floatArrayOf(100f, 150f, 120f, 100f),
                    floatArrayOf(0f, 1f, 1f, 1f)
                )
            )
        }
    )

    private fun showPresetsDialog() {
        val builtIn = builtInPresets()
        val user = presetStore.names()
        val entries = ArrayList<String>()
        builtIn.forEach { entries.add(it.first) }
        user.forEach { entries.add("\u2605 $it") }
        entries.add("+ Guardar ajustes actuales como preset\u2026")
        if (user.isNotEmpty()) entries.add("\u2212 Eliminar un preset\u2026")

        MaterialAlertDialogBuilder(this)
            .setTitle("Presets")
            .setItems(entries.toTypedArray()) { _, which ->
                when {
                    which < builtIn.size -> builtIn[which].second.invoke()
                    which < builtIn.size + user.size -> loadUserPreset(user[which - builtIn.size])
                    which == builtIn.size + user.size -> showSavePresetDialog()
                    else -> showDeletePresetDialog()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadUserPreset(name: String) {
        val preset = presetStore.load(name)
        if (preset == null) {
            Toast.makeText(this, "No se pudo cargar \"$name\"", Toast.LENGTH_SHORT).show()
            return
        }
        // A preset holds the sound settings only; the master power switch stays as it is.
        currentState = preset.copy(isEnabled = currentState.isEnabled)
        updateUiFromState(currentState)
        dispatchStateUpdate()
        Toast.makeText(this, "Preset \"$name\" cargado", Toast.LENGTH_SHORT).show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "Nombre del preset"
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(PresetStore.MAX_NAME_LENGTH))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Guardar preset")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> Toast.makeText(this, "Escribí un nombre", Toast.LENGTH_SHORT).show()
                    presetStore.exists(name) -> MaterialAlertDialogBuilder(this)
                        .setTitle("Sobrescribir")
                        .setMessage("Ya existe \"$name\". \u00bfReemplazarlo con los ajustes actuales?")
                        .setPositiveButton("Reemplazar") { _, _ -> savePreset(name) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    else -> savePreset(name)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun savePreset(name: String) {
        val ok = presetStore.save(name, currentState)
        Toast.makeText(
            this,
            if (ok) "Preset \"$name\" guardado" else "No se pudo guardar (m\u00e1x. ${PresetStore.MAX_PRESETS})",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDeletePresetDialog() {
        val user = presetStore.names()
        if (user.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar preset")
            .setItems(user.toTypedArray()) { _, which ->
                val name = user[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle("Eliminar")
                    .setMessage("\u00bfEliminar el preset \"$name\"?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        presetStore.delete(name)
                        Toast.makeText(this, "Preset \"$name\" eliminado", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyHarmanTarget() {
        val gains = floatArrayOf(
            5.2f, 5.0f, 4.8f, 4.4f, 4.0f, 3.5f, 2.8f, 2.0f, 1.2f, 0.5f,
            0.0f, 0.0f, 0.0f, 0.2f, 0.5f, 1.0f, 1.8f, 2.6f, 3.4f, 4.2f,
            4.8f, 4.0f, 2.5f, 1.0f, 0.0f, -0.5f, -1.0f, -0.5f, 0.0f, 1.0f,
            1.5f, 1.0f
        )
        System.arraycopy(gains, 0, currentState.fixedGains, 0, 32)
        currentState.toneGains[0] = 1.5f
        currentState.toneGains[1] = 0.5f
        currentState.toneGains[2] = 1.0f
        updateUiFromState(currentState)
        dispatchStateUpdate()
    }

    private fun applySubBass() {
        val gains = floatArrayOf(
            9.0f, 8.8f, 8.5f, 7.8f, 6.5f, 5.0f, 3.5f, 2.0f, 0.8f, 0.0f,
            -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.2f, 0.5f, 0.8f, 1.0f,
            1.2f, 1.5f, 1.8f, 2.0f, 2.2f, 2.0f, 1.5f, 1.0f, 0.5f, 0.0f,
            -0.5f, -1.0f
        )
        System.arraycopy(gains, 0, currentState.fixedGains, 0, 32)
        currentState.toneGains[0] = 4.5f
        currentState.toneGains[1] = -0.5f
        currentState.toneGains[2] = 1.5f
        updateUiFromState(currentState)
        dispatchStateUpdate()
    }

    private fun applyCrystalVocal() {
        val gains = floatArrayOf(
            -2.0f, -1.5f, -1.0f, -0.5f, 0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f,
            1.2f, 1.5f, 1.8f, 2.2f, 2.8f, 3.5f, 4.2f, 4.5f, 4.2f, 3.8f,
            3.2f, 2.5f, 2.0f, 1.5f, 1.0f, 0.8f, 0.5f, 0.2f, 0.0f, 0.0f,
            0.0f, 0.0f
        )
        System.arraycopy(gains, 0, currentState.fixedGains, 0, 32)
        currentState.toneGains[0] = -1.0f
        currentState.toneGains[1] = 3.2f
        currentState.toneGains[2] = 1.8f
        updateUiFromState(currentState)
        dispatchStateUpdate()
    }

    private fun applyVShape() {
        val gains = floatArrayOf(
            7.0f, 6.8f, 6.2f, 5.5f, 4.5f, 3.2f, 2.0f, 0.8f, -0.2f, -1.0f,
            -1.8f, -2.2f, -2.5f, -2.5f, -2.2f, -1.8f, -1.0f, 0.0f, 0.8f, 1.8f,
            2.6f, 3.4f, 4.2f, 5.0f, 5.8f, 6.4f, 6.8f, 7.0f, 6.5f, 5.8f,
            5.0f, 4.0f
        )
        System.arraycopy(gains, 0, currentState.fixedGains, 0, 32)
        currentState.toneGains[0] = 3.8f
        currentState.toneGains[1] = -1.8f
        currentState.toneGains[2] = 3.5f
        updateUiFromState(currentState)
        dispatchStateUpdate()
    }

    private fun applyAcoustic() {
        val gains = floatArrayOf(
            2.0f, 2.2f, 2.5f, 2.8f, 3.0f, 2.8f, 2.4f, 1.8f, 1.2f, 0.8f,
            0.5f, 0.2f, 0.0f, 0.0f, 0.2f, 0.5f, 0.8f, 1.2f, 1.5f, 1.8f,
            2.0f, 2.2f, 2.5f, 2.8f, 3.0f, 3.2f, 3.0f, 2.8f, 2.2f, 1.5f,
            1.0f, 0.5f
        )
        System.arraycopy(gains, 0, currentState.fixedGains, 0, 32)
        currentState.toneGains[0] = 1.8f
        currentState.toneGains[1] = 0.8f
        currentState.toneGains[2] = 2.0f
        updateUiFromState(currentState)
        dispatchStateUpdate()
    }

    override fun onPause() {
        // Flush the final drag value if the activity is backgrounded immediately.
        mainHandler.removeCallbacks(persistStateRunnable)
        if (::prefsManager.isInitialized && persistPending) {
            persistPending = false
            prefsManager.saveCurrentState(currentState)
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Pick up any change made while the app was in the background (notification / tile).
        if (::prefsManager.isInitialized && eqService != null) {
            eqService?.getCurrentState()?.let {
                currentState = it.deepCopy()
                updateUiFromState(currentState)
            }
        }
    }

    override fun onDestroy() {
        eqService?.stateListener = null
        mainHandler.removeCallbacks(persistStateRunnable)
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
