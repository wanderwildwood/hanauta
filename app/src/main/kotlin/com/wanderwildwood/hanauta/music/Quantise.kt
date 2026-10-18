package com.wanderwildwood.hanauta.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ticks to a quarter note, in the app and in the MIDI file it writes. */
const val PPQ = 480

/** A note on the grid: [start] and [length] in ticks, [pitch] a MIDI note number. */
data class Note(val start: Int, val length: Int, val pitch: Int) {
    val end get() = start + length
}

/** What the notes are snapped to in time. */
enum class Grid(val ticks: Int) {
    Quarter(PPQ), Eighth(PPQ / 2), Sixteenth(PPQ / 4), Triplet(PPQ / 3);
}

/**
 * A key: [tonic] a pitch class, 0 = C. [Chromatic] snaps to nothing but the nearest semitone.
 * In a minor key the raised seventh counts as in the key, as it is in most tunes.
 */
data class Key(val tonic: Int, val minor: Boolean) {
    val chromatic get() = tonic < 0

    fun contains(pitch: Int): Boolean {
        if (chromatic) return true
        val d = ((pitch - tonic) % 12 + 12) % 12
        return d in if (minor) MINOR else MAJOR
    }

    /** How many sharps (above 0) or flats (below 0) its signature has. */
    val fifths: Int
        get() {
            if (chromatic) return 0
            val major = if (minor) (tonic + 3) % 12 else tonic
            // By the major key's tonic: C Db D Eb E F F# G Ab A Bb B.
            return intArrayOf(0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)[major]
        }

    companion object {
        val Chromatic = Key(-1, false)
        private val MAJOR = setOf(0, 2, 4, 5, 7, 9, 11)
        private val MINOR = setOf(0, 2, 3, 5, 7, 8, 10, 11)
    }
}

/** How the sung notes are put on the grid. */
data class Placing(
    val tempo: Float,
    val beatsPerBar: Int,
    val key: Key,
    val grid: Grid,
    /** Where beat one of bar one falls in the recording, in seconds. */
    val beatZero: Float,
    /** How far the singer is from concert pitch, in semitones: taken off every note. */
    val tuning: Float,
)

object Quantise {

    fun place(sung: List<SungNote>, p: Placing): List<Note> {
        if (sung.isEmpty()) return emptyList()
        val secondsPerTick = 60f / (p.tempo * PPQ)
        val g = p.grid.ticks
        val raw = sung.map { s ->
            val a = ((s.start - p.beatZero) / secondsPerTick / g).roundToInt() * g
            var b = ((s.end - p.beatZero) / secondsPerTick / g).roundToInt() * g
            if (b <= a) b = a + g
            Note(a, b - a, snap(s.pitch - p.tuning, p.key))
        }.sortedBy { it.start }

        // One line of melody: a note ends where the next begins, and of two that begin
        // together the longer is kept.
        val out = ArrayList<Note>()
        for (n in raw) {
            val last = out.lastOrNull()
            if (last != null && n.start == last.start) {
                if (n.length > last.length) out[out.size - 1] = n
                continue
            }
            if (last != null && last.end > n.start) out[out.size - 1] = last.copy(length = n.start - last.start)
            out += n
        }

        return out
    }

    /**
     * A tune started before the count-in finished: beat one moved back by whole bars until the
     * first note is in bar one, so the bar lines stay where the click put them.
     */
    fun align(sung: List<SungNote>, p: Placing): Placing {
        val first = sung.minOfOrNull { it.start } ?: return p
        val bar = 60f / p.tempo * p.beatsPerBar
        val half = p.grid.ticks * 60f / (p.tempo * PPQ) / 2
        var zero = p.beatZero
        while (first < zero - half) zero -= bar
        return p.copy(beatZero = zero)
    }

    /**
     * The note a sung pitch is taken for. A pitch sung squarely on a semitone keeps it even
     * outside the key; one between two is put on the nearer that is in the key.
     */
    fun snap(pitch: Float, key: Key): Int {
        val nearest = pitch.roundToInt()
        if (key.chromatic || key.contains(nearest)) return nearest
        if (abs(pitch - nearest) < 0.25f) return nearest
        val below = floor(pitch).toInt()
        val candidates = listOf(below - 1, below, below + 1, below + 2).filter(key::contains)
        return candidates.minByOrNull { abs(it - pitch) } ?: nearest
    }

    /**
     * How far the voice sits from concert pitch, in semitones, from -0.5 to 0.5: the mean of
     * every note's distance from its nearest semitone, longer notes counting for more.
     * Taken round a circle, so a voice a half step off is not averaged to nothing.
     */
    fun tuning(sung: List<SungNote>): Float {
        if (sung.isEmpty()) return 0f
        var x = 0.0
        var y = 0.0
        for (s in sung) {
            val a = 2 * PI * (s.pitch - s.pitch.roundToInt())
            x += cos(a) * s.length
            y += sin(a) * s.length
        }
        return (atan2(y, x) / (2 * PI)).toFloat()
    }

    /**
     * The key a tune is most likely in: each key's profile (Krumhansl and Kessler's) against
     * how long each pitch class was sung.
     */
    fun guessKey(sung: List<SungNote>, tuning: Float): Key {
        if (sung.isEmpty()) return Key(0, false)
        val weight = DoubleArray(12)
        for (s in sung) weight[((s.pitch - tuning).roundToInt() % 12 + 12) % 12] += s.length.toDouble()
        var best = Key(0, false)
        var bestScore = Double.NEGATIVE_INFINITY
        for (minor in listOf(false, true)) for (t in 0 until 12) {
            val profile = if (minor) KK_MINOR else KK_MAJOR
            val score = correlate(weight) { profile[((it - t) % 12 + 12) % 12] }
            if (score > bestScore + 1e-9) { bestScore = score; best = Key(t, minor) }
        }
        return best
    }

    private fun correlate(w: DoubleArray, profile: (Int) -> Double): Double {
        val mw = w.average()
        val mp = (0 until 12).sumOf(profile) / 12
        var num = 0.0; var dw = 0.0; var dp = 0.0
        for (i in 0 until 12) {
            val a = w[i] - mw; val b = profile(i) - mp
            num += a * b; dw += a * a; dp += b * b
        }
        return if (dw == 0.0 || dp == 0.0) 0.0 else num / Math.sqrt(dw * dp)
    }

    private val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /**
     * For a tune sung without the click: the tempo that puts the most note starts on eighth
     * notes, between 60 and 180, the first note on beat one. Of tempos that fit about as
     * well, the one nearest a walking pace is taken, since twice and half a tempo fit too.
     */
    fun guessTempo(sung: List<SungNote>): Pair<Float, Float> {
        if (sung.size < 3) return 100f to (sung.firstOrNull()?.start ?: 0f)
        val zero = sung.first().start
        val onsets = sung.map { it.start - zero }
        var best = 100f
        var bestScore = Double.MAX_VALUE
        var bpm = 60f
        while (bpm <= 180f) {
            val eighth = 30.0 / bpm
            var err = 0.0
            for (o in onsets) {
                val r = o / eighth
                val d = abs(r - Math.round(r))
                err += d * d
            }
            err /= onsets.size
            val score = err + 0.004 * abs(ln(bpm / 100.0))
            if (score < bestScore) { bestScore = score; best = bpm }
            bpm += 0.5f
        }
        return best to zero
    }
}
