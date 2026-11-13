package com.auravibe.controller.hw

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.Executor

/**
 * Tier 2: open a minimal Camera2 session (tiny ImageReader surface, no preview) with
 * FLASH_MODE_TORCH, optionally setting one vendor request key to 1. This is how a vendor
 * "fill light / CCT" switch could be reached if the firmware exposes it to apps.
 * On/off only. Needs the CAMERA permission.
 */
class SessionBackend(private val context: Context) {
    private val cm = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("aura-cam").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var openChannel: String? = null
    @Volatile var lastError: String? = null; private set

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun set(channel: String, fraction: Float) {
        if (fraction > 0.001f) open(channel) else close()
    }

    @SuppressLint("MissingPermission")
    private fun open(channel: String) {
        if (openChannel == channel) return
        close()
        if (!hasPermission()) { lastError = "Camera permission not granted"; return }
        openChannel = channel
        val parts = channel.removePrefix("sess:").split(":", limit = 2)
        val camId = parts[0]
        val keyName = parts.getOrNull(1)
        lastError = null
        runCatching {
            cm.openCamera(camId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (openChannel != channel) { d.close(); return }
                    device = d
                    startSession(d, camId, keyName)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (device == d) device = null }
                override fun onError(d: CameraDevice, error: Int) { lastError = "Camera error $error"; d.close() }
            })
        }.onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}"; openChannel = null }
    }

    private fun startSession(d: CameraDevice, camId: String, keyName: String?) = runCatching {
        val chars = cm.getCameraCharacteristics(camId)
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes?.minByOrNull { it.width * it.height }
        val r = ImageReader.newInstance(size?.width ?: 320, size?.height ?: 240, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        reader = r
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(r.surface)), executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    runCatching {
                        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(r.surface)
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        if (keyName != null) setVendorKey(b, chars, keyName)
                        s.setRepeatingRequest(b.build(), null, handler)
                    }.onFailure { lastError = "Request failed: ${it.message}" }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { lastError = "Session configure failed" }
            },
        )
        d.createCaptureSession(cfg)
    }.onFailure { lastError = "Session failed: ${it.message}"; Log.w(TAG, "session", it) }

    @Suppress("UNCHECKED_CAST")
    private fun setVendorKey(b: CaptureRequest.Builder, chars: CameraCharacteristics, name: String) {
        val key = (chars.availableCaptureRequestKeys + chars.availableSessionKeys).firstOrNull { it.name == name } ?: run {
            lastError = "Key $name not found"; return
        }
        val k = key as CaptureRequest.Key<Any>
        val ok = listOf<Any>(1, 1.toByte(), 1f, 1L, intArrayOf(1), byteArrayOf(1))
            .any { v -> runCatching { b.set(k, v) }.isSuccess }
        if (!ok) lastError = "Could not set $name (unknown type)"
    }

    fun close() {
        openChannel = null
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null; device = null; reader = null
    }

    companion object { private const val TAG = "SessionBackend" }
}
