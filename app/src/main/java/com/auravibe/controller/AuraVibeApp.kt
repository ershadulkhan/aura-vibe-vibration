package com.auravibe.controller

import android.app.Application
import com.auravibe.controller.data.SettingsRepo
import com.auravibe.controller.engine.PatternEngine
import com.auravibe.controller.hw.AuraHal
import com.auravibe.controller.hw.Vibes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Tiny manual service locator — no DI framework, keeps the APK and cold start small. */
class AuraVibeApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: SettingsRepo; private set
    lateinit var hal: AuraHal; private set
    lateinit var engine: PatternEngine; private set
    lateinit var vibes: Vibes; private set

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = SettingsRepo(this, scope)
        hal = AuraHal(this, repo)
        // Single-threaded lane so hardware writes never interleave.
        engine = PatternEngine(hal, CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)))
        vibes = Vibes(this)
    }

    companion object {
        lateinit var instance: AuraVibeApp; private set
    }
}
