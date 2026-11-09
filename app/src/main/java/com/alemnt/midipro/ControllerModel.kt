package com.alemnt.midipro

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alemnt.midi.DawControl
import com.alemnt.midi.Message
import com.alemnt.midi.MidiEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class Transport(val playing: Boolean = false, val recording: Boolean = false, val elapsedMs: Long = 0)

@HiltViewModel
class ControllerModel @Inject constructor(
    val midi: MidiEngine,
    val bluetooth: BleDiscovery,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val storage = context.getSharedPreferences("controller", Context.MODE_PRIVATE)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _preset = MutableStateFlow(decode(storage.getString("live", null)) ?: Preset())
    val preset = _preset.asStateFlow()
    private val _page = MutableStateFlow(SurfacePage.PIANO)
    val page = _page.asStateFlow()
    private val _transport = MutableStateFlow(Transport())
    val transport = _transport.asStateFlow()
    private val _latched = MutableStateFlow<Set<Int>>(emptySet())
    val latched = _latched.asStateFlow()
    private val _notice = MutableStateFlow("")
    val notice = _notice.asStateFlow()
    private val touchIds = mutableMapOf<Long, Int>()
    private val lastValue = IntArray(6) { -1 }
    private val pending = arrayOfNulls<Job>(6)
    private val lastSend = LongArray(6)
    private val nextValue = IntArray(6)
    private var clock: Job? = null
    private var startedAt = 0L
    private var persistJob: Job? = null

    init {
        midi.virtualEnabled(preset.value.virtual)
        midi.setGroup(preset.value.group)
        midi.clockSettings(preset.value.bpm, preset.value.clock)
        midi.metronome(preset.value.metronome && !preset.value.dawTransport)
        viewModelScope.launch {
            var destination: String? = null
            midi.status.collect { state ->
                if (destination != state.destination) {
                    _latched.value = emptySet()
                    destination = state.destination
                }
            }
        }
    }

    private fun decode(value: String?) = value?.let {
        runCatching { json.decodeFromString<Preset>(it) }.getOrNull()?.takeIf(Preset::valid)
    }

    fun change(edit: (Preset) -> Preset) {
        val old = _preset.value
        val new = edit(old)
        if (old.channel != new.channel || old.root != new.root || old.scale != new.scale ||
            old.octave != new.octave || old.pads != new.pads || old.chords != new.chords ||
            old.chordOrder != new.chordOrder || old.pianoOctaves != new.pianoOctaves ||
            old.smartOctaves != new.smartOctaves || old.virtual != new.virtual || old.group != new.group) release()
        if (old.virtual != new.virtual) midi.virtualEnabled(new.virtual)
        if (old.group != new.group) midi.setGroup(new.group)
        if (old.dawTransport != new.dawTransport) stopLocal()
        if (old.metronome != new.metronome || old.dawTransport != new.dawTransport) {
            midi.metronome(new.metronome && !new.dawTransport)
        }
        if (old.bpm != new.bpm || old.clock != new.clock) midi.clockSettings(new.bpm, new.clock)
        if (old.channel != new.channel || old.dawTransport != new.dawTransport ||
            old.faders.indices.any { old.faders[it].cc != new.faders[it].cc }) {
            pending.forEach { it?.cancel() }
            lastValue.fill(-1)
        }
        _preset.value = new
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(180)
            storage.edit().putString("live", json.encodeToString(Preset.serializer(), new)).apply()
        }
    }

    fun lastBridgeHost(): String = storage.getString("bridgeHost", "").orEmpty()

    fun connectBridge(host: String, code: String) {
        storage.edit().putString("bridgeHost", host).apply()
        midi.connectLan(host, code)
    }

    fun select(page: SurfacePage) {
        if (page == _page.value) return
        release()
        _page.value = page
    }

    fun release() {
        midi.panic()
        touchIds.clear()
        _latched.value = emptySet()
        pending.forEach { it?.cancel() }
    }

    fun save(name: String) {
        val presetName = name.trim()
        if (presetName.isEmpty()) return
        storage.edit().putString("preset:$presetName", json.encodeToString(Preset.serializer(), preset.value)).apply()
        _notice.value = "Preset guardado · $presetName"
    }

    fun names() = storage.all.keys.filter { it.startsWith("preset:") }.map { it.removePrefix("preset:") }.sorted()
    fun load(name: String) {
        decode(storage.getString("preset:$name", null))?.let {
            release()
            change { _ -> it }
            _notice.value = "Preset cargado · $name"
        }
    }

    fun export(uri: Uri) = viewModelScope.launch {
        val encoded = json.encodeToString(Preset.serializer(), preset.value)
        _notice.value = withContext(Dispatchers.IO) {
            runCatching {
                checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(encoded) }
                "Configuración exportada"
            }.getOrDefault("No se pudo exportar el JSON")
        }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        val imported = withContext(Dispatchers.IO) {
            runCatching {
                checkNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                    val bytes = stream.readNBytes(262145)
                    if (bytes.size > 262144) null else decode(bytes.toString(Charsets.UTF_8))
                }
            }.getOrNull()
        }
        if (imported == null) _notice.value = "JSON inválido o incompatible"
        else {
            release()
            change { imported }
            _notice.value = "Configuración importada"
        }
    }

    fun press(token: Long, notes: List<Int>, pressure: Float, padIndex: Int? = null) {
        if (notes.isEmpty()) return
        val p = preset.value
        val pad = padIndex?.let { p.pads[it] }
        val velocity = if (pad?.pressure ?: p.touchVelocity) velocity(pressure, pad?.velocity ?: p.velocity)
            else pad?.velocity ?: p.velocity
        if (pad?.toggle == true) {
            val latchToken = Long.MIN_VALUE + padIndex
            if (padIndex in _latched.value) {
                _latched.value -= padIndex
                midi.noteOff(latchToken)
            } else {
                _latched.value += padIndex
                midi.noteOn(latchToken, p.channel, notes, velocity)
            }
        } else {
            touchIds[token] = if (pressure in 0.01f..1f) (pressure * 127).roundToInt() else -1
            midi.noteOn(token, p.channel, notes, velocity)
        }
    }

    fun lift(token: Long) {
        if (touchIds.remove(token) != null) midi.noteOff(token)
    }

    fun pressure(token: Long, pressure: Float) {
        if (!preset.value.aftertouch || pressure <= 0f) return
        val last = touchIds[token]?.takeIf { it >= 0 } ?: return
        val value = (pressure * 127).roundToInt().coerceIn(1, 127)
        if (abs(last - value) >= 3) {
            touchIds[token] = value
            midi.pressure(token, value)
        }
    }

    private fun velocity(pressure: Float, fallback: Int) =
        if (pressure > 0f && pressure < 0.999f) (pressure * 127).roundToInt().coerceIn(1, 127) else fallback

    fun moveFader(index: Int, position: Float, final: Boolean = false) {
        val p = preset.value
        val fader = p.faders[index]
        if (p.dawTransport && p.channel == DawControl.CHANNEL &&
            DawControl.entries.any { it.cc == fader.cc }) return
        val target = position.coerceIn(0f, 1f)
        val shown = if (p.smoothing && !final) fader.value + (target - fader.value) * 0.52f else target
        change { it.copy(faders = it.faders.replace(index, fader.copy(value = shown))) }
        if (!p.stream && !final) return
        val value = fader.output(shown)
        val threshold = if (p.jitter && fader.fine && !final) 28 else 1
        if (abs(lastValue[index] - value) < threshold && lastValue[index] >= 0 && !final) return
        nextValue[index] = value
        fun send() {
            val current = nextValue[index]
            midi.send(Message.Control(p.channel, fader.cc, current, fader.fine))
            lastValue[index] = current
            lastSend[index] = SystemClock.uptimeMillis()
        }
        val wait = 8 - (SystemClock.uptimeMillis() - lastSend[index])
        if (final || wait <= 0) {
            pending[index]?.cancel()
            pending[index] = null
            send()
        } else if (pending[index]?.isActive != true) {
            pending[index] = viewModelScope.launch {
                delay(wait)
                send()
            }
        }
    }

    fun bend(value: Int) = midi.send(Message.Bend(preset.value.channel, value))
    fun sendProgram() = preset.value.run { midi.send(Message.Program(channel, program, bankMsb, bankLsb)) }

    fun toggleMetronome() {
        if (preset.value.dawTransport) midi.sendDaw(DawControl.CLICK)
        else change { it.copy(metronome = !it.metronome) }
    }

    fun play() {
        if (preset.value.dawTransport) {
            midi.sendDaw(DawControl.PLAY)
            return
        }
        val old = _transport.value
        if (old.playing) return
        midi.startClock(resume = old.elapsedMs > 0)
        startedAt = SystemClock.elapsedRealtime() - old.elapsedMs
        _transport.value = old.copy(playing = true)
        clock?.cancel()
        clock = viewModelScope.launch {
            while (isActive) {
                delay(80)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                _transport.update { if (it.playing) it.copy(elapsedMs = elapsed) else it }
            }
        }
    }

    fun pause() {
        if (preset.value.dawTransport) {
            midi.sendDaw(DawControl.PAUSE)
            return
        }
        if (!_transport.value.playing) return
        clock?.cancel()
        _transport.value = _transport.value.copy(playing = false, elapsedMs = SystemClock.elapsedRealtime() - startedAt)
        midi.stopClock()
    }

    fun record() {
        if (preset.value.dawTransport) {
            midi.sendDaw(DawControl.RECORD)
            return
        }
        val recording = !_transport.value.recording
        midi.send(Message.Transport(if (recording) 0x06 else 0x07))
        _transport.value = _transport.value.copy(recording = recording)
        if (recording) play()
    }

    fun stop() {
        if (preset.value.dawTransport) midi.sendDaw(DawControl.PAUSE, resetToStart = true)
        stopLocal()
    }

    fun stopLocal() {
        clock?.cancel()
        if (_transport.value.recording) midi.send(Message.Transport(0x07))
        midi.stopClock()
        _transport.value = Transport()
        release()
    }

    override fun onCleared() {
        stopLocal()
        bluetooth.stop()
    }
}
