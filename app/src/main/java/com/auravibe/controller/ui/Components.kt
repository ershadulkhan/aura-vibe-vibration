package com.auravibe.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Aura.Card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Column {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, fontSize = 13.sp, color = Aura.Muted)
    }
}

/** 0 = warm orange → 1 = cool white. */
fun tempColor(t: Float): Color {
    val mid = Color(0xFFFFC680)
    return if (t < 0.5f) lerp(Aura.Warm, mid, t * 2f) else lerp(mid, Aura.Cool, (t - 0.5f) * 2f)
}

/**
 * Chunky slider painted over a custom track. The track is drawn by [track]; the material
 * slider sits on top with transparent tracks and a large thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BigSlider(
    value: Float, onChange: (Float) -> Unit, onDone: () -> Unit = {},
    range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    track: Brush, thumb: Color = Color.White, enabled: Boolean = true,
) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(track))
        Slider(
            value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = thumb, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun SliderLabels(left: String, right: String, center: String? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(left, fontSize = 13.sp, color = Aura.Muted, modifier = Modifier.weight(1f))
        if (center != null) Text(center, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(right, fontSize = 13.sp, color = Aura.Muted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
fun FieldLabel(text: String) = Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

/** Simple accent slider (yellow / blue). */
@Composable
fun AccentSlider(
    value: Float, onChange: (Float) -> Unit, onDone: () -> Unit = {},
    range: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, accent: Color = Aura.Yellow,
) = BigSlider(
    value, onChange, onDone, range, steps,
    track = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.35f), accent)), thumb = accent,
)

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, accent: Color = Aura.Blue, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 13.sp, color = Aura.Muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White),
        )
    }
}

@Composable
fun Chip(label: String, selected: Boolean, accent: Color = Aura.Yellow, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) accent else Aura.CardHi)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) { Text(label, fontSize = 14.sp, color = if (selected) Color.Black else Color.White) }
}

/** Big selectable tile with emoji, title and subtitle. */
@Composable
fun ChoiceTile(emoji: String, title: String, sub: String?, selected: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else Aura.CardHi)
            .border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 22.sp)
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
        if (sub != null) Text(sub, fontSize = 10.sp, color = Aura.Muted, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun BigButton(text: String, color: Color, textColor: Color = Color.Black, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .background(color).clickable(onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}
