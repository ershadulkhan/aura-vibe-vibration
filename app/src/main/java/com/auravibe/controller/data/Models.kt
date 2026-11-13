package com.auravibe.controller.data

import org.json.JSONObject

/** Low-level animation description consumed by the PatternEngine. */
enum class LightPattern { BREATHE, INVERSE_BREATHE, STROBE, HEARTBEAT }

data class AnimSpec(
    val pattern: LightPattern = LightPattern.BREATHE,
    val repeat: Int = 3,          // 0 = continuous
    val rampUpMs: Int = 600,
    val holdMs: Int = 300,
    val rampDownMs: Int = 600,
    val timeoutSec: Int = 60,
)

/** What the user picks on the Home screen. */
enum class LightStyle(val emoji: String, val title: String, val sub: String) {
    STEADY("🌕", "Always On", "Steady light"),
    BREATHE("🌊", "Smooth Pulse", "Dim ➔ Bright ➔ Dim"),
    INVERSE("✨", "Reverse Pulse", "Bright ➔ Dim ➔ Bright"),
    STROBE("⚡", "Fast Blink", "Strobe"),
    HEARTBEAT("💓", "Heartbeat", "Double blink"),
}

enum class VibeStyle(val emoji: String, val title: String) {
    SINGLE("📳", "Single Tap"),
    DOUBLE("📳📳", "Double Tap"),
    LONG("🌊", "Long Strong Buzz"),
    HEARTBEAT("💓", "Heartbeat"),
    URGENT("🚨", "Urgent Rapid"),
}

/** Which VibrationAttributes usage to use (whichever gets through OriginOS silent mode). */
enum class VibeUsage(val label: String) {
    ALARM("Alarm"),
    COMMUNICATION_REQUEST("Communication"),
    HARDWARE_FEEDBACK("Hardware feedback"),
    RINGTONE("Ringtone"),
}

data class LightSettings(
    val style: LightStyle = LightStyle.STEADY,
    val temp: Float = 0f,          // 0 = warm orange, 1 = cool white
    val level: Int = 3,            // 1..5  (20%..100%)
    val speed: Float = 0.5f,       // 0 = very slow & smooth, 1 = fast
    val count: Int = 3,            // 1..15
    val infinite: Boolean = false, // keep going until the phone is unlocked / tapped off
) {
    val brightness get() = level / 5f

    fun toAnim(timeoutSec: Int, forceCount: Int? = null): AnimSpec {
        fun lerp(slow: Int, fast: Int) = (slow + (fast - slow) * speed).toInt()
        val repeat = forceCount ?: if (infinite) 0 else count
        return when (style) {
            LightStyle.STEADY, LightStyle.BREATHE ->
                AnimSpec(LightPattern.BREATHE, repeat, lerp(2500, 150), lerp(1000, 80), lerp(2500, 150), timeoutSec)
            LightStyle.INVERSE ->
                AnimSpec(LightPattern.INVERSE_BREATHE, repeat, lerp(2500, 150), lerp(1000, 80), lerp(2500, 150), timeoutSec)
            LightStyle.STROBE ->
                AnimSpec(LightPattern.STROBE, repeat, 100, lerp(700, 50), lerp(700, 60), timeoutSec)
            LightStyle.HEARTBEAT ->
                AnimSpec(LightPattern.HEARTBEAT, repeat, 100, lerp(900, 150), lerp(900, 150), timeoutSec)
        }
    }

    fun toJson(): String = JSONObject().put("s", style.name).put("t", temp.toDouble()).put("l", level)
        .put("sp", speed.toDouble()).put("c", count).put("i", infinite).toString()

    companion object {
        fun fromJson(s: String?): LightSettings = runCatching {
            val o = JSONObject(s!!)
            LightSettings(
                enumOr(o.optString("s"), LightStyle.STEADY), o.optDouble("t", 0.0).toFloat(), o.optInt("l", 3),
                o.optDouble("sp", 0.5).toFloat(), o.optInt("c", 3), o.optBoolean("i"),
            )
        }.getOrDefault(LightSettings())
    }
}

data class VibeSettings(
    val enabled: Boolean = true,
    val style: VibeStyle = VibeStyle.DOUBLE,
    val intensity: Float = 1f,     // 0.2..1
    val lengthMs: Int = 150,       // 100..2000
    val repeat: Int = 1,           // 1..5
) {
    fun toJson(): String = JSONObject().put("e", enabled).put("s", style.name).put("i", intensity.toDouble())
        .put("l", lengthMs).put("r", repeat).toString()

    companion object {
        fun fromJson(s: String?): VibeSettings = runCatching {
            val o = JSONObject(s!!)
            VibeSettings(
                o.optBoolean("e", true), enumOr(o.optString("s"), VibeStyle.DOUBLE),
                o.optDouble("i", 1.0).toFloat(), o.optInt("l", 150), o.optInt("r", 1),
            )
        }.getOrDefault(VibeSettings())
    }
}

data class AppRule(
    val vibrate: Boolean = false,
    val aura: Boolean = false,
    val custom: Boolean = false,
    val temp: Float = 0f,
    val vibeStyle: VibeStyle = VibeStyle.DOUBLE,
) {
    val active get() = vibrate || aura

    fun toJson(): JSONObject = JSONObject().put("v", vibrate).put("a", aura).put("cu", custom)
        .put("ct", temp.toDouble()).put("cs", vibeStyle.name)

    companion object {
        fun fromJson(o: JSONObject) = AppRule(
            o.optBoolean("v"), o.optBoolean("a"), o.optBoolean("cu"),
            o.optDouble("ct", 0.0).toFloat(), enumOr(o.optString("cs"), VibeStyle.DOUBLE),
        )
    }
}

/**
 * Light hardware mapping. Channel ids:
 *  - "cam:<id>"            torch strength API (also hidden / physical camera ids)
 *  - "/sys/...led dir"     sysfs LED node via Shizuku (switch nodes committed afterwards)
 *  - "mtk:<type>:<ct>"     MediaTek /sys/class/flashlight_core torch interface via Shizuku
 *  - "sess:<cam>[:<key>]"  Camera2 capture session in torch mode (+ optional vendor key = 1)
 */
data class EngineSettings(
    val enabled: Boolean = true,
    val onlyInQuietModes: Boolean = true,
    val usage: VibeUsage = VibeUsage.ALARM,
    val cooldownMs: Int = 3000,
    val autoOffMin: Int = 10,
    val warmNode: String = "",
    val coolNode: String = "",
    val switchNodes: List<String> = emptyList(),
    val light: LightSettings = LightSettings(),
    val vibe: VibeSettings = VibeSettings(),
)

inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
    runCatching { enumValueOf<E>(name!!) }.getOrDefault(def)
