package com.auravibe.controller.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Context.store by preferencesDataStore("auravibe")

/**
 * DataStore-backed settings. Everything is mirrored into hot StateFlows so the
 * notification listener does an in-memory O(1) HashMap lookup per notification.
 */
class SettingsRepo(private val context: Context, private val scope: CoroutineScope) {

    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val quiet = booleanPreferencesKey("only_quiet")
        val usage = stringPreferencesKey("usage")
        val cooldown = intPreferencesKey("cooldown")
        val temp = floatPreferencesKey("temp")
        val autoTemp = booleanPreferencesKey("auto_temp")
        val bright = intPreferencesKey("bright")
        val backend = stringPreferencesKey("backend")
        val warm = stringPreferencesKey("warm_node")
        val cool = stringPreferencesKey("cool_node")
        val anim = stringPreferencesKey("anim")
        val notifAnim = stringPreferencesKey("notif_anim")
        val rules = stringPreferencesKey("rules")
    }

    val settings: StateFlow<EngineSettings> = context.store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, EngineSettings())

    val rules: StateFlow<Map<String, AppRule>> = context.store.data
        .map { p -> parseRules(p[K.rules]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun update(block: (EngineSettings) -> EngineSettings) = scope.launch {
        context.store.edit { p ->
            val s = block(p.toSettings())
            p[K.enabled] = s.enabled
            p[K.quiet] = s.onlyInQuietModes
            p[K.usage] = s.usage.name
            p[K.cooldown] = s.cooldownMs
            p[K.temp] = s.colorTemp
            p[K.autoTemp] = s.autoTemp
            p[K.bright] = s.brightnessLevel
            p[K.backend] = s.backend.name
            p[K.warm] = s.warmNode
            p[K.cool] = s.coolNode
            p[K.anim] = s.anim.toJson()
            p[K.notifAnim] = s.notifAnim.toJson()
        }
    }

    fun setRule(pkg: String, rule: AppRule) = scope.launch {
        context.store.edit { p ->
            val map = parseRules(p[K.rules]).toMutableMap()
            if (rule.active || rule.pattern != VibePattern.SHORT_DOUBLE) map[pkg] = rule else map.remove(pkg)
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v.toJson()) }
            p[K.rules] = o.toString()
        }
    }

    private fun Preferences.toSettings() = EngineSettings(
        enabled = this[K.enabled] ?: true,
        onlyInQuietModes = this[K.quiet] ?: true,
        usage = enumOr(this[K.usage], VibeUsage.ALARM),
        cooldownMs = this[K.cooldown] ?: 3000,
        colorTemp = this[K.temp] ?: 0.5f,
        autoTemp = this[K.autoTemp] ?: false,
        brightnessLevel = this[K.bright] ?: 3,
        backend = enumOr(this[K.backend], AuraBackend.AUTO),
        warmNode = this[K.warm] ?: "",
        coolNode = this[K.cool] ?: "",
        anim = this[K.anim]?.let(::animFromJson) ?: AnimSpec(),
        notifAnim = this[K.notifAnim]?.let(::animFromJson) ?: EngineSettings().notifAnim,
    )

    private fun parseRules(raw: String?): Map<String, AppRule> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap { o.keys().forEach { k -> put(k, AppRule.fromJson(o.getJSONObject(k))) } }
        }.getOrDefault(emptyMap())
    }

    private fun AnimSpec.toJson() = JSONObject()
        .put("p", pattern.name).put("r", repeat).put("u", rampUpMs)
        .put("h", holdMs).put("d", rampDownMs).put("t", timeoutSec).toString()

    private fun animFromJson(s: String): AnimSpec = runCatching {
        val o = JSONObject(s)
        AnimSpec(
            pattern = enumOr(o.optString("p"), LightPattern.BREATHE),
            repeat = o.optInt("r", 3), rampUpMs = o.optInt("u", 600), holdMs = o.optInt("h", 300),
            rampDownMs = o.optInt("d", 600), timeoutSec = o.optInt("t", 60),
        )
    }.getOrDefault(AnimSpec())

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(def)
}
