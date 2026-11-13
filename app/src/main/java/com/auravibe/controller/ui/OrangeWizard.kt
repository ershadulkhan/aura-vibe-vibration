package com.auravibe.controller.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.auravibe.controller.hw.Calibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Find my Orange Light": blinks every discovered channel until the user says which one is orange. */
@Composable
fun OrangeWizard(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = AuraVibeApp.instance
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<Calibrator.Candidate>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var whiteSaved by remember { mutableStateOf<String?>(null) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        app.turnLightOff()
        val list = withContext(Dispatchers.IO) { app.calibrator.candidates() }
        if (list.any { it.channel.startsWith("sess:") } && !app.hal.session.hasPermission()) camPerm.launch(Manifest.permission.CAMERA)
        candidates = list
    }

    val current = candidates?.getOrNull(index)
    // Blink the channel under test: 1.5 s on, 0.6 s off, until the user answers.
    LaunchedEffect(current?.channel, saved) {
        val ch = current?.channel ?: return@LaunchedEffect
        if (saved != null) return@LaunchedEffect
        error = null
        try {
            while (isActive) {
                val e = withContext(Dispatchers.IO) { app.hal.testChannel(ch, true) }
                if (e != null) error = e
                delay(1500)
                withContext(Dispatchers.IO) { app.hal.testChannel(ch, false) }
                delay(600)
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { app.hal.testChannel(ch, false) }
        }
    }
    DisposableEffect(Unit) { onDispose { scope.launch(Dispatchers.IO) { app.hal.release() } } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Aura.Card)
                .verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🟠 Find My Orange Light", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val list = candidates
            when {
                saved != null -> {
                    Text("Saved! 🎉", fontSize = 20.sp, color = Aura.Ok)
                    Text("Orange light is now set to:\n$saved", textAlign = TextAlign.Center, color = Aura.Muted, fontSize = 13.sp)
                    BigButton("🟠 Turn On Warm Orange Now", Aura.Warm) {
                        onClose(); app.turnLightOn(app.repo.settings.value.light.copy(temp = 0f))
                    }
                    BigButton("Done", Aura.CardHi, Color.White, onClick = onClose)
                }
                list == null -> {
                    CircularProgressIndicator(color = Aura.Yellow)
                    Text("Scanning light hardware…", color = Aura.Muted)
                }
                current == null -> {
                    Text(
                        if (list.isEmpty()) "No light channels were found." else "That was every channel we could find.",
                        fontSize = 17.sp, textAlign = TextAlign.Center,
                    )
                    Text(
                        if (!app.hal.shizuku.granted())
                            "The orange LED is usually only reachable through the kernel LED driver. Start Shizuku and authorise AuraVibe in Setup, then run this again."
                        else
                            "Your firmware doesn't let apps (or the Shizuku shell) switch the orange LED. That normally needs root. Copy the report and share it so the right node can be found.",
                        fontSize = 13.sp, color = Aura.Muted, textAlign = TextAlign.Center,
                    )
                    if (whiteSaved != null) Text("White light saved: $whiteSaved", fontSize = 12.sp, color = Aura.Ok)
                    BigButton("📋 Copy LED Hardware Report", Aura.Yellow) { copyReport(ctx) }
                    BigButton("🔁 Start Again", Aura.CardHi, Color.White) { index = 0 }
                    BigButton("Close", Aura.CardHi, Color.White, onClick = onClose)
                }
                else -> {
                    Text("Testing channel ${index + 1} of ${list.size}…", color = Aura.Muted)
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Aura.Warm.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        Text("💡", fontSize = 32.sp)
                    }
                    Text("Look at the back of the phone.\nDid the ORANGE light turn on?", fontSize = 18.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    Text(current.title, fontSize = 14.sp)
                    Text(current.detail, fontSize = 11.sp, color = Aura.Muted, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    error?.let {
                        Text("⚠️ This channel refused: $it", fontSize = 12.sp, color = Aura.Bad, textAlign = TextAlign.Center)
                    }
                    BigButton("🟠 Yes, it's ORANGE — Save This!", Aura.Warm) {
                        app.repo.update { it.copy(warmNode = current.channel) }
                        saved = current.channel
                    }
                    BigButton("⚪ That was the WHITE light", Color.White) {
                        app.repo.update { it.copy(coolNode = current.channel) }
                        whiteSaved = current.channel
                        index++
                    }
                    BigButton("❌ Nothing lit up — Try Next", Aura.CardHi, Color.White) { index++ }
                    BigButton("📋 Copy LED Hardware Report", Aura.CardHi, Color.White) { copyReport(ctx) }
                    BigButton("Stop", Aura.CardHi, Aura.Muted, onClick = onClose)
                }
            }
        }
    }
}

fun copyReport(ctx: Context) {
    val app = AuraVibeApp.instance
    app.scope.launch(Dispatchers.IO) {
        val text = app.calibrator.report()
        withContext(Dispatchers.Main) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AuraVibe LED report", text))
            Toast.makeText(ctx, "LED hardware report copied", Toast.LENGTH_SHORT).show()
        }
    }
}
