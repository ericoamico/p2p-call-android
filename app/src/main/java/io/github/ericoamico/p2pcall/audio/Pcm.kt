package io.github.ericoamico.p2pcall.audio

import kotlin.math.min

const val SAMPLE_RATE = 48000

/** Fila circular de amostras PCM 16 bits. Quando enche, descarta o mais antigo (frames inteiros). */
class PcmRing(capacitySamples: Int, private val channels: Int) {
    private val buffer = ShortArray(capacitySamples - capacitySamples % channels)
    private var start = 0
    private var size = 0

    @Synchronized
    fun write(data: ShortArray, offset: Int = 0, length: Int = data.size) {
        val capacity = buffer.size
        for (i in offset until offset + length) {
            if (size == capacity) {
                start = (start + channels) % capacity
                size -= channels
            }
            buffer[(start + size) % capacity] = data[i]
            size++
        }
    }

    /** Soma até [length] amostras em [dst]; o que faltar fica como está (silêncio). */
    @Synchronized
    fun mixInto(dst: IntArray, length: Int) {
        val n = min(length, size - size % channels)
        val capacity = buffer.size
        for (i in 0 until n) dst[i] += buffer[(start + i) % capacity]
        start = (start + n) % capacity
        size -= n
    }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }
}

object Pcm {
    fun clamp(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Converte para estéreo 48 kHz (mono vai igual nos dois canais). */
    fun toStereo48k(src: ShortArray, count: Int, channels: Int, sampleRate: Int): ShortArray {
        if (channels <= 0 || sampleRate <= 0) return ShortArray(0)
        val frames = count / channels
        if (frames == 0) return ShortArray(0)
        val outFrames = if (sampleRate == SAMPLE_RATE) frames else (frames.toLong() * SAMPLE_RATE / sampleRate).toInt()
        val out = ShortArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val f = if (sampleRate == SAMPLE_RATE) i else min(frames - 1, (i.toLong() * sampleRate / SAMPLE_RATE).toInt())
            val left = src[f * channels]
            val right = if (channels > 1) src[f * channels + 1] else left
            out[2 * i] = left
            out[2 * i + 1] = right
        }
        return out
    }
}
