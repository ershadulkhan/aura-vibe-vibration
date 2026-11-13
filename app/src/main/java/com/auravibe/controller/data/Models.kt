package com.auravibe.controller.data

import org.json.JSONObject

enum class VibePattern(val label: String) {
    SHORT_DOUBLE("Short double tap"),
    STRONG_PULSE("Strong pulse"),
    HEARTBEAT("Heartbeat"),
    CUSTOM("Custom duration"),
}

enum class LightPattern(val label: String) {
    BREATHE("Breathe"),
    INVERSE_BREATHE("Inverse breathe"),
    STROBE("Strobe / blink"),
    HEARTBEAT("SOS / heartbeat"),
}

/** Which VibrationAttributes usage to use for forced vibration (pick whichever pierces OriginOS). */
enum class VibeUsage(val label: String) {
    ALARM("Alarm"),
    COMMUNICATION_REQUEST("Communication request"),
    HARDWARE_FEEDBACK("Hardware feedback"),
    RINGTONE("Ringtone"),
}

/** Which hardware backend drives the Aura light. */
enum class AuraBackend(val label: String) {
    AUTO("Auto (best available)"),
    CAMERA_TORCH("Camera torch API"),
    SHIZUKU_SYSFS("Shizuku sysfs LEDs"),
}

data class AppRule(
    val vibrate: Boolean = false,
    val aura: Boolean = false,
    val pattern: VibePattern = VibePattern.SHORT_DOUBLE,
    val customMs: Int = 400,
) {
    val active get() = vibrate || aura

    fun toJson(): JSONObject = JSONObject()
        .put("v", vibrate).put("a", aura).put("p", pattern.name).put("c", customMs)

    companion object {
        fun fromJson(o: JSONObject) = AppRule(
            vibrate = o.optBoolean("v"),
            aura = o.optBoolean("a"),
            pattern = runCatching { VibePattern.valueOf(o.optString("p")) }.getOrDefault(VibePattern.SHORT_DOUBLE),
            customMs = o.optInt("c", 400),
        )
    }
}

/** Animation parameters shared by Studio pulse mode and notification flashes. */
data class AnimSpec(
    val pattern: LightPattern = LightPattern.BREATHE,
    val repeat: Int = 3,              // 1..20, 0 = continuous
    val rampUpMs: Int = 600,          // 100..5000
    val holdMs: Int = 300,            // 100..3000
    val rampDownMs: Int = 600,        // 100..5000
    val timeoutSec: Int = 60,         // safety cutoff
)

data class EngineSettings(
    val enabled: Boolean = true,
    val onlyInQuietModes: Boolean = true,     // only act in Vibrate / Silent ringer modes
    val usage: VibeUsage = VibeUsage.ALARM,
    val cooldownMs: Int = 3000,
    val colorTemp: Float = 0.5f,              // 0 = warm orange, 1 = cool white
    val autoTemp: Boolean = false,
    val brightnessLevel: Int = 3,             // 1..5
    val backend: AuraBackend = AuraBackend.AUTO,
    val warmNode: String = "",                // sysfs LED dir, e.g. /sys/class/leds/led:torch_0
    val coolNode: String = "",
    val anim: AnimSpec = AnimSpec(),
    val notifAnim: AnimSpec = AnimSpec(pattern = LightPattern.STROBE, repeat = 3, rampUpMs = 100, holdMs = 150, rampDownMs = 100, timeoutSec = 10),
)
