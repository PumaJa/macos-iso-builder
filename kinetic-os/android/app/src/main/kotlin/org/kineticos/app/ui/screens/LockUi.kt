package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.kineticos.app.graph
import org.kineticos.app.ui.*

/** Lock pill for the dash status strip; opens the PIN dialog. Hidden when the vehicle script has no lock support. */
@Composable
fun LockPill() {
    val g = LocalContext.current.graph
    val st by g.connection.scriptStatus.collectAsStateWithLifecycle()
    val lock = st?.lock ?: return
    var open by remember { mutableStateOf(false) }
    BigButton(stringResource(if (lock.locked) R.string.lock_pill_locked else R.string.lock_pill_lock), color = if (lock.locked) K.Danger else K.Accent, selected = lock.locked, compact = true, modifier = Modifier.padding(end = 6.dp)) { open = true }
    if (open) LockDialog { open = false }
}

/**
 * Full-screen lock screen while the vehicle is locked: dimmed vehicle photo, animated security ring with the brand mark,
 * large status, tamper alarm and an on-screen PIN pad (no dialog). Landscape: ring left, keypad right.
 */
@Composable
fun LockedOverlay() {
    val g = LocalContext.current.graph
    val st by g.connection.scriptStatus.collectAsStateWithLifecycle()
    val lock = st?.lock?.takeIf { it.locked } ?: return
    val vehicle by g.activeVehicle.collectAsStateWithLifecycle()
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }
    fun submit() {
        if (pin.length < 4 || busy) return
        busy = true
        scope.launch {
            val r = g.lockAction("unlock", pin); pin = ""; busy = false
            if (r != "Vehicle unlocked") { msg = r; shake++ }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {   // swallow touches
        vehicle?.photoUri?.let { VehiclePhoto(it, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.70f), Color.Black.copy(alpha = 0.96f)), radius = 1400f)))
        val status: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(contentAlignment = Alignment.Center) {
                    LockRing(lock.tamper, Modifier.size(if (landscape) 190.dp else 210.dp))
                    BrandMark(56)
                }
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.lock_screen_title), style = titleStyle(46).copy(letterSpacing = 14.sp), color = Color.White)
                Text((vehicle?.name ?: "").uppercase(), style = labelStyle(12).copy(letterSpacing = 4.sp), color = Color.White.copy(alpha = 0.55f))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LockChip(stringResource(R.string.lock_chip_immobilizer)); LockChip(stringResource(R.string.lock_chip_persist))
                    if (g.connection.isSimulated) LockChip(stringResource(R.string.sim_short))
                }
                if (lock.tamper) TamperBanner(Modifier.padding(top = 14.dp))
            }
        }
        val pad: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                PinDots(pin.length, shake)
                Text(when {
                    lock.lockedOut -> stringResource(R.string.lock_lockedout, lock.lockoutRemainingS)
                    msg != null -> msg!!
                    else -> stringResource(R.string.lock_enter_pin)
                }, style = labelStyle(11), color = if (msg != null || lock.lockedOut) Color.White else Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 10.dp))
                PinPad(enabled = !busy && !lock.lockedOut, compact = landscape,
                    onDigit = { d -> if (pin.length < 8) { pin += d; msg = null } }, onDelete = { pin = pin.dropLast(1) }, onOk = { submit() })
            }
        }
        if (landscape) Row(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            status(Modifier.weight(1f)); pad(Modifier.weight(1f))
        } else Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            status(Modifier.weight(1f)); pad(Modifier)
        }
    }
}

/** Brand mark only (own logo image if set). */
@Composable
private fun BrandMark(size: Int) {
    val brand = LocalBrand.current
    CompositionLocalProvider(LocalBrand provides brand.copy(name = "")) { KineticLogo(size = size) }
}

