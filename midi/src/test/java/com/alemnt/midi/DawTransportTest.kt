package com.alemnt.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DawTransportTest {
    @Test fun usesDedicatedChannelAndMomentaryControlValues() {
        assertEquals(listOf(110, 111, 112, 113, 114), DawControl.entries.map { it.cc })
        for (control in DawControl.entries) {
            assertArrayEquals(byteArrayOf(0xbf.toByte(), control.cc.toByte(), 127),
                MidiCodec.encode(control.press(), Wire.BYTES))
            assertArrayEquals(byteArrayOf(0xbf.toByte(), control.cc.toByte(), 0),
                MidiCodec.encode(control.release(), Wire.BYTES))
        }
    }

    @Test fun readsDawStateAcrossPacketsWithoutTreatingNotesAsFeedback() {
        var feedback = DawFeedback()
        val reader = DawFeedbackReader { cc, value -> feedback = feedback.withControl(cc, value) }
        fun accept(vararg bytes: Int) {
            reader.accept(ByteArray(bytes.size) { bytes[it].toByte() }, 0, bytes.size)
        }
        accept(0xbf, 110)
        accept(0xf8, 127, 112, 127, 114, 127)
        assertEquals(DawFeedback(playing = true, recording = true, click = true), feedback)
        accept(0xb0, 110, 0, 0x9f, 114, 0, 0xf0, 110, 0, 0xf7)
        assertEquals(DawFeedback(playing = true, recording = true, click = true), feedback)
        accept(0xbf, 110, 0, 112, 0, 114, 0)
        assertEquals(DawFeedback(playing = false, recording = false, click = false), feedback)
    }
}
