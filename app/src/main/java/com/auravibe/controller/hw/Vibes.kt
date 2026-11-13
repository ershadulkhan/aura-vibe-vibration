package com.auravibe.controller.hw

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.auravibe.controller.data.VibePattern
import com.auravibe.controller.data.VibeUsage

class Vibes(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    fun vibrate(pattern: VibePattern, customMs: Int, usage: VibeUsage) {
        if (!vibrator.hasVibrator()) return
        val effect = when (pattern) {
            VibePattern.SHORT_DOUBLE -> VibrationEffect.createWaveform(
                longArrayOf(0, 70, 90, 70), intArrayOf(0, 255, 0, 255), -1)
            VibePattern.STRONG_PULSE -> VibrationEffect.createOneShot(450, 255)
            VibePattern.HEARTBEAT -> VibrationEffect.createWaveform(
                longArrayOf(0, 90, 110, 180, 450, 90, 110, 180), intArrayOf(0, 200, 0, 255, 0, 200, 0, 255), -1)
            VibePattern.CUSTOM -> VibrationEffect.createOneShot(customMs.coerceIn(20, 5000).toLong(), 255)
        }
        val attrs = VibrationAttributes.createForUsage(
            when (usage) {
                VibeUsage.ALARM -> VibrationAttributes.USAGE_ALARM
                VibeUsage.COMMUNICATION_REQUEST -> VibrationAttributes.USAGE_COMMUNICATION_REQUEST
                VibeUsage.HARDWARE_FEEDBACK -> VibrationAttributes.USAGE_HARDWARE_FEEDBACK
                VibeUsage.RINGTONE -> VibrationAttributes.USAGE_RINGTONE
            }
        )
        vibrator.vibrate(effect, attrs)
    }
}
