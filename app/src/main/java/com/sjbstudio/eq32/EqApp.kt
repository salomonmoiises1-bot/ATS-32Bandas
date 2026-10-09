package com.sjbstudio.eq32

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

class EqApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Force true pitch-black AMOLED dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
