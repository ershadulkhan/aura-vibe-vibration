package com.auravibe.controller

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.auravibe.controller.data.LightSettings
import com.auravibe.controller.data.LightStyle
import com.auravibe.controller.data.SettingsRepo
import com.auravibe.controller.engine.AuraMode
import com.auravibe.controller.engine.PatternEngine
import com.auravibe.controller.hw.AuraHal
import com.auravibe.controller.hw.Calibrator
import com.auravibe.controller.hw.Vibes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Tiny manual service locator — no DI framework, keeps the APK and cold start small. */
class AuraVibeApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: SettingsRepo; private set
    lateinit var hal: AuraHal; private set
    lateinit var engine: PatternEngine; private set
    lateinit var vibes: Vibes; private set
    lateinit var calibrator: Calibrator; private set
    private var unlockReceiver: BroadcastReceiver? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = SettingsRepo(this, scope)
        hal = AuraHal(this, repo)
        // Single-threaded lane so hardware writes never interleave.
        engine = PatternEngine(hal, CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)))
        vibes = Vibes(this)
        calibrator = Calibrator(hal, repo)
        scope.launch { engine.mode.collect { if (it == AuraMode.OFF) stopWatchingUnlock() } }
    }

    val lightOn get() = engine.mode.value != AuraMode.OFF

    /** Turn the light on with the user's current style (or a given override). */
    fun turnLightOn(l: LightSettings = repo.settings.value.light) {
        val timeout = repo.settings.value.autoOffMin.coerceAtLeast(1) * 60
        if (l.style == LightStyle.STEADY) engine.steady(l.brightness, l.temp, timeout)
        else engine.play(l.toAnim(timeout), l.brightness, l.temp)
    }

    fun turnLightOff() = engine.stop()

    /** Notification flash. "Infinite" keeps going until the phone is unlocked. */
    fun notificationFlash(l: LightSettings) {
        val anim = if (l.style == LightStyle.STEADY) l.copy(style = LightStyle.STROBE, speed = 0.7f).toAnim(30, if (l.infinite) 0 else l.count)
        else l.toAnim(if (l.infinite) 600 else 60)
        engine.play(anim, l.brightness, l.temp)
        if (anim.repeat == 0) watchUnlock()
    }

    private fun watchUnlock() {
        if (unlockReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { engine.stop() }
        }
        registerReceiver(r, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        unlockReceiver = r
    }

    private fun stopWatchingUnlock() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
    }

    suspend fun awaitSettings() = repo.settings.first()

    companion object {
        lateinit var instance: AuraVibeApp; private set
    }
}