/** Security ring: 72 ticks, a slowly rotating scan arc and a breathing glow. Tamper → fast white flashing. */
@Composable
private fun LockRing(tamper: Boolean, modifier: Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ring")
    val rot by t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(if (tamper) 1200 else 6000, easing = androidx.compose.animation.core.LinearEasing)), label = "rot")
    val breathe by t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(if (tamper) 250 else 2200), androidx.compose.animation.core.RepeatMode.Reverse), label = "br")
    androidx.compose.foundation.Canvas(modifier) {
        val c = center; val ro = size.minDimension / 2; val n = 72
        for (i in 0 until n) {
            val a = Math.toRadians(i * 360.0 / n - 90)
            // ticks near the scan head are brighter
            val d = ((i * 360f / n - rot) % 360f + 360f) % 360f
            val head = if (d > 300f) (d - 300f) / 60f else 0f
            val alpha = if (tamper) breathe else (0.18f + 0.82f * head)
            val len = if (i % 6 == 0) ro * 0.13f else ro * 0.08f
            val o = androidx.compose.ui.geometry.Offset(c.x + (ro * Math.cos(a)).toFloat(), c.y + (ro * Math.sin(a)).toFloat())
            val ii = androidx.compose.ui.geometry.Offset(c.x + ((ro - len) * Math.cos(a)).toFloat(), c.y + ((ro - len) * Math.sin(a)).toFloat())
            drawLine(Color.White.copy(alpha = alpha), ii, o, strokeWidth = if (i % 6 == 0) 5f else 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        drawCircle(Color.White.copy(alpha = 0.10f + 0.10f * breathe), ro * 0.74f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawCircle(Color.White.copy(alpha = 0.05f * breathe), ro * 0.70f, c)
    }
}

@Composable
private fun LockChip(text: String) {
    Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text.uppercase(), style = labelStyle(10), color = Color.White.copy(alpha = 0.8f))
    }
}

/** Inverted, blinking alarm bar. */
@Composable
private fun TamperBanner(modifier: Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "tamper")
    val a by t.animateFloat(1f, 0.25f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(500), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = a)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.lock_tamper).uppercase(), style = labelStyle(12), color = Color.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun PinDots(count: Int, shake: Int) {
    val off = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(shake) { if (shake > 0) for (x in listOf(-18f, 16f, -12f, 8f, -4f, 0f)) off.animateTo(x, androidx.compose.animation.core.tween(45)) }
    Row(Modifier.offset(x = off.value.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(maxOf(4, count)) { i ->
            Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .then(if (i < count) Modifier.background(Color.White) else Modifier.border(2.dp, Color.White.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)))
        }
    }
}

@Composable
private fun PinPad(enabled: Boolean, compact: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onOk: () -> Unit) {
    val key = if (compact) 58.dp else 72.dp
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        listOf("123", "456", "789", "<0>").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 22.dp)) {
                row.forEach { ch ->
                    val (label, action) = when (ch) { '<' -> "⌫" to onDelete; '>' -> "OK" to onOk; else -> ch.toString() to { onDigit(ch) } }
                    val isOk = ch == '>'
                    Box(Modifier.size(key).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (isOk) Color.White else Color.White.copy(alpha = if (ch == '<') 0f else 0.10f))
                        .clickable(enabled = enabled) { action() }, contentAlignment = Alignment.Center) {
                        Text(label, style = if (ch.isDigit()) numStyle(if (compact) 26 else 30, FontWeight.Medium) else labelStyle(14), color = if (isOk) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.3f))
                    }
                }
            }
        }
    }
}

