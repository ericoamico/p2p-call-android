package io.github.ericoamico.p2pcall.audio

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Grava a chamada (o que enviamos: microfone + áudio do aparelho, e as vozes dos participantes)
 * em WAV estéreo 48 kHz, salvo em Música/P2PCall. O bloco do microfone (10 ms) é o relógio:
 * a cada bloco, soma o que chegou de cada participante.
 */
class CallRecorder(private val context: Context, room: String) {
    private val fileName: String = run {
        val safeRoom = room.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        "P2PCall_${safeRoom}_$stamp.wav"
    }
    private val temp = File(context.cacheDir, fileName)
    private val file = RandomAccessFile(temp, "rw").apply {
        setLength(0)
        write(ByteArray(WAV_HEADER))
    }
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val remotes = ConcurrentHashMap<AudioTrack, RemoteSource>()
    @Volatile private var closed = false
    private var dataBytes = 0L
    private var mix = IntArray(0)

    private val writer = thread(name = "Gravacao") {
        var bytes = ByteArray(0)
        while (true) {
            val chunk = queue.take()
            if (chunk === END) break
            if (bytes.size < chunk.size * 2) bytes = ByteArray(chunk.size * 2)
            for (i in chunk.indices) {
                bytes[2 * i] = (chunk[i].toInt() and 0xFF).toByte()
                bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte()
            }
            file.write(bytes, 0, chunk.size * 2)
            dataBytes += chunk.size * 2
        }
    }

    fun attach(track: AudioTrack) {
        if (closed) return
        val source = RemoteSource()
        if (remotes.putIfAbsent(track, source) != null) return
        try { track.addSink(source) } catch (_: Exception) { remotes.remove(track) }
    }

    fun detach(track: AudioTrack) {
        val source = remotes.remove(track) ?: return
        try { track.removeSink(source) } catch (_: Exception) { /* trilha já descartada */ }
    }

    /** Chamado pelo [AudioPipeline] a cada bloco enviado. */
    fun pushOutgoing(samples: ShortArray, count: Int, channels: Int, sampleRate: Int) {
        if (closed) return
        val stereo = Pcm.toStereo48k(samples, count, channels, sampleRate)
        if (mix.size < stereo.size) mix = IntArray(stereo.size)
        val m = mix
        for (i in stereo.indices) m[i] = stereo[i].toInt()
        for (source in remotes.values) source.ring.mixInto(m, stereo.size)
        val out = ShortArray(stereo.size) { Pcm.clamp(m[it]) }
        queue.offer(out)
    }

    /** Fecha o arquivo e o copia para Música/P2PCall. Retorna false se falhar. */
    fun finish(): Boolean {
        if (closed) return false
        closed = true
        for (track in remotes.keys.toList()) detach(track)
        queue.offer(END)
        writer.join(3000)
        return try {
            writeHeader()
            file.close()
            publish()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar a gravação", e)
            false
        } finally {
            temp.delete()
        }
    }

    private fun writeHeader() {
        val header = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }
        file.seek(0)
        file.write(header.array())
    }

    private fun publish() {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/P2PCall")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore recusou o arquivo")
        resolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    /** Recebe o áudio decodificado de uma trilha remota (até 0,5 s acumulado). */
    private class RemoteSource : AudioTrackSink {
        val ring = PcmRing(SAMPLE_RATE, 2)
        private var shorts = ShortArray(0)

        override fun onData(data: ByteBuffer, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int, timestampMs: Long) {
            if (bitsPerSample != 16 || channels <= 0) return
            val count = frames * channels
            if (shorts.size < count) shorts = ShortArray(count)
            val view = data.duplicate().order(ByteOrder.nativeOrder())
            view.clear()
            val available = minOf(count, view.capacity() / 2)
            view.asShortBuffer().get(shorts, 0, available)
            ring.write(Pcm.toStereo48k(shorts, available, channels, sampleRate))
        }
    }

    private companion object {
        const val TAG = "CallRecorder"
        const val WAV_HEADER = 44
        val END = ShortArray(0)
    }
}
