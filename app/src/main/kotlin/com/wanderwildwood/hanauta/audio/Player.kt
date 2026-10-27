package com.wanderwildwood.hanauta.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.wanderwildwood.hanauta.music.Note
import com.wanderwildwood.hanauta.music.PPQ
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Plays the notes back as a soft plucked tone, with the recording quieter underneath if wanted,
 * from a given tick to the end.
 */
object Player {
    private const val RATE = 44100
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _position = MutableStateFlow(-1)
    /**
     * Where the playback has got to, as the tick of the beat being played, or -1 when it is not
     * playing. It moves a beat at a time, so a screen that follows it redraws once a beat and
     * not once a frame.
     */
    val position: StateFlow<Int> = _position

    @Volatile private var on = false
    @Volatile private var track: AudioTrack? = null

    fun play(notes: List<Note>, tempo: Float, fromTick: Int, voice: File?, beatZero: Float) {
        stop()
        on = true
        _position.value = (fromTick / PPQ) * PPQ
        _playing.value = true
        val secondsPerTick = 60.0 / (tempo * PPQ)
        thread(name = "hum-play") {
            try {
                val pcm = render(notes, tempo, fromTick, voice, beatZero)
                if (!on || pcm.isEmpty()) return@thread
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
                    .build()
                track = t
                t.play()
                var off = 0
                while (on && off < pcm.size) {
                    val w = t.write(pcm, off, minOf(4096, pcm.size - off))
                    if (w <= 0) break
                    off += w
                    follow(t, fromTick, secondsPerTick)
                }
                // Let what is buffered finish before letting go; no longer than it could take,
                // in case the head never moves (no speaker to move it).
                if (on) {
                    t.stop()
                    val deadline = System.currentTimeMillis() + 1500
                    while (on && t.playbackHeadPosition < pcm.size && System.currentTimeMillis() < deadline) {
                        follow(t, fromTick, secondsPerTick)
                        Thread.sleep(20)
                    }
                }
            } finally {
                track?.let { runCatching { it.pause(); it.flush(); it.release() } }
                track = null
                _position.value = -1
                _playing.value = false
            }
        }
    }

    fun stop() {
        on = false
        _position.value = -1
        _playing.value = false
        // A write waiting on a full buffer returns once the track is paused.
        runCatching { track?.pause(); track?.flush() }
    }

    /** Moves [position] to the beat the speaker has reached. */
    private fun follow(t: AudioTrack, fromTick: Int, secondsPerTick: Double) {
        val tick = fromTick + (t.playbackHeadPosition.toDouble() / RATE / secondsPerTick).toInt()
        // Only ever forward: a track that has been told to stop may count its head from zero again.
        val beat = (tick / PPQ) * PPQ
        if (on && beat > _position.value) _position.value = beat
    }

    private fun render(notes: List<Note>, tempo: Float, fromTick: Int, voice: File?, beatZero: Float): ShortArray {
        val secondsPerTick = 60.0 / (tempo * PPQ)
        val playing = notes.filter { it.end > fromTick }
        val endTick = playing.maxOfOrNull { it.end } ?: return ShortArray(0)
        val start = fromTick * secondsPerTick
        val length = (endTick - fromTick) * secondsPerTick + 0.4
        val mix = FloatArray((length * RATE).toInt())

        for (n in playing) {
            val a = ((maxOf(n.start, fromTick) * secondsPerTick - start) * RATE).toInt()
            val b = minOf(mix.size, ((n.end * secondsPerTick - start) * RATE).toInt() + RATE / 20)
            val f = 440.0 * 2.0.pow((n.pitch - 69) / 12.0)
            for (i in a until b) {
                val t = (i - a).toDouble() / RATE
                val release = ((b - i).toDouble() / (RATE / 20)).coerceAtMost(1.0)
                val env = minOf(1.0, t / 0.005) * (0.35 + 0.65 * exp(-t * 4)) * release
                val w = 2 * PI * f * t
                mix[i] += (env * (sin(w) + 0.4 * sin(2 * w) * exp(-t * 6) + 0.15 * sin(3 * w) * exp(-t * 9)) * 0.3).toFloat()
            }
        }

        if (voice != null) {
            Wav.read(voice)?.let { (samples, rate) ->
                // Where the playback starts, in the recording's own time.
                val from = beatZero + start
                for (i in mix.indices) {
                    val p = ((from + i.toDouble() / RATE) * rate).toInt()
                    if (p in samples.indices) mix[i] += samples[p] * 0.5f
                }
            }
        }
        return ShortArray(mix.size) { (mix[it].coerceIn(-1f, 1f) * 30000).toInt().toShort() }
    }
}
