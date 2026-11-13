package com.auravibe.controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.data.AnimSpec
import com.auravibe.controller.data.LightPattern
import com.auravibe.controller.engine.AuraMode

@Composable
fun StudioScreen() {
    val app = AuraVibeApp.instance
    val s by app.repo.settings.collectAsStateWithLifecycle()
    val mode by app.engine.mode.collectAsStateWithLifecycle()

    // Local drag state so sliders feel instant; persisted on release.
    var temp by remember(s.colorTemp) { mutableStateOf(s.colorTemp) }
    var level by remember(s.brightnessLevel) { mutableStateOf(s.brightnessLevel) }
    val effTemp = if (s.autoTemp) app.hal.effectiveTemp(s) else temp

    fun relight(m: AuraMode = mode) {
        val cur = app.repo.settings.value
        when (m) {
            AuraMode.ON -> app.engine.steady(level / 5f, if (cur.autoTemp) app.hal.effectiveTemp(cur) else temp, cur.anim.timeoutSec)
            AuraMode.PULSE -> app.engine.play(cur.anim, level / 5f, if (cur.autoTemp) app.hal.effectiveTemp(cur) else temp)
            AuraMode.OFF -> app.engine.stop()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Aura Light Studio", fontSize = 28.sp)

        AuraPreview(on = mode != AuraMode.OFF, temp = effTemp, level = level)

        SectionCard {
            PillSelector(
                options = listOf("Aura light on", "Pulse mode", "Off"),
                selected = when (mode) { AuraMode.ON -> 0; AuraMode.PULSE -> 1; AuraMode.OFF -> 2 },
                onSelect = { relight(listOf(AuraMode.ON, AuraMode.PULSE, AuraMode.OFF)[it]) },
            )
            TempSlider(
                value = temp, auto = s.autoTemp,
                onAuto = { a -> app.repo.update { it.copy(autoTemp = a) } },
                onChange = { temp = it; app.engine.adjust(level / 5f, it) },
                onDone = { app.repo.update { it.copy(colorTemp = temp) }; if (mode == AuraMode.PULSE) relight() },
            )
            BrightnessSlider(
                level = level,
                onChange = { level = it; app.engine.adjust(it / 5f, effTemp) },
                onDone = { app.repo.update { it.copy(brightnessLevel = level) }; if (mode == AuraMode.PULSE) relight() },
            )
            Text(app.hal.describe(), fontSize = 12.sp, color = Aura.Muted)
        }

        SectionCard {
            SectionTitle("Pulse pattern", "Used by Pulse mode in the Studio")
            AnimEditor(s.anim) { spec -> app.repo.update { it.copy(anim = spec) } }
        }

        SectionCard {
            SectionTitle("Notification flash", "Played when a notification from an Aura-enabled app arrives")
            AnimEditor(s.notifAnim) { spec -> app.repo.update { it.copy(notifAnim = spec) } }
            Button(
                onClick = { app.engine.play(app.repo.settings.value.notifAnim, level / 5f, effTemp) },
                colors = ButtonDefaults.buttonColors(containerColor = Aura.Yellow, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Live test notification flash") }
        }
    }
}

@Composable
private fun AuraPreview(on: Boolean, temp: Float, level: Int) {
    val c = tempColor(temp)
    Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(160.dp)) {
            val r = size.minDimension / 2
            if (on) {
                drawCircle(
                    Brush.radialGradient(listOf(c.copy(alpha = 0.15f + 0.12f * level), Color.Transparent), center, r),
                    r, center,
                )
            }
            drawCircle(Color(0xFF2A2A2D), r * 0.62f, center)
            drawCircle(if (on) c.copy(alpha = 0.4f + 0.12f * level) else Color(0xFF3A3A3E), r * 0.55f, center, style = Stroke(width = r * 0.12f))
            drawCircle(Color(0xFF111113), r * 0.32f, center)
            drawCircle(Color(0xFF26262A), r * 0.16f, center + Offset(r * 0.05f, -r * 0.05f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimEditor(spec: AnimSpec, onChange: (AnimSpec) -> Unit) {
    var local by remember(spec) { mutableStateOf(spec) }
    fun push(n: AnimSpec) { local = n; onChange(n) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LightPattern.entries.forEach { p -> Chip(p.label, local.pattern == p) { push(local.copy(pattern = p)) } }
    }
    LabeledSlider(
        "Repeat / blink count", local.repeat.toFloat(), 0f..20f,
        if (local.repeat == 0) "Continuous" else "${local.repeat}×", { push(local.copy(repeat = it.toInt())) }, steps = 19,
    )
    LabeledSlider("Ramp-up (fade in)", local.rampUpMs.toFloat(), 100f..5000f, "${local.rampUpMs} ms", { push(local.copy(rampUpMs = it.toInt())) })
    LabeledSlider("Hold (peak / valley)", local.holdMs.toFloat(), 100f..3000f, "${local.holdMs} ms", { push(local.copy(holdMs = it.toInt())) })
    LabeledSlider("Ramp-down (fade out)", local.rampDownMs.toFloat(), 100f..5000f, "${local.rampDownMs} ms", { push(local.copy(rampDownMs = it.toInt())) })
    LabeledSlider(
        "Safety cutoff", local.timeoutSec.toFloat(), 5f..600f,
        if (local.timeoutSec >= 60) "${local.timeoutSec / 60} min ${local.timeoutSec % 60} s" else "${local.timeoutSec} s",
        { push(local.copy(timeoutSec = it.toInt())) }, accent = Aura.Bad,
    )
}
