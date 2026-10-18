package com.wanderwildwood.hanauta.music

import com.wanderwildwood.hanauta.pitch.AutoCorrelation
import com.wanderwildwood.hanauta.pitch.Correlation
import com.wanderwildwood.hanauta.pitch.CorrelationBasedFrequency
import com.wanderwildwood.hanauta.pitch.findCorrelationBasedFrequency
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * The voice's pitch through a recording, one reading every [HOP_SECONDS].
 *
 * [midi] is the pitch as a fractional MIDI note (69 is A 440), or NaN where nothing was sung
 * clearly enough to have one. [level] is the loudness of the same stretch (RMS, 0 to 1).
 */
class Track(val midi: FloatArray, val level: FloatArray, val offset: Float = 0f) {
    val size get() = midi.size

    /** The middle of the stretch reading [i] was taken from, in seconds. */
    fun time(i: Int) = offset + i * HOP_SECONDS

    companion object {
        const val HOP_SECONDS = 0.01f
    }
}

/**
 * Reads the pitch out of a recording, with Tuner's autocorrelation: the period is the shift at
 * which the sound best matches itself, checked against its multiples so a strong second
 * period is not taken for the first.
 */
object Tracker {
    /** The rate the analysis runs at; a recording is brought down to it first. */
    const val RATE = 22050
    private const val WINDOW = 1024
    private const val F_MIN = 65f    // C2, below any voice humming
    private const val F_MAX = 1100f  // C6, above any voice singing a tune

    /** How alike the sound is to itself one period on, 0 to 1, for a reading to count. */
    private const val CLARITY = 0.6f

    /**
     * [samples] at [rate], one channel, -1 to 1. [onProgress] is told the share done, 0 to 1,
     * now and then.
     */
    fun analyse(samples: FloatArray, rate: Int, onProgress: (Float) -> Unit = {}): Track {
        val x = resample(samples, rate, RATE)
        val hop = (RATE * Track.HOP_SECONDS).toInt()
        val n = if (x.size < WINDOW) 0 else (x.size - WINDOW) / hop + 1
        val midi = FloatArray(n) { Float.NaN }
        val level = FloatArray(n)
        val correlation = Correlation(WINDOW)
        val acf = AutoCorrelation(WINDOW, 1f / RATE)
        val result = CorrelationBasedFrequency(0f, 0f, 0f)
        val frame = FloatArray(WINDOW)

        for (i in 0 until n) {
            if (i % 200 == 0) onProgress(i.toFloat() / n)
            val from = i * hop
            var sum = 0.0
            var mean = 0.0
            for (k in 0 until WINDOW) mean += x[from + k]
            mean /= WINDOW
            for (k in 0 until WINDOW) {
                val v = (x[from + k] - mean).toFloat()
                frame[k] = v
                sum += v * v
            }
            val rms = sqrt(sum / WINDOW).toFloat()
            level[i] = rms
            if (rms < 1e-4f) continue

            correlation.correlate(frame, acf.values)
            findCorrelationBasedFrequency(result, acf, F_MIN, F_MAX, 0.1f, 0.85f)
            val f = result.frequency
            if (f < F_MIN || f > F_MAX || acf.values[0] <= 0f) continue
            // The correlation shrinks with the shift because less of the window overlaps;
            // take that out so a low note is judged as fairly as a high one.
            val lag = result.timeShift * RATE
            val overlap = (WINDOW - lag) / WINDOW
            val clarity = result.correlationAtTimeShift / (acf.values[0] * overlap)
            if (clarity >= CLARITY) midi[i] = toMidi(f)
        }
        onProgress(1f)
        return Track(midi, level, WINDOW / 2f / RATE)
    }

    fun toMidi(frequency: Float) = 69f + 12f * log2(frequency / 440f)

    /** Linear resampling: plenty for finding pitch, and it keeps no state. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        // Going down, a short running mean first, so what is above the new rate's limit is
        // not folded back into the band the voice is in.
        val src = if (from > to) {
            val k = (from / to).coerceAtLeast(1)
            if (k == 1) x else FloatArray(x.size) { i ->
                var s = 0f
                var c = 0
                for (j in i until minOf(x.size, i + k)) { s += x[j]; c++ }
                s / c
            }
        } else x
        val n = (src.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return FloatArray(n) { i ->
            val p = i * step
            val a = p.toInt()
            val b = minOf(a + 1, src.size - 1)
            val t = (p - a).toFloat()
            src[a] * (1 - t) + src[b] * t
        }
    }
}
