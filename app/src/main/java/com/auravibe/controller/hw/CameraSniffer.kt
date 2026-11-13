package com.auravibe.controller.hw

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Learn from vivo Camera": with Shizuku (shell uid) we ask cameraserver to record every
 * change of the light-related request keys for all camera clients
 * (`cmd media.camera watch start -m <tags>`, Android 12+), let the user switch on the Aura
 * light in the stock Camera app, then read the recorded values back and turn them into a
 * [VendorRecipe]. Settings / system-property / logcat changes are captured as well.
 * Nothing runs in the background in between — cameraserver does the recording.
 */
class CameraSniffer(private val hal: AuraHal) {

    data class Snapshot(val tags: List<String>, val settings: Map<String, String>, val props: Map<String, String>, val startTime: Long, val watchStart: String)

    data class Result(
        val watchWorked: Boolean,
        val keyValues: LinkedHashMap<String, MutableList<String>>,
        val torchSeen: Boolean,
        val recipe: VendorRecipe?,
        val settingChanges: List<Triple<String, String, String>>, // ns/key, old, new
        val propChanges: List<Triple<String, String, String>>,
        val logLines: List<String>,
        val raw: String,
    ) {
        val settingRecipes: List<Pair<String, String>> = settingChanges.map { (key, old, new) ->
            val (ns, k) = key.split("/", limit = 2)
            val off = if (old == "(unset)") "settings delete $ns $k" else "settings put $ns $k $old"
            "Setting $key: $old → $new" to "shell:settings put $ns $k $new|OFF|$off"
        }
    }

    private val modeKeys = listOf("vivo.control.currentModeEx", "vivo.control.currentmodeS", "com.vivo.current_ui_module", "vivo.control.sceneMode")
    private val flashKeys = listOf("android.flash.mode", "android.flash.state", "android.control.aeMode", "android.flash.strengthLevel")

    private fun tagsToWatch(): List<String> {
        val back = hal.torch.backCameraId() ?: "0"
        val vendor = hal.torch.vendorKeyNames(back)
        // Light-related first, then all other vivo control keys so nothing is missed.
        val light = vendor.filter { n -> VendorRecipe.LIGHT_HINTS.any { n.contains(it, true) } }
        val control = vendor.filter { it.startsWith("vivo.control.") || it.startsWith("com.mediatek.flashfeature") }
        return (flashKeys + light + modeKeys.filter { it in vendor } + control).distinct()
    }

    private fun readSettings(): Map<String, String> {
        val out = HashMap<String, String>()
        for (ns in listOf("system", "secure", "global")) {
            hal.shizuku.exec("settings list $ns").lines().forEach { l ->
                val i = l.indexOf('=')
                if (i > 0) out["$ns/${l.substring(0, i)}"] = l.substring(i + 1)
            }
        }
        return out
    }

