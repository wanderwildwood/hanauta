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

    private val _failed = MutableStateFlow(false)
    /**
     * True when the last play could not reach the speaker (no output to open, or one that
     * refused the sound). The take's screen says so; the next play tries again.
     */
    val failed: StateFlow<Boolean> = _failed

    fun seenFailure() {
        _failed.value = false
    }

    @Volatile private var on = false
    @Volatile private var track: AudioTrack? = null
    /** Which play is the current one: an older one still rendering stands down when it sees a newer. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    fun play(notes: List<Note>, tempo: Float, fromTick: Int, voice: File?, beatZero: Float) {
        stop()
        val mine = generation.incrementAndGet()
        fun current() = on && generation.get() == mine
        on = true
        _failed.value = false
        _position.value = (fromTick / PPQ) * PPQ
        _playing.value = true
        val secondsPerTick = 60.0 / (tempo * PPQ)
        thread(name = "hum-play") {
            var own: AudioTrack? = null
            var mix: Mix? = null
            try {
                // Made a block at a time as it is played, the recording read a stretch at a
                // time: a long take is never held whole.
                val m = Mix(notes, tempo, fromTick, voice, beatZero)
                mix = m
                if (!current() || m.frames == 0) return@thread
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
                    .build()
                own = t
                track = t
                t.play()
                val block = ShortArray(BLOCK)
                var filled = 0
                var used = 0
                var off = 0
                while (current() && off < m.frames) {
                    if (used == filled) {
                        filled = minOf(BLOCK, m.frames - off)
                        m.render(off, filled, block)
                        used = 0
                    }
                    val w = t.write(block, used, filled - used)
                    if (w < 0) _failed.value = true
                    if (w <= 0) break
                    used += w
                    off += w
                    follow(t, fromTick, secondsPerTick)
                }
                // Let what is buffered finish before letting go; no longer than it could take,
                // in case the head never moves (no speaker to move it).
                if (current()) {
                    t.stop()
                    val deadline = System.currentTimeMillis() + 1500
                    while (current() && t.playbackHeadPosition < m.frames && System.currentTimeMillis() < deadline) {
                        follow(t, fromTick, secondsPerTick)
                        Thread.sleep(20)
                    }
                }
            } catch (e: Throwable) {
                // A track that would not open or play, or a recording too big to read: said on
                // the take's screen, not a crash.
                if (current()) _failed.value = true
            } finally {
                runCatching { mix?.close() }
                // Only this play's own track, and the state only while this play is the current one.
                own?.let { runCatching { it.pause(); it.flush(); it.release() } }
                if (track === own) track = null
                if (generation.get() == mine) {
                    _position.value = -1
                    _playing.value = false
                }
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

    /** Frames made and written to the speaker at a time. */
    private const val BLOCK = 4096

    /**
     * The notes as a plucked tone from [fromTick] to the end, with the recording [voice] at half
     * loudness underneath if given, made a block at a time.
     */
    private class Mix(notes: List<Note>, tempo: Float, fromTick: Int, voice: File?, beatZero: Float) : AutoCloseable {
        private class Tone(val a: Int, val b: Int, val f: Double)

        private val secondsPerTick = 60.0 / (tempo * PPQ)
        private val start = fromTick * secondsPerTick
        /** Where the playback starts, in the recording's own time. */
        private val from = beatZero + start
        val frames: Int
        private val tones: List<Tone>
        private var reader: Wav.Reader? = null
        private var mix = FloatArray(0)
        private var heard = FloatArray(0)

        init {
            val playing = notes.filter { it.end > fromTick }
            val endTick = playing.maxOfOrNull { it.end }
            frames = if (endTick == null) 0 else (((endTick - fromTick) * secondsPerTick + 0.4) * RATE).toInt()
            tones = playing.map { n ->
                val a = ((maxOf(n.start, fromTick) * secondsPerTick - start) * RATE).toInt()
                val b = minOf(frames, ((n.end * secondsPerTick - start) * RATE).toInt() + RATE / 20)
                Tone(a, b, 440.0 * 2.0.pow((n.pitch - 69) / 12.0))
            }
            if (voice != null && frames > 0) {
                val r = Wav.Reader(voice)
                if (r.layout != null) reader = r else r.close()
            }
        }

        /** Frames [first] to [first] + [n] of the playback into [out]. */
        fun render(first: Int, n: Int, out: ShortArray) {
            if (mix.size < n) mix = FloatArray(n)
            val m = mix
            m.fill(0f, 0, n)
            val end = first + n
            for (tone in tones) {
                if (tone.b <= first || tone.a >= end) continue
                for (i in maxOf(tone.a, first) until minOf(tone.b, end)) {
                    val t = (i - tone.a).toDouble() / RATE
                    val release = ((tone.b - i).toDouble() / (RATE / 20)).coerceAtMost(1.0)
                    val env = minOf(1.0, t / 0.005) * (0.35 + 0.65 * exp(-t * 4)) * release
                    val w = 2 * PI * tone.f * t
                    m[i - first] += (env * (sin(w) + 0.4 * sin(2 * w) * exp(-t * 6) + 0.15 * sin(3 * w) * exp(-t * 9)) * 0.3).toFloat()
                }
            }
            val r = reader
            val l = r?.layout
            if (r != null && l != null) {
                val rate = l.rate
                fun at(i: Int) = ((from + i.toDouble() / RATE) * rate).toInt()
                // Only the stretch of the recording this block plays over.
                val lo = maxOf(0L, at(first).toLong())
                val hi = minOf(l.frames - 1, at(end - 1).toLong())
                if (lo <= hi) {
                    val count = (hi - lo + 1).toInt()
                    if (heard.size < count) heard = FloatArray(count)
                    r.read(lo, count, heard)
                    for (k in 0 until n) {
                        val p = at(first + k).toLong()
                        if (p in lo..hi) m[k] += heard[(p - lo).toInt()] * 0.5f
                    }
                }
            }
            for (k in 0 until n) out[k] = (m[k].coerceIn(-1f, 1f) * 30000).toInt().toShort()
        }

        override fun close() {
            reader?.close()
        }
    }
}
