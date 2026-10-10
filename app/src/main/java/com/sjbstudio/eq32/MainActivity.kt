package com.sjbstudio.eq32

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sjbstudio.eq32.data.EqPreferencesManager
import com.sjbstudio.eq32.databinding.ActivityMainBinding
import com.sjbstudio.eq32.service.EqService
import com.sjbstudio.eq32.state.EqState32WithMDRC
import com.sjbstudio.eq32.ui.EqSlidersAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: EqPreferencesManager
    private lateinit var slidersAdapter: EqSlidersAdapter

    private var eqService: EqService? = null
    private var isBound: Boolean = false
    private var currentState = EqState32WithMDRC()

    // Choreographer 16ms frame-rate limiter to debounce high-frequency touch updates
    private var isFrameScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistStateRunnable = Runnable {
        prefsManager.saveCurrentState(currentState)
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
                currentState = serviceState
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
        currentState = prefsManager.loadCurrentState()

        // Start & bind DSP service
        EqService.startService(this)
        bindService(Intent(this, EqService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        setupTopToolbar()
        setupKnobs()
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

        // Treble Knob (8000 Hz HighShelf)
        binding.knobTreble.setValue(currentState.toneGains[2])
        binding.knobTreble.onValueChangedListener = { gain ->
            currentState.toneGains[2] = gain
            scheduleStateDispatch()
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
        eqService?.updateState(currentState)
        mainHandler.removeCallbacks(persistStateRunnable)
        mainHandler.postDelayed(persistStateRunnable, 250L)
    }

    // Used for explicit actions (preset, reset) where an immediate save is desired.
    private fun dispatchStateUpdate() {
        mainHandler.removeCallbacks(persistStateRunnable)
        prefsManager.saveCurrentState(currentState)
        eqService?.updateState(currentState)
    }

    private fun updateUiFromState(state: EqState32WithMDRC) {
        binding.switchMasterPower.isChecked = state.isEnabled
        binding.knobBass.setValue(state.toneGains[0])
        binding.knobMid.setValue(state.toneGains[1])
        binding.knobTreble.setValue(state.toneGains[2])
        binding.eqGraphView.updateCurve(state)
        slidersAdapter.notifyDataSetChanged()
        binding.mdrcView.setState(state)
    }

    private fun resetAllToFlat() {
        currentState = EqState32WithMDRC()
        updateUiFromState(currentState)
        dispatchStateUpdate()
        Toast.makeText(this, "Reset all bands to flat reference (0 dB)", Toast.LENGTH_SHORT).show()
    }

    private fun showPresetsDialog() {
        val presetNames = arrayOf(
            "Pure Flat Reference",
            "Harman Target Over-Ear",
            "Sub-Bass Thunder & Punch",
            "Crystal Vocal & Intelligibility",
            "V-Shape Energy",
            "Acoustic Warmth & Air"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Select EQ Preset")
            .setItems(presetNames) { _, which ->
                when (which) {
                    0 -> resetAllToFlat()
                    1 -> applyHarmanTarget()
                    2 -> applySubBass()
                    3 -> applyCrystalVocal()
                    4 -> applyVShape()
                    5 -> applyAcoustic()
                }
            }
            .setNegativeButton("Cancel", null)
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
        if (::prefsManager.isInitialized) prefsManager.saveCurrentState(currentState)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(persistStateRunnable)
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
