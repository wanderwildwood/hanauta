package com.wanderwildwood.hanauta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderwildwood.hanauta.music.Key
import com.wanderwildwood.hanauta.music.Note
import com.wanderwildwood.hanauta.music.NoteNames
import com.wanderwildwood.hanauta.music.PPQ
import com.wanderwildwood.hanauta.music.Track
import kotlin.math.abs
import kotlin.math.roundToInt

/** The rows a page of the roll shows: [low] to [high] semitones, both included. */
data class PitchRange(val low: Int, val high: Int) {
    val rows get() = high - low + 1

    companion object {
        private const val FEWEST = 13

        /** Room for every note on the page with a row to spare above and below, at least an octave. */
        fun around(pitches: List<Int>, fallback: Int): PitchRange {
            val lo = (pitches.minOrNull() ?: fallback) - 1
            val hi = (pitches.maxOrNull() ?: fallback) + 1
            val missing = FEWEST - (hi - lo + 1)
            return if (missing > 0) PitchRange(lo - missing / 2, hi + (missing - missing / 2)) else PitchRange(lo, hi)
        }
    }
}

/**
 * How the voice's own pitch line is placed against the notes: in time by the take's tempo and
 * beat one, and in pitch less the take's tuning, so a voice a little sharp throughout runs
 * through the middle of its notes rather than along their tops.
 */
class VoiceLine(val track: Track, val beatZero: Float, val tempo: Float, val tuning: Float)

/**
 * One page of the tune as a piano roll: a row a semitone, a column a step of the grid, bar
 * lines solid and beats dotted, each note a black bar. The chosen note is drawn hollow inside
 * a heavy border. What the voice did is the dotted line behind, so a wrong note shows where it
 * left the singing.
 */
