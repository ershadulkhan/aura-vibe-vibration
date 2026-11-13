package com.auravibe.controller.hw

import android.content.Context
import com.auravibe.controller.data.SettingsRepo

/**
 * Mixes the warm (orange) and cool (white) channels and routes each to its backend.
 * temp: 0 = pure orange, 1 = pure white.  warm = b·(1−t), cool = b·t.
 */
class AuraHal(context: Context, private val repo: SettingsRepo) {
    val torch = TorchBackend(context)
    val shizuku = ShizukuBackend()
    val session = SessionBackend(context)

    @Volatile var isLit = false
        private set

    fun warmChannel(): String? = repo.settings.value.warmNode.ifBlank { null }
    fun coolChannel(): String? = repo.settings.value.coolNode.ifBlank { null } ?: torch.defaultChannel()
    val hasOrange get() = warmChannel() != null

    fun apply(brightness: Float, temp: Float) {
        val b = brightness.coerceIn(0f, 1f)
        val t = temp.coerceIn(0f, 1f)
        val warm = warmChannel()
        val cool = coolChannel()
        val values = LinkedHashMap<String, Float>()
        when {
            warm != null && cool != null && warm != cool -> { values[warm] = b * (1f - t); values[cool] = b * t }
            warm != null -> values[warm] = b
            cool != null -> values[cool] = b
        }
        write(values, checked = false)
        isLit = b > 0f
    }

    /** Light a single channel at full power (calibration). Returns an error/diagnostic message. */
    fun testChannel(channel: String, on: Boolean): String? = write(mapOf(channel to if (on) 1f else 0f), checked = true)

    private fun write(values: Map<String, Float>, checked: Boolean): String? {
        val errors = mutableListOf<String>()
        values.forEach { (ch, f) ->
            when {
                ch.startsWith("cam:") -> torch.set(ch, f)?.let(errors::add)
                ch.startsWith("sess:") -> { session.set(ch, f); session.lastError?.let(errors::add) }
            }
        }
        val shell = values.filterKeys { it.startsWith("/") || it.startsWith("mtk:") }
        if (shell.isNotEmpty()) {
            if (!shizuku.granted()) errors += "Shizuku not connected"
            else {
                val sw = repo.settings.value.switchNodes
                if (checked) shizuku.applyChecked(shell, sw)?.let(errors::add) else shizuku.apply(shell, sw)
            }
        }
        return errors.joinToString("\n").ifBlank { null }
    }

    fun off() {
        torch.offAll()
        val s = repo.settings.value
        val shellCh = listOfNotNull(s.warmNode, s.coolNode).filter { it.startsWith("/") || it.startsWith("mtk:") }
        if (shellCh.isNotEmpty() && shizuku.granted()) shizuku.apply(shellCh.associateWith { 0f }, s.switchNodes)
        session.close()
        isLit = false
    }

    fun release() {
        off()
        shizuku.release()
    }
}
