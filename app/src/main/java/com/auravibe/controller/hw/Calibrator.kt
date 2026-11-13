package com.auravibe.controller.hw

import com.auravibe.controller.data.SettingsRepo

/** Builds the list of light channels to try in the "Find my Orange Light" wizard, plus a text report. */
class Calibrator(private val hal: AuraHal, private val repo: SettingsRepo) {

    data class Candidate(val channel: String, val title: String, val detail: String)

    private val skip = listOf("backlight", "vibrator", "mmc", "charging", "kpd", "lcd", "button")
    private val hints = listOf("torch", "flash", "aura", "warm", "yellow", "amber", "red", "white", "mt-flash", "flashlight")

    fun candidates(): List<Candidate> {
        val out = mutableListOf<Candidate>()

        // Most promising on vivo: the camera HAL's own "soft light" (Aura) keys.
        val back = hal.torch.backCameraId()
        if (back != null) {
            VendorRecipe.curated(back, hal.torch.vendorKeyNames(back)).forEach { (title, r) ->
                out += Candidate(
                    r.channel, "Aura recipe: $title",
                    r.entries.joinToString(", ") { "${it.first.substringAfterLast('.')}=${it.second}" } + if (r.torch) " + flash" else "",
                )
            }
        }

        if (hal.shizuku.granted()) {
            val leds = hal.shizuku.scanLeds()
            val switches = leds.filter { it.isSwitch }.map { it.path }
            repo.update { it.copy(switchNodes = switches) }
            leds.filter { !it.isSwitch && skip.none { s -> it.name.contains(s, true) } }
                .sortedByDescending { n -> hints.any { n.name.contains(it, true) } }
                .forEach { n ->
                    out += Candidate(
                        n.path, "LED node ${n.name}",
                        "max ${n.max}" + (if (switches.isNotEmpty()) " · committed via ${switches.joinToString { it.substringAfterLast('/') }}" else "") +
                            if (!n.writable) " · may be read-only" else "",
                    )
                }
            if (hal.shizuku.hasMtkFlashlight()) {
                for (type in 0..1) for (ct in 0..1)
                    out += Candidate("mtk:$type:$ct", "MediaTek flashlight type $type, LED $ct", "flashlight_core torch interface")
            }
        }

        hal.torch.list(refresh = true).filter { it.facing == "back" }.forEach { t ->
            out += Candidate("cam:${t.id}", "Camera torch ${t.id}", "${t.source} · ${t.maxLevel} brightness levels")
        }
        return out
    }

    fun report(): String = buildString {
        appendLine("AuraVibe LED hardware report")
        appendLine("Shizuku: " + when { hal.shizuku.granted() -> "connected"; hal.shizuku.binderAlive() -> "not authorised"; else -> "not running" })
        appendLine("Camera permission: " + if (hal.session.hasPermission()) "granted" else "not granted")
        appendLine("Warm channel: ${repo.settings.value.warmNode.ifBlank { "(not set)" }}")
        appendLine("Cool channel: ${repo.settings.value.coolNode.ifBlank { "(default torch)" }}")
        appendLine("\n# Camera torches")
        hal.torch.list(refresh = true).forEach { appendLine("cam:${it.id}  ${it.facing}  maxLevel=${it.maxLevel}  (${it.source})") }
        appendLine("\n# Light-related vendor camera keys")
        hal.torch.vendorKeys().forEach { (cam, keys) -> keys.forEach { appendLine("cam $cam: $it") } }
        hal.torch.backCameraId()?.let { appendLine("(cam $it has ${hal.torch.vendorKeyNames(it).size} vendor keys in total)") }
        hal.session.lastError?.let { appendLine("\nLast camera-session error: $it") }
        if (hal.shizuku.granted()) {
            appendLine("\n# /sys/class/leds  (path | max_brightness | brightness | shell-writable)")
            val leds = hal.shizuku.scanLeds()
            if (leds.isEmpty()) appendLine("(empty — hidden from the shell by SELinux; needs root)")
            leds.forEach { appendLine("${it.path} | ${it.max} | ${it.current} | ${if (it.writable) "yes" else "no"}") }
            appendLine("\n# Other light interfaces")
            appendLine(hal.shizuku.otherInterfaces().ifBlank { "(none)" })
        } else appendLine("\n(sysfs not scanned — start Shizuku and authorise AuraVibe)")
    }
}
