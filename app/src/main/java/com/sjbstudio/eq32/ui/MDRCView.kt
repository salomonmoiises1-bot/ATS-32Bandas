package com.sjbstudio.eq32.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
import com.sjbstudio.eq32.databinding.ViewMdrcBinding
import com.sjbstudio.eq32.state.EqState32WithMDRC

class MDRCView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewMdrcBinding = ViewMdrcBinding.inflate(LayoutInflater.from(context), this, true)
    private var currentState: EqState32WithMDRC = EqState32WithMDRC()
    private var selectedBand: Int = 0

    var onMdrcChangedListener: ((EqState32WithMDRC) -> Unit)? = null

    init {
        orientation = VERTICAL
        setupListeners()
    }

    private fun setupListeners() {
        binding.switchMdrcMaster.setOnCheckedChangeListener { _, isChecked ->
            currentState = currentState.copy(mdrcEnabled = isChecked)
            onMdrcChangedListener?.invoke(currentState)
        }

        // Band selection tabs (Band 1: Sub <120Hz, Band 2: Low-Mid, Band 3: High-Mid, Band 4: Air)
        binding.btnBand0.setOnClickListener { selectBand(0) }
        binding.btnBand1.setOnClickListener { selectBand(1) }
        binding.btnBand2.setOnClickListener { selectBand(2) }
        binding.btnBand3.setOnClickListener { selectBand(3) }

        // Threshold Slider (-40 dB to 0 dB)
        binding.sliderThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentState.mdrcThreshold[selectedBand] = value
                binding.tvThresholdVal.text = "%.1f dB".format(value)
                onMdrcChangedListener?.invoke(currentState)
            }
        }

        // Ratio Slider (1:1 to 20:1)
        binding.sliderRatio.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentState.mdrcRatio[selectedBand] = value
                binding.tvRatioVal.text = "%.1f:1".format(value)
                onMdrcChangedListener?.invoke(currentState)
            }
        }

        // Attack Slider (1 ms to 100 ms)
        binding.sliderAttack.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentState.mdrcAttack[selectedBand] = value
                binding.tvAttackVal.text = "%d ms".format(value.toInt())
                onMdrcChangedListener?.invoke(currentState)
            }
        }

        // Release Slider (10 ms to 500 ms)
        binding.sliderRelease.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentState.mdrcRelease[selectedBand] = value
                binding.tvReleaseVal.text = "%d ms".format(value.toInt())
                onMdrcChangedListener?.invoke(currentState)
            }
        }

        // Makeup Gain Slider (0 dB to 18 dB)
        binding.sliderMakeup.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentState.mdrcMakeup[selectedBand] = value
                binding.tvMakeupVal.text = "+%.1f dB".format(value)
                onMdrcChangedListener?.invoke(currentState)
            }
        }
    }

    fun setState(state: EqState32WithMDRC) {
        currentState = state
        binding.switchMdrcMaster.isChecked = state.mdrcEnabled
        selectBand(selectedBand)
    }

    private fun selectBand(band: Int) {
        selectedBand = band

        // Update button active states
        val buttons = listOf(binding.btnBand0, binding.btnBand1, binding.btnBand2, binding.btnBand3)
        buttons.forEachIndexed { i, btn ->
            btn.alpha = if (i == band) 1.0f else 0.5f
        }

        // Update sliders to active band values
        binding.sliderThreshold.value = currentState.mdrcThreshold[band]
        binding.tvThresholdVal.text = "%.1f dB".format(currentState.mdrcThreshold[band])

        binding.sliderRatio.value = currentState.mdrcRatio[band]
        binding.tvRatioVal.text = "%.1f:1".format(currentState.mdrcRatio[band])

        binding.sliderAttack.value = currentState.mdrcAttack[band]
        binding.tvAttackVal.text = "%d ms".format(currentState.mdrcAttack[band].toInt())

        binding.sliderRelease.value = currentState.mdrcRelease[band]
        binding.tvReleaseVal.text = "%d ms".format(currentState.mdrcRelease[band].toInt())

        binding.sliderMakeup.value = currentState.mdrcMakeup[band]
        binding.tvMakeupVal.text = "+%.1f dB".format(currentState.mdrcMakeup[band])
    }
}
