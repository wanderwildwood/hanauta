package com.wanderwildwood.hanauta.music

import kotlin.math.abs
import kotlin.math.roundToInt

/** A note as it was sung: when, in seconds, and its pitch as a fractional MIDI note. */
data class SungNote(val start: Float, val end: Float, val pitch: Float) {
    val length get() = end - start
}

/**
 * Turns a pitch track into notes.
 *
 * A voice slides into a note, wavers on it and slides out, so a note is taken to be a stretch
 * where the pitch stays within about half a semitone of one place, and its pitch is the middle
 * of that stretch. A new note starts where the pitch settles somewhere else, or where the voice
 * dips in loudness and comes back: the same note sung twice.
 */
object Segmenter {
    /** Readings either side used to smooth the pitch. */
    private const val SMOOTH = 3
    /** A gap in the readings this short (in readings) is a blip, not a breath. */
    private const val BRIDGE = 4
    /** How far, in semitones, the pitch moves before it is another note. */
    private const val MOVE = 0.6f
    /** How long, in readings, it has to stay moved. */
    private const val SETTLE = 5
    /** A note shorter than this, in seconds, is a slide or a click, not a note. */
    const val SHORTEST = 0.07f
    /** A note shorter than this, in seconds, a step or less from its neighbour, is a slide into or out of it. */
    private const val SLIDE = 0.12f
    /** A dip to this share of the loudness either side is a new start on the same note. */
    private const val DIP = 0.55f

    fun segment(track: Track): List<SungNote> {
        val n = track.size
        if (n == 0) return emptyList()
        val loudest = percentile(track.level, 0.95f)
        val quiet = maxOf(0.005f, loudest * 0.08f)
        val voiced = BooleanArray(n) { !track.midi[it].isNaN() && track.level[it] >= quiet }
        val pitch = smooth(track.midi, voiced)

        val notes = ArrayList<SungNote>()
        var i = 0
        while (i < n) {
            if (!voiced[i]) { i++; continue }
            // A run of voice, bridging short gaps.
            var end = i
            var j = i
            while (j < n) {
                if (voiced[j]) { end = j; j++; continue }
                var k = j
                while (k < n && !voiced[k] && k - j < BRIDGE) k++
                if (k < n && voiced[k] && k - j < BRIDGE) j = k else break
            }
            splitRun(track, pitch, voiced, i, end, notes)
            i = end + 1
        }
        return notes
    }

    /** Splits one run of voice, readings [from]..[to], into notes. */
    private fun splitRun(track: Track, pitch: FloatArray, voiced: BooleanArray, from: Int, to: Int, out: MutableList<SungNote>) {
        val starts = ArrayList<Int>()
        starts += from
        var anchor = pitch[from]
        var moved = 0
        var movedAt = -1
        for (k in from + 1..to) {
            if (!voiced[k]) continue
            val p = pitch[k]
            if (abs(p - anchor) > MOVE) {
                if (moved == 0) movedAt = k
                moved++
                if (moved >= SETTLE) {
                    starts += movedAt
                    anchor = median(pitch, voiced, movedAt, k)
                    moved = 0
                }
            } else {
                moved = 0
                // Drift slowly with the voice, so a long note sung flat at its end is one note.
                anchor += (p - anchor) * 0.05f
            }
        }
        // Loudness dips: the same pitch sung again.
        val dipped = HashSet<Int>()
        for (k in dips(track.level, from, to)) {
            val near = starts.firstOrNull { abs(it - k) < SETTLE * 2 }
            if (near == null) { starts += k; dipped += k } else dipped += near
        }
        starts.sort()

        val run = ArrayList<SungNote>()
        for (s in starts.indices) {
            val a = starts[s]
            val b = if (s + 1 < starts.size) starts[s + 1] - 1 else to
            // The middle of the note: its first and last fifth are the slide in and out.
            val len = b - a + 1
            val trim = len / 5
            val p = median(pitch, voiced, a + trim, b - trim).takeIf { !it.isNaN() } ?: median(pitch, voiced, a, b)
            if (p.isNaN()) continue
            run += SungNote(track.time(a), track.time(b + 1), p)
        }
        out += joinSlides(run, dipsAt = starts.filter { k -> dipped.contains(k) }.map { track.time(it) })
    }

    /**
     * A scoop up into a note, or a fall off the end of one, is read as a short note of its own
     * a step or so away. Joined to the note it leads into or falls from; then anything still
     * too short to be a note is dropped. A start where the voice dipped is never joined across.
     */
    private fun joinSlides(run: List<SungNote>, dipsAt: List<Float>): List<SungNote> {
        val notes = run.toMutableList()
        fun startsAtDip(n: SungNote) = dipsAt.any { abs(it - n.start) < 0.001f }
        var changed = true
        while (changed) {
            changed = false
            for (i in notes.indices) {
                val n = notes[i]
                if (n.length >= SLIDE) continue
                val next = notes.getOrNull(i + 1)
                val prev = notes.getOrNull(i - 1)
                val into = next != null && abs(next.pitch - n.pitch) < 1.6f && !startsAtDip(next)
                val off = prev != null && abs(prev.pitch - n.pitch) < 1.6f && !startsAtDip(n)
                when {
                    into -> { notes[i + 1] = next!!.copy(start = n.start); notes.removeAt(i) }
                    off -> { notes[i - 1] = prev!!.copy(end = n.end); notes.removeAt(i) }
                    else -> continue
                }
                changed = true
                break
            }
        }
        return notes.filter { it.length >= SHORTEST }
    }

    /** Readings where the loudness falls well below its peaks either side, then rises again. */
    private fun dips(level: FloatArray, from: Int, to: Int): List<Int> {
        val found = ArrayList<Int>()
        val reach = 12
        var k = from + reach
        while (k <= to - reach) {
            val here = level[k]
            var isMin = true
            for (j in k - 2..k + 2) if (level[j] < here) { isMin = false; break }
            if (isMin) {
                var before = 0f
                var after = 0f
                for (j in k - reach until k) before = maxOf(before, level[j])
                for (j in k + 1..k + reach) after = maxOf(after, level[j])
                if (here < DIP * minOf(before, after)) {
                    found += k
                    k += reach
                    continue
                }
            }
            k++
        }
        return found
    }

    /** A running median over the voiced readings, which takes out octave jumps one or two long. */
    private fun smooth(midi: FloatArray, voiced: BooleanArray): FloatArray {
        val out = FloatArray(midi.size) { Float.NaN }
        val buf = FloatArray(2 * SMOOTH + 1)
        for (i in midi.indices) {
            if (!voiced[i]) continue
            var c = 0
            for (j in maxOf(0, i - SMOOTH)..minOf(midi.size - 1, i + SMOOTH)) if (voiced[j]) buf[c++] = midi[j]
            buf.sort(0, c)
            val m = buf[c / 2]
            // A reading far off its neighbours, an octave as often as not, is the tracker's
            // mistake rather than the voice's.
            out[i] = if (abs(midi[i] - m) > 1f) m else midi[i]
        }
        return out
    }

    private fun median(x: FloatArray, voiced: BooleanArray, from: Int, to: Int): Float {
        if (to < from) return Float.NaN
        val v = (from..to).filter { voiced[it] && !x[it].isNaN() }.map { x[it] }.sorted()
        return if (v.isEmpty()) Float.NaN else v[v.size / 2]
    }

    private fun percentile(x: FloatArray, q: Float): Float {
        if (x.isEmpty()) return 0f
        val s = x.sorted()
        return s[((s.size - 1) * q).roundToInt()]
    }
}
