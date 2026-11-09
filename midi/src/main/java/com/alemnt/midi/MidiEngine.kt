package com.alemnt.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Destination(val info: MidiDeviceInfo, val port: Int, val name: String, val ump: Boolean) {
    val id get() = "${info.id}:$port:$ump"
}
data class MidiStatus(
    val destination: String? = null,
    val label: String = "Sin destino físico",
    val wire: Wire = Wire.BYTES,
    val virtualClients: Int = 0,
    val sent: Long = 0
)

class MidiEngine(context: Context) {
    private val manager = context.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            task.run()
        }, "MIDI output")
    }
    private val _destinations = MutableStateFlow<List<Destination>>(emptyList())
    val destinations = _destinations.asStateFlow()
    private val _status = MutableStateFlow(MidiStatus())
    val status = _status.asStateFlow()
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private val virtual = mutableMapOf<Wire, Pair<MidiReceiver, Int>>()
    private var virtualGroup = 0
    private var group = 0
    private var generation = 0
    private var virtualEnabled = true
    private val tokens = mutableMapOf<Long, Pair<Int, List<Int>>>()
    private val counts = mutableMapOf<Pair<Int, Int>, Int>()

    init {
        val callback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = refresh()
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                refresh()
                if (device?.info?.id == info.id) {
                    generation++
                    close("Dispositivo desconectado")
                }
            }
        }
        listOf(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS)
            .forEach { transport -> manager?.registerDeviceCallback(transport, worker, callback) }
        refresh()
    }

    private fun refresh() {
        if (manager == null) return
        _destinations.value = listOf(false, true).flatMap { ump ->
            val transport = if (ump) MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS
                else MidiManager.TRANSPORT_MIDI_BYTE_STREAM
            manager.getDevicesForTransport(transport).flatMap { info ->
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
    }

    fun open(destination: Destination, force: Wire?, midiGroup: Int) = worker.execute {
        val service = manager ?: return@execute
        val ticket = ++generation
        close("Abriendo ${destination.name}…")
        service.openDevice(destination.info, { opened ->
            worker.execute {
                if (ticket != generation) {
                    opened?.close()
                } else {
                    val wire = if (!destination.ump) Wire.BYTES else force ?:
                        if ((opened?.info?.defaultProtocol ?: 0) >= 17) Wire.UMP2 else Wire.UMP1
                    bind(opened, destination.port, destination.id, destination.name, wire, midiGroup)
                }
            }
        }, main)
    }

    @SuppressLint("MissingPermission")
    fun openBluetooth(peripheral: BluetoothDevice, label: String) = worker.execute {
        val service = manager ?: return@execute
        val ticket = ++generation
        close("Conectando por Bluetooth…")
        try {
            service.openBluetoothDevice(peripheral, { opened ->
                worker.execute {
                    if (ticket != generation) opened?.close()
                    else {
                        val input = opened?.info?.ports?.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                        bind(opened, input?.portNumber ?: -1, "ble:${opened?.info?.id}", label, Wire.BYTES, 0)
                    }
                }
            }, main)
        } catch (_: SecurityException) {
            close("Permiso Bluetooth requerido")
        }
    }

    private fun bind(opened: MidiDevice?, number: Int, id: String, label: String, wire: Wire, midiGroup: Int) {
        val newPort = if (number >= 0) opened?.openInputPort(number) else null
        if (opened == null || newPort == null) {
            opened?.close()
            _status.value = _status.value.copy(label = "Puerto ocupado o no disponible")
            return
        }
        device = opened
        port = newPort
        group = midiGroup
        _status.value = _status.value.copy(destination = id, label = label, wire = wire)
    }

    fun disconnect() = worker.execute {
        generation++
        close("Sin destino físico")
    }

    private fun close(label: String) {
        releaseAll()
        runCatching { port?.close() }
        runCatching { device?.close() }
        port = null
        device = null
        _status.value = _status.value.copy(destination = null, label = label, wire = Wire.BYTES)
    }

    fun virtualReceiver(receiver: MidiReceiver?, clients: Int, wire: Wire = Wire.BYTES) = worker.execute {
        if (receiver != null && clients > 0) virtual[wire] = receiver to clients
        else virtual.remove(wire)
        _status.value = _status.value.copy(virtualClients = virtual.values.sumOf { it.second })
    }

    fun setGroup(value: Int) = worker.execute {
        if (group != value || virtualGroup != value) releaseAll()
        group = value
        virtualGroup = value
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
                runCatching { device?.close() }
                port = null
                device = null
                _status.value = current.copy(destination = null, label = "Conexión MIDI perdida", wire = Wire.BYTES)
            }
        }
        if (virtualEnabled) virtual.forEach { (wire, target) ->
            sent = deliver(target.first, wire, if (wire == Wire.BYTES) 0 else virtualGroup) || sent
        }
        if (sent) _status.value = _status.value.copy(sent = current.sent + 1)
    }
}
