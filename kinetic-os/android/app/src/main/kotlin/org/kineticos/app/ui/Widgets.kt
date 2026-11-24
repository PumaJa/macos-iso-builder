package org.kineticos.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import org.kineticos.telemetry.Q
import org.kineticos.telemetry.Quality

fun qColor(q: Quality, normal: Color): Color = when (q) {
    Quality.MEASURED, Quality.CALCULATED -> normal
    Quality.ESTIMATED -> K.Estimated
    Quality.SIMULATED -> K.Sim
    Quality.UNAVAILABLE -> normal.copy(alpha = 0.3f)
}
fun qTag(q: Quality) = when (q) { Quality.MEASURED -> "MEAS"; Quality.CALCULATED -> "CALC"; Quality.ESTIMATED -> "EST"; Quality.SIMULATED -> "SIM"; Quality.UNAVAILABLE -> "N/A" }
fun fmt(q: Q, decimals: Int = 0, scale: Double = 1.0): String = if (!q.isAvailable) "—" else "%.${decimals}f".format(q.value!! * scale)

@Composable
fun Metric(label: String, q: Q, unit: String, decimals: Int = 0, scale: Double = 1.0, modifier: Modifier = Modifier, big: Boolean = false) {
    val p = LocalPalette.current
    Column(modifier.padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = labelStyle(11), color = p.textDim)
            Spacer(Modifier.width(6.dp))
            Text(qTag(q.quality), style = labelStyle(8), color = qColor(q.quality, p.textDim))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(q, decimals, scale), style = numStyle(if (big) 44 else 28), color = qColor(q.quality, p.text))
            Spacer(Modifier.width(4.dp))
            Text(unit, style = labelStyle(11), color = p.textDim, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** Arc gauge: power (cyan) above zero, regen (green) below zero. */
@Composable
fun PowerArc(fraction: Float, regen: Boolean, modifier: Modifier = Modifier, simulated: Boolean = false) {
    val p = LocalPalette.current
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessMediumLow), label = "arc")
    Canvas(modifier) {
        val stroke = size.minDimension * 0.06f
        val r = Rect(Offset(stroke, stroke), Size(size.width - 2 * stroke, size.height * 2 - 2 * stroke))
        drawArc(p.grid, 180f, 180f, false, r.topLeft, r.size, style = Stroke(stroke, cap = StrokeCap.Round))
        val col = if (simulated) K.Sim else if (regen) K.Regen else K.Accent
        drawArc(Brush.sweepGradient(listOf(col.copy(alpha = 0.4f), col)), 180f, 180f * anim, false, r.topLeft, r.size, style = Stroke(stroke, cap = StrokeCap.Round))
        for (i in 0..10) {
            val a = Math.toRadians(180.0 + i * 18.0); val cx = r.center.x; val cy = r.center.y
            val ro = r.width / 2 - stroke * 1.2f; val ri = ro - stroke * (if (i % 5 == 0) 1.2f else 0.6f)
            drawLine(p.textDim, Offset(cx + ro * Math.cos(a).toFloat(), cy + ro * Math.sin(a).toFloat()), Offset(cx + ri * Math.cos(a).toFloat(), cy + ri * Math.sin(a).toFloat()), 2f)
        }
    }
}

/** Horizontal battery bar with segments. */
@Composable
fun BatteryBar(socPct: Double?, quality: Quality, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val f = ((socPct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
    val col = when { socPct == null -> p.grid; socPct < 15 -> K.Danger; socPct < 30 -> K.Warn; else -> qColor(quality, K.Accent) }
    Canvas(modifier.height(14.dp)) {
        val n = 20; val gap = 3.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) drawRoundRect(if ((i + 0.5f) / n <= f) col else p.grid, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2f))
    }
}

@Composable
fun SimBanner() {
    Box(Modifier.fillMaxWidth().background(K.Sim).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text("SIMULATION – NOT REAL VEHICLE DATA", style = labelStyle(11), color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = labelStyle(10), color = color)
    }
}

/** Large, glove-friendly button. */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, color: Color = K.Accent, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val h = if (LocalGlove.current) 64.dp else 48.dp
    Box(
        modifier.heightIn(min = h).clip(RoundedCornerShape(14.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else p.surface2)
            .border(if (selected) 2.dp else 1.dp, if (selected) color else p.grid, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = labelStyle(14).copy(letterSpacing = 1.sp), color = if (!enabled) p.textDim else if (selected) color else p.text, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
}

@Composable
fun SectionTitle(t: String) { Text(t.uppercase(), style = labelStyle(13), color = LocalPalette.current.textDim, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.grid, RoundedCornerShape(16.dp)).padding(12.dp), content = content)
}

/** Simple, fast line chart for telemetry series (multiple series, shared X). */
@Composable
fun LineChart(series: List<Pair<Color, List<Float?>>>, modifier: Modifier = Modifier, cursor: Float? = null, labels: List<String> = emptyList()) {
    val p = LocalPalette.current
    Column(modifier) {
        if (labels.isNotEmpty()) Row { series.zip(labels).forEach { (s, l) -> Text("■ $l  ", color = s.first, style = labelStyle(10)) } }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            for (i in 0..4) { val y = size.height * i / 4; drawLine(p.grid, Offset(0f, y), Offset(size.width, y), 1f) }
            series.forEach { (col, data) ->
                val vals = data.filterNotNull(); if (vals.size < 2) return@forEach
                val mn = vals.min(); val mx = vals.max().let { if (it - mn < 1e-6f) mn + 1f else it }
                val path = Path(); var started = false
                data.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    val x = size.width * i / (data.size - 1).coerceAtLeast(1); val y = size.height * (1 - (v - mn) / (mx - mn))
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                drawPath(path, col, style = Stroke(2.dp.toPx()))
            }
            cursor?.let { c -> val x = size.width * c; drawLine(K.Warn, Offset(x, 0f), Offset(x, size.height), 2f) }
        }
    }
}
