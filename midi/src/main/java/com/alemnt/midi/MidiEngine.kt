package com.alemnt.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Destination(val info: MidiDeviceInfo, val port: Int, val name: String, val ump: Boolean) {
    val id get() = "${info.id}:$port:$ump"
    val usbPeripheral get() = !ump && info.type == MidiDeviceInfo.TYPE_USB &&
        info.properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java) == null
}
data class MidiStatus(
    val destination: String? = null,
    val label: String = "Sin destino físico",
    val wire: Wire = Wire.BYTES,
    val virtualClients: Int = 0,
    val sent: Long = 0,
    val lastNoteOn: Int? = null,
    val lastNoteAt: Long = 0,
    val daw: DawFeedback = DawFeedback()
)

class MidiEngine(context: Context) {
    private val manager = context.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            task.run()
        }, "MIDI output")
    }
    private var tick: ScheduledFuture<*>? = null
    private var pulseAt = 0L
    private var bpm = 120
    private var sendClock = true
    private var running = false
    private var click: ToneGenerator? = null
    private var beatPulse = 0
    private var lan: LanMidiOutput? = null
    private var opening = false
    private var autoUsb = true
    private val _destinations = MutableStateFlow<List<Destination>>(emptyList())
    val destinations = _destinations.asStateFlow()
    private val _status = MutableStateFlow(MidiStatus())
    val status = _status.asStateFlow()
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var feedbackPort: MidiOutputPort? = null
    private var rewind: ScheduledFuture<*>? = null
    private val virtual = mutableMapOf<Wire, Pair<MidiReceiver, Int>>()
    private var group = 0
    private var generation = 0
    private var virtualEnabled = true
    private val tokens = mutableMapOf<Long, Pair<Int, List<Int>>>()
    private val counts = mutableMapOf<Pair<Int, Int>, Int>()

    init {
        listOf(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS)
            .forEach { transport ->
                manager?.registerDeviceCallback(transport, worker, object : MidiManager.DeviceCallback() {
                    override fun onDeviceAdded(info: MidiDeviceInfo) = refresh()
                    override fun onDeviceRemoved(info: MidiDeviceInfo) {
                        if (device?.info?.id == info.id) {
                            generation++
                            close("Dispositivo desconectado")
                        }
                        refresh()
                    }
                })
            }
        worker.execute(::refresh)
    }

    fun scanPorts() = worker.execute(::refresh)

    private fun refresh() {
        if (manager == null) return
        _destinations.value = listOf(false, true).flatMap { ump ->
            val transport = if (ump) MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS
                else MidiManager.TRANSPORT_MIDI_BYTE_STREAM
            devices(transport, ump).flatMap { info ->
                val label = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI ${info.id}"
                info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT && it.name != "Controller UMP" }.map { port ->
                    val type = when (info.type) {
                        MidiDeviceInfo.TYPE_USB -> "USB"
                        MidiDeviceInfo.TYPE_BLUETOOTH -> "BLE"
                        else -> "Virtual"
                    }
                    val portName = port.name.orEmpty().ifBlank { "Entrada ${port.portNumber + 1}" }
                    Destination(info, port.portNumber, "$label · $portName · $type", ump)
                }
            }
        }
        if (autoUsb && !opening && _status.value.destination == null) {
            _destinations.value.singleOrNull { it.usbPeripheral }?.let { openPort(it, null) }
        }
    }

    @Suppress("DEPRECATION")
    private fun devices(transport: Int, ump: Boolean): List<MidiDeviceInfo> {
        val current = manager?.getDevicesForTransport(transport).orEmpty()
        return if (ump) current.toList()
            else (current + manager?.devices.orEmpty()).distinctBy { it.id }
    }

    fun open(destination: Destination, force: Wire?) = worker.execute {
        autoUsb = destination.usbPeripheral
        openPort(destination, force)
    }

    private fun openPort(destination: Destination, force: Wire?) {
        val service = manager ?: return
        val ticket = ++generation
        close("Abriendo ${destination.name}…")
        opening = true
        try {
            service.openDevice(destination.info, { opened ->
                worker.execute {
                    if (ticket != generation) {
                        opened?.close()
                    } else {
                        val wire = if (!destination.ump) Wire.BYTES else force ?:
                            if ((opened?.info?.defaultProtocol ?: 0) >= 17) Wire.UMP2 else Wire.UMP1
                        bind(opened, destination.port, destination.id, destination.name, wire, destination.usbPeripheral)
                    }
                }
            }, main)
        } catch (_: IllegalArgumentException) {
            close("El puerto MIDI ya no está disponible")
        } catch (_: SecurityException) {
            close("Android rechazó el acceso a ese puerto")
        }
    }

    @SuppressLint("MissingPermission")
    fun openBluetooth(peripheral: BluetoothDevice, label: String) = worker.execute {
        val service = manager ?: return@execute
        autoUsb = false
        val ticket = ++generation
        close("Conectando por Bluetooth…")
        opening = true
        try {
            service.openBluetoothDevice(peripheral, { opened ->
                worker.execute {
                    if (ticket != generation) opened?.close()
                    else {
                        val input = opened?.info?.ports?.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                        bind(opened, input?.portNumber ?: -1, "ble:${opened?.info?.id}", label, Wire.BYTES)
                    }
                }
            }, main)
        } catch (_: SecurityException) {
            close("Permiso Bluetooth requerido")
        }
    }

    private fun bind(opened: MidiDevice?, number: Int, id: String, label: String, wire: Wire,
                     usbFeedback: Boolean = false) {
        opening = false
        val newPort = if (number >= 0) opened?.openInputPort(number) else null
        if (opened == null || newPort == null) {
            opened?.close()
            _status.value = _status.value.copy(label = "Puerto ocupado o no disponible")
            return
        }
        device = opened
        port = newPort
        _status.value = _status.value.copy(destination = id, label = label, wire = wire)
        if (usbFeedback && opened.info.outputPortCount > 0) {
            feedbackPort = runCatching { opened.openOutputPort(0) }.getOrNull()
            val ticket = generation
            val reader = DawFeedbackReader { cc, value ->
                worker.execute {
                    if (ticket == generation && device === opened) {
                        val current = _status.value
                        val feedback = current.daw.withControl(cc, value)
                        if (current.daw != feedback) _status.value = current.copy(daw = feedback)
                    }
                }
            }
            feedbackPort?.connect(object : MidiReceiver() {
                override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    reader.accept(data, offset, count)
                }
            })
        }
    }

    fun connectLan(host: String, code: String) = worker.execute {
        if (host.isBlank() || code.length != 6 || code.any { it !in '0'..'9' }) return@execute
        autoUsb = false
        val ticket = ++generation
        close("Conectando al puente en $host…")
        opening = true
        lan = LanMidiOutput(host, code, onReady = {
            worker.execute {
                if (ticket == generation) {
                    opening = false
                    _status.value = _status.value.copy(destination = "lan:$host", label = "Puente MIDI · $host")
                }
            }
        }, onClosed = { reason ->
            worker.execute { if (ticket == generation) close(reason) }
        }).also { it.start() }
    }

    fun disconnect() = worker.execute {
        autoUsb = false
        generation++
        close("Sin destino físico")
    }

    private fun close(label: String) {
        rewind?.cancel(false)
        rewind = null
        releaseAll()
        runCatching { feedbackPort?.close() }
        feedbackPort = null
        runCatching { port?.close() }
        runCatching { device?.close() }
        port = null
        device = null
        lan?.close()
        lan = null
        opening = false
        _status.value = _status.value.copy(destination = null, label = label, wire = Wire.BYTES, daw = DawFeedback())
    }

    fun virtualReceiver(receiver: MidiReceiver?, clients: Int, wire: Wire = Wire.BYTES) = worker.execute {
        if (receiver != null && clients > 0) virtual[wire] = receiver to clients
        else virtual.remove(wire)
        _status.value = _status.value.copy(virtualClients = virtual.values.sumOf { it.second })
    }

    fun setGroup(value: Int) = worker.execute {
        if (group != value) releaseAll()
        group = value
    }

    fun virtualEnabled(enabled: Boolean) = worker.execute {
        if (virtualEnabled != enabled) releaseAll()
        virtualEnabled = enabled
    }

    fun noteOn(token: Long, channel: Int, notes: List<Int>, velocity: Int) = worker.execute {
        release(token)
        tokens[token] = channel to notes
        notes.forEach { pitch ->
            val key = channel to pitch
            val count = counts[key] ?: 0
            counts[key] = count + 1
            if (count == 0) transmit(Message.Note(channel, pitch, velocity, true))
        }
    }

    fun noteOff(token: Long) = worker.execute { release(token) }

    private fun release(token: Long) {
        val (channel, notes) = tokens.remove(token) ?: return
        notes.forEach { pitch ->
            val key = channel to pitch
            val count = counts.getValue(key) - 1
            if (count == 0) {
                counts.remove(key)
                transmit(Message.Note(channel, pitch, 0, false))
            } else counts[key] = count
        }
    }

    fun pressure(token: Long, value: Int) = worker.execute {
        tokens[token]?.let { (channel, notes) ->
            notes.forEach { transmit(Message.Pressure(channel, it, value)) }
        }
    }

    fun clockSettings(tempo: Int, enabled: Boolean) = worker.execute {
        bpm = tempo
        sendClock = enabled
    }

    fun metronome(enabled: Boolean) = worker.execute {
        if (enabled && click == null) {
            click = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 62) }.getOrNull()
        } else if (!enabled) {
            click?.release()
            click = null
        }
    }

    fun startClock(resume: Boolean) = worker.execute {
        if (!resume) beatPulse = 0
        tick?.cancel(false)
        transmit(Message.System(if (resume) 0xFB else 0xFA))
        running = true
        pulseAt = System.nanoTime()
        pulse()
    }

    fun stopClock() = worker.execute {
        val wasRunning = running
        running = false
        tick?.cancel(false)
        tick = null
        click?.stopTone()
        if (wasRunning) transmit(Message.System(0xFC))
    }

    private fun pulse() {
        if (!running) return
        val now = System.nanoTime()
        val interval = 60_000_000_000L / (bpm * 24)
        if (beatPulse == 0) click?.startTone(ToneGenerator.TONE_PROP_BEEP, 45)
        beatPulse = (beatPulse + 1) % 24
        if (sendClock) transmit(Message.System(0xF8))
        pulseAt = maxOf(pulseAt + interval, now + interval / 2)
        tick = worker.schedule(::pulse, (pulseAt - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    fun sendDaw(control: DawControl, resetToStart: Boolean = false) = worker.execute {
        val feedback = _status.value.daw
        if (control == DawControl.PLAY && feedback.playing == true) return@execute
        if (!resetToStart && control == DawControl.PAUSE &&
            feedback.playing == false && feedback.recording != true) return@execute
        if (control != DawControl.CLICK) {
            rewind?.cancel(false)
            rewind = null
        }
        sendDawPulse(control)
        if (resetToStart) {
            // Waveform stops on the first command and zeroes its cursor when already stopped.
            rewind = worker.schedule({ sendDawPulse(DawControl.REWIND_ZERO) }, 150, TimeUnit.MILLISECONDS)
        }
    }

    private fun sendDawPulse(control: DawControl) {
        transmit(control.press())
        transmit(control.release())
    }

    fun send(message: Message) = worker.execute { transmit(message) }
    fun panic() = worker.execute { releaseAll() }

    private fun releaseAll() {
        tokens.keys.toList().forEach(::release)
    }

    private fun transmit(message: Message) {
        val timestamp = System.nanoTime()
        val current = _status.value
        var sent = false
        fun deliver(receiver: MidiReceiver, wire: Wire, group: Int): Boolean = try {
            val data = MidiCodec.encode(message, wire, group)
            receiver.send(data, 0, data.size, timestamp)
            true
        } catch (_: IOException) {
            false
        }
        port?.let { output ->
            sent = deliver(output, current.wire, group)
            if (!sent) {
                runCatching { output.close() }
                runCatching { feedbackPort?.close() }
                feedbackPort = null
                runCatching { device?.close() }
                port = null
                device = null
                _status.value = current.copy(destination = null, label = "Conexión MIDI perdida", wire = Wire.BYTES, daw = DawFeedback())
            }
        }
        lan?.let { sent = it.send(MidiCodec.encode(message, Wire.BYTES)) || sent }
        if (virtualEnabled) virtual.forEach { (wire, target) ->
            sent = deliver(target.first, wire, if (wire == Wire.BYTES) 0 else group) || sent
        }
        if (sent) {
            val state = _status.value
            _status.value = if (message is Message.Note && message.on)
                state.copy(sent = state.sent + 1, lastNoteOn = message.pitch, lastNoteAt = SystemClock.uptimeMillis())
            else state.copy(sent = state.sent + 1)
        }
    }
}
