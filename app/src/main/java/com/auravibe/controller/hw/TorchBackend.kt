package com.auravibe.controller.hw

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Torch strength API. Enumerates listed camera ids, their physical sub-cameras and hidden
 * numeric ids 0..15 (some vivo firmwares put the second Aura LED behind its own id).
 */
class TorchBackend(context: Context) {
    private val cm = context.getSystemService(CameraManager::class.java)
    private val last = HashMap<String, Int>()
    private var cache: List<TorchInfo>? = null

    data class TorchInfo(val id: String, val facing: String, val maxLevel: Int, val source: String)

    fun list(refresh: Boolean = false): List<TorchInfo> {
        if (!refresh) cache?.let { return it }
        val out = LinkedHashMap<String, TorchInfo>()
        val listed = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        fun probe(id: String, source: String) {
            if (out.containsKey(id)) return
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: return
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) return
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "external"
            }
            out[id] = TorchInfo(id, facing, c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1, source)
        }
        listed.forEach { probe(it, "listed") }
        listed.forEach { id ->
            runCatching { cm.getCameraCharacteristics(id).physicalCameraIds }.getOrNull()
                ?.forEach { probe(it, "physical of $id") }
        }
        (0..15).map { it.toString() }.filter { it !in listed }.forEach { probe(it, "hidden") }
        return out.values.toList().also { cache = it }
    }

    /** All vendor (non android.*) request + session key names for one camera. */
    fun vendorKeyNames(id: String): Set<String> = runCatching {
        val c = cm.getCameraCharacteristics(id)
        (c.availableCaptureRequestKeys.map { it.name } + c.availableSessionKeys.map { it.name })
            .filter { !it.startsWith("android.") }.toSet()
    }.getOrDefault(emptySet())

    /** Vendor keys that look light related, per listed camera id. */
    fun vendorKeys(): Map<String, List<String>> = runCatching {
        cm.cameraIdList.associateWith { id ->
            vendorKeyNames(id).filter { n -> VendorRecipe.LIGHT_HINTS.any { n.contains(it, ignoreCase = true) } }.sorted()
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    fun backCameraId(): String? = runCatching {
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
    }.getOrNull()

    fun defaultChannel(): String? = list().firstOrNull { it.facing == "back" }?.let { "cam:${it.id}" }

    /** fraction 0..1 → strength level; returns an error message or null. */
    fun set(channel: String, fraction: Float): String? {
        val id = channel.removePrefix("cam:")
        val max = list().firstOrNull { it.id == id }?.maxLevel ?: 1
        val level = if (fraction <= 0.001f) 0 else (fraction * max).toInt().coerceIn(1, max)
        if (last[id] == level) return null
        last[id] = level
        return runCatching {
            when {
                level == 0 -> cm.setTorchMode(id, false)
                max > 1 -> cm.turnOnTorchWithStrengthLevel(id, level)
                else -> cm.setTorchMode(id, true)
            }
        }.exceptionOrNull()?.let { e ->
            Log.w(TAG, "torch $id=$level failed", e); last.remove(id); "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun offAll() {
        last.keys.toList().forEach { id -> runCatching { cm.setTorchMode(id, false) } }
        last.clear()
    }

    companion object {
        private const val TAG = "TorchBackend"
    }
}
