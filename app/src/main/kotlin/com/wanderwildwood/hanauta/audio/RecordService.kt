package com.wanderwildwood.hanauta.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import com.wanderwildwood.hanauta.BuildConfig
import com.wanderwildwood.hanauta.MainActivity
import com.wanderwildwood.hanauta.R
import com.wanderwildwood.hanauta.data.Prefs
import com.wanderwildwood.hanauta.data.Take
import com.wanderwildwood.hanauta.data.Takes
import com.wanderwildwood.hanauta.ui.Words
import com.wanderwildwood.hanauta.music.Transcribe
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Records a take, or reads one in from a file, and finds its notes, as a foreground service:
 * the screen timing out, or the phone being put in a pocket, does not cut a take short.
 */
class RecordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(getString(R.string.notify_recording)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val action = intent?.action
        if (running || intent == null || action == null) return START_NOT_STICKY
        running = true
        stopListening = false
        thread(name = "hum-take") {
            try {
                when (action) {
                    RECORD -> record(
                        intent.getIntExtra("tempo", 100),
                        intent.getIntExtra("beats", 4),
                        intent.getBooleanExtra("click", true),
                    )
                    IMPORT -> import(intent.data!!, intent.getStringExtra("name"), intent.getIntExtra("beats", 4))
                }
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // asked for before the Record button does anything
    private fun record(tempo: Int, beats: Int, click: Boolean) {
        val id = System.currentTimeMillis()
        val takes = Takes.get(this)
        val file = takes.wav(id)
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5) * 2)
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            Recording.stage.value = Stage.Failed(Reason.NoMicrophone)
            return
        }

        val beatSeconds = 60f / tempo
        val countIn = if (click) beats * beatSeconds else 0f
        val clicker = if (click) Clicker(tempo, beats) else null
        val recTs = AudioTimestamp()
        var beatZero = countIn
        var aligned = !click

        Wav.Writer(file, RATE).use { wav ->
            rec.startRecording()
            clicker?.start()
            val block = ShortArray(RATE / 20)
            var lastSecond = -1
            var lastBeat = 0
            var loud = false
            while (!stopListening) {
                val n = rec.read(block, 0, block.size)
                if (n <= 0) break
                wav.write(block, n)

                // Where beat one of the tune falls in the recording: the moment the click's
                // first frame left the speaker, measured against the moment the recording's
                // first frame came in. The two streams start apart by however long each takes
                // to get going, and this takes that out. Without timestamps, they are taken to
                // have started together.
                // A gap of more than half a second is a clock that cannot be trusted, not a
                // latency (they run from a few milliseconds to a few hundred), and is left out.
                if (!aligned && clicker != null && wav.frames > RATE / 2) {
                    val played = clicker.firstFrameNanos()
                    if (played != null && rec.getTimestamp(recTs, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val recFirst = recTs.nanoTime - recTs.framePosition * 1_000_000_000L / RATE
                        val gap = (played - recFirst) / 1e9f
                        if (BuildConfig.DEBUG) Log.d("hanauta", "click alignment: gap ${gap}s")
                        if (gap > -0.5f && gap < 0.5f) beatZero = countIn + gap
                        aligned = true
                    }
                }

                var sum = 0.0
                for (i in 0 until n) sum += block[i] * block[i].toDouble()
                loud = sqrt(sum / n) > 600
                val t = wav.frames.toFloat() / RATE
                if (t < beatZero && click) {
                    val beat = ((t - (beatZero - countIn)) / beatSeconds).toInt().coerceIn(0, beats - 1) + 1
                    if (beat != lastBeat) {
                        lastBeat = beat
                        Recording.stage.value = Stage.CountIn(beat, beats)
                    }
                } else {
                    val s = (t - beatZero).toInt().coerceAtLeast(0)
                    if (s != lastSecond) {
                        lastSecond = s
                        Recording.stage.value = Stage.Listening(s, loud)
                    }
                }
                if (t > Decode.LONGEST_SECONDS) break
            }
            clicker?.stop()
            rec.stop()
            rec.release()
        }

        find(id, File(file.path), name = null, tempo = if (click) tempo.toFloat() else null, beatZero = if (click) beatZero else null, beats = beats)
    }

    private fun import(uri: Uri, name: String?, beats: Int) {
        val id = System.currentTimeMillis()
        val takes = Takes.get(this)
        val file = takes.wav(id)
        val read = runCatching {
            if (uri.scheme == "file") Wav.read(File(uri.path!!)) ?: Decode.read(this, uri)
            else Decode.read(this, uri)
        }.getOrNull()
        if (read == null || read.first.isEmpty()) {
            Recording.stage.value = Stage.Failed(Reason.Unreadable)
            return
        }
        Wav.write(file, read.first, read.second)
        find(id, file, name ?: displayName(uri), tempo = null, beatZero = null, beats = beats)
    }

    /** The notes out of a recording that is now on disk, saved as a take. */
    private fun find(id: Long, file: File, name: String?, tempo: Float?, beatZero: Float?, beats: Int) {
        Recording.stage.value = Stage.Finding(0f)
        notify(getString(R.string.notify_finding))
        val takes = Takes.get(this)
        val (samples, rate) = Wav.read(file) ?: run {
            takes.delete(id)
            Recording.stage.value = Stage.Failed(Reason.Unreadable)
            return
        }
        val t = Transcribe.run(samples, rate, beats, Prefs(this).grid, tempo, beatZero) {
            Recording.stage.value = Stage.Finding(it)
        }
        if (t.notes.isEmpty()) {
            takes.delete(id)
            Recording.stage.value = Stage.Failed(Reason.NoNotes)
            return
        }
        val p = t.placing
        val take = Take(
            id = id,
            name = name?.takeIf { it.isNotBlank() } ?: Words.recordedAt(id),
            seconds = samples.size.toFloat() / rate,
            tempo = p.tempo, beatsPerBar = p.beatsPerBar, key = p.key, grid = p.grid,
            beatZero = p.beatZero, tuning = p.tuning,
            clicked = tempo != null,
            sung = t.sung, notes = t.notes,
        )
        takes.savePitch(id, t.track)
        takes.save(take)
        Recording.stage.value = Stage.Done(id)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))

    companion object {
        const val RECORD = "record"
        const val IMPORT = "import"
        /** The rate takes are recorded at: every Android phone records at it. */
        const val RATE = 44100
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1

        @Volatile var stopListening = false
        @Volatile private var running = false
    }
}

