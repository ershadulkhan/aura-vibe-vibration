package com.auravibe.controller.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.data.LightSettings
import com.auravibe.controller.data.LightStyle
import com.auravibe.controller.data.VibeSettings
import com.auravibe.controller.data.VibeStyle
import com.auravibe.controller.engine.AuraMode

@Composable
fun HomeScreen() {
    val app = AuraVibeApp.instance
    val s by app.repo.settings.collectAsStateWithLifecycle()
    val mode by app.engine.mode.collectAsStateWithLifecycle()
    val on = mode != AuraMode.OFF
    var wizard by rememberSaveable { mutableStateOf(false) }
    var learn by rememberSaveable { mutableStateOf(false) }

    // Local copy so sliders feel instant; saved on release.
    var light by remember(s.light) { mutableStateOf(s.light) }

    fun save(l: LightSettings, relight: Boolean = false) {
        light = l
        app.repo.updateLight { l }
        if (relight && app.lightOn) app.turnLightOn(l)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroButton(on, light.temp) { if (on) app.turnLightOff() else app.turnLightOn(light) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("🟠", "Pure Orange", 0f), Triple("🌤️", "Warm Mix", 0.35f), Triple("⚪", "Pure White", 1f)).forEach { (e, t, v) ->
                ChoiceTile(e, t, null, on && light.temp == v, tempColor(v), Modifier.weight(1f)) {
                    val l = light.copy(temp = v); save(l); app.turnLightOn(l)
                }
            }
        }

        if (!app.hal.hasOrange) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Aura.Warm.copy(alpha = 0.15f))
                    .clickable { learn = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🟠", fontSize = 22.sp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Orange light not set up yet", fontWeight = FontWeight.SemiBold)
                    Text("Tap to learn it from the vivo Camera app", fontSize = 12.sp, color = Aura.Muted)
                }
                Text("›", fontSize = 26.sp, color = Aura.Warm)
            }
        }

        LightCard(light, on, onLive = { l ->
            light = l
            if (app.engine.mode.value == AuraMode.ON) app.engine.adjust(l.brightness, l.temp)
        }, onSave = { l, relight -> save(l, relight) }, onWizard = { wizard = true }, onLearn = { learn = true })

        VibeCard(s.vibe, onSave = { v -> app.repo.updateVibe { v } }, onTest = { v -> app.vibes.vibrate(v, app.repo.settings.value.usage) })
    }
    if (wizard) OrangeWizard { wizard = false }
    if (learn) LearnFromCamera { learn = false }
}

