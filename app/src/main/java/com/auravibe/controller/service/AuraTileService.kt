package com.auravibe.controller.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.auravibe.controller.AuraVibeApp

class AuraTileService : TileService() {
    override fun onStartListening() = refresh(AuraVibeApp.instance.lightOn)

    override fun onClick() {
        val app = AuraVibeApp.instance
        val turnOn = !app.lightOn
        if (turnOn) app.turnLightOn() else app.turnLightOff()
        refresh(turnOn)
    }

    private fun refresh(on: Boolean) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (on) "On" else "Off"
            updateTile()
        }
    }
}
