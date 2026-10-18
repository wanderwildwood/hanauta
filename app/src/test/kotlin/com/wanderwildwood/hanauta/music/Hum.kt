package com.wanderwildwood.hanauta.music

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A made-up voice humming a known tune, for the tests: a scoop up into each note, vibrato,
 * a few harmonics, a little breath noise, and the whole voice a fifth of a semitone sharp.
 */
object Hum {
    /** (MIDI pitch, beats) — a rest is pitch 0. */
    val TUNE = listOf(
        60 to 1.0, 62 to 0.5, 64 to 0.5, 65 to 1.0, 67 to 1.0,
        67 to 0.5, 67 to 0.5, 69 to 1.0, 0 to 1.0,
        67 to 0.5, 65 to 0.5, 64 to 1.0, 62 to 1.0, 60 to 2.0,
    )

    fun render(
        rate: Int = 44100,
        tempo: Double = 100.0,
        lead: Double = 0.4,
        detune: Double = 0.2,
        seed: Int = 1,
    ): FloatArray {
        val beat = 60.0 / tempo
        val total = lead + TUNE.sumOf { it.second } * beat + 0.5
        val out = FloatArray((total * rate).toInt())
        val rnd = Random(seed)
        var t0 = lead
        var phase = 0.0
        for ((pitch, beats) in TUNE) {
            val len = beats * beat
            if (pitch > 0) {
                val from = (t0 * rate).toInt()
                // Each note let go a little early, as a singer breathes between them.
                val to = ((t0 + len - 0.04) * rate).toInt()
                for (i in from until to) {
                    val t = (i - from).toDouble() / rate
                    val scoop = if (t < 0.05) -1.0 * (1 - t / 0.05) else 0.0
                    val vibrato = if (t > 0.15) 0.3 * sin(2 * PI * 5.5 * t) else 0.0
                    val m = pitch + detune + scoop + vibrato
                    val f = 440.0 * 2.0.pow((m - 69) / 12)
                    phase += 2 * PI * f / rate
                    val env = minOf(1.0, t / 0.02, (to - i).toDouble() / rate / 0.03)
                    val v = sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase) + 0.1 * sin(4 * phase)
                    out[i] = (0.25 * env * v).toFloat()
                }
            }
            t0 += len
        }
        for (i in out.indices) out[i] += (rnd.nextFloat() - 0.5f) * 0.01f
        return out
    }

    /** The tune as the grid should have it at an eighth-note grid, rests left out. */
    fun expected(): List<Note> {
        var tick = 0
        val out = ArrayList<Note>()
        for ((pitch, beats) in TUNE) {
            val len = (beats * PPQ).toInt()
            if (pitch > 0) out += Note(tick, len, pitch)
            tick += len
        }
        return out
    }
}
