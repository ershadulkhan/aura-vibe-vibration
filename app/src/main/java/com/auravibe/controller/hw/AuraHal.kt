package com.auravibe.controller.hw

import android.content.Context
import com.auravibe.controller.data.AuraBackend
import com.auravibe.controller.data.EngineSettings
import com.auravibe.controller.data.SettingsRepo
import java.time.LocalTime

/**
 * Hardware abstraction for the Aura light. A "channel" is either "cam:<id>" (torch API) or
 * an absolute sysfs LED directory (Shizuku). Warm and cool channels are mixed according
 * to colour temperature; with a single channel only brightness is controllable.
 */
class AuraHal(context: Context, private val repo: SettingsRepo) {
    val torch = TorchBackend(context)
    val shizuku = ShizukuBackend()

    @Volatile var isLit = false
        private set

    private fun channels(s: EngineSettings): Pair<String?, String?> {
        val warm = s.warmNode.ifBlank { null }
        val cool = s.coolNode.ifBlank { null }
        val allowed = { ch: String? ->
            ch != null && when (s.backend) {
                AuraBackend.AUTO -> true
                AuraBackend.CAMERA_TORCH -> ch.startsWith("cam:")
                AuraBackend.SHIZUKU_SYSFS -> ch.startsWith("/")
            }
        }
        val w = warm.takeIf { allowed(it) }
        val c = cool.takeIf { allowed(it) }
        if (w == null && c == null) {
            return if (s.backend == AuraBackend.SHIZUKU_SYSFS) null to null else null to torch.defaultChannel()
        }
        return w to c
    }

    fun effectiveTemp(s: EngineSettings): Float {
        if (!s.autoTemp) return s.colorTemp
        val h = LocalTime.now().hour
        return if (h >= 19 || h < 7) 0.15f else 0.85f
    }

    /** brightness 0..1, temp 0 (warm) .. 1 (cool). */
    fun apply(brightness: Float, temp: Float) {
        val s = repo.settings.value
        val (warm, cool) = channels(s)
        val b = brightness.coerceIn(0f, 1f)
        if (warm != null && cool != null && warm != cool) {
            val t = temp.coerceIn(0f, 1f)
            write(warm, b * minOf(1f, 2f * (1f - t)))
            write(cool, b * minOf(1f, 2f * t))
        } else {
            (warm ?: cool)?.let { write(it, b) }
        }
        isLit = b > 0f
    }

    private fun write(ch: String, f: Float) =
        if (ch.startsWith("cam:")) torch.set(ch, f) else shizuku.set(ch, f)

    fun off() {
        torch.offAll()
        shizuku.offAll()
        isLit = false
    }

    /** Free the shell process / camera handles once an effect finishes. */
    fun release() {
        off()
        shizuku.release()
    }

    fun describe(): String {
        val (w, c) = channels(repo.settings.value)
        return when {
            w == null && c == null -> "No light channel available"
            w != null && c != null && w != c -> "Dual-tone: warm=$w, cool=$c"
            else -> "Single channel: ${w ?: c} (brightness only)"
        }
    }
}