    private fun readProps(): Map<String, String> {
        val re = Regex("""^\[(.*)]: \[(.*)]$""")
        return hal.shizuku.exec("getprop").lines().mapNotNull { re.find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] } }.toMap()
    }

    fun start(): Snapshot {
        val tags = tagsToWatch()
        val t = tags.joinToString(",")
        val out = hal.shizuku.exec("cmd media.camera watch stop >/dev/null 2>&1; cmd media.camera watch start -m $t")
        // Older builds: per-device tag monitor through dumpsys.
        hal.shizuku.exec("dumpsys media.camera -m $t >/dev/null")
        return Snapshot(tags, readSettings(), readProps(), System.currentTimeMillis(), out.trim())
    }

    fun finish(snap: Snapshot): Result {
        var raw = hal.shizuku.exec("cmd media.camera watch dump")
        hal.shizuku.exec("cmd media.camera watch stop")
        val watchWorked = snap.tags.any { raw.contains(it) }
        if (!watchWorked) raw += "\n--- dumpsys media.camera ---\n" + hal.shizuku.exec("dumpsys media.camera | grep -iE 'monitor|soft|flash|torch|led|colour|cct' | head -n 400")

        // Parse "...<tag>: [ 1 ]" style lines (TagMonitor output) into value histories.
        val values = LinkedHashMap<String, MutableList<String>>()
        val num = Regex("""-?\d+(\.\d+)?""")
        val byLength = snap.tags.sortedByDescending { it.length }
        raw.lineSequence().forEach { line ->
            val tag = byLength.firstOrNull { line.contains("$it:") || line.contains("$it ") } ?: return@forEach
            val after = line.substringAfter(tag).trimStart(':', ' ')
            val v = num.findAll(after).map { it.value }.joinToString(",")
            if (v.isNotEmpty()) {
                val list = values.getOrPut(tag) { mutableListOf() }
                if (list.lastOrNull() != v) list += v
            }
        }
        val torchSeen = values["android.flash.mode"]?.any { it == "2" } == true

        val oldS = snap.settings
        val newS = readSettings()
        val settingChanges = (oldS.keys + newS.keys).filter { oldS[it] != newS[it] }
            .filter { !it.contains("screen_brightness") && !it.contains("time") && !it.endsWith("_count") }
            .map { Triple(it, oldS[it] ?: "(unset)", newS[it] ?: "(unset)") }
        val newP = readProps()
        val propChanges = (snap.props.keys + newP.keys).filter { snap.props[it] != newP[it] }
            .map { Triple(it, snap.props[it] ?: "(unset)", newP[it] ?: "(unset)") }

        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(snap.startTime))
        val logLines = hal.shizuku.exec(
            "logcat -d -v time -T '$since' | grep -iE 'softlight|soft_light|aura|torch|flashlight|fill.?light|led_current|colour.temp|cct' | grep -v auravibe | tail -n 150",
        ).lines().filter { it.isNotBlank() }

        return Result(watchWorked, values, torchSeen, buildRecipe(values, torchSeen), settingChanges, propChanges, logLines, raw)
    }

    /** Turn observed values into a recipe: light keys that were ever non-zero, with sliders mapped. */
    private fun buildRecipe(values: Map<String, List<String>>, torch: Boolean): VendorRecipe? {
        val back = hal.torch.backCameraId() ?: "0"
        val entries = mutableListOf<Pair<String, String>>()
        values.forEach { (key, seq) ->
            if (key.startsWith("android.")) return@forEach
            val isLight = VendorRecipe.LIGHT_HINTS.any { key.contains(it, true) }
            val isMode = key in modeKeys
            if (!isLight && !isMode) return@forEach
            val nonZero = seq.filter { v -> v.split(",").any { (it.toDoubleOrNull() ?: 0.0) != 0.0 } }
            if (nonZero.isEmpty() && !isMode) return@forEach
            val last = (nonZero.lastOrNull() ?: seq.last())
            val singles = seq.mapNotNull { if (it.contains(",")) null else it.toDoubleOrNull() }
            val value = when {
                singles.distinct().size >= 3 && (key.contains("temperature", true) || key.contains("cct", true)) ->
                    // The user slides from white to orange: last value = orange (tone 0), first = white.
                    "T(${fmt(singles.last())},${fmt(singles.first())})"
                singles.distinct().size >= 3 && listOf("strength", "current", "level", "duty").any { key.contains(it, true) } ->
                    "B(${fmt(singles.min())},${fmt(singles.max())})"
                else -> last
            }
            entries += key to value
        }
        if (entries.none { !it.first.let { k -> k in modeKeys } }) return null
        return VendorRecipe(back, torch, entries)
    }

    private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    fun report(r: Result): String = buildString {
        appendLine("AuraVibe — learned from vivo Camera")
        appendLine("Tag watch worked: ${r.watchWorked}")
        appendLine("Flash TORCH seen: ${r.torchSeen}")
        appendLine("Learned recipe: ${r.recipe?.channel ?: "(none)"}")
        appendLine("\n# Key value changes")
        r.keyValues.forEach { (k, v) -> appendLine("$k: ${v.take(30).joinToString(" → ")}") }
        appendLine("\n# Settings changes")
        r.settingChanges.forEach { appendLine("${it.first}: ${it.second} → ${it.third}") }
        appendLine("\n# Property changes")
        r.propChanges.take(60).forEach { appendLine("${it.first}: ${it.second} → ${it.third}") }
        appendLine("\n# Logcat (light related)")
        r.logLines.takeLast(80).forEach { appendLine(it) }
        appendLine("\n# Raw watch output (first 120 lines)")
        r.raw.lines().take(120).forEach { appendLine(it) }
    }
}
