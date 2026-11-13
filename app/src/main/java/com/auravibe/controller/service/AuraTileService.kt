package com.auravibe.controller.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.engine.AuraMode

class AuraTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val app = AuraVibeApp.instance
        val s = app.repo.settings.value
        if (app.engine.mode.value == AuraMode.OFF) {
            app.engine.steady(s.brightnessLevel / 5f, app.hal.effectiveTemp(s), s.anim.timeoutSec)
        } else {
            app.engine.stop()
        }
        refresh(app.engine.mode.value != AuraMode.OFF)
    }

    private fun refresh(on: Boolean = AuraVibeApp.instance.engine.mode.value != AuraMode.OFF) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (on) "On" else "Off"
            updateTile()
        }
    }
}
