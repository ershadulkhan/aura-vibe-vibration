package com.auravibe.controller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.hw.CameraSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { INTRO, STARTING, WAITING, READING, RESULT }

/** "⚡ Auto-Detect from vivo Camera": records what the stock Camera sends while the user turns on Aura light. */
@Composable
fun LearnFromCamera(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = AuraVibeApp.instance
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.INTRO) }
    var snap by remember { mutableStateOf<CameraSniffer.Snapshot?>(null) }
    var result by remember { mutableStateOf<CameraSniffer.Result?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val shizukuOk = app.hal.shizuku.granted()

    fun copy(text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AuraVibe capture", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    fun test(channel: String) = scope.launch {
        app.turnLightOff()
        status = "Testing… look at the back of the phone"
        withContext(Dispatchers.IO) { app.hal.testChannel(channel, true) }
        delay(3000)
        val err = if (channel.startsWith("sess:")) app.hal.session.lastError else null
        withContext(Dispatchers.IO) { app.hal.testChannel(channel, false) }
        status = err?.let { "⚠️ $it" } ?: "Did the ORANGE light turn on? If yes, tap Save."
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Aura.Card)
                .verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⚡ Learn from vivo Camera", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            when (step) {
                Step.INTRO -> {
                    Text(
                        "AuraVibe will record exactly what the vivo Camera app sends to the light hardware while you switch the Aura light on, then copy it.",
                        textAlign = TextAlign.Center, color = Aura.Muted, fontSize = 14.sp,
                    )
                    Steps()
                    if (!shizukuOk) Text("⚠️ Shizuku must be running and allowed (Setup → 2).", color = Aura.Bad, fontSize = 13.sp)
                    BigButton("Start & open Camera", if (shizukuOk) Aura.Yellow else Aura.CardHi) {
                        if (!shizukuOk) return@BigButton
                        step = Step.STARTING
                        scope.launch {
                            app.turnLightOff()
                            snap = withContext(Dispatchers.IO) { app.sniffer.start() }
                            step = Step.WAITING
                            val launch = ctx.packageManager.getLaunchIntentForPackage("com.android.camera")
                                ?: Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                            ctx.safeStart(launch)
                        }
                    }
                    BigButton("Close", Aura.CardHi, Color.White, onClick = onClose)
                }
                Step.STARTING, Step.READING -> {
                    CircularProgressIndicator(color = Aura.Yellow)
                    Text(if (step == Step.STARTING) "Starting the recorder…" else "Reading what the Camera did…", color = Aura.Muted)
                }
                Step.WAITING -> {
                    Text("Recording… 🔴", fontSize = 18.sp, color = Aura.Bad)
                    Steps()
                    BigButton("✅ I'm done — show what was captured", Aura.Yellow) {
                        val sn = snap ?: return@BigButton
                        step = Step.READING
                        scope.launch {
                            result = withContext(Dispatchers.IO) { app.sniffer.finish(sn) }
                            step = Step.RESULT
                        }
                    }
                    BigButton("Open Camera again", Aura.CardHi, Color.White) {
                        ctx.safeStart(ctx.packageManager.getLaunchIntentForPackage("com.android.camera") ?: Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    }
                }
                Step.RESULT -> {
                    val r = result!!
                    val changed = r.keyValues.filterKeys { !it.startsWith("android.") }
                    Text(
                        when {
                            r.recipe != null -> "Found the Aura light commands 🎉"
                            r.settingRecipes.isNotEmpty() -> "Found setting changes to try"
                            !r.watchWorked -> "The camera recorder isn't available on this firmware"
                            else -> "Nothing light-related was captured"
                        },
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    if (changed.isNotEmpty()) {
                        Text(
                            changed.entries.take(14).joinToString("\n") { (k, v) -> "${k.substringAfterLast('.')}: ${v.takeLast(5).joinToString(" → ")}" },
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Aura.Muted,
                        )
                    }
                    r.recipe?.let { rec ->
                        Text(rec.channel, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Aura.Yellow)
                        BigButton("▶️ Test learned light (3 s)", Aura.Yellow) { test(rec.channel) }
                        BigButton("🟠 Save as my Orange Light", Aura.Warm) {
                            app.repo.update { it.copy(warmNode = rec.channel) }
                            status = "Saved! The Home screen now uses it."
                        }
                    }
                    r.settingRecipes.take(6).forEach { (title, ch) ->
                        Text(title, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Aura.Muted)
                        BigButton("▶️ Test this setting", Aura.CardHi, Color.White) { test(ch) }
                        BigButton("Save this setting as Orange", Aura.CardHi, Aura.Warm) { app.repo.update { it.copy(warmNode = ch) }; status = "Saved." }
                    }
                    status?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("⚠️")) Aura.Bad else Aura.Ok, textAlign = TextAlign.Center) }
                    if (r.recipe == null && r.settingRecipes.isEmpty()) {
                        Text(
                            if (!r.watchWorked) "Copy the capture and share it — it shows what the system allowed us to see."
                            else "Make sure you turned on \"Aura light always on\" and moved the colour slider while recording, then try again.",
                            fontSize = 13.sp, color = Aura.Muted, textAlign = TextAlign.Center,
                        )
                    }
                    BigButton("📋 Copy capture report", Aura.CardHi, Color.White) { copy(app.sniffer.report(r)) }
                    BigButton("🔁 Record again", Aura.CardHi, Color.White) { result = null; status = null; step = Step.INTRO }
                    BigButton("Done", Aura.CardHi, Aura.Muted, onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun Steps() {
    Text(
        "1. In vivo Camera (Photo mode) tap the ⚡ flash icon\n" +
            "2. Choose \"Aura light always on\"\n" +
            "3. Start with the colour slider at WHITE, then slide slowly all the way to ORANGE\n" +
            "4. Move the brightness slider from low to high\n" +
            "5. Wait 3 seconds, then come back to AuraVibe",
        fontSize = 14.sp, modifier = Modifier.fillMaxWidth(),
    )
}
