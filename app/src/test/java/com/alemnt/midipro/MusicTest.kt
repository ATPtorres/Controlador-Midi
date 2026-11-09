package com.alemnt.midipro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTest {
    @Test fun diatonicMinorChords() {
        val preset = Preset(root = 4, scale = Scale.MINOR)
        assertEquals(listOf("Em", "F♯dim", "G", "Am", "Bm", "C", "D"),
            (0..6).map { preset.chord(it)?.name })
        assertEquals(listOf(4, 6, 7, 9, 11, 0, 2), preset.smart().take(7).map { it % 12 })
    }

    @Test fun noteRangesForAllScalesAndRoots() {
        for (scale in Scale.entries) for (root in 0..11) for (octave in 0..6) {
            val p = Preset(root = root, scale = scale, octave = octave, smartOctaves = 6)
            assertTrue(p.valid())
            assertEquals(24, p.grid().size)
            assertEquals(6 * scale.steps.size, p.smart().size)
            assertTrue((p.grid() + p.smart()).all { it in 0..127 && scale.contains(root, it) })
            assertTrue((0..20).flatMap { p.chord(it)?.notes.orEmpty() }.all { it in 0..127 })
        }
    }

    @Test fun customVoicingsAndReorderingPersistPerCell() {
        val order = Preset().chordOrder.replace(0, 9).replace(9, 0)
        val custom = Voicing(size = 4, inversion = 1, custom = listOf(0, 3, 7, 10))
        val voices = Preset().chords.replace(0, custom)
        val initial = Preset(chords = voices)
        val moved = initial.copy(chordOrder = order)
        assertEquals(initial.chord(0), moved.chord(9))
        assertEquals(listOf(51, 55, 58, 60), moved.chord(9)?.notes)
    }
}
