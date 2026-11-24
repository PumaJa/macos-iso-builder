package org.kineticos.app.ui.screens

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
        Slider(value, onChange, valueRange = range, enabled = enabled, colors = SliderDefaults.colors(thumbColor = K.Accent, activeTrackColor = K.Accent))
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, color = p.text); sub?.let { Text(it, color = p.textDim, style = labelStyle(10)) } }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = K.Accent))
    }
}

fun String.toD() = replace(',', '.').toDoubleOrNull()
