package com.wanderwildwood.hanauta.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One channel of 16-bit PCM in a plain WAV file: what a take's recording is kept as. */
object Wav {
    private const val HEADER = 44

    /** A file to write a recording into a block at a time; [close] puts the lengths in. */
    class Writer(file: File, private val rate: Int) : AutoCloseable {
        private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(header(rate, 0)) }
        var frames = 0L
            private set

        fun write(samples: ShortArray, count: Int) {
            val b = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) b.putShort(samples[i])
            out.write(b.array())
            frames += count
        }

        override fun close() {
            out.seek(0)
            out.write(header(rate, frames * 2))
            out.close()
        }
    }

    fun write(file: File, samples: FloatArray, rate: Int) {
        Writer(file, rate).use { w ->
            val block = ShortArray(4096)
            var i = 0
            while (i < samples.size) {
                val n = minOf(block.size, samples.size - i)
                for (k in 0 until n) block[k] = (samples[i + k].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                w.write(block, n)
                i += n
            }
        }
    }

    /** The samples, -1 to 1, and their rate; null for anything but 16-bit PCM. Stereo is mixed down. */
    fun read(file: File): Pair<FloatArray, Int>? {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < HEADER || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var p = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var format = 0
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            val body = p + 8
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt()
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (format != 1 || bits != 16 || channels < 1) return null
                    // A recording cut off before its header was finished says its data is empty
                    // or longer than the file: take what is there.
                    val end = if (size <= 0 || body + size > bytes.size) bytes.size else body + size
                    val frames = (end - body) / (2 * channels)
                    val out = FloatArray(frames)
                    for (f in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += b.getShort(body + (f * channels + c) * 2) / 32768f
                        out[f] = s / channels
                    }
                    return out to rate
                }
            }
            p = body + size + (size and 1)
        }
        return null
    }

    private fun header(rate: Int, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(1)          // PCM, one channel
        b.putInt(rate); b.putInt(rate * 2)    // byte rate
        b.putShort(2); b.putShort(16)         // block align, bits
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }
}
