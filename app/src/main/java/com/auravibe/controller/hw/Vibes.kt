package com.auravibe.controller.hw

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.auravibe.controller.data.VibeSettings
import com.auravibe.controller.data.VibeStyle
import com.auravibe.controller.data.VibeUsage

class Vibes(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    fun vibrate(v: VibeSettings, usage: VibeUsage, styleOverride: VibeStyle? = null) {
        if (!vibrator.hasVibrator()) return
        val amp = if (vibrator.hasAmplitudeControl()) (v.intensity.coerceIn(0.2f, 1f) * 255).toInt().coerceIn(1, 255) else 255
        val len = v.lengthMs.coerceIn(100, 2000).toLong()
        val t = ArrayList<Long>()
        val a = ArrayList<Int>()
        fun on(ms: Long, amplitude: Int = amp) { t += ms; a += amplitude }
        fun off(ms: Long) { t += ms; a += 0 }
        off(0)
        repeat(v.repeat.coerceIn(1, 5)) { i ->
            if (i > 0) off(400)
            when (styleOverride ?: v.style) {
                VibeStyle.SINGLE -> on(len)
                VibeStyle.DOUBLE -> { on(len); off(maxOf(80, len / 2)); on(len) }
                VibeStyle.LONG -> on(minOf(len * 3, 3000), maxOf(amp, 230))
                VibeStyle.HEARTBEAT -> { on(maxOf(60, len / 2), amp * 3 / 4); off(110); on(len); off(450) }
                VibeStyle.URGENT -> repeat(5) { on(maxOf(50, len / 2)); off(60) }
            }
        }
        val attrs = VibrationAttributes.createForUsage(
            when (usage) {
                VibeUsage.ALARM -> VibrationAttributes.USAGE_ALARM
                VibeUsage.COMMUNICATION_REQUEST -> VibrationAttributes.USAGE_COMMUNICATION_REQUEST
                VibeUsage.HARDWARE_FEEDBACK -> VibrationAttributes.USAGE_HARDWARE_FEEDBACK
                VibeUsage.RINGTONE -> VibrationAttributes.USAGE_RINGTONE
            },
        )
        vibrator.vibrate(VibrationEffect.createWaveform(t.toLongArray(), a.toIntArray(), -1), attrs)
    }
}
