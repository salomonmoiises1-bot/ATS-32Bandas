package com.sjbstudio.eq32.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sjbstudio.eq32.data.EqPreferencesManager

class SJBStudioTileService : TileService() {

    private lateinit var prefsManager: EqPreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefsManager = EqPreferencesManager(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val current = prefsManager.loadCurrentState()
        val toggled = current.copy(isEnabled = !current.isEnabled)
        prefsManager.saveCurrentState(toggled)

        // Send toggle intent to EqService
        val intent = Intent(this, EqService::class.java).apply {
            action = EqService.ACTION_UPDATE_STATE
        }
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            // Background start refused; the new state is already persisted and is applied next time the service starts.
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val current = prefsManager.loadCurrentState()
        if (current.isEnabled) {
            tile.state = Tile.STATE_ACTIVE
            if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = "Activo (32 bandas)"
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = "En bypass"
        }
        tile.updateTile()
    }
}
