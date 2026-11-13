package com.auravibe.controller.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auravibe.controller.AuraVibeApp
import com.auravibe.controller.data.AppRule
import com.auravibe.controller.data.VibePattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val system: Boolean)

private object IconCache {
    val cache = LruCache<String, ImageBitmap>(160)
}

private enum class Filter(val label: String) { ALL("All apps"), ENABLED("Enabled only"), USER("User apps"), SYSTEM("System apps") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val app = AuraVibeApp.instance
    val rules by app.repo.rules.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .filter { it.packageName != ctx.packageName }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
                .sortedBy { it.label.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("App bypass rules", fontSize = 28.sp, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Aura.Blue, unfocusedBorderColor = Aura.CardHi,
                focusedContainerColor = Aura.Card, unfocusedContainerColor = Aura.Card,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                Filter.USER -> !e.system
                Filter.SYSTEM -> e.system
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { e ->
                val rule = rules[e.pkg] ?: AppRule()
                AppRow(
                    e, rule, expanded == e.pkg,
                    onExpand = { expanded = if (expanded == e.pkg) null else e.pkg },
                    onRule = { app.repo.setRule(e.pkg, it) },
                )
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRow(e: AppEntry, rule: AppRule, open: Boolean, onExpand: () -> Unit, onRule: (AppRule) -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState(IconCache.cache.get(e.pkg), e.pkg) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(e.pkg).toBitmap(96, 96).asImageBitmap() }
                .getOrNull()?.also { IconCache.cache.put(e.pkg, it) }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Aura.Card)
            .clickable(onClick = onExpand)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Aura.CardHi)) {
                icon?.let { Image(it, null, Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.pkg, fontSize = 11.sp, color = Aura.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MiniToggle("Vibe", rule.vibrate, Aura.Blue) { onRule(rule.copy(vibrate = it)) }
            Spacer(Modifier.width(6.dp))
            MiniToggle("Aura", rule.aura, Aura.Yellow) { onRule(rule.copy(aura = it)) }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vibration pattern", fontSize = 13.sp, color = Aura.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VibePattern.entries.forEach { p -> Chip(p.label, rule.pattern == p, Aura.Blue) { onRule(rule.copy(pattern = p)) } }
                }
                if (rule.pattern == VibePattern.CUSTOM) {
                    LabeledSlider("Duration", rule.customMs.toFloat(), 50f..3000f, "${rule.customMs} ms",
                        { onRule(rule.copy(customMs = it.toInt())) }, accent = Aura.Blue)
                }
                Chip("Test vibration", false) {
                    val a = AuraVibeApp.instance
                    a.vibes.vibrate(rule.pattern, rule.customMs, a.repo.settings.value.usage)
                }
            }
        }
    }
}

@Composable
private fun MiniToggle(label: String, on: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = if (accent == Aura.Yellow) Color.Black else Color.White),
        )
        Text(label, fontSize = 10.sp, color = Aura.Muted)
    }
}
