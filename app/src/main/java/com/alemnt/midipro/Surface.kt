package com.alemnt.midipro

import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private data class Key(
    val index: Int,
    val rect: Rect,
    val notes: List<Int>,
    val title: String,
    val detail: String = "",
    val color: Int = 0,
    val black: Boolean = false,
    val marked: Boolean = false
)
private data class Held(val key: Key, val point: Offset)
private data class Pulse(val point: Offset, val start: Long)

private fun keyboard(page: SurfacePage, p: Preset, width: Float, height: Float, density: Float,
                     offset: Float): List<Key> {
    val w = (if (page == SurfacePage.PIANO) p.pianoWidth else p.smartWidth) * density
    if (page == SurfacePage.SCALE) return p.smart().mapIndexed { i, note ->
        val x = i * w - offset
        Key(note, Rect(x, 0f, x + w - 2, height), listOf(note), noteName(note),
            degrees.getOrElse(i % p.scale.steps.size) { (i % p.scale.steps.size + 1).toString() },
            (i % p.scale.steps.size) % palette.size, marked = i % p.scale.steps.size == 0)
    }
    val first = (minOf(p.octave, 9 - p.pianoOctaves) + 1) * 12
    val white = ArrayList<Key>()
    val black = ArrayList<Key>()
    val blackNotes = setOf(1, 3, 6, 8, 10)
    var nWhite = 0
    repeat(p.pianoOctaves * 12) { i ->
        val note = first + i
        val isBlack = note % 12 in blackNotes
        val x = nWhite * w - offset
        val rect = if (isBlack) Rect(x - w * .31f, 0f, x + w * .31f, height * .63f)
            else Rect(x, 0f, x + w - 1.4f, height)
        val key = Key(note, rect, listOf(note), if (p.labels) noteName(note) else "",
            black = isBlack, marked = p.scale.contains(p.root, note))
        if (isBlack) black.add(key) else {
            white.add(key)
            nWhite++
        }
    }
    return (white + black).filter { it.rect.right > 0f && it.rect.left < width }
}

private fun layout(page: SurfacePage, p: Preset, width: Float, height: Float, density: Float,
                   offset: Float): List<Key> {
    if (page == SurfacePage.PIANO || page == SurfacePage.SCALE) return keyboard(page, p, width, height, density, offset)
    val columns = when (page) { SurfacePage.CHORDS -> 7; SurfacePage.FADERS -> 6; else -> 4 }
    val rows = when (page) { SurfacePage.CHORDS -> 3; SurfacePage.GRID -> 6; SurfacePage.PADS -> 2; else -> 1 }
    val gap = 5 * density
    val w = width / columns
    val h = height / rows
    val grid = if (page == SurfacePage.GRID) p.grid() else emptyList()
    return List(columns * rows) { index ->
        val x = (index % columns) * w
        val y = (index / columns) * h
        val box = Rect(x + gap, y + gap, x + w - gap, y + h - gap)
        when (page) {
            SurfacePage.PADS -> p.pads[index].run {
                Key(index, box, listOf(note), icon, "${index + 1}  ·  ${noteName(note)}${if (toggle) "  ↻" else ""}", color)
            }
            SurfacePage.GRID -> {
                val degree = (rows - 1 - index / columns) * columns + index % columns
                val step = degree % p.scale.steps.size
                val note = grid[degree]
                Key(index, box, listOf(note), noteName(note), if (step == 0) "TÓNICA" else "",
                    step % palette.size, marked = step == 0)
            }
            SurfacePage.CHORDS -> {
                val chord = p.chord(index)
                Key(index, box, chord?.notes.orEmpty(), chord?.name ?: "·",
                    if (p.learning && chord != null) chord.notes.joinToString(" ") { pitchNames[it % 12] }
                    else degrees[index % columns], p.chordOrder[index] % columns)
            }
            SurfacePage.FADERS -> p.faders[index].run {
                Key(index, box, emptyList(), "CC $cc", output(value).toString(), color)
            }
            else -> error("Keyboard is laid out separately")
        }
    }
}