@Composable
fun PianoRoll(
    notes: List<Note>,
    selected: Int,
    from: Int,
    ticks: Int,
    beatsPerBar: Int,
    key: Key,
    range: PitchRange,
    voice: VoiceLine?,
    /** The tick of the beat being played, or -1 when nothing is playing. */
    playhead: Int = -1,
    onTap: (Int) -> Unit,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val gutter = with(LocalDensity.current) { 40.dp.toPx() }
    // A note is found within this of a press: with its own width, a target of 48 dp or more.
    val reach = with(LocalDensity.current) { 24.dp.toPx() }
    val tap by rememberUpdatedState(onTap)
    val page by rememberUpdatedState(onPage)
    val current by rememberUpdatedState(Triple(notes, from, ticks))
    val rows by rememberUpdatedState(range)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val (ns, f, t) = current
                    val w = size.width - gutter
                    val rowH = size.height.toFloat() / rows.rows
                    val tick = f + ((p.x - gutter) / w * t)
                    val pitch = rows.high - (p.y / rowH).toInt()
                    // The note under the press, else the nearest on the page within a fingertip.
                    val hit = ns.indexOfFirst { it.pitch == pitch && tick >= it.start && tick < it.end }
                    if (hit >= 0) { tap(hit); return@detectTapGestures }
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for ((i, n) in ns.withIndex()) {
                        if (n.end <= f || n.start >= f + t) continue
                        val x0 = gutter + (maxOf(n.start, f) - f).toFloat() / t * w
                        val x1 = gutter + (minOf(n.end, f + t) - f).toFloat() / t * w
                        val y = (rows.high - n.pitch + 0.5f) * rowH
                        val dx = if (p.x < x0) x0 - p.x else if (p.x > x1) p.x - x1 else 0f
                        val d = maxOf(dx, abs(p.y - y) - rowH / 2)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    tap(if (bestD <= reach) best else -1)
                }
            }
            .pointerInput(Unit) {
                var turned = false
                detectHorizontalDragGestures(
                    onDragStart = { turned = false },
                    onDragEnd = { turned = false },
                    onDragCancel = { turned = false },
                ) { _, drag ->
                    if (turned || abs(drag) < 2f) return@detectHorizontalDragGestures
                    turned = true
                    page(if (drag < 0) 1 else -1)
                }
            },
    ) {
        drawRect(paper)
        val w = size.width - gutter
        val rowH = size.height / range.rows
        fun x(tick: Int) = gutter + (tick - from).toFloat() / ticks * w
        val thin = 1.dp.toPx()
        val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))

        // Rows: a dotted line under each C, and the names down the side. Each C and the chosen
        // note's row are always named; the other notes of the key are named where a name fits
        // without touching another, so a short roll (the phone turned sideways) still names
        // every second or third row rather than only the Cs.
        val flats = NoteNames.flats(key)
        val chosenPitch = notes.getOrNull(selected)?.pitch
        val nameH = measurer.measure("C4", TextStyle(fontSize = 14.sp)).size.height.toFloat()
        val named = ArrayList<Float>()
        fun centre(pitch: Int) = (range.high - pitch + 0.5f) * rowH
        fun fits(pitch: Int) = named.none { abs(it - centre(pitch)) < nameH }
        fun name(pitch: Int) {
            named += centre(pitch)
            label(measurer, NoteNames.pitchClass(pitch, flats) + (pitch / 12 - 1), Offset(2.dp.toPx(), (range.high - pitch) * rowH), rowH, ink, pitch == chosenPitch)
        }
        for (r in 0 until range.rows) {
            val pitch = range.high - r
            if (pitch % 12 == 0) {
                drawLine(ink, Offset(gutter, r * rowH + rowH), Offset(size.width, r * rowH + rowH), thin, pathEffect = dots)
            }
        }
        chosenPitch?.takeIf { it in range.low..range.high }?.let(::name)
        for (pitch in range.high downTo range.low) if (pitch % 12 == 0 && pitch != chosenPitch && fits(pitch)) name(pitch)
        for (pitch in range.high downTo range.low) if (key.contains(pitch) && pitch % 12 != 0 && pitch != chosenPitch && fits(pitch)) name(pitch)

        // Columns: bar lines solid, beats dotted.
        val bar = PPQ * beatsPerBar
        var t = (from / PPQ) * PPQ
        while (t <= from + ticks) {
            val xx = x(t)
            if (t % bar == 0) drawLine(ink, Offset(xx, 0f), Offset(xx, size.height), 2.dp.toPx())
            else drawLine(ink, Offset(xx, 0f), Offset(xx, size.height), thin, pathEffect = dots)
            t += PPQ
        }
        drawLine(ink, Offset(gutter, 0f), Offset(gutter, size.height), thin)

        // The voice, a dot a reading.
        if (voice != null) {
            val secondsPerTick = 60f / (voice.tempo * PPQ)
            val r = 1.dp.toPx()
            for (i in 0 until voice.track.size) {
                val m = voice.track.midi[i] - voice.tuning
                if (m.isNaN()) continue
                val tick = ((voice.track.time(i) - voice.beatZero) / secondsPerTick).roundToInt()
                if (tick < from || tick > from + ticks || i % 2 == 1) continue
                val y = (range.high - m + 0.5f) * rowH
                if (y < 0 || y > size.height) continue
                drawCircle(ink, r, Offset(x(tick), y))
            }
        }

        // The notes.
        val pad = maxOf(1f, rowH * 0.12f)
        for ((i, n) in notes.withIndex()) {
            if (n.end <= from || n.start >= from + ticks) continue
            if (n.pitch < range.low || n.pitch > range.high) continue
            val x0 = x(maxOf(n.start, from)) + 1.dp.toPx()
            val x1 = x(minOf(n.end, from + ticks)) - 1.dp.toPx()
            val y0 = (range.high - n.pitch) * rowH + pad
            val box = Size(maxOf(2f, x1 - x0), rowH - 2 * pad)
            val corner = CornerRadius(2.dp.toPx())
            if (i == selected) {
                drawRoundRect(paper, Offset(x0, y0), box, corner)
                drawRoundRect(ink, Offset(x0, y0), box, corner, style = Stroke(3.dp.toPx()))
            } else {
                drawRoundRect(ink, Offset(x0, y0), box, corner)
            }
        }

        // The playhead: a heavy line at the beat being played, stepping a beat at a time.
        if (playhead in from..from + ticks) {
            val xx = x(playhead)
            drawLine(ink, Offset(xx, 0f), Offset(xx, size.height), 4.dp.toPx())
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(
    measurer: TextMeasurer, text: String, at: Offset, rowH: Float, ink: Color, bold: Boolean,
) {
    val style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    val m = measurer.measure(text, style)
    // Kept inside the roll: a row shorter than the name would push the top or bottom one out.
    val y = (at.y + (rowH - m.size.height) / 2).coerceIn(0f, maxOf(0f, size.height - m.size.height))
    drawText(m, topLeft = Offset(at.x, y))
}
