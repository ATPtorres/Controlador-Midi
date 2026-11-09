package com.alemnt.midipro

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alemnt.midi.MidiStatus
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: ControllerModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val page by model.page.collectAsStateWithLifecycle()
            val preset by model.preset.collectAsStateWithLifecycle()
            LaunchedEffect(page) {
                val orientation = if (page == SurfacePage.GRID) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                if (requestedOrientation != orientation) requestedOrientation = orientation
            }
            ControllerTheme(preset.dark) { Workspace(page, preset, model) }
        }
    }

    override fun onStop() {
        model.stopLocal()
        model.bluetooth.stop()
        super.onStop()
    }
}

private val darkScheme = darkColorScheme(
    primary = Color(0xFF76DCFF), onPrimary = Color(0xFF062536), secondary = Color(0xFFAB95FF),
    background = Color(0xFF10131B), surface = Color(0xFF151A25), surfaceContainer = Color(0xFF1E2637),
    onSurface = Color(0xFFECF1FF), onSurfaceVariant = Color(0xFFA5B2CC),
    outline = Color(0xFF65728C), error = Color(0xFFFF7C9B)
)
private val lightScheme = lightColorScheme(
    primary = Color(0xFF005D82), secondary = Color(0xFF6247A8), background = Color(0xFFF5F7FD),
    surface = Color(0xFFE9EEF9), surfaceContainer = Color(0xFFDDE6F7), onSurface = Color(0xFF172238),
    onSurfaceVariant = Color(0xFF465470), outline = Color(0xFF66748F)
)

@Composable
private fun ControllerTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)

@Composable
private fun Workspace(page: SurfacePage, p: Preset, model: ControllerModel) {
    val status by model.midi.status.collectAsStateWithLifecycle()
    val transport by model.transport.collectAsStateWithLifecycle()
    val latched by model.latched.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<String?>(null) }
    var cell by remember(page) { mutableStateOf<Int?>(null) }
    var edit by remember(page) { mutableStateOf(false) }
    val grid = page == SurfacePage.GRID
    val canEdit = page in listOf(SurfacePage.PADS, SurfacePage.CHORDS, SurfacePage.FADERS)
    val keyboard = page == SurfacePage.PIANO || page == SurfacePage.SCALE
    val showScale = grid || keyboard || page == SurfacePage.CHORDS
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) model.export(it)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.import(it)
    }
    val blePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) model.bluetooth.start()
    }
    val searching by model.bluetooth.feedback.collectAsStateWithLifecycle()
    LaunchedEffect(searching) {
        if (searching.startsWith("Buscando")) {
            delay(10_000)
            model.bluetooth.stop()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
            val compact = maxHeight < 430.dp
            val broad = maxWidth > 760.dp
            val portrait = maxHeight > maxWidth
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 7.dp)) {
                TransportBar(transport, p.bpm, status, p.virtual, p.metronome, p.dawTransport, compact, broad, portrait, model) { panel = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (grid) {
                        SelectMenu("Grid Smart Keyboard", SurfacePage.entries.map { it to it.short },
                            { model.select(it) })
                    } else {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            SurfacePage.entries.forEach { candidate ->
                                FilterChip(selected = page == candidate, onClick = { model.select(candidate) },
                                    label = { Text(candidate.short, maxLines = 1) },
                                    modifier = Modifier.height(if (compact) 37.dp else 44.dp))
                            }
                        }
                    }
                    if (canEdit) {
                        FilterChip(edit, {
                            model.release()
                            edit = !edit
                        }, { Text(if (edit) "Salir de edición" else "Editar") })
                    }
                }

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showScale) {
                        SelectMenu("Raíz · ${pitchNames[p.root]}", pitchNames.mapIndexed { i, name -> i to name },
                            { value -> model.change { it.copy(root = value) } })
                        SelectMenu(p.scale.label, Scale.entries.map { it to it.label },
                            { scale -> model.change { it.copy(scale = scale) } })
                        if (grid) {
                            TextButton(onClick = { model.change { it.copy(octave = (it.octave - 1).coerceAtLeast(0)) } }) {
                                Text("−")
                            }
                            Text("Oct. ${p.octave}", fontSize = 12.sp)
                            TextButton(onClick = { model.change { it.copy(octave = (it.octave + 1).coerceAtMost(6)) } }) {
                                Text("+")
                            }
                        } else {
                            TextButton(onClick = { panel = "superficie" }) { Text(if (keyboard) "Tamaño y rango" else "Rango") }
                        }
                    }
                    if (page == SurfacePage.FADERS) {
                        FilterChip(p.stream, { model.change { it.copy(stream = !it.stream) } }, { Text("Stream CC") })
                        TextButton(onClick = { panel = "superficie" }) { Text("Suavizado") }
                    }
                    if (page == SurfacePage.PADS && edit) Text("Toca un pad para configurarlo", fontSize = 12.sp)
                    if (page == SurfacePage.CHORDS && edit) Text("Toca una celda para editarla o arrástrala para moverla", fontSize = 12.sp)
                    if (page == SurfacePage.FADERS && edit) Text("Toca un fader para asignarlo", fontSize = 12.sp)
                    if (page == SurfacePage.CHORDS && !edit) {
                        FilterChip(p.learning, { model.change { it.copy(learning = !it.learning) } }, { Text("Ver notas") })
                    }
                }

                PerformanceSurface(page, p, model, latched, status.lastNoteOn, status.lastNoteAt, edit, { cell = it },
                    Modifier.weight(1f).fillMaxWidth())
                if (!compact && !grid) {
                    Text("${page.label}   /   CANAL ${p.channel + 1}   /   ${status.sent} EVENTOS MIDI",
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (panel != null) {
        ConfigPanel(panel!!, page, p, status, model, notice, { panel = null },
            { export.launch("midi-performance-preset.json") },
            { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            { blePermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)) })
    }
    cell?.let { EditCell(page, it, p, model) { cell = null } }
}

