package com.auravibe.controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Aura.Card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Column {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, fontSize = 13.sp, color = Aura.Muted)
    }
}

/** OriginOS camera-style pill selector with yellow highlight. */
@Composable
fun PillSelector(options: List<String>, selected: Int, onSelect: (Int) -> Unit, accent: Color = Aura.Yellow) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Aura.CardHi)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) Color.Black else Color.White, fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

fun tempColor(t: Float): Color =
    if (t < 0.5f) lerp(Aura.Warm, Aura.WarmYellow, t * 2f) else lerp(Aura.WarmYellow, Aura.Cool, (t - 0.5f) * 2f)

/** Colour temperature slider: gradient track + 'A' auto button, like the stock camera. */
@Composable
fun TempSlider(value: Float, auto: Boolean, onAuto: (Boolean) -> Unit, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (auto) Aura.Yellow else Color.White)
                .clickable { onAuto(!auto) },
            contentAlignment = Alignment.Center,
        ) { Text("A", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(Aura.Bad, Aura.Warm, Aura.WarmYellow, Aura.Cool))),
            )
            Slider(
                value = value, onValueChange = onChange, onValueChangeFinished = onDone, enabled = !auto,
                colors = SliderDefaults.colors(
                    thumbColor = tempColor(value),
                    disabledThumbColor = tempColor(value).copy(alpha = 0.6f),
                    activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent, disabledInactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}

/** 5-step brightness slider with anchor dots and dim/bright sun glyphs. */
@Composable
fun BrightnessSlider(level: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Sun(small = true)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(12.dp),
            ) {
                val y = size.height / 2
                drawLine(Color(0xFF5A5A5E), Offset(0f, y), Offset(size.width, y), strokeWidth = 4f)
                for (i in 0..4) drawCircle(Color(0xFFBDBDC2), radius = 6f, center = Offset(size.width * i / 4f, y))
            }
            Slider(
                value = level.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(1, 5)) },
                onValueChangeFinished = onDone, valueRange = 1f..5f, steps = 3,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Spacer(Modifier.width(10.dp))
        Sun(small = false)
    }
}

@Composable
private fun Sun(small: Boolean) {
    Canvas(Modifier.size(if (small) 18.dp else 24.dp)) {
        val c = center
        val r = size.minDimension * (if (small) 0.18f else 0.22f)
        drawCircle(Color.White, r, c)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val d = size.minDimension * 0.42f
            drawCircle(Color.White, size.minDimension * 0.05f, Offset(c.x + (d * Math.cos(a)).toFloat(), c.y + (d * Math.sin(a)).toFloat()))
        }
    }
}

@Composable
fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
    onChange: (Float) -> Unit, accent: Color = Aura.Yellow, steps: Int = 0,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(display, fontSize = 14.sp, color = accent)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF3A3A3E)),
        )
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (sub != null) Text(sub, fontSize = 13.sp, color = Aura.Muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Aura.Blue, checkedThumbColor = Color.White),
        )
    }
}

@Composable
fun Chip(label: String, selected: Boolean, accent: Color = Aura.Yellow, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else Aura.CardHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, fontSize = 13.sp, color = if (selected) Color.Black else Color.White) }
}
