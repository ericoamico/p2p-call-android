package io.github.ericoamico.p2pcall.audio

import android.media.AudioFormat
import org.webrtc.audio.AudioRecordDataCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Recebe cada bloco de 10 ms do microfone antes de ir para o codificador Opus:
 * aplica o silenciar (só o microfone), soma o áudio do aparelho e entrega à gravação.
 * Assim o áudio do aparelho continua indo com o microfone silenciado, como no desktop,
 * sem nova trilha nem renegociação.
 */
class AudioPipeline : AudioRecordDataCallback {
    @Volatile var muted = false
    @Volatile var systemAudio: SystemAudioCapture? = null
    @Volatile var recorder: CallRecorder? = null

    private var work = ShortArray(0)

    override fun onAudioDataRecorded(audioFormat: Int, channelCount: Int, sampleRate: Int, buffer: ByteBuffer) {
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT) return
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        view.clear()
        val count = view.capacity() / 2
        if (work.size < count) work = ShortArray(count)
        val samples = work

        val muted = muted
        val system = systemAudio?.takeIf { it.channels == channelCount }
        if (muted) samples.fill(0, 0, count) else view.asShortBuffer().get(samples, 0, count)
        system?.mixInto(samples, count)
        if (muted || system != null) view.asShortBuffer().put(samples, 0, count)

        recorder?.pushOutgoing(samples, count, channelCount, sampleRate)
    }
}
