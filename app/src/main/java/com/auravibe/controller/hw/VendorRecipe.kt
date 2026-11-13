package com.auravibe.controller.hw

import kotlin.math.roundToInt

/**
 * A Camera2 "recipe" that may switch on the vivo Aura (soft light) LED: a set of vendor
 * capture-request keys plus whether FLASH_MODE_TORCH is also requested.
 *
 * Channel string:  sess:<cameraId>|<t|n>|key=value;key=value
 * Values may be a number, a comma list (array) and may contain tokens:
 *   L = brightness level 1..6,   K = colour temperature in kelvin (2700 warm .. 6500 cool)
 */
data class VendorRecipe(val cameraId: String, val torch: Boolean, val entries: List<Pair<String, String>>) {
    val usesTone get() = entries.any { it.second.contains('K') }
    val channel get() = "sess:$cameraId|${if (torch) "t" else "n"}|" + entries.joinToString(";") { "${it.first}=${it.second}" }

    companion object {
        fun parse(channel: String): VendorRecipe? {
            if (!channel.startsWith("sess:")) return null
            val parts = channel.removePrefix("sess:").split("|")
            if (parts.size < 3) return null
            val entries = parts[2].split(";").mapNotNull { e ->
                val i = e.indexOf('=')
                if (i <= 0) null else e.substring(0, i).trim() to e.substring(i + 1).trim()
            }
            return VendorRecipe(parts[0].trim(), parts[1].trim() == "t", entries)
        }

        fun kelvin(tone: Float) = (2700 + (6500 - 2700) * tone.coerceIn(0f, 1f)).roundToInt()

        fun resolve(v: String, level: Int, kelvin: Int) = v.replace("L", level.toString()).replace("K", kelvin.toString())

        fun zero(v: String) = v.split(",").joinToString(",") { "0" }

        /** Candidate Java values to try for a vendor key (its exact type is not exposed). */
        fun javaValues(v: String): List<Any> {
            val parts = v.split(",").map { it.trim() }
            val ints = parts.map { it.toIntOrNull() }
            if (parts.size == 1) {
                val n = ints[0]
                if (n != null) return listOf(n, n.toByte(), n.toFloat(), n.toLong(), n.toDouble(), intArrayOf(n), byteArrayOf(n.toByte()))
                val f = parts[0].toFloatOrNull() ?: return emptyList()
                return listOf(f, f.toDouble(), floatArrayOf(f))
            }
            if (ints.any { it == null }) {
                val fl = parts.map { it.toFloatOrNull() ?: 0f }
                return listOf(fl.toFloatArray(), fl.map { it.toDouble() }.toDoubleArray())
            }
            val a = ints.map { it!! }
            return listOf(a.toIntArray(), a.map { it.toByte() }.toByteArray(), a.map { it.toLong() }.toLongArray(), a.map { it.toFloat() }.toFloatArray())
        }

        private const val SOFT = "vivo.control.isSoftLight"
        private const val STUDIO = "vivo.control.softLightStudio"
        private const val CCT = "vivo.control.colour.temperature"
        private const val DUTY = "com.mediatek.flashfeature.customizedTorchDuty"

        /** Ordered list of recipes worth trying on this camera, limited to keys it really has. */
        fun curated(cam: String, keys: Set<String>): List<Pair<String, VendorRecipe>> {
            fun r(title: String, torch: Boolean, vararg e: Pair<String, String>) = title to VendorRecipe(cam, torch, e.toList())
            val all = listOf(
                r("Soft light (Aura) on", false, SOFT to "1"),
                r("Soft light + flash", true, SOFT to "1"),
                r("Soft light studio", false, STUDIO to "1"),
                r("Soft light studio + flash", true, STUDIO to "1"),
                r("Soft light + studio", false, SOFT to "1", STUDIO to "1"),
                r("Soft light, warm colour", false, SOFT to "1", CCT to "K"),
                r("Soft light + flash, warm colour", true, SOFT to "1", CCT to "K"),
                r("Soft light + studio, warm colour", false, SOFT to "1", STUDIO to "1", CCT to "K"),
                r("MediaTek dual LED: 2nd LED only", true, DUTY to "0,L"),
                r("MediaTek dual LED: 1st LED only", true, DUTY to "L,0"),
                r("MediaTek dual LED: both", true, DUTY to "L,L"),
                r("MediaTek torch duty", true, DUTY to "L"),
                r("vivo torch strength", true, "vivo.control.torchStrengthLevel" to "L"),
                r("vivo LED current", true, "vivo.control.led_current" to "L"),
                r("vivo LED current level", true, "vivo.parameter.led_current_level" to "L"),
                r("vivo engineer flash", true, "vivo.control.engineer_flash_value" to "L"),
                r("vivo short torch mode", true, "vivo.control.isShortTorchFlashMode" to "1"),
            )
            return all.filter { (_, rec) -> rec.entries.all { it.first in keys } }
        }

        /** Vendor keys that look related to the flash / Aura light (for the report). */
        val LIGHT_HINTS = listOf("softlight", "torch", "flash", "led_current", "colour.temperature", "cct", "aura", "filllight", "fill_light", "relighting", "3dlight", "eyelight")
    }
}
