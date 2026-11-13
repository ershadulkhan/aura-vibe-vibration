package com.auravibe.controller.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.data.AppRule
import com.auravibe.controller.data.VibeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val system: Boolean, val social: Boolean)

private object IconCache { val cache = LruCache<String, ImageBitmap>(160) }

private enum class Filter(val label: String) { ENABLED("⭐ Enabled"), SOCIAL("💬 Social & Work"), ALL("All apps") }

private val SOCIAL_HINTS = listOf(
    "whatsapp", "linkedin", "gmail", "android.gm", "telegram", "messenger", "facebook", "instagram", "twitter",
    "slack", "teams", "outlook", "discord", "signal", "viber", "imo", "snapchat", "zoom", "skype", "googlequicksearchbox",
    "android.apps.messaging", "wechat", "line", "threads", "reddit", "tiktok", "chat", "mail", "calendar",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val app = AuraVibeApp.instance
    val rules by app.repo.rules.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var sheetFor by remember { mutableStateOf<AppEntry?>(null) }

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    val social = it.category == ApplicationInfo.CATEGORY_SOCIAL || it.category == ApplicationInfo.CATEGORY_PRODUCTIVITY ||
                        SOCIAL_HINTS.any { h -> it.packageName.contains(h, true) }
                    AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0, social)
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Which apps should alert me?", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text("Turn on 📳 Vibrate and/or 💡 Light for each app", fontSize = 13.sp, color = Aura.Muted, modifier = Modifier.padding(bottom = 12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Aura.Blue, unfocusedBorderColor = Aura.CardHi,
                focusedContainerColor = Aura.Card, unfocusedContainerColor = Aura.Card,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f -> Chip(f.label, filter == f, Aura.Blue) { filter = f } }
        }

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Aura.Yellow) }
            return@Column
        }
        val q = query.trim().lowercase()
        val shown = list.filter { e ->
            (q.isEmpty() || e.label.lowercase().contains(q) || e.pkg.contains(q)) && when (filter) {
                Filter.ALL -> true
                Filter.ENABLED -> rules[e.pkg]?.active == true
                Filter.SOCIAL -> e.social
            }
        }.sortedByDescending { rules[it.pkg]?.active == true }   // enabled apps pinned to the top

        if (shown.isEmpty()) {
            Text(if (filter == Filter.ENABLED) "No apps turned on yet. Pick some under All apps." else "No apps match.",
                color = Aura.Muted, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { e ->
                val rule = rules[e.pkg] ?: AppRule()
                AppRow(e, rule, onOpen = { sheetFor = e }, onRule = { app.repo.setRule(e.pkg, it) })
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    sheetFor?.let { e ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, containerColor = Aura.Card) {
            AppSheet(e, rules[e.pkg] ?: AppRule()) { app.repo.setRule(e.pkg, it) }
        }
    }
}

@Composable
private fun AppIcon(pkg: String, size: Int = 46) {
    val ctx = LocalContext.current
    var icon by remember(pkg) { mutableStateOf(IconCache.cache.get(pkg)) }
    LaunchedEffect(pkg) {
        if (icon == null) icon = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }
                .getOrNull()?.also { IconCache.cache.put(pkg, it) }
        }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Aura.CardHi)) {
        icon?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun AppRow(e: AppEntry, rule: AppRule, onOpen: () -> Unit, onRule: (AppRule) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Aura.Card).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(e.pkg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rule.custom) Text("Custom settings", fontSize = 11.sp, color = Aura.Yellow)
        }
        ToggleBtn("📳", "Vibrate", rule.vibrate, Aura.Blue) { onRule(rule.copy(vibrate = !rule.vibrate)) }
        Spacer(Modifier.width(6.dp))
        ToggleBtn("💡", "Aura", rule.aura, Aura.Yellow) { onRule(rule.copy(aura = !rule.aura)) }
    }
}

@Composable
private fun ToggleBtn(emoji: String, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.size(width = 64.dp, height = 56.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) accent else Aura.CardHi)
            .border(1.dp, if (on) accent else Color(0xFF3A3A3E), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, fontSize = 18.sp)
        Text(if (on) "$label ✓" else label, fontSize = 10.sp, color = if (on) Color.Black else Aura.Muted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AppSheet(e: AppEntry, rule: AppRule, onRule: (AppRule) -> Unit) {
    val app = AuraVibeApp.instance
    var cur by remember(rule) { mutableStateOf(rule) }
    fun set(r: AppRule) { cur = r; onRule(r) }
    Column(Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(e.pkg, 52)
            Spacer(Modifier.width(12.dp))
            Text(e.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        SwitchRow("📳 Vibrate for this app", null, cur.vibrate) { set(cur.copy(vibrate = it)) }
        SwitchRow("💡 Flash light for this app", null, cur.aura, Aura.Yellow) { set(cur.copy(aura = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceTile("⚙️", "Use my main settings", null, !cur.custom, Aura.Blue, Modifier.weight(1f)) { set(cur.copy(custom = false)) }
            ChoiceTile("🎨", "Custom colour & vibration", null, cur.custom, Aura.Yellow, Modifier.weight(1f)) { set(cur.copy(custom = true)) }
        }
        if (cur.custom) {
            FieldLabel("Light colour for this app")
            BigSlider(
                cur.temp, { cur = cur.copy(temp = it) }, { onRule(cur) },
                track = Brush.horizontalGradient(listOf(Aura.Warm, Color(0xFFFFC680), Color.White)), thumb = tempColor(cur.temp),
            )
            SliderLabels("🟠 Warm Orange", "⚪ Cool White")
            FieldLabel("Vibration style for this app")
            VibeStyle.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { st ->
                        ChoiceTile(st.emoji, st.title, null, cur.vibeStyle == st, Aura.Blue, Modifier.weight(1f)) {
                            set(cur.copy(vibeStyle = st))
                            app.vibes.vibrate(app.repo.settings.value.vibe, app.repo.settings.value.usage, st)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        BigButton("▶️ Preview this app's alert", Aura.Yellow) {
            val s = app.repo.settings.value
            if (cur.vibrate) app.vibes.vibrate(s.vibe, s.usage, if (cur.custom) cur.vibeStyle else null)
            if (cur.aura) app.notificationFlash(if (cur.custom) s.light.copy(temp = cur.temp, infinite = false) else s.light.copy(infinite = false))
        }
    }
}
