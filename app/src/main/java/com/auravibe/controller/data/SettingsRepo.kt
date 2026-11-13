package com.auravibe.controller.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

/** DataStore-backed settings mirrored into hot StateFlows (O(1) lookups from the listener). */
class SettingsRepo(private val context: Context, private val scope: CoroutineScope) {

    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val quiet = booleanPreferencesKey("only_quiet")
        val usage = stringPreferencesKey("usage")
        val cooldown = intPreferencesKey("cooldown")
        val autoOff = intPreferencesKey("auto_off_min")
        val warm = stringPreferencesKey("warm_node")
        val cool = stringPreferencesKey("cool_node")
        val switches = stringPreferencesKey("switch_nodes")
        val light = stringPreferencesKey("light_v2")
        val vibe = stringPreferencesKey("vibe_v2")
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
            p[K.autoOff] = s.autoOffMin
            p[K.warm] = s.warmNode
            p[K.cool] = s.coolNode
            p[K.switches] = s.switchNodes.joinToString("\n")
            p[K.light] = s.light.toJson()
            p[K.vibe] = s.vibe.toJson()
        }
    }

    fun updateLight(block: (LightSettings) -> LightSettings) = update { it.copy(light = block(it.light)) }
    fun updateVibe(block: (VibeSettings) -> VibeSettings) = update { it.copy(vibe = block(it.vibe)) }

    fun setRule(pkg: String, rule: AppRule) = scope.launch {
        context.store.edit { p ->
            val map = parseRules(p[K.rules]).toMutableMap()
            if (rule.active || rule.custom) map[pkg] = rule else map.remove(pkg)
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
        autoOffMin = this[K.autoOff] ?: 10,
        warmNode = this[K.warm] ?: "",
        coolNode = this[K.cool] ?: "",
        switchNodes = this[K.switches]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
        light = LightSettings.fromJson(this[K.light]),
        vibe = VibeSettings.fromJson(this[K.vibe]),
    )

    private fun parseRules(raw: String?): Map<String, AppRule> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap { o.keys().forEach { k -> put(k, AppRule.fromJson(o.getJSONObject(k))) } }
        }.getOrDefault(emptyMap())
    }
}