@Composable
fun LockDialog(close: () -> Unit) {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val st by g.connection.scriptStatus.collectAsStateWithLifecycle()
    val lock = st?.lock
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var setMode by remember { mutableStateOf(lock?.pinSet == false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(action: String) { busy = true; scope.launch { msg = g.lockAction(action, pin, newPin.takeIf { it.isNotBlank() }); busy = false; if (msg == "Vehicle LOCKED" || msg == "Vehicle unlocked") close() } }
    AlertDialog(onDismissRequest = close, containerColor = p.surface, confirmButton = {}, title = { Text(stringResource(if (lock?.locked == true) R.string.lock_title_unlock else if (setMode) R.string.lock_title_setpin else R.string.lock_title_lock)) }, text = {
        Column {
            if (lock == null) { Text(stringResource(R.string.lock_needs_script), color = p.text); return@Column }
            if (lock.lockedOut) Text(stringResource(R.string.lock_lockedout, lock.lockoutRemainingS), color = K.Danger)
            if (!setMode || lock.pinSet) Field(stringResource(if (setMode) R.string.lock_pin_current else R.string.lock_pin), pin, { v -> pin = v.filter { it.isDigit() }.take(8) }, Modifier.fillMaxWidth(), number = true, password = true)
            if (setMode) Field(stringResource(R.string.lock_pin_new), newPin, { v -> newPin = v.filter { it.isDigit() }.take(8) }, Modifier.fillMaxWidth(), number = true, password = true)
            Text(stringResource(R.string.lock_pin_hint), color = p.textDim, style = labelStyle(10))
            msg?.let { Text(it, color = if (it.startsWith("Vehicle") || it.startsWith("PIN saved")) K.Regen else K.Warn) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                when {
                    setMode -> { BigButton(stringResource(R.string.lock_btn_save_pin), Modifier.weight(1f), enabled = !busy) { run("setpin") }; if (lock.pinSet) BigButton(stringResource(R.string.lock_btn_back), Modifier.weight(1f)) { setMode = false; msg = null } }
                    lock.locked -> BigButton(stringResource(R.string.lock_btn_unlock), Modifier.weight(1f), enabled = !busy && !lock.lockedOut, color = K.Accent) { run("unlock") }
                    else -> { BigButton(stringResource(R.string.lock_btn_lock), Modifier.weight(1f), enabled = !busy, color = K.Danger) { run("lock") }; BigButton(stringResource(R.string.lock_btn_change_pin), Modifier.weight(1f)) { setMode = true; msg = null } }
                }
            }
            if (!setMode && !lock.locked && lock.pinSet) ParentalSection(pin)
        }
    })
}

/** Traction-control indicator: shows the intervention level; filled while the vehicle script is intervening. */
@Composable
fun TcPill() {
    val g = LocalContext.current.graph
    val st by g.connection.scriptStatus.collectAsStateWithLifecycle()
    val pct = st?.tcPercent?.takeIf { it > 0 } ?: return
    val active = st?.tcActive == true && st?.airborne != true
    Box(Modifier.padding(end = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .background(if (active) K.Accent else Color.Transparent).border(1.dp, if (active) K.Accent else LocalPalette.current.grid, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.tc_pill, pct), style = labelStyle(11), color = if (active) K.OnAccent else LocalPalette.current.text)
    }
}

/** "N" toggle (neutral): only shown when the vehicle script supports it. */
@Composable
fun NeutralPill() {
    val g = LocalContext.current.graph
    val st by g.connection.scriptStatus.collectAsStateWithLifecycle()
    val n = st?.neutral ?: return
    val scope = rememberCoroutineScope()
    BigButton("N", color = if (n) K.Warn else LocalPalette.current.textDim, selected = n, compact = true, modifier = Modifier.padding(end = 6.dp).widthIn(min = 56.dp)) {
        scope.launch { g.toast(g.setNeutral(!n)) }
    }
}

/** Parental limit section (inside the lock dialog). */
@Composable
fun ParentalSection(pin: String) {
    val g = LocalContext.current.graph; val p = LocalPalette.current
    val st by g.connection.scriptStatus.collectAsStateWithLifecycle()
    val cur = st?.parentalScale ?: return
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(cur.toFloat()) }
    var speed by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.parental_title) + if (st?.parentalActive == true) " · %.0f %%".format(cur * 100) else "", style = labelStyle(12), color = K.Accent, modifier = Modifier.padding(top = 10.dp))
    LabeledSlider(stringResource(R.string.parental_power), scale, { scale = it }, 0.05f..1f)
    Field(stringResource(R.string.parental_speed), speed, { speed = it.filter { c -> c.isDigit() }.take(3) }, Modifier.fillMaxWidth(), number = true)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BigButton(stringResource(R.string.parental_apply), Modifier.weight(1f), compact = true) { scope.launch { msg = g.setParental(pin, scale.toDouble(), speed.toDoubleOrNull()) } }
        BigButton(stringResource(R.string.parental_clear), Modifier.weight(1f), compact = true) { scope.launch { msg = g.setParental(pin, 1.0, null) } }
    }
    msg?.let { Text(it, color = p.textDim, style = labelStyle(10)) }
    Text(stringResource(R.string.parental_note), color = p.textDim, style = labelStyle(9))
}
