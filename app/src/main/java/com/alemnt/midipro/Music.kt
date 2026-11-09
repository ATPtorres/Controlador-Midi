package com.alemnt.midipro

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

val pitchNames = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
fun noteName(note: Int) = pitchNames[note % 12] + (note / 12 - 1)
val degrees = listOf("I", "II", "III", "IV", "V", "VI", "VII")

enum class Scale(val label: String, vararg val steps: Int) {
    MAJOR("Major", 0, 2, 4, 5, 7, 9, 11),
    MINOR("Minor", 0, 2, 3, 5, 7, 8, 10),
    HARMONIC("Harmonic Minor", 0, 2, 3, 5, 7, 8, 11),
    MELODIC("Melodic Minor", 0, 2, 3, 5, 7, 9, 11),
    DORIAN("Dorian", 0, 2, 3, 5, 7, 9, 10),
    PHRYGIAN("Phrygian", 0, 1, 3, 5, 7, 8, 10),
    LYDIAN("Lydian", 0, 2, 4, 6, 7, 9, 11),
    MIXOLYDIAN("Mixolydian", 0, 2, 4, 5, 7, 9, 10),
    LOCRIAN("Locrian", 0, 1, 3, 5, 6, 8, 10),
    PENT_MAJOR("Pentatonic Major", 0, 2, 4, 7, 9),
    PENT_MINOR("Pentatonic Minor", 0, 3, 5, 7, 10),
    BLUES("Blues", 0, 3, 5, 6, 7, 10),
    CHROMATIC("Chromatic", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
    ARABIC("Arabic", 0, 1, 4, 5, 7, 8, 11),
    GYPSY("Gypsy", 0, 2, 3, 6, 7, 8, 11),
    JAPANESE("Japanese", 0, 1, 5, 7, 8),
    WHOLE_TONE("Whole Tone", 0, 2, 4, 6, 8, 10),
    DIMINISHED("Diminished", 0, 2, 3, 5, 6, 8, 9, 11);

    fun offset(index: Int) = steps[index % steps.size] + 12 * (index / steps.size)
    fun contains(root: Int, note: Int) = Math.floorMod(note - root, 12) in steps
}

enum class SurfacePage(val label: String, val short: String) {
    PIANO("Piano Keyboard", "Piano"), PADS("Drum Pads", "Pads"),
    SCALE("Smart Scale Keyboard", "Escala"), CHORDS("Chord Performance Matrix", "Acordes"),
    GRID("Grid Smart Keyboard", "Grid"), FADERS("MIDI Automation Faders", "Faders")
}

val palette = listOf(0xFF45CFFF, 0xFFAB85FF, 0xFFFF5F97, 0xFFFF9F62, 0xFFF4D969, 0xFF63E3AC, 0xFF759CFF, 0xFFD797F0)
val padIcons = listOf("◆", "●", "✦", "✚", "■", "▲", "◉", "✶")

@Serializable
data class Pad(val note: Int, val color: Int, val icon: String, val toggle: Boolean = false,
               val pressure: Boolean = false, val velocity: Int = 100)

@Serializable
data class Voicing(val size: Int = 3, val inversion: Int = 0, val custom: List<Int> = emptyList())

enum class Curve(val label: String) { LINEAR("Lineal"), LOG("Logarítmica"), EXP("Exponencial") }

const val MAX_FADER_NAME = 32

@Serializable
data class Fader(val cc: Int, val color: Int, val value: Float = 0f, val fine: Boolean = true,
                 val curve: Curve = Curve.LINEAR, val name: String = "") {
    fun output(position: Float): Int {
        val curved = when (curve) {
            Curve.LINEAR -> position
            Curve.LOG -> (kotlin.math.ln(1f + 9 * position) / kotlin.math.ln(10f))
            Curve.EXP -> position * position
        }
        return (curved.coerceIn(0f, 1f) * if (fine) 16383 else 127).roundToInt()
    }
}

@Serializable
data class Preset(
    val schema: Int = 1,
    val root: Int = 0,
    val scale: Scale = Scale.MAJOR,
    val octave: Int = 3,
    val pianoOctaves: Int = 4,
    val smartOctaves: Int = 3,
    val pianoWidth: Float = 46f,
    val smartWidth: Float = 65f,
    val labels: Boolean = true,
    val learning: Boolean = false,
    val channel: Int = 0,
    val velocity: Int = 100,
    val touchVelocity: Boolean = false,
    val aftertouch: Boolean = false,
    val haptics: Boolean = false,
    val dark: Boolean = true,
    val virtual: Boolean = true,
    val group: Int = 0,
    val bpm: Int = 120,
    val clock: Boolean = true,
    val metronome: Boolean = false,
    val dawTransport: Boolean = true,
    val bankMsb: Int = 0,
    val bankLsb: Int = 0,
    val program: Int = 0,
    val stream: Boolean = true,
    val smoothing: Boolean = true,
    val jitter: Boolean = true,
    val pads: List<Pad> = List(8) { Pad(36 + it, it, padIcons[it]) },
    val chords: List<Voicing> = List(21) { Voicing() },
    val chordOrder: List<Int> = List(21) { it },
    val faders: List<Fader> = listOf(1, 7, 10, 11, 74, 71).mapIndexed { i, cc -> Fader(cc, i) }
) {
    fun valid(): Boolean = schema == 1 && root in 0..11 && octave in 0..6 && pianoOctaves in 1..8 &&
        smartOctaves in 1..6 && pianoWidth in 26f..120f && smartWidth in 32f..130f &&
        channel in 0..15 && velocity in 1..127 && group in 0..15 && bpm in 30..300 &&
        listOf(bankMsb, bankLsb, program).all { it in 0..127 } &&
        pads.size == 8 && pads.all { it.note in 0..127 && it.color in palette.indices &&
            it.icon.length in 1..8 && it.velocity in 1..127 } &&
        chords.size == 21 && chords.all { it.size in 3..7 && it.inversion in 0 until
            (it.custom.size.takeIf { size -> size > 0 } ?: it.size) &&
            it.custom.size <= 8 && it.custom.all { note -> note in -24..36 } } &&
        chordOrder.sorted() == (0..20).toList() &&
        faders.size == 6 && faders.all { it.cc in 0..127 && it.color in palette.indices && it.value in 0f..1f &&
            it.name.length <= MAX_FADER_NAME && it.name.none(Char::isISOControl) }

    fun grid(): List<Int> {
        val offset = minOf((octave + 1) * 12 + root, 127 - scale.offset(23))
        val base = offset - Math.floorMod(offset - root, 12)
        return List(24) { base + scale.offset(it) }
    }

    fun smart(): List<Int> {
        val first = minOf((octave + 1) * 12 + root, 127 - scale.offset(smartOctaves * scale.steps.size - 1))
        val base = first - Math.floorMod(first - root, 12)
        return List(smartOctaves * scale.steps.size) { base + scale.offset(it) }
    }

    fun chord(slot: Int): Chord? {
        val source = chordOrder[slot]
        val degree = source % 7
        val voice = chords[source]
        if (degree >= scale.steps.size && voice.custom.isEmpty()) return null
        val tonic = (minOf(octave, 4) + 1 + source / 7) * 12 + root
        val chordRoot = tonic + scale.offset(degree)
        val intervals = voice.custom.ifEmpty {
            List(voice.size) { scale.offset(degree + it * 2) - scale.offset(degree) }
        }
        val voiced = intervals.distinct().sorted().toMutableList()
        repeat(voice.inversion.coerceAtMost(voiced.size - 1)) {
            var first = voiced.removeAt(0) + 12
            while (first <= voiced.last()) first += 12
            voiced.add(first)
        }
        var base = chordRoot
        while (base + voiced.last() > 127) base -= 12
        while (base + voiced.first() < 0) base += 12
        val normalized = intervals.map { Math.floorMod(it, 12) }.toSet()
        val quality = when {
            3 in normalized && 6 in normalized && 7 !in normalized -> "dim"
            3 in normalized && 7 in normalized && 4 !in normalized -> "m"
            4 in normalized && 8 in normalized && 7 !in normalized -> "+"
            4 in normalized && 7 in normalized -> ""
            else -> "·"
        }
        val extension = when (intervals.size) {
            4 -> "7"
            5 -> "9"
            6 -> "11"
            7 -> "13"
            else -> ""
        }
        val name = pitchNames[chordRoot % 12] + when {
            extension.isEmpty() -> quality
            quality == "dim" && 10 in normalized && extension == "7" -> "ø7"
            11 in normalized && quality == "m" -> "m(maj$extension)"
            11 in normalized -> "${quality}maj$extension"
            else -> quality + extension
        }
        return Chord(name, voiced.map { base + it })
    }
}

data class Chord(val name: String, val notes: List<Int>)

fun <T> List<T>.replace(index: Int, value: T) = toMutableList().apply { this[index] = value }.toList()