@Composable
private fun HeroButton(on: Boolean, temp: Float, onClick: () -> Unit) {
    val c = tempColor(temp)
    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
        0.75f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(230.dp).clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(230.dp)) {
                val r = size.minDimension / 2
                if (on) drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f * pulse), Color.Transparent), center, r), r, center)
                drawCircle(if (on) c else Color(0xFF26262A), r * 0.62f, center)
                drawCircle(if (on) Color.White.copy(alpha = 0.6f) else Color(0xFF3A3A3E), r * 0.62f, center, style = Stroke(width = 6f))
                drawCircle(if (on) Color.White.copy(alpha = 0.35f) else Color(0xFF3A3A3E), r * 0.36f, center, style = Stroke(width = 10f))
            }
            Text("⏻", fontSize = 54.sp, color = if (on) Color.Black.copy(alpha = 0.75f) else Color.White)
        }
        Text(
            if (on) "Aura Light is ON" else "Tap to Turn On Light",
            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (on) c else Color.White,
        )
        Text(if (on) "Tap the circle to turn off" else "One tap — uses your saved colour & style", fontSize = 13.sp, color = Aura.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LightCard(
    l: LightSettings, on: Boolean,
    onLive: (LightSettings) -> Unit, onSave: (LightSettings, Boolean) -> Unit, onWizard: () -> Unit, onLearn: () -> Unit,
) {
    val app = AuraVibeApp.instance
    var cur by remember(l) { mutableStateOf(l) }
    SectionCard {
        SectionTitle("💡 Light Tuning")

        FieldLabel("Colour tone")
        BigSlider(
            cur.temp, onChange = { cur = cur.copy(temp = it); onLive(cur) }, onDone = { onSave(cur, cur.style != LightStyle.STEADY) },
            track = Brush.horizontalGradient(listOf(Aura.Warm, Color(0xFFFFC680), Color.White)), thumb = tempColor(cur.temp),
        )
        SliderLabels("🟠 Warm Orange", "⚪ Cool White")

        FieldLabel("Brightness")
        BigSlider(
            cur.level.toFloat(), onChange = { cur = cur.copy(level = it.toInt().coerceIn(1, 5)); onLive(cur) },
            onDone = { onSave(cur, cur.style != LightStyle.STEADY) }, range = 1f..5f, steps = 3,
            track = Brush.horizontalGradient(listOf(Color(0xFF3A3A3E), Aura.Yellow)), thumb = Aura.Yellow,
        )
        SliderLabels("Dim", "Max", "${cur.level * 20}%")

        FieldLabel("Light style")
        val styles = LightStyle.entries
        styles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { st ->
                    ChoiceTile(st.emoji, st.title, st.sub, cur.style == st, Aura.Yellow, Modifier.weight(1f)) {
                        cur = cur.copy(style = st); onSave(cur, on)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }

        AnimatedVisibility(cur.style != LightStyle.STEADY) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel("Fade-in / fade-out speed")
                AccentSlider(cur.speed, { cur = cur.copy(speed = it) }, { onSave(cur, on) })
                SliderLabels("🐢 Slow & gentle", "🐇 Fast")

                FieldLabel("How many times")
                Box(Modifier.fillMaxWidth()) {
                    Column {
                        AccentSlider(cur.count.toFloat(), { cur = cur.copy(count = it.toInt()) }, { onSave(cur, false) }, 1f..15f, 13)
                        SliderLabels("1 time", "15 times", if (cur.infinite) "Until unlocked" else "${cur.count}×")
                    }
                }
                SwitchRow("Keep going until I unlock the phone", "For notifications; the button stops it manually", cur.infinite, Aura.Yellow) {
                    cur = cur.copy(infinite = it); onSave(cur, false)
                }
            }
        }

        BigButton("▶️ Test Light Now", Aura.Yellow) { app.turnLightOn(cur) }
        BigButton("⚡ Auto-Detect Orange from vivo Camera", Aura.CardHi, Aura.Yellow, onClick = onLearn)
        BigButton("🟠 Step-by-Step Channel Tester", Aura.CardHi, Aura.Warm, onClick = onWizard)
    }
}

@Composable
private fun VibeCard(v: VibeSettings, onSave: (VibeSettings) -> Unit, onTest: (VibeSettings) -> Unit) {
    var cur by remember(v) { mutableStateOf(v) }
    fun commit(n: VibeSettings, feel: Boolean = true) { cur = n; onSave(n); if (feel) onTest(n) }
    SectionCard {
        SectionTitle("📳 Vibration Tuning")
        SwitchRow("Force app vibrations in Vibrate / Silent mode", "Uses the apps you pick in My Apps", cur.enabled) { commit(cur.copy(enabled = it), false) }

        FieldLabel("Vibration style")
        VibeStyle.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { st ->
                    ChoiceTile(st.emoji, st.title, null, cur.style == st, Aura.Blue, Modifier.weight(1f)) { commit(cur.copy(style = st)) }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }

        FieldLabel("Strength")
        AccentSlider(cur.intensity, { cur = cur.copy(intensity = it) }, { commit(cur) }, 0.2f..1f, accent = Aura.Blue)
        SliderLabels("Soft", "Maximum power", "${(cur.intensity * 100).toInt()}%")

        FieldLabel("Length")
        AccentSlider(cur.lengthMs.toFloat(), { cur = cur.copy(lengthMs = it.toInt()) }, { commit(cur) }, 100f..2000f, accent = Aura.Blue)
        SliderLabels("Short tick", "Long buzz", "%.1f s".format(cur.lengthMs / 1000f))

        FieldLabel("Repeat")
        AccentSlider(cur.repeat.toFloat(), { cur = cur.copy(repeat = it.toInt()) }, { commit(cur) }, 1f..5f, 3, accent = Aura.Blue)
        SliderLabels("1 time", "5 times", "${cur.repeat}× per notification")

        BigButton("▶️ Test Vibration Now", Aura.Blue, Color.White) { onTest(cur) }
    }
}