@Composable
private fun TransportBar(t: Transport, bpm: Int, status: MidiStatus, virtualEnabled: Boolean,
                         metronome: Boolean, dawTransport: Boolean, compact: Boolean, broad: Boolean, portrait: Boolean,
                         model: ControllerModel, open: (String) -> Unit) {
    val elapsed = t.elapsedMs / 1000
    val time = if (dawTransport) "DAW" else String.format(Locale.ROOT, "%02d:%02d", elapsed / 60, elapsed % 60)
    val playing = if (dawTransport) status.daw.playing == true else t.playing
    val recording = if (dawTransport) status.daw.recording == true else t.recording
    val click = if (dawTransport) status.daw.click == true else metronome
    val height = if (compact) 40.dp else 49.dp
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
        .padding(horizontal = 5.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (broad) {
                Text("MIDI PRO", fontWeight = FontWeight.Black, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 7.dp))
            }
            listOf(
                Triple("▶", playing, model::play),
                Triple("Ⅱ", !dawTransport && !t.playing && t.elapsedMs > 0, model::pause),
                Triple("●", recording, model::record),
                Triple("■", false, model::stop)
            ).forEachIndexed { i, (symbol, active, action) ->
                val name = listOf("Play", "Pause", "Record", "Stop")[i]
                val colors = MaterialTheme.colorScheme
                val highlight = if (i == 2) colors.error else colors.primary
                Button(action, Modifier.height(height).width(if (broad) 83.dp else 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) highlight else colors.surfaceContainer,
                        contentColor = if (active) colors.background else if (i == 2) highlight else colors.onSurface),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    val label = if (broad) "$symbol $name" else symbol
                    Text(label, fontWeight = FontWeight.Bold, fontSize = if (broad) 13.sp else 17.sp,
                        modifier = Modifier.semantics { contentDescription = name })
                }
            }
            FilterChip(click, model::toggleMetronome,
                { Text(if (dawTransport) "Metrónomo DAW" else "Metrónomo", maxLines = 1) })
            if (!portrait) TransportIndicators(time, bpm.takeUnless { dawTransport }, status, virtualEnabled, open)
        }
        if (portrait) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TransportIndicators(time, bpm.takeUnless { dawTransport }, status, virtualEnabled, open)
            }
        }
    }
}

@Composable
private fun TransportIndicators(time: String, bpm: Int?, status: MidiStatus, virtualEnabled: Boolean,
                                open: (String) -> Unit) {
    Text(time, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 4.dp))
    if (bpm != null) TextButton(onClick = { open("ajustes") }) { Text("$bpm BPM", maxLines = 1, fontSize = 11.sp) }
    val connected = status.destination != null || (status.virtualClients > 0 && virtualEnabled)
    TextButton(onClick = { open("midi") }) {
        Text(if (connected) "● MIDI" else "○ MIDI", maxLines = 1, fontSize = 12.sp,
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = { open("ajustes") }) { Text("⚙", fontSize = 19.sp) }
    TextButton(onClick = { open("presets") }) { Text("Presets", maxLines = 1, fontSize = 11.sp) }
}

@Composable
fun <T> SelectMenu(label: String, options: List<Pair<T, String>>, select: (T) -> Unit,
                   modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding =
            androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Text("$label  ▾", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.fillMaxHeight(.72f)) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; select(value) })
            }
        }
    }
}
