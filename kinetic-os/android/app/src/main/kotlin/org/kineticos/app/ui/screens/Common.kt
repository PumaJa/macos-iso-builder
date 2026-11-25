package org.kineticos.app.ui.screens
import androidx.compose.ui.res.stringResource
import org.kineticos.app.R
import org.kineticos.vehicle.MotorCharacter
import org.kineticos.vehicle.VehicleType

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.kineticos.app.ui.*

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, error: String? = null, password: Boolean = false) {
    OutlinedTextField(value, onChange, modifier.padding(vertical = 3.dp), label = { Text(label) }, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else if (password) KeyboardType.Password else KeyboardType.Text))
}

@Composable
fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f, display: (Float) -> String = { "%.0f%%".format(it * 100) }, enabled: Boolean = true) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row { Text(label, color = if (enabled) p.text else p.textDim, modifier = Modifier.weight(1f)); Text(display(value), color = K.Accent, style = labelStyle(13)) }
        Slider(value, onChange, valueRange = range, enabled = enabled, colors = SliderDefaults.colors(thumbColor = K.Accent, activeTrackColor = K.Accent, inactiveTrackColor = p.surface2, activeTickColor = K.OnAccent, inactiveTickColor = p.textDim))
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, color = p.text); sub?.let { Text(it, color = p.textDim, style = labelStyle(10)) } }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = K.Accent, checkedThumbColor = K.OnAccent, uncheckedTrackColor = p.surface2, uncheckedThumbColor = p.textDim, uncheckedBorderColor = p.grid))
    }
}

fun String.toD() = replace(',', '.').toDoubleOrNull()

@Composable
fun VehicleType.uiLabel(): String = stringResource(when (this) {
    VehicleType.E_MOTORCYCLE -> R.string.vt_motorcycle; VehicleType.E_MOTOCROSS -> R.string.vt_motocross; VehicleType.E_BIKE -> R.string.vt_bike
    VehicleType.E_SCOOTER -> R.string.vt_scooter; VehicleType.E_KART -> R.string.vt_kart; VehicleType.CUSTOM_BUILD -> R.string.vt_custom
    VehicleType.RC -> R.string.vt_rc; VehicleType.MULTI_MOTOR -> R.string.vt_multi
})

@Composable
fun MotorCharacter.uiLabel(): String = stringResource(when (this) {
    MotorCharacter.ELECTRIC_LINEAR -> R.string.mc_electric; MotorCharacter.TWO_STROKE_125 -> R.string.mc_2s125; MotorCharacter.TWO_STROKE_250 -> R.string.mc_2s250
    MotorCharacter.FOUR_STROKE_250 -> R.string.mc_4s250; MotorCharacter.FOUR_STROKE_450 -> R.string.mc_4s450; MotorCharacter.CUSTOM -> R.string.mc_custom
})
