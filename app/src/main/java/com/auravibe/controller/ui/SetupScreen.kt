package com.auravibe.controller.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.data.VibeUsage
import com.auravibe.controller.service.AuraNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val app = AuraVibeApp.instance
    val s by app.repo.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var wizard by rememberSaveable { mutableStateOf(false) }
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

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Setup", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        SectionCard {
            SectionTitle("1. Let AuraVibe see notifications")
            StatusRow("Notification access", listenerOn, "Turn on") {
                ctx.safeStart(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(ctx, AuraNotificationListener::class.java).flattenToString(),
                    ),
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                )
            }
            StatusRow("Battery: unrestricted", batteryOk, "Allow") {
                ctx.safeStart(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            Text(
                "If Android says \"Restricted setting\": App info → ⋮ → Allow restricted settings.\nOn OriginOS also allow \"High background power consumption\" and Auto-start in App info.",
                fontSize = 12.sp, color = Aura.Muted,
            )
            BigButton("Open App info", Aura.CardHi, Color.White) {
                ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }

        SectionCard {
            SectionTitle("2. Orange light", "The orange LED needs Shizuku on most phones")
            StatusRow(
                "Shizuku " + when { shizukuGranted -> "connected"; shizukuAlive -> "running — tap Allow"; else -> "not running" },
                shizukuGranted, if (shizukuAlive) "Allow" else "Get it",
            ) {
                if (shizukuAlive) { app.hal.shizuku.requestPermission(); scope.launch { delay(1500); tick++ } }
                else ctx.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
            StatusRow("Orange light: " + (s.warmNode.ifBlank { "not found yet" }), s.warmNode.isNotBlank(), "Find it") { wizard = true }
            Text("White light: " + (s.coolNode.ifBlank { "default camera flash" }), fontSize = 13.sp, color = Aura.Muted, fontFamily = FontFamily.Monospace)
            BigButton("🟠 Fix / Calibrate Orange Light", Aura.Warm) { wizard = true }
            BigButton("📋 Copy LED Hardware Report", Aura.CardHi, Color.White) { copyReport(ctx) }
            if (s.warmNode.isNotBlank() || s.coolNode.isNotBlank()) {
                BigButton("Reset light setup", Aura.CardHi, Aura.Bad) { app.repo.update { it.copy(warmNode = "", coolNode = "") } }
            }
        }

        VendorLab()

        SectionCard {
            SectionTitle("3. Make vibration get through", "Phone is in $ringer mode. Switch to Vibrate, tap each, keep the one you feel.")
            VibeUsage.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { u ->
                        ChoiceTile("📳", u.label, if (s.usage == u) "In use ✓" else "Tap to test", s.usage == u, Aura.Blue, Modifier.weight(1f)) {
                            app.repo.update { it.copy(usage = u) }
                            app.vibes.vibrate(s.vibe, u)
                        }
                    }
                }
            }
        }

        SectionCard {
            SectionTitle("More options")
            SwitchRow("AuraVibe is active", "Master switch for all alerts", s.enabled) { v -> app.repo.update { it.copy(enabled = v) } }
            SwitchRow("Only in Vibrate / Silent mode", "Off = also alert when the phone rings normally", s.onlyInQuietModes) { v ->
                app.repo.update { it.copy(onlyInQuietModes = v) }
            }
            var cd by remember(s.cooldownMs) { mutableStateOf(s.cooldownMs.toFloat()) }
            FieldLabel("Quiet time between alerts from the same app")
            AccentSlider(cd, { cd = it }, { app.repo.update { st -> st.copy(cooldownMs = cd.toInt()) } }, 0f..15000f, accent = Aura.Blue)
            SliderLabels("None", "15 s", "%.0f s".format(cd / 1000f))
            var off by remember(s.autoOffMin) { mutableStateOf(s.autoOffMin.toFloat()) }
            FieldLabel("Auto turn-off for the light (safety)")
            AccentSlider(off, { off = it }, { app.repo.update { st -> st.copy(autoOffMin = off.toInt()) } }, 1f..60f)
            SliderLabels("1 min", "60 min", "${off.toInt()} min")
        }
        Box(Modifier.padding(bottom = 8.dp))
    }
    if (wizard) OrangeWizard { wizard = false }
}

@Composable
private fun StatusRow(title: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✅" else "⚠️", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (!ok) Chip(action, true, Aura.Blue, onAction)
    }
}

fun Context.safeStart(vararg intents: Intent) {
    for (i in intents) {
        if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** Lets the user try any camera vendor key combination without a new app build. */
@Composable
private fun VendorLab() {
    val app = AuraVibeApp.instance
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var cam by rememberSaveable { mutableStateOf(app.hal.torch.backCameraId() ?: "0") }
    var keys by rememberSaveable { mutableStateOf("vivo.control.isSoftLight=1") }
    var torch by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val camPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    fun channel() = com.auravibe.controller.hw.VendorRecipe(cam.trim(), torch, keys.split(";", "\n").mapNotNull { e ->
        val i = e.indexOf('='); if (i <= 0) null else e.substring(0, i).trim() to e.substring(i + 1).trim()
    }).channel

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle("🧪 Vendor Key Lab", "Advanced: try any camera key for the orange light") }
            Chip(if (open) "Hide" else "Open", open, Aura.Yellow) { open = !open }
        }
        if (open) {
            Text("One key=value per line. Value can be a number or a list like 0,3. Use L for brightness (1-6) and K for colour (2700 warm … 6500 white).",
                fontSize = 12.sp, color = Aura.Muted)
            androidx.compose.material3.OutlinedTextField(cam, { cam = it }, label = { Text("Camera id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(keys, { keys = it }, label = { Text("Keys") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White))
            SwitchRow("Also request the normal flash (torch)", null, torch, Aura.Yellow) { torch = it }
            BigButton("▶️ Test for 3 seconds", Aura.Yellow) {
                if (!app.hal.session.hasPermission()) { camPerm.launch(android.Manifest.permission.CAMERA); return@BigButton }
                val ch = channel()
                app.turnLightOff()
                status = "Testing…"
                scope.launch {
                    app.hal.testChannel(ch, true)
                    delay(3000)
                    status = app.hal.session.lastError?.let { "⚠️ $it" } ?: "Sent without errors. Did the orange light turn on?"
                    app.hal.testChannel(ch, false)
                }
            }
            status?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("⚠️")) Aura.Bad else Aura.Muted) }
            BigButton("🟠 Save as my Orange Light", Aura.CardHi, Aura.Warm) {
                val ch = channel()
                app.repo.update { it.copy(warmNode = ch) }
                status = "Saved as orange light."
            }
            Text("Key names: tap Copy LED Hardware Report to see the light-related keys your camera has.", fontSize = 11.sp, color = Aura.Muted)
        }
    }
}
