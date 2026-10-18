package com.wanderwildwood.hanauta.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Any sound file the phone can play, read out as one channel of samples, -1 to 1. */
object Decode {
    /** Ten minutes is the longest take; a longer file is read that far. */
    const val LONGEST_SECONDS = 600

    fun read(context: Context, uri: Uri): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no sound in the file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var out = FloatArray(rate * 30)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val float = encoding == AudioFormat.ENCODING_PCM_FLOAT
                            val frames = info.size / ((if (float) 4 else 2) * channels)
                            if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                            for (f in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += if (float) buf.float else buf.short / 32768f
                                out[n++] = s / channels
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            if (n >= rate * LONGEST_SECONDS) break
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return out.copyOf(minOf(n, rate * LONGEST_SECONDS)) to rate
        } finally {
            extractor.release()
        }
    }
}
