package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

/** Full-screen overlay while the vehicle is locked (also shows tamper warnings). */
@Composable
fun LockedOverlay() {
    val g = LocalContext.current.graph
    val st by g.connection.scriptStatus.collectAsStateWithLifecycle()
    val lock = st?.lock?.takeIf { it.locked } ?: return
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("🔒", style = numStyle(72))
            Text(stringResource(R.string.lock_overlay_title), style = labelStyle(24).copy(fontWeight = FontWeight.Bold), color = K.Danger)
            Text(stringResource(R.string.lock_overlay_text), color = Color.White, style = labelStyle(11))
            if (lock.tamper) Text(stringResource(R.string.lock_tamper), style = labelStyle(16), color = K.Warn, modifier = Modifier.padding(top = 12.dp))
            if (g.connection.isSimulated) Pill(stringResource(R.string.sim_short), K.Sim, Modifier.padding(top = 8.dp))
            BigButton(stringResource(R.string.lock_unlock_pin), Modifier.fillMaxWidth().padding(top = 20.dp), color = K.Accent) { open = true }
        }
    }
    if (open) LockDialog { open = false }
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
        }
    })
}
