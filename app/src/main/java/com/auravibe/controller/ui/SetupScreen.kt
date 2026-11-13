package com.auravibe.controller.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.data.AuraBackend
import com.auravibe.controller.data.VibePattern
import com.auravibe.controller.data.VibeUsage
import com.auravibe.controller.hw.ShizukuBackend
import com.auravibe.controller.hw.TorchBackend
import com.auravibe.controller.service.AuraNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Probe(
    val torches: List<TorchBackend.TorchInfo> = emptyList(),
    val vendorKeys: List<String> = emptyList(),
    val leds: List<ShizukuBackend.LedNode> = emptyList(),
    val platform: List<String> = emptyList(),
    val ran: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val app = AuraVibeApp.instance
    val s by app.repo.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val listenerOn = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val ringer = remember(tick) {
        when (ctx.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Ring"
        }
    }
    val shizukuAlive = remember(tick) { app.hal.shizuku.binderAlive() }
    val shizukuGranted = remember(tick) { app.hal.shizuku.granted() }
    var probe by remember { mutableStateOf(Probe()) }
    var scanning by remember { mutableStateOf(false) }

    fun runProbe() = scope.launch {
        scanning = true
        probe = withContext(Dispatchers.IO) {
            val sh = app.hal.shizuku
            Probe(app.hal.torch.list(), app.hal.torch.vendorKeys(),
                if (sh.granted()) sh.scan() else emptyList(),
                if (sh.granted()) sh.scanPlatform() else emptyList(), true)
        }
        scanning = false
    }

    fun flashTest(channel: String) = scope.launch(Dispatchers.IO) {
        val h = app.hal
        if (channel.startsWith("cam:")) h.torch.set(channel, 1f) else h.shizuku.set(channel, 1f)
        delay(1000)
        if (channel.startsWith("cam:")) h.torch.set(channel, 0f) else h.shizuku.set(channel, 0f)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Engine & hardware", fontSize = 28.sp)

        SectionCard {
            SectionTitle("Permissions")
            StatusRow("Notification access", listenerOn, "Grant") {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(ctx, AuraNotificationListener::class.java).flattenToString())
                ctx.safeStart(i, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            StatusRow("Battery: unrestricted", batteryOk, "Allow") {
                ctx.safeStart(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            Text(
                "On OriginOS also open App info → Battery → allow \"High background power consumption\" and enable Auto-start, otherwise iManager may kill the listener.",
                fontSize = 12.sp, color = Aura.Muted,
            )
            Button(
                onClick = { ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
                colors = ButtonDefaults.buttonColors(containerColor = Aura.CardHi, contentColor = Color.White),
            ) { Text("Open App info") }
        }

        SectionCard {
            SectionTitle("Engine", "Current ringer mode: $ringer")
            SwitchRow("Engine enabled", null, s.enabled) { v -> app.repo.update { it.copy(enabled = v) } }
            SwitchRow(
                "Only in Vibrate / Silent mode", "Off = also act in Ring mode",
                s.onlyInQuietModes,
            ) { v -> app.repo.update { it.copy(onlyInQuietModes = v) } }
            var cd by remember(s.cooldownMs) { mutableStateOf(s.cooldownMs.toFloat()) }
            LabeledSlider("Per-app cooldown", cd, 0f..15000f, "${"%.1f".format(cd / 1000f)} s", {
                cd = it; app.repo.update { st -> st.copy(cooldownMs = it.toInt()) }
            }, accent = Aura.Blue)
        }

        SectionCard {
            SectionTitle("Vibration attribute tester", "Put the phone in Vibrate or Silent mode, test each, keep the one you feel")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VibeUsage.entries.forEach { u ->
                    Chip(u.label + if (s.usage == u) " ✓" else "", s.usage == u, Aura.Blue) {
                        app.repo.update { it.copy(usage = u) }
                        app.vibes.vibrate(VibePattern.SHORT_DOUBLE, 0, u)
                    }
                }
            }
        }

        SectionCard {
            SectionTitle("Hardware probe & calibration", app.hal.describe())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuraBackend.entries.forEach { b -> Chip(b.label, s.backend == b) { app.repo.update { it.copy(backend = b) } } }
            }
            StatusRow(
                "Shizuku " + when { shizukuGranted -> "connected"; shizukuAlive -> "running, not authorised"; else -> "not running" },
                shizukuGranted, "Authorise",
            ) { app.hal.shizuku.requestPermission(); scope.launch { delay(1500); tick++ } }
            Button(
                onClick = { runProbe() }, enabled = !scanning,
                colors = ButtonDefaults.buttonColors(containerColor = Aura.Yellow, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (scanning) "Scanning…" else "Scan light hardware") }

            if (probe.ran) {
                Text("Tier 1 — camera torch channels", fontSize = 14.sp, color = Aura.Yellow)
                if (probe.torches.isEmpty()) Text("No flash-capable cameras reported", fontSize = 12.sp, color = Aura.Muted)
                probe.torches.forEach { t ->
                    ChannelRow("cam:${t.id}", "${t.facing} · ${t.maxLevel} strength levels", true, s.warmNode, s.coolNode,
                        onWarm = { ch -> app.repo.update { it.copy(warmNode = ch) } },
                        onCool = { ch -> app.repo.update { it.copy(coolNode = ch) } },
                        onTest = { flashTest(it) })
                }
                Text("Tier 2 — vendor camera keys", fontSize = 14.sp, color = Aura.Yellow)
                Text(
                    if (probe.vendorKeys.isEmpty()) "None exposed to third-party apps" else probe.vendorKeys.joinToString("\n"),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Aura.Muted,
                )
                Text("Tier 3 — sysfs LEDs via Shizuku", fontSize = 14.sp, color = Aura.Yellow)
                if (!shizukuGranted) Text("Authorise Shizuku to scan /sys/class/leds", fontSize = 12.sp, color = Aura.Muted)
                else if (probe.leds.isEmpty()) Text("No LED nodes visible", fontSize = 12.sp, color = Aura.Muted)
                probe.leds.forEach { n ->
                    ChannelRow(n.path, "max ${n.max} · now ${n.current} · " + if (n.writable) "writable" else "read-only for shell",
                        n.writable, s.warmNode, s.coolNode,
                        onWarm = { ch -> app.repo.update { it.copy(warmNode = ch) } },
                        onCool = { ch -> app.repo.update { it.copy(coolNode = ch) } },
                        onTest = { flashTest(it) })
                }
                if (probe.platform.isNotEmpty()) Text(probe.platform.joinToString("\n"), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Aura.Muted)
                Chip("Clear warm/cool mapping", false) { app.repo.update { it.copy(warmNode = "", coolNode = "") } }
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "●" else "○", color = if (ok) Aura.Ok else Aura.Bad, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (!ok) Chip(action, true, Aura.Blue, onAction)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelRow(
    channel: String, info: String, usable: Boolean, warm: String, cool: String,
    onWarm: (String) -> Unit, onCool: (String) -> Unit, onTest: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(channel, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Text(info, fontSize = 11.sp, color = if (usable) Aura.Muted else Aura.Bad)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Warm" + if (warm == channel) " ✓" else "", warm == channel, Aura.Warm) { onWarm(channel) }
            Chip("Cool" + if (cool == channel) " ✓" else "", cool == channel, Color.White) { onCool(channel) }
            Chip("Test 1 s", false) { onTest(channel) }
        }
    }
}

private fun Context.safeStart(vararg intents: Intent) {
    for (i in intents) {
        if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