/**
 * The click: a short tick on each beat, higher on the first of the bar, written to the speaker
 * from its own thread for as long as the take runs.
 */
private class Clicker(tempo: Int, private val beats: Int) {
    private val rate = RecordService.RATE
    private val beatFrames = rate * 60 / tempo
    private val high = tick(1760.0)
    private val low = tick(1175.0)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
        .build()
    @Volatile private var on = true
    private var worker: Thread? = null
    private val ts = AudioTimestamp()

    private fun tick(f: Double) = ShortArray(rate / 25) { i ->
        val t = i.toDouble() / rate
        (sin(2 * PI * f * t) * exp(-t * 90) * 20000).toInt().toShort()
    }

    fun start() {
        track.play()
        worker = thread(name = "hum-click") {
            val beat = ShortArray(beatFrames)
            var n = 0
            while (on) {
                beat.fill(0)
                val t = if (n % beats == 0) high else low
                t.copyInto(beat, 0, 0, minOf(t.size, beat.size))
                var off = 0
                while (on && off < beat.size) {
                    val w = track.write(beat, off, beat.size - off)
                    if (w <= 0) { on = false; break }
                    off += w
                }
                n++
            }
        }
    }

    /** When the click's first frame was played, on the monotonic clock; null until known. */
    fun firstFrameNanos(): Long? {
        if (!track.getTimestamp(ts) || ts.framePosition <= 0) return null
        return ts.nanoTime - ts.framePosition * 1_000_000_000L / rate
    }

    fun stop() {
        on = false
        runCatching { track.pause(); track.flush() }
        worker?.join(500)
        track.release()
    }
}
