package com.auravibe.controller.hw

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Tier 1: Android 13+ torch strength API. Each flash-capable camera ID is exposed as one
 * channel ("cam:<id>"). Some multi-LED modules expose a second ID for the second LED
 * colour; the probe lets the user map them to Warm / Cool.
 */
class TorchBackend(context: Context) {
    private val cm = context.getSystemService(CameraManager::class.java)
    private val last = HashMap<String, Int>()

    data class TorchInfo(val id: String, val facing: String, val maxLevel: Int, val defaultLevel: Int)

    fun list(): List<TorchInfo> = runCatching {
        cm.cameraIdList.mapNotNull { id ->
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) return@mapNotNull null
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "external"
            }
            TorchInfo(
                id, facing,
                c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1,
                c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: 1,
            )
        }
    }.getOrElse { Log.w(TAG, "torch list failed", it); emptyList() }

    /** Tier 2 probe: vendor-specific characteristic keys that look flash / aura related. */
    fun vendorKeys(): List<String> = runCatching {
        cm.cameraIdList.flatMap { id ->
            cm.getCameraCharacteristics(id).keys
                .map { it.name }
                .filter { n -> VENDOR_HINTS.any { n.contains(it, ignoreCase = true) } && !n.startsWith("android.") }
                .map { "cam $id → $it" }
        }.distinct()
    }.getOrDefault(emptyList())

    fun defaultChannel(): String? =
        list().firstOrNull { it.facing == "back" }?.let { "cam:${it.id}" }

    /** fraction 0..1 → torch strength level; 0 turns the torch off. */
    fun set(channel: String, fraction: Float) {
        val id = channel.removePrefix("cam:")
        val info = list().firstOrNull { it.id == id } ?: return
        val level = if (fraction <= 0.001f) 0 else (fraction * info.maxLevel).toInt().coerceIn(1, info.maxLevel)
        if (last[id] == level) return
        last[id] = level
        runCatching {
            when {
                level == 0 -> cm.setTorchMode(id, false)
                info.maxLevel > 1 -> cm.turnOnTorchWithStrengthLevel(id, level)
                else -> cm.setTorchMode(id, true)
            }
        }.onFailure { Log.w(TAG, "torch set $id=$level failed", it); last.remove(id) }
    }

    fun offAll() {
        last.keys.toList().forEach { id -> runCatching { cm.setTorchMode(id, false) } }
        last.clear()
    }

    companion object {
        private const val TAG = "TorchBackend"
        private val VENDOR_HINTS = listOf("flash", "torch", "aura", "led", "vivo", "softlight", "ring")
    }
}
