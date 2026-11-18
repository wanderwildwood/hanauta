package com.wanderwildwood.hanauta.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One channel of 16-bit PCM in a plain WAV file: what a take's recording is kept as. */
object Wav {
    private const val HEADER = 44
    /** Frames read from disk at a time. */
    private const val BLOCK_FRAMES = 8192

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

    /** Where a WAV file's samples lie: one frame is a sample of each channel, 16-bit PCM. */
    class Layout(val rate: Int, val channels: Int, val dataStart: Long, val frames: Long)

    /**
     * An open recording to read a stretch of at a time, so a long take is never held whole;
     * [layout] is null for anything but 16-bit PCM.
     */
    class Reader(file: File) : AutoCloseable {
        private val f = RandomAccessFile(file, "r")
        val layout: Layout? = try { layoutOf(f) } catch (e: Throwable) { f.close(); throw e }

        /**
         * [count] frames from frame [first] into [out] from [at], -1 to 1, stereo mixed down.
         * The frames asked for must lie in the file.
         */
        fun read(first: Long, count: Int, out: FloatArray, at: Int = 0) {
            val l = layout ?: return
            val frameBytes = 2 * l.channels
            val block = minOf(count, BLOCK_FRAMES)
            val buf = ByteArray(block * frameBytes)
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            f.seek(l.dataStart + first * frameBytes)
            var done = 0
            while (done < count) {
                val n = minOf(block, count - done)
                f.readFully(buf, 0, n * frameBytes)
                for (k in 0 until n) {
                    var s = 0f
                    for (c in 0 until l.channels) s += b.getShort((k * l.channels + c) * 2) / 32768f
                    out[at + done + k] = s / l.channels
                }
                done += n
            }
        }

        override fun close() = f.close()
    }

    /** The samples, -1 to 1, and their rate; null for anything but 16-bit PCM. Stereo is mixed down. */
    fun read(file: File): Pair<FloatArray, Int>? {
        Reader(file).use { r ->
            val l = r.layout ?: return null
            if (l.frames > Int.MAX_VALUE) return null
            val out = FloatArray(l.frames.toInt())
            r.read(0, out.size, out)
            return out to l.rate
        }
    }

    /**
     * The samples brought down to [rate] when the file's own rate is a whole multiple of it, each
     * the mean of the ones it stands for: just what [com.wanderwildwood.hanauta.music.Tracker.resample]
     * makes of them, without the full-rate samples ever being held. A file at any other rate is
     * read at its own. Null for anything but 16-bit PCM.
     */
    fun readDown(file: File, rate: Int): Pair<FloatArray, Int>? {
        Reader(file).use { r ->
            val l = r.layout ?: return null
            if (l.rate <= rate || l.rate % rate != 0) {
                if (l.frames > Int.MAX_VALUE) return null
                val out = FloatArray(l.frames.toInt())
                r.read(0, out.size, out)
                return out to l.rate
            }
            val k = l.rate / rate
            val n = l.frames / k
            if (n > Int.MAX_VALUE) return null
            val out = FloatArray(n.toInt())
            val groups = maxOf(1, BLOCK_FRAMES / k)
            val chunk = FloatArray(groups * k)
            var i = 0
            while (i < out.size) {
                val g = minOf(groups, out.size - i)
                r.read(i.toLong() * k, g * k, chunk)
                for (j in 0 until g) {
                    var s = 0f
                    for (m in 0 until k) s += chunk[j * k + m]
                    out[i + j] = s / k
                }
                i += g
            }
            return out to rate
        }
    }

    /**
     * Walks the file's chunks to its samples. A chunk other than the samples that says it runs
     * past the end of the file, or backwards, ends the walk: a damaged file, not one to loop on.
     */
    private fun layoutOf(f: RandomAccessFile): Layout? {
        val length = f.length()
        if (length < HEADER) return null
        val head = ByteArray(12)
        f.seek(0)
        f.readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var p = 12L
        var rate = 0
        var channels = 0
        var bits = 0
        var format = 0
        val chunk = ByteArray(8)
        val fmt = ByteArray(16)
        while (p + 8 <= length) {
            f.seek(p)
            f.readFully(chunk)
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            val body = p + 8
            if (id == "data") {
                if (format != 1 || bits != 16 || channels !in 1..8 || rate <= 0) return null
                // A recording cut off before its header was finished says its data is empty
                // or longer than the file: take what is there.
                val end = if (size <= 0 || body + size > length) length else body + size
                return Layout(rate, channels, body, (end - body) / (2 * channels))
            }
            if (size < 0 || body + size > length) return null
            if (id == "fmt ") {
                if (size < 16) return null
                f.readFully(fmt)
                val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                format = b.getShort(0).toInt()
                channels = b.getShort(2).toInt()
                rate = b.getInt(4)
                bits = b.getShort(14).toInt()
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
