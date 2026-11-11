package io.github.ericoamico.p2pcall.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread

/**
 * Áudio do aparelho durante o compartilhamento de tela (equivalente ao loopback por processo do
 * desktop): captura o que os outros apps tocam, *excluindo* este app, então as vozes da chamada
 * nunca voltam para quem está ouvindo. É somado à trilha do microfone em [AudioPipeline].
 */
class SystemAudioCapture private constructor(
    private val record: AudioRecord,
    val channels: Int,
) {
    // Até 250 ms acumulados; mais que isso vira atraso, então o mais antigo é descartado.
    private val ring = PcmRing(SAMPLE_RATE * channels / 4, channels)
    @Volatile private var running = true
    private var mixBuffer = IntArray(0)

    private val reader = thread(name = "AudioDoAparelho", priority = Thread.MAX_PRIORITY) {
        val chunk = ShortArray(SAMPLE_RATE / 100 * channels) // 10 ms
        try {
            record.startRecording()
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n > 0) ring.write(chunk, 0, n)
                else if (n < 0) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Captura do áudio do aparelho parou", e)
        }
    }

    /** Soma o áudio do aparelho em [samples] (mesmo número de canais da captura). */
    fun mixInto(samples: ShortArray, count: Int) {
        if (mixBuffer.size < count) mixBuffer = IntArray(count)
        val mix = mixBuffer
        for (i in 0 until count) mix[i] = samples[i].toInt()
        ring.mixInto(mix, count)
        for (i in 0 until count) samples[i] = Pcm.clamp(mix[i])
    }

    fun stop() {
        running = false
        try { record.stop() } catch (_: Exception) { }
        reader.join(500)
        record.release()
    }

    companion object {
        private const val TAG = "SystemAudioCapture"

        /** Retorna null se o aparelho não permitir a captura (a tela vai sem áudio). */
        @SuppressLint("MissingPermission") // RECORD_AUDIO já foi concedida para entrar na sala.
        fun start(projection: MediaProjection, channels: Int): SystemAudioCapture? = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(mask)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE / 10 * channels * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                SystemAudioCapture(record, channels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Áudio do aparelho indisponível", e)
            null
        }
    }
}
