package com.alemnt.midipro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alemnt.midi.MidiStatus
import com.alemnt.midi.Wire
import kotlin.math.roundToInt

@Composable
private fun Panel(title: String, dismiss: () -> Unit, confirm: (@Composable () -> Unit)? = null,
                  content: @Composable ColumnScope.() -> Unit) {
    val height = LocalConfiguration.current.screenHeightDp
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = (height - 146).coerceAtLeast(85).dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp),
                content = content)
        },
        confirmButton = { if (confirm != null) confirm() else TextButton(onClick = dismiss) { Text("Cerrar") } },
        dismissButton = if (confirm == null) null else ({ TextButton(onClick = dismiss) { Text("Cancelar") } }))
}

@Composable
fun ConfigPanel(panel: String, page: SurfacePage, p: Preset, status: MidiStatus, model: ControllerModel,
                notice: String, dismiss: () -> Unit, export: () -> Unit, import: () -> Unit, scan: () -> Unit) {
    val title = when (panel) {
        "midi" -> "Conexiones MIDI"
        "presets" -> "Presets y JSON"
        "superficie" -> page.label
        else -> "Configuración global"
    }
    Panel(title, dismiss) {
        when (panel) {
            "midi" -> MidiPanel(p, status, model, scan)
            "presets" -> {
                var name by remember { mutableStateOf("") }
                var saved by remember { mutableStateOf(model.names()) }
                OutlinedTextField(name, { if (it.length <= 48) name = it }, Modifier.fillMaxWidth(),
                    label = { Text("Nombre del preset") }, singleLine = true)
                Button(onClick = { model.save(name); saved = model.names() }, enabled = name.isNotBlank()) { Text("Guardar") }
                saved.forEach { preset ->
                    TextButton(onClick = { model.load(preset) }) { Text("Cargar · $preset") }
                }
                HorizontalDivider()
                Row {
                    TextButton(onClick = export) { Text("Exportar JSON") }
                    TextButton(onClick = import) { Text("Importar JSON") }
                }
                if (notice.isNotEmpty()) Hint(notice)
            }
            "superficie" -> when (page) {
                SurfacePage.PIANO, SurfacePage.SCALE, SurfacePage.CHORDS -> {
                    IntControl("Octava base", p.octave, 0..6) { value -> model.change { it.copy(octave = value) } }
                    if (page == SurfacePage.PIANO) {
                        IntControl("Octavas de piano", p.pianoOctaves, 1..8) { value ->
                            model.change { it.copy(pianoOctaves = value) }
                        }
                        FloatControl("Tamaño de tecla", p.pianoWidth, 26f..120f) { width ->
                            model.change { it.copy(pianoWidth = width) }
                        }
                        SwitchLine("Etiquetas de nota", p.labels) { enabled -> model.change { it.copy(labels = enabled) } }
                        Hint("Los círculos azules indican notas de la escala. Todas las teclas envían MIDI.")
                    }
                    if (page == SurfacePage.SCALE) {
                        IntControl("Octavas del teclado", p.smartOctaves, 1..6) { value ->
                            model.change { it.copy(smartOctaves = value) }
                        }
                        FloatControl("Tamaño de tecla", p.smartWidth, 32f..130f) { width ->
                            model.change { it.copy(smartWidth = width) }
                        }
                    }
                    if (page == SurfacePage.CHORDS) Hint("Las escalas de menos de siete grados dejan celdas libres para acordes personalizados.")
                }
                SurfacePage.FADERS -> {
                    SwitchLine("Enviar durante el movimiento", p.stream) { enabled -> model.change { it.copy(stream = enabled) } }
                    SwitchLine("Suavizado", p.smoothing) { enabled -> model.change { it.copy(smoothing = enabled) } }
                    SwitchLine("Filtro anti-jitter", p.jitter) { enabled -> model.change { it.copy(jitter = enabled) } }
                    Hint("MIDI 1.0: 14 bits con pares de CC 0–31. Otros CC se reducen a 7 bits. MIDI 2.0 escala el valor a su campo de 32 bits.")
                }
                else -> Unit
            }
            else -> {
                IntControl("Canal MIDI", p.channel + 1, 1..16) { value -> model.change { it.copy(channel = value - 1) } }
                IntControl("Velocity", p.velocity, 1..127) { value -> model.change { it.copy(velocity = value) } }
                SwitchLine("Velocity por presión", p.touchVelocity) { enabled -> model.change { it.copy(touchVelocity = enabled) } }
                SwitchLine("Aftertouch polifónico", p.aftertouch) { enabled -> model.change { it.copy(aftertouch = enabled) } }
                SwitchLine("Vibración háptica", p.haptics) { enabled -> model.change { it.copy(haptics = enabled) } }
                SwitchLine("Tema oscuro", p.dark) { enabled -> model.change { it.copy(dark = enabled) } }
                HorizontalDivider()
                IntControl("BPM", p.bpm, 30..300) { value -> model.change { it.copy(bpm = value) } }
                SwitchLine("Enviar MIDI Clock", p.clock) { enabled -> model.change { it.copy(clock = enabled) } }
                var bend by remember { mutableFloatStateOf(8192f) }
                Text("Pitch Bend · ${bend.roundToInt() - 8192}")
                Slider(bend, { bend = it; model.bend(it.roundToInt()) }, valueRange = 0f..16383f,
                    onValueChangeFinished = { bend = 8192f; model.bend(8192) })
                IntControl("Banco MSB", p.bankMsb, 0..127) { value -> model.change { it.copy(bankMsb = value) } }
                IntControl("Banco LSB", p.bankLsb, 0..127) { value -> model.change { it.copy(bankLsb = value) } }
                IntControl("Programa", p.program, 0..127) { value -> model.change { it.copy(program = value) } }
                Button(onClick = model::sendProgram) { Text("Enviar banco y programa") }
                TextButton(onClick = model::release) { Text("Liberar notas MIDI") }
            }
        }
    }
}

