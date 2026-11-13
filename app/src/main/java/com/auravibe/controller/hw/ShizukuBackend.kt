package com.auravibe.controller.hw

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Tier 3: write LED driver nodes in /sys through a Shizuku shell (uid 2000, no root).
 *
 * NOTE: on most production kernels /sys/class/leds/<x>/brightness is writable only by
 * root/system. The scanner reports whether each node is writable from the shell user so
 * the user can see what the device actually allows.
 */
class ShizukuBackend {
    private var shell: Process? = null
    private var stdin: BufferedWriter? = null
    private val maxCache = HashMap<String, Int>()
    private val last = HashMap<String, Int>()

    data class LedNode(val path: String, val max: Int, val current: Int, val writable: Boolean)

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = binderAlive() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission() {
        if (binderAlive() && !granted()) runCatching { Shizuku.requestPermission(REQ) }
    }

    /** Shizuku 13 hides newProcess; it is still the supported way to run shell commands without an AIDL user service. */
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    fun exec(command: String): String {
        if (!granted()) return ""
        return runCatching {
            val p = newProcess(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrElse { Log.w(TAG, "exec failed", it); "" }
    }

    fun scan(): List<LedNode> {
        val script = """
            for d in /sys/class/leds/* ; do
              m=${'$'}(cat ${'$'}d/max_brightness 2>/dev/null); b=${'$'}(cat ${'$'}d/brightness 2>/dev/null)
              if [ -w ${'$'}d/brightness ]; then w=w; else w=r; fi
              echo "${'$'}d|${'$'}m|${'$'}b|${'$'}w"
            done
        """.trimIndent()
        return exec(script).lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 4) null
            else LedNode(p[0], p[1].trim().toIntOrNull() ?: 255, p[2].trim().toIntOrNull() ?: 0, p[3].trim() == "w")
        }.onEach { maxCache[it.path] = it.max }
    }

    /** Extra paths worth eyeballing (platform devices that mention flash/aura). */
    fun scanPlatform(): List<String> =
        exec("ls -d /sys/devices/platform/*flash* /sys/devices/platform/*aura* /sys/devices/platform/*torch* 2>/dev/null")
            .lines().filter { it.isNotBlank() }

    private fun ensureShell(): BufferedWriter? {
        stdin?.let { return it }
        if (!granted()) return null
        return runCatching {
            val p = newProcess(arrayOf("sh"))
            shell = p
            BufferedWriter(OutputStreamWriter(p.outputStream)).also { stdin = it }
        }.getOrNull()
    }

    /** fraction 0..1 written as integer brightness into <node>/brightness via a persistent shell. */
    fun set(node: String, fraction: Float) {
        val max = maxCache[node] ?: exec("cat $node/max_brightness").trim().toIntOrNull()?.also { maxCache[node] = it } ?: 255
        val v = (fraction.coerceIn(0f, 1f) * max).toInt()
        if (last[node] == v) return
        last[node] = v
        val w = ensureShell() ?: return
        runCatching { w.write("echo $v > $node/brightness\n"); w.flush() }
            .onFailure { release() }
    }

    fun offAll() {
        last.keys.toList().forEach { set(it, 0f) }
    }

    fun release() {
        runCatching { stdin?.write("exit\n"); stdin?.flush() }
        runCatching { shell?.destroy() }
        stdin = null; shell = null; last.clear()
    }

    companion object {
        private const val TAG = "ShizukuBackend"
        const val REQ = 4207
    }
}
