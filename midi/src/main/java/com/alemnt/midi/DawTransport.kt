package com.alemnt.midi

enum class DawControl(val cc: Int) {
    PLAY(110), PAUSE(111), RECORD(112), REWIND_ZERO(113), CLICK(114);

    fun press(): Message.Control = Message.Control(CHANNEL, cc, 127)
    fun release(): Message.Control = Message.Control(CHANNEL, cc, 0)

    companion object {
        const val CHANNEL = 15
    }
}

data class DawFeedback(
    val playing: Boolean? = null,
    val recording: Boolean? = null,
    val click: Boolean? = null
) {
    fun withControl(cc: Int, value: Int): DawFeedback = when (cc) {
        DawControl.PLAY.cc -> copy(playing = value >= 64)
        DawControl.RECORD.cc -> copy(recording = value >= 64)
        DawControl.CLICK.cc -> copy(click = value >= 64)
        else -> this
    }
}

/** Decodes ordinary MIDI CC feedback, including fragmented messages and running status. */
internal class DawFeedbackReader(private val onControl: (cc: Int, value: Int) -> Unit) {
    private var status = 0
    private var first = -1

    fun accept(bytes: ByteArray, offset: Int, count: Int) {
        for (index in offset until offset + count) {
            val value = bytes[index].toInt() and 0xff
            when {
                value >= 0xf8 -> Unit
                value >= 0x80 -> {
                    status = if (value < 0xf0) value else 0
                    first = -1
                }
                status == (0xb0 or DawControl.CHANNEL) -> {
                    if (first < 0) first = value else {
                        onControl(first, value)
                        first = -1
                    }
                }
            }
        }
    }
}