@Composable
private fun MidiPanel(p: Preset, status: MidiStatus, model: ControllerModel, scan: () -> Unit) {
    val destinations by model.midi.destinations.collectAsStateWithLifecycle()
    val bluetooth by model.bluetooth.peripherals.collectAsStateWithLifecycle()
    val feedback by model.bluetooth.feedback.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf<Wire?>(null) }
    val protocol = when (status.wire) {
        Wire.BYTES -> "MIDI 1.0"
        Wire.UMP1 -> "UMP · MIDI 1.0"
        Wire.UMP2 -> "UMP · MIDI 2.0"
    }
    Text(status.label, fontWeight = FontWeight.Bold)
    Hint("$protocol · ${status.sent} eventos enviados")
    if (status.destination != null) TextButton(onClick = model.midi::disconnect) { Text("Desconectar") }
    SwitchLine("Salida virtual Android", p.virtual) { enabled -> model.change { it.copy(virtual = enabled) } }
    Hint("${status.virtualClients} clientes virtuales. En otra app Android abre «Controller Out» o «Controller UMP» en Android 15+.")
    HorizontalDivider()
    Text("Puertos del sistema", fontWeight = FontWeight.SemiBold)
    SelectMenu(mode?.name ?: "Protocolo automático",
        listOf(null to "Automático", Wire.UMP1 to "UMP MIDI 1.0", Wire.UMP2 to "UMP MIDI 2.0"), { mode = it })
    IntControl("Grupo UMP", p.group + 1, 1..16) { value -> model.change { it.copy(group = value - 1) } }
    if (destinations.isEmpty()) Hint("Conecta una interfaz o un sintetizador USB MIDI, o selecciona MIDI en el modo USB del teléfono si está disponible.")
    destinations.forEach { destination ->
        TextButton(onClick = { model.midi.open(destination, mode, p.group) }) { Text(destination.name) }
    }
    HorizontalDivider()
    Text("Bluetooth LE MIDI", fontWeight = FontWeight.SemiBold)
    Hint(feedback)
    Button(onClick = scan) { Text("Buscar dispositivos") }
    bluetooth.forEach {
        TextButton(onClick = { model.bluetooth.stop(); model.midi.openBluetooth(it.device, it.name) }) { Text(it.name) }
    }
}

