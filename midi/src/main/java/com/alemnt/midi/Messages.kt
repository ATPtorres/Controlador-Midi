package com.alemnt.midi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

sealed interface Message {
    data class Note(val channel: Int, val pitch: Int, val velocity: Int, val on: Boolean) : Message
    data class Pressure(val channel: Int, val pitch: Int, val value: Int) : Message
    data class Control(val channel: Int, val controller: Int, val value: Int, val fine: Boolean = false) : Message
    data class Bend(val channel: Int, val value: Int) : Message
    data class Program(val channel: Int, val program: Int, val bankMsb: Int, val bankLsb: Int) : Message
    data class System(val status: Int) : Message
    data class Transport(val command: Int) : Message
}

enum class Wire { BYTES, UMP1, UMP2 }

object MidiCodec {
    fun encode(message: Message, wire: Wire, group: Int = 0): ByteArray {
        val packets = byteMessages(message)
        if (message is Message.Transport && wire != Wire.BYTES) {
            return buffer(8).putInt((0x30 or group) shl 24 or 0x047F7F)
                .putInt(0x06000000 or (message.command shl 16)).array()
        }
        if (wire == Wire.BYTES) return packets.fold(ByteArray(0)) { a, b -> a + b }
        if (wire == Wire.UMP1 || message is Message.System) {
            val out = buffer(packets.size * 4)
            packets.forEach { p ->
                val type = if ((p[0].toInt() and 0xFF) >= 0xF0) 1 else 2
                out.putInt(((type * 16 + group) shl 24) or
                    ((p[0].toInt() and 255) shl 16) or ((p.getOrElse(1) { 0 }.toInt() and 255) shl 8) or
                    (p.getOrElse(2) { 0 }.toInt() and 255))
            }
            return out.array()
        }
        val prefix = (0x40 or group) shl 24
        fun pair(status: Int, a: Int, b: Int, value: Int): ByteArray =
            buffer(8).putInt(prefix or (status shl 16) or (a shl 8) or b).putInt(value).array()
        return when (message) {
            is Message.Note -> pair((if (message.on) 0x90 else 0x80) or message.channel,
                message.pitch, 0, (((message.velocity * 65535.0) / 127).roundToInt() shl 16))
            is Message.Pressure -> pair(0xA0 or message.channel, message.pitch, 0, scale32(message.value, 127))
            is Message.Control -> pair(0xB0 or message.channel, message.controller, 0,
                scale32(message.value, if (message.fine) 16383 else 127))
            is Message.Bend -> pair(0xE0 or message.channel, 0, 0,
                if (message.value == 8192) Int.MIN_VALUE else scale32(message.value, 16383))
            is Message.Program -> pair(0xC0 or message.channel, 0, 1,
                (message.program shl 24) or (message.bankMsb shl 8) or message.bankLsb)
            else -> error("System messages use 32-bit UMP")
        }
    }

    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    private fun scale32(value: Int, max: Int) = (value.toLong() * 0xFFFFFFFFL / max).toInt()
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun byteMessages(m: Message): List<ByteArray> = when (m) {
        is Message.Note -> listOf(bytes((if (m.on) 0x90 else 0x80) or m.channel, m.pitch, m.velocity))
        is Message.Pressure -> listOf(bytes(0xA0 or m.channel, m.pitch, m.value))
        is Message.Control -> if (m.fine && m.controller < 32)
            listOf(bytes(0xB0 or m.channel, m.controller, m.value shr 7),
                bytes(0xB0 or m.channel, m.controller + 32, m.value and 127))
        else listOf(bytes(0xB0 or m.channel, m.controller, if (m.fine) m.value shr 7 else m.value))
        is Message.Bend -> listOf(bytes(0xE0 or m.channel, m.value and 127, m.value shr 7))
        is Message.Program -> listOf(bytes(0xB0 or m.channel, 0, m.bankMsb),
            bytes(0xB0 or m.channel, 32, m.bankLsb), bytes(0xC0 or m.channel, m.program))
        is Message.System -> listOf(bytes(m.status))
        is Message.Transport -> {
            // Universal Real-Time MMC, device ID 0x7F (all call).
            listOf(bytes(0xF0, 0x7F, 0x7F, 0x06, m.command, 0xF7))
        }
    }

}
