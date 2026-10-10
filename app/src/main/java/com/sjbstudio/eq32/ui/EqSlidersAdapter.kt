package com.sjbstudio.eq32.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sjbstudio.eq32.databinding.ItemSliderBinding

class EqSlidersAdapter(
    private val frequencies: DoubleArray,
    private val gains: FloatArray,
    private val onGainChanged: (Int, Float) -> Unit
) : RecyclerView.Adapter<EqSlidersAdapter.SliderViewHolder>() {

    inner class SliderViewHolder(val binding: ItemSliderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SliderViewHolder {
        val binding = ItemSliderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SliderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SliderViewHolder, position: Int) {
        val freq = frequencies[position]
        val gain = gains[position]

        // Format frequency label
        val freqLabel = if (freq >= 1000.0) {
            val k = freq / 1000.0
            if (k >= 10.0) "%dk".format(Math.round(k)) else "%.1fk".format(k)
        } else {
            "%d".format(Math.round(freq))
        }

        holder.binding.tvFreqLabel.text = freqLabel
        holder.binding.tvGainVal.text = if (gain >= 0) "+%.1f".format(gain) else "%.1f".format(gain)

        // Set slider value without triggering listener loop
        holder.binding.sliderVertical.clearOnChangeListeners()
        holder.binding.sliderVertical.value = gain.coerceIn(-12.0f, 12.0f)

        // Keep the vertical drag owned by the slider. The screen is inside a
        // NestedScrollView, which otherwise steals vertical gestures and makes
        // the thumb appear to move with the page instead of changing gain.
        holder.binding.sliderVertical.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE ->
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // preserve Material Slider's own touch handling
        }

        holder.binding.sliderVertical.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                gains[position] = value
                holder.binding.tvGainVal.text = if (value >= 0) "+%.1f".format(value) else "%.1f".format(value)
                onGainChanged(position, value)
            }
        }

        // Double click to reset band to 0 dB
        holder.binding.tvGainVal.setOnClickListener {
            gains[position] = 0f
            holder.binding.sliderVertical.value = 0f
            holder.binding.tvGainVal.text = "0.0"
            onGainChanged(position, 0f)
        }
    }

    override fun getItemCount(): Int = frequencies.size
}
