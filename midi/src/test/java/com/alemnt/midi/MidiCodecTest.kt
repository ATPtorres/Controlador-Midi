package com.alemnt.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MidiCodecTest {
    private fun expect(wire: Wire, event: Message, vararg bytes: Int, group: Int = 0) {
        assertArrayEquals(ByteArray(bytes.size) { bytes[it].toByte() }, MidiCodec.encode(event, wire, group))
    }

    @Test fun noteAndChannel() {
        expect(Wire.BYTES, Message.Note(9, 36, 100, true), 0x99, 36, 100)
        expect(Wire.BYTES, Message.Note(9, 36, 0, false), 0x89, 36, 0)
        expect(Wire.UMP1, Message.Note(9, 36, 100, true), 0x23, 0x99, 36, 100, group = 3)
        expect(Wire.UMP2, Message.Note(9, 36, 127, true), 0x43, 0x99, 36, 0, 255, 255, 0, 0, group = 3)
    }

    @Test fun pairedControllersAndFallback() {
        expect(Wire.BYTES, Message.Control(0, 7, 8193, true), 0xB0, 7, 64, 0xB0, 39, 1)
        expect(Wire.BYTES, Message.Control(0, 74, 8193, true), 0xB0, 74, 64)
        expect(Wire.UMP2, Message.Control(0, 74, 16383, true), 0x40, 0xB0, 74, 0, 255, 255, 255, 255)
    }

    @Test fun programAndPitchCenter() {
        val program = Message.Program(2, 9, 3, 4)
        expect(Wire.BYTES, program, 0xB2, 0, 3, 0xB2, 32, 4, 0xC2, 9)
        expect(Wire.UMP2, program, 0x40, 0xC2, 0, 1, 9, 0, 3, 4)
        expect(Wire.UMP2, Message.Bend(2, 8192), 0x40, 0xE2, 0, 0, 0x80, 0, 0, 0)
    }

    @Test fun transportAndClock() {
        expect(Wire.UMP2, Message.System(0xF8), 0x10, 0xF8, 0, 0)
        expect(Wire.BYTES, Message.Transport(6), 0xF0, 0x7F, 0x7F, 6, 6, 0xF7)
        expect(Wire.UMP2, Message.Transport(7), 0x30, 0x04, 0x7F, 0x7F, 0x06, 0x07, 0, 0)
    }
}
