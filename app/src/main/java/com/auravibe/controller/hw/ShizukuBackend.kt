package com.auravibe.controller.hw

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Shell access through Shizuku (uid 2000, no root) for kernel light interfaces:
 *  - /sys/class/leds/<node>/brightness, committed with led:switch_N (vivo / MediaTek pattern)
 *  - /sys/class/flashlight_core/flashlight/flashlight_torch  ("type ct part level", MediaTek)
 */
class ShizukuBackend {
    private var shell: Process? = null
    private var stdin: BufferedWriter? = null
    private val maxCache = HashMap<String, Int>()
    private var lastBatch: String? = null

    data class LedNode(val path: String, val max: Int, val current: Int, val writable: Boolean) {
        val name get() = path.substringAfterLast('/')
        val isSwitch get() = name.contains("switch", ignoreCase = true)
    }

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = binderAlive() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission() {
        if (binderAlive() && !granted()) runCatching { Shizuku.requestPermission(REQ) }
    }

    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    /** Run a command, returning stdout+stderr. */
    fun exec(command: String): String {
        if (!granted()) return ""
        return runCatching {
            val p = newProcess(arrayOf("sh", "-c", "$command 2>&1"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrElse { Log.w(TAG, "exec failed", it); "exec failed: ${it.message}" }
    }

    fun scanLeds(): List<LedNode> {
        val script = """
            for d in /sys/class/leds/* ; do
              m=${'$'}(cat ${'$'}d/max_brightness 2>/dev/null); b=${'$'}(cat ${'$'}d/brightness 2>/dev/null)
              if [ -w ${'$'}d/brightness ]; then w=w; else w=r; fi
              echo "N|${'$'}d|${'$'}m|${'$'}b|${'$'}w"
            done
        """.trimIndent()
        return exec(script).lines().filter { it.startsWith("N|") }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 5 || p[1].endsWith("*")) null
            else LedNode(p[1], p[2].trim().toIntOrNull() ?: 255, p[3].trim().toIntOrNull() ?: 0, p[4].trim() == "w")
        }.onEach { maxCache[it.path] = it.max }
    }

    fun hasMtkFlashlight(): Boolean = exec("[ -e $MTK_TORCH ] && echo yes").contains("yes")

    /** Free-form listing of other light-related kernel interfaces for the report. */
    fun otherInterfaces(): String = exec(
        "ls -l /sys/class/flashlight_core/ /sys/class/flashlight_core/*/ 2>/dev/null; " +
            "ls /proc/driver/ 2>/dev/null | grep -i -E 'flash|led|torch|aura'; " +
            "ls -d /sys/devices/platform/*flash* /sys/devices/platform/*aura* /sys/devices/platform/*torch* 2>/dev/null",
    )

    private fun max(node: String) = maxCache[node]
        ?: exec("cat $node/max_brightness").trim().toIntOrNull()?.also { maxCache[node] = it } ?: 255

    private fun mtkLevel(f: Float) = if (f <= 0.001f) 0 else (f * MTK_MAX).toInt().coerceIn(1, MTK_MAX)

    /** Build one shell line applying all sysfs channels, then committing via switch nodes. */
    fun buildBatch(values: Map<String, Float>, switches: List<String>): String {
        val sb = StringBuilder()
        val anyOn = values.values.any { it > 0.001f }
        val leds = values.filterKeys { it.startsWith("/") }
        if (leds.isNotEmpty()) switches.forEach { sb.append("echo 0 > $it/brightness; ") }
        leds.forEach { (node, f) -> sb.append("echo ${(f.coerceIn(0f, 1f) * max(node)).toInt()} > $node/brightness; ") }
        if (leds.isNotEmpty() && anyOn) switches.forEach { sb.append("echo 1 > $it/brightness; ") }
        values.filterKeys { it.startsWith("mtk:") }.forEach { (ch, f) ->
            val (type, ct) = ch.removePrefix("mtk:").split(":").map { it.toIntOrNull() ?: 0 }
            sb.append("echo \"$type $ct 0 ${mtkLevel(f)}\" > $MTK_TORCH; ")
        }
        return sb.toString()
    }

    private fun ensureShell(): BufferedWriter? {
        stdin?.let { return it }
        if (!granted()) return null
        return runCatching {
            val p = newProcess(arrayOf("sh"))
            shell = p
            BufferedWriter(OutputStreamWriter(p.outputStream)).also { stdin = it }
        }.getOrNull()
    }

    /** Fast path for animations: persistent shell, deduplicated. */
    fun apply(values: Map<String, Float>, switches: List<String>) {
        val cmd = buildBatch(values, switches)
        if (cmd.isEmpty() || cmd == lastBatch) return
        lastBatch = cmd
        val w = ensureShell() ?: return
        runCatching { w.write(cmd + "\n"); w.flush() }.onFailure { release() }
    }

    /** Slow path for the calibration wizard: returns shell output (errors) for diagnostics. */
    fun applyChecked(values: Map<String, Float>, switches: List<String>): String? {
        lastBatch = null
        val out = exec(buildBatch(values, switches)).trim()
        return out.ifBlank { null }
    }

    fun release() {
        runCatching { stdin?.write("exit\n"); stdin?.flush() }
        runCatching { shell?.destroy() }
        stdin = null; shell = null; lastBatch = null
    }

    companion object {
        private const val TAG = "ShizukuBackend"
        const val REQ = 4207
        const val MTK_TORCH = "/sys/class/flashlight_core/flashlight/flashlight_torch"
        const val MTK_MAX = 6
    }
}