@Composable
fun PerformanceSurface(page: SurfacePage, p: Preset, model: ControllerModel, latched: Set<Int>,
                       editing: Boolean, editCell: (Int) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val view = LocalView.current
    val live by rememberUpdatedState(p)
    val cellEditor by rememberUpdatedState(editCell)
    var held by remember(page, editing, p.root, p.scale, p.octave, p.channel) {
        mutableStateOf<Map<Long, Held>>(emptyMap())
    }
    var pulses by remember(page) { mutableStateOf<Map<Int, Pulse>>(emptyMap()) }
    var animationTime by remember(page) { mutableLongStateOf(0L) }
    LaunchedEffect(pulses.isNotEmpty()) {
        while (pulses.isNotEmpty()) {
            withFrameNanos { animationTime = SystemClock.uptimeMillis() }
            val remaining = pulses.filterValues { animationTime - it.start < 320 }
            if (remaining.size != pulses.size) pulses = remaining
        }
    }
    var drag by remember(page, editing) { mutableStateOf<Pair<Int, Int>?>(null) }
    var offset by remember(page) { mutableFloatStateOf(0f) }
    val keyboard = page == SurfacePage.PIANO || page == SurfacePage.SCALE
    val paper = MaterialTheme.colorScheme.surface
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(modifier) {
        val fullWidth = constraints.maxWidth.toFloat()
        fun maxOffset(preset: Preset): Float {
            val total = if (page == SurfacePage.PIANO) preset.pianoOctaves * 7 * preset.pianoWidth
                else preset.smartOctaves * preset.scale.steps.size * preset.smartWidth
            return (total * density - fullWidth).coerceAtLeast(0f)
        }
        val scroll = offset.coerceIn(0f, maxOffset(p))
        val liveOffset by rememberUpdatedState(scroll)
        Column(Modifier.fillMaxSize()) {
            if (keyboard) {
                Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(7.dp))
                    .background(surface).pointerInput(page, fullWidth) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val before = live
                            val oldWidth = if (page == SurfacePage.PIANO) before.pianoWidth else before.smartWidth
                            val width = (oldWidth * zoom).coerceIn(
                                if (page == SurfacePage.PIANO) 26f..120f else 32f..130f)
                            val after = if (page == SurfacePage.PIANO) before.copy(pianoWidth = width)
                                else before.copy(smartWidth = width)
                            if (after != before) model.change { after }
                            offset = ((offset.coerceIn(0f, maxOffset(before)) + centroid.x) *
                                width / oldWidth - centroid.x - pan.x).coerceIn(0f, maxOffset(after))
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text("↔  Arrastra para desplazar  ·  Pellizca para acercar", color = muted, fontSize = 11.sp)
                }
            }
            Canvas(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(10.dp))
                .background(paper).semantics { contentDescription = page.label }
                .pointerInput(page, editing, p.root, p.scale, p.octave, p.channel, p.pianoOctaves, p.smartOctaves) {
                    var origin: Int? = null
                    try {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                val config = live
                                val keys = layout(page, config, size.width.toFloat(), size.height.toFloat(),
                                    density, liveOffset)
                                for (pointer in event.changes) {
                                    val token = pointer.id.value
                                    val key = keys.lastOrNull { it.rect.contains(pointer.position) }
                                    val was = held[token]
                                    if (editing) {
                                        if (pointer.pressed && !pointer.previousPressed) {
                                            if (page == SurfacePage.CHORDS) origin = key?.index
                                            else key?.let { cellEditor(it.index) }
                                        } else if (page == SurfacePage.CHORDS) {
                                            if (pointer.pressed && origin != null && key != null) {
                                                drag = origin!! to key.index
                                            } else if (!pointer.pressed && pointer.previousPressed) {
                                                val from = origin
                                                val to = key?.index
                                                origin = null
                                                drag = null
                                                if (from != null && to == from) cellEditor(from)
                                                else if (from != null && to != null) model.change { old ->
                                                    val order = old.chordOrder
                                                    old.copy(chordOrder = order.replace(from, order[to]).replace(to, order[from]))
                                                }
                                            }
                                        }
                                    } else if (page == SurfacePage.FADERS) {
                                        val active = was?.key ?: key
                                        if (active != null && (was != null || pointer.pressed)) {
                                            val pos = 1f - (pointer.position.y - active.rect.top - 26 * density) /
                                                (active.rect.height - 52 * density).coerceAtLeast(1f)
                                            model.moveFader(active.index, pos, !pointer.pressed)
                                            held = if (pointer.pressed) held + (token to Held(active, pointer.position))
                                                else held - token
                                        }
                                    } else if (!pointer.pressed) {
                                        if (was != null) {
                                            model.lift(token)
                                            held = held - token
                                        }
                                    } else {
                                        val canSlide = keyboard || page == SurfacePage.GRID
                                        val next = if (canSlide || !pointer.previousPressed) key else was?.key
                                        if (next?.index != was?.key?.index) {
                                            if (was != null) model.lift(token)
                                            if (next != null) {
                                                if (page == SurfacePage.PADS || page == SurfacePage.GRID) {
                                                    pulses = pulses + (next.index to Pulse(pointer.position, SystemClock.uptimeMillis()))
                                                }
                                                model.press(token, next.notes, pointer.pressure,
                                                    next.index.takeIf { page == SurfacePage.PADS })
                                                if (config.haptics && next.notes.isNotEmpty()) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                }
                                                held = held + (token to Held(next, pointer.position))
                                            } else held = held - token
                                        } else if (was != null) model.pressure(token, pointer.pressure)
                                    }
                                    pointer.consume()
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    } finally {
                        held.keys.forEach(model::lift)
                        held = emptyMap()
                        drag = null
                    }
                }) {
                val keys = layout(page, p, size.width, size.height, density, scroll)
                clipRect {
                    keys.forEach { key ->
                        if (key.rect.width <= 1f || key.rect.height <= 1f) return@forEach
                        val finger = held.values.firstOrNull { it.key.index == key.index }
                        val active = finger != null || (page == SurfacePage.PADS && key.index in latched)
                        val accent = Color(palette[key.color])
                        val r = key.rect
                        when (page) {
                            SurfacePage.PIANO -> {
                                val color = if (key.black) {
                                    if (active) Color(0xFF29627C) else Color(0xFF131A28)
                                } else {
                                    if (active) Color(0xFF99DEFC) else Color(0xFFE6EBF3)
                                }
                                val base = if (key.black) Color(0xFF253043) else Color.White
                                rounded(r, Brush.verticalGradient(listOf(base, color), r.top, r.bottom), 4 * density)
                                val dot = Offset(r.center.x, r.bottom - 34 * density)
                                if (key.marked) {
                                    drawCircle(Color(0xFF32B9FF).copy(alpha = .25f), 7 * density, dot)
                                    drawCircle(Color(0xFF32B9FF), 3.6f * density, dot)
                                }
                                label(key.title, r.center.x, r.bottom - 13 * density, 11 * density,
                                    if (key.black) Color.White else Color(0xFF202C44))
                            }
                            SurfacePage.FADERS -> {
                                val fader = p.faders[key.index]
                                rounded(r, Brush.verticalGradient(listOf(surface, paper), r.top, r.bottom), 11 * density)
                                val top = r.top + 26 * density
                                val bottom = r.bottom - 26 * density
                                val y = bottom - (bottom - top) * fader.value
                                val x = r.center.x
                                drawLine(muted.copy(alpha = .28f), Offset(x, top), Offset(x, bottom), 5 * density)
                                drawLine(accent, Offset(x, y), Offset(x, bottom), 5 * density)
                                drawCircle(accent.copy(alpha = .24f), 13 * density, Offset(x, y))
                                drawRoundRect(accent, Offset(x - 13 * density, y - 5 * density),
                                    Size(26 * density, 10 * density), CornerRadius(4 * density))
                                label(key.title, x, r.top + 17 * density, 12 * density, ink)
                                label(key.detail, x, r.bottom - 8 * density, 11 * density, muted)
                            }
                            else -> {
                                val highlight = active || drag?.second == key.index
                                val pulse = pulses[key.index]
                                val progress = if (pulse != null) ((animationTime - pulse.start) / 320f).coerceIn(0f, 1f) else 1f
                                val lift = if (page == SurfacePage.PADS) 3 * density *
                                    (if (pulse != null) kotlin.math.sin(progress * Math.PI).toFloat()
                                    else if (active) .60f else 0f) else 0f
                                val box = Rect(r.left - lift, r.top - lift, r.right + lift, r.bottom + lift)
                                val alpha = if (highlight) .76f else if (key.marked) .28f else .12f
                                rounded(box, Brush.verticalGradient(
                                    listOf(lerpSurface(surface, accent, alpha), surface), box.top, box.bottom), 11 * density)
                                drawRoundRect(accent.copy(alpha = if (highlight) .95f else .30f),
                                    box.topLeft, box.size, CornerRadius(11 * density),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(if (highlight) 2 * density else density))
                                if (finger != null) {
                                    val center = Offset(finger.point.x.coerceIn(r.left, r.right),
                                        finger.point.y.coerceIn(r.top, r.bottom))
                                    clipRect(r.left, r.top, r.right, r.bottom) {
                                        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .48f), Color.Transparent),
                                            center, r.width * .72f), r.width * .72f, center)
                                    }
                                }
                                if (pulse != null) {
                                    val radius = (r.width + r.height) * (.04f + .54f * progress)
                                    clipRect(r.left, r.top, r.right, r.bottom) {
                                        drawCircle(accent.copy(alpha = (1f - progress) * .64f), radius, pulse.point,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density))
                                    }
                                }
                                val titleSize = when (page) {
                                    SurfacePage.PADS -> 29f
                                    SurfacePage.CHORDS -> 16f
                                    else -> 21f
                                }
                                label(key.title, r.center.x, r.center.y + 3 * density,
                                    minOf(titleSize * density, r.height * .44f), ink, r.width - 6 * density)
                                label(key.detail, r.center.x, r.center.y + minOf(22 * density, r.height * .39f),
                                    10 * density, if (p.dark) accent else muted, r.width - 6 * density)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun lerpSurface(surface: Color, accent: Color, fraction: Float) =
    androidx.compose.ui.graphics.lerp(surface, accent, fraction)

private fun DrawScope.rounded(rect: Rect, brush: Brush, radius: Float) =
    drawRoundRect(brush, rect.topLeft, rect.size, CornerRadius(radius))

private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

private fun DrawScope.label(text: String, x: Float, y: Float, font: Float, color: Color,
                            maxWidth: Float = Float.MAX_VALUE) {
    if (text.isEmpty() || maxWidth <= 0f) return
    labelPaint.textSize = font
    val width = labelPaint.measureText(text)
    if (width > maxWidth) labelPaint.textSize *= maxWidth / width
    labelPaint.color = color.toArgb()
    drawContext.canvas.nativeCanvas.drawText(text, x, y, labelPaint)
}
