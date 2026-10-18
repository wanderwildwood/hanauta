package com.wanderwildwood.hanauta.music

/** Everything read out of one recording. */
class Transcription(val track: Track, val sung: List<SungNote>, val placing: Placing, val notes: List<Note>)

object Transcribe {
    /**
     * From the sound to notes. With [tempo] and [beatZero] known from the click they are used;
     * without, both are guessed from the notes. [key] null guesses it too.
     */
    fun run(
        samples: FloatArray,
        rate: Int,
        beatsPerBar: Int,
        grid: Grid,
        tempo: Float? = null,
        beatZero: Float? = null,
        key: Key? = null,
        onProgress: (Float) -> Unit = {},
    ): Transcription {
        val track = Tracker.analyse(samples, rate, onProgress)
        val sung = Segmenter.segment(track)
        val tuning = Quantise.tuning(sung)
        val (t, zero) = if (tempo != null && beatZero != null) tempo to beatZero else Quantise.guessTempo(sung)
        val placing = Quantise.align(sung, Placing(t, beatsPerBar, key ?: Quantise.guessKey(sung, tuning), grid, zero, tuning))
        return Transcription(track, sung, placing, Quantise.place(sung, placing))
    }
}
