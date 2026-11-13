package com.auravibe.controller.engine

import com.auravibe.controller.data.AnimSpec
import com.auravibe.controller.data.LightPattern
import com.auravibe.controller.hw.AuraHal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class AuraMode { OFF, ON, PULSE }

/**
 * Short-lived coroutine animator. Exactly one job runs at a time; when it finishes or is
 * cancelled the light is switched off and the hardware handles are released.
 */
class PatternEngine(private val hal: AuraHal, private val scope: CoroutineScope) {
    private var job: Job? = null
    private val _mode = MutableStateFlow(AuraMode.OFF)
    val mode: StateFlow<AuraMode> = _mode

    /** Steady light at [peak] with a safety timeout. */
    fun steady(peak: Float, temp: Float, timeoutSec: Int) = start(AuraMode.ON) {
        hal.apply(peak, temp)
        delay(timeoutSec.coerceAtLeast(1) * 1000L)
    }

    /** Update a steady light live (slider drags) without restarting the timeout. */
    fun adjust(peak: Float, temp: Float) {
        if (_mode.value == AuraMode.ON) scope.launch { hal.apply(peak, temp) }
    }

    fun play(spec: AnimSpec, peak: Float, temp: Float, mode: AuraMode = AuraMode.PULSE) = start(mode) {
        val floor = minOf(0.2f, peak)
        var i = 0
        while (spec.repeat == 0 || i < spec.repeat) {
            when (spec.pattern) {
                LightPattern.BREATHE -> {
                    ramp(floor, peak, spec.rampUpMs, temp); hal.apply(peak, temp); delay(spec.holdMs.toLong())
                    ramp(peak, floor, spec.rampDownMs, temp); hal.apply(0f, temp); delay(spec.holdMs / 2L)
                }
                LightPattern.INVERSE_BREATHE -> {
                    hal.apply(peak, temp); delay(spec.holdMs / 2L)
                    ramp(peak, floor, spec.rampDownMs, temp); delay(spec.holdMs.toLong())
                    ramp(floor, peak, spec.rampUpMs, temp)
                }
                LightPattern.STROBE -> {
                    hal.apply(peak, temp); delay(spec.holdMs.toLong())
                    hal.apply(0f, temp); delay(spec.rampDownMs.toLong())
                }
                LightPattern.HEARTBEAT -> {
                    repeat(2) { hal.apply(peak, temp); delay(110); hal.apply(0f, temp); delay(140) }
                    delay((spec.holdMs + spec.rampDownMs).toLong())
                }
            }
            i++
        }
    }.also { safety(spec.timeoutSec) }

    private var safetyJob: Job? = null
    private fun safety(sec: Int) {
        safetyJob?.cancel()
        val target = job
        safetyJob = scope.launch {
            delay(sec.coerceAtLeast(1) * 1000L)
            if (job === target) stop()
        }
    }

    private suspend fun ramp(from: Float, to: Float, ms: Int, temp: Float) {
        val steps = (ms / FRAME_MS).coerceAtLeast(1)
        for (s in 1..steps) {
            val t = s / steps.toFloat()
            // ease-in-out so the eye perceives a smooth breathe
            val e = t * t * (3 - 2 * t)
            hal.apply(from + (to - from) * e, temp)
            delay(FRAME_MS.toLong())
        }
    }

    private fun start(mode: AuraMode, block: suspend () -> Unit): Job {
        job?.cancel()
        safetyJob?.cancel()
        _mode.value = mode
        lateinit var self: Job
        self = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                withTimeoutOrNull(MAX_RUN_MS) { block() }
            } finally {
                withContext(NonCancellable) {
                    // Only clean up if no newer effect has replaced us.
                    if (job === self) {
                        hal.release()
                        job = null
                        _mode.value = AuraMode.OFF
                    }
                }
            }
        }
        job = self
        self.start()
        return self
    }

    fun stop() {
        job?.cancel()
        safetyJob?.cancel()
        job = null
        scope.launch { hal.release() }
        _mode.value = AuraMode.OFF
    }

    companion object {
        private const val FRAME_MS = 40
        private const val MAX_RUN_MS = 30 * 60 * 1000L // absolute hard cap: 30 min
    }
}