@Composable
fun EditCell(page: SurfacePage, index: Int, p: Preset, model: ControllerModel, dismiss: () -> Unit) {
    when (page) {
        SurfacePage.PADS -> {
            var pad by remember(index) { mutableStateOf(p.pads[index]) }
            Panel("Pad ${index + 1}", dismiss, {
                Button(onClick = { model.change { it.copy(pads = it.pads.replace(index, pad)) }; dismiss() }) { Text("Guardar") }
            }) {
                IntControl("Nota MIDI · ${noteName(pad.note)}", pad.note, 0..127) { pad = pad.copy(note = it) }
                IntControl("Velocity", pad.velocity, 1..127) { pad = pad.copy(velocity = it) }
                SwitchLine("Toggle", pad.toggle) { pad = pad.copy(toggle = it) }
                SwitchLine("Usar presión del dispositivo", pad.pressure) { pad = pad.copy(pressure = it) }
                OutlinedTextField(pad.icon, { if (it.length <= 8) pad = pad.copy(icon = it) },
                    label = { Text("Icono o texto breve") }, singleLine = true)
                Swatches(pad.color) { pad = pad.copy(color = it) }
            }
        }
        SurfacePage.CHORDS -> {
            val slot = p.chordOrder[index]
            var voice by remember(slot) { mutableStateOf(p.chords[slot]) }
            var custom by remember(slot) { mutableStateOf(voice.custom.joinToString(", ")) }
            val parts = custom.split(",").map { it.trim() }
            val intervals = if (custom.isBlank()) emptyList() else parts.mapNotNull { it.toIntOrNull() }
            val valid = custom.isBlank() || (parts.size == intervals.size && intervals.size in 1..8 &&
                intervals.all { it in -24..36 } && intervals.distinct().size == intervals.size)
            val inversionMax = (intervals.size.takeIf { it > 0 } ?: voice.size) - 1
            Panel("Acorde · ${degrees[index % 7]} · Fila ${index / 7 + 1}", dismiss, {
                Button(enabled = valid, onClick = {
                    val value = voice.copy(custom = intervals, inversion = voice.inversion.coerceAtMost(inversionMax))
                    model.change { it.copy(chords = it.chords.replace(slot, value)) }
                    dismiss()
                }) { Text("Guardar") }
            }) {
                val forms = listOf(3 to "Tríada", 4 to "Séptima", 5 to "Novena", 6 to "Onceava", 7 to "Treceava")
                SelectMenu(forms.first { it.first == voice.size }.second, forms, { voice = voice.copy(size = it) })
                IntControl("Inversión", voice.inversion.coerceAtMost(inversionMax), 0..inversionMax) {
                    voice = voice.copy(inversion = it)
                }
                OutlinedTextField(custom, { if (it.length <= 65) custom = it }, label = { Text("Semitonos de voicing") },
                    placeholder = { Text("0, 3, 7, 10") }, isError = !valid, singleLine = true)
                Hint("Usa intervalos distintos de −24 a 36. Vacío aplica el acorde diatónico.")
            }
        }
        SurfacePage.FADERS -> {
            var fader by remember(index) { mutableStateOf(p.faders[index]) }
            Panel("Fader ${index + 1}", dismiss, {
                Button(onClick = {
                    model.change { it.copy(faders = it.faders.replace(index, fader)) }; dismiss()
                }) { Text("Guardar") }
            }) {
                IntControl("Control Change", fader.cc, 0..127) { fader = fader.copy(cc = it) }
                SelectMenu(fader.curve.label, Curve.entries.map { it to it.label }, { fader = fader.copy(curve = it) })
                SwitchLine("Alta resolución · 14 bits", fader.fine) { fader = fader.copy(fine = it) }
                if (fader.fine && fader.cc >= 32) Hint("Este CC usa 7 bits en MIDI 1.0.")
                Swatches(fader.color) { fader = fader.copy(color = it) }
            }
        }
        else -> Unit
    }
}

@Composable
private fun Swatches(active: Int, select: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        palette.forEachIndexed { i, color ->
            Box(Modifier.size(29.dp).clip(CircleShape).background(Color(color)).semantics {
                contentDescription = "Color ${i + 1}"
            }.clickable { select(i) }, contentAlignment = Alignment.Center) {
                if (active == i) Text("✓", color = Color(0xFF141C2C), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

@Composable
private fun SwitchLine(label: String, enabled: Boolean, toggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Switch(enabled, toggle)
    }
}

@Composable
private fun FloatControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
    Text("$label · ${value.roundToInt()}", fontSize = 13.sp)
    Slider(value, update, valueRange = range)
}

@Composable
private fun IntControl(label: String, value: Int, range: IntRange, update: (Int) -> Unit) {
    if (range.first == range.last) {
        Text("$label · $value", fontSize = 13.sp)
        return
    }
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        OutlinedTextField(text, {
            if (it.length <= 3 && it.all(Char::isDigit)) {
                text = it
                it.toIntOrNull()?.takeIf { number -> number in range }?.let(update)
            }
        }, Modifier.size(width = 76.dp, height = 49.dp), singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Slider(value.toFloat(), { update(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = if (range.last - range.first < 25) range.last - range.first - 1 else 0)
}
