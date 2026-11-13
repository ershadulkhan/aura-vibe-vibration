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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Drives the light through a minimal Camera2 session (tiny ImageReader, no preview) using a
 * [VendorRecipe]. The session stays open while an effect runs; "off" frames send a request
 * with the flash off and recipe keys zeroed, and the camera is closed after 2 s idle.
 * All state lives on one handler thread. Needs the CAMERA permission.
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
    private var want: Triple<VendorRecipe, Float, Float>? = null
    private var lastSent: String? = null
    private val closeRunnable = Runnable { closeNow() }

    @Volatile var lastError: String? = null; private set
    @Volatile var isOpen = false; private set

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun set(channel: String, fraction: Float, tone: Float) {
        val r = VendorRecipe.parse(channel) ?: run { lastError = "Bad recipe: $channel"; return }
        handler.post { update(r, fraction, tone) }
    }

    private fun update(r: VendorRecipe, f: Float, tone: Float) {
        want = Triple(r, f, tone)
        handler.removeCallbacks(closeRunnable)
        val same = openChannel == r.channel
        if (f <= 0.001f) {
            if (same) { send(); handler.postDelayed(closeRunnable, 2000) } else if (openChannel != null) closeNow()
            return
        }
        when {
            same && session != null -> send()
            same -> Unit // still opening; onConfigured will send
            else -> open(r)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(r: VendorRecipe) {
        closeNow()
        if (!hasPermission()) { lastError = "Camera permission not granted"; return }
        openChannel = r.channel
        lastError = null
        runCatching {
            cm.openCamera(r.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (openChannel != r.channel) { d.close(); return }
                    device = d
                    startSession(d, r)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (device == d) closeNow() }
                override fun onError(d: CameraDevice, error: Int) {
                    lastError = "Camera error $error (camera busy or blocked)"; d.close(); if (device == d || device == null) closeNow()
                }
            })
        }.onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}"; openChannel = null }
    }

    private fun chars(id: String) = cm.getCameraCharacteristics(id)

    private fun startSession(d: CameraDevice, r: VendorRecipe) = runCatching {
        val c = chars(r.cameraId)
        val size = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.minByOrNull { it.width * it.height }
        val rd = ImageReader.newInstance(size?.width ?: 320, size?.height ?: 240, ImageFormat.YUV_420_888, 2)
        rd.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        reader = rd
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(rd.surface)), executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (openChannel != r.channel) { s.close(); return }
                    session = s; isOpen = true; lastSent = null
                    send()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { lastError = "Camera session configure failed"; closeNow() }
            },
        )
        // Session-level vendor keys must be given when the session is created.
        val sessionKeyNames = c.availableSessionKeys.map { it.name }.toSet()
        val sessionEntries = r.entries.filter { it.first in sessionKeyNames }
        if (sessionEntries.isNotEmpty()) {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val (_, f, tone) = want ?: Triple(r, 1f, 0f)
            sessionEntries.forEach { (k, v) -> applyKey(b, c, k, VendorRecipe.resolve(v, f, tone)) }
            cfg.sessionParameters = b.build()
        }
        d.createCaptureSession(cfg)
    }.onFailure { lastError = "Session failed: ${it.message}"; Log.w(TAG, "session", it); closeNow() }

    private fun level(f: Float) = ceil(f * 6).toInt().coerceIn(1, 6)

    private fun send() {
        val (r, f, tone) = want ?: return
        val s = session ?: return
        val d = device ?: return
        val on = f > 0.001f
        val sig = "$on|" + r.entries.joinToString { VendorRecipe.resolve(it.second, f, tone) }
        if (sig == lastSent) return
        lastSent = sig
        runCatching {
            val c = chars(r.cameraId)
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(reader!!.surface)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, if (on && r.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            if (on && r.torch && Build.VERSION.SDK_INT >= 35) runCatching {
                val max = c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1
                if (max > 1) b.set(CaptureRequest.FLASH_STRENGTH_LEVEL, ceil(f * max).toInt().coerceIn(1, max))
            }
            r.entries.forEach { (key, v) ->
                val resolved = VendorRecipe.resolve(v, if (on) f else 1f, tone)
                applyKey(b, c, key, if (on) resolved else VendorRecipe.zero(resolved))
            }
            s.setRepeatingRequest(b.build(), null, handler)
        }.onFailure { lastError = "Request failed: ${it.message}"; lastSent = null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyKey(b: CaptureRequest.Builder, c: CameraCharacteristics, name: String, value: String) {
        val key = (c.availableCaptureRequestKeys + c.availableSessionKeys).firstOrNull { it.name == name }
        if (key == null) { lastError = "This camera has no key $name"; return }
        val k = key as CaptureRequest.Key<Any>
        val ok = VendorRecipe.javaValues(value).any { v -> runCatching { b.set(k, v) }.isSuccess }
        if (!ok) lastError = "Could not set $name = $value (type mismatch)"
    }

    private fun closeNow() {
        handler.removeCallbacks(closeRunnable)
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null; device = null; reader = null
        openChannel = null; lastSent = null; isOpen = false
    }

    /** Close and wait (briefly) so the torch API can use the camera right after. */
    fun close() {
        if (Looper.myLooper() == thread.looper) { closeNow(); return }
        val latch = CountDownLatch(1)
        handler.post { want = null; closeNow(); latch.countDown() }
        latch.await(800, TimeUnit.MILLISECONDS)
    }

    fun clearError() { lastError = null }

    companion object { private const val TAG = "SessionBackend" }
}
