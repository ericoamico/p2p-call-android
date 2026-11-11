package io.github.ericoamico.p2pcall.call

import android.os.SystemClock
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/** Estado da conexão P2P com um participante. */
internal class PeerSession(val id: String, val pc: PeerConnection) {
    /** Nome de exibição informado pelo participante (null para clientes web). */
    var displayName: String? = null
    @Volatile var connected = false

    /** Trilhas de áudio recebidas (microfone e, do web, às vezes o áudio do PC). */
    val remoteAudio = mutableListOf<AudioTrack>()

    /** Tela recebida (só clientes desktop/Android enviam vídeo). */
    @Volatile var remoteVideo: VideoTrack? = null
        private set
    private val frameWatch = FrameWatch()
    val lastFrameAt: Long get() = frameWatch.last

    private val pendingCandidates = mutableListOf<IceCandidate>()
    var hasRemoteDescription = false
        private set

    fun setRemoteVideo(track: VideoTrack) {
        remoteVideo?.let { runCatching { it.removeSink(frameWatch) } }
        remoteVideo = track
        track.addSink(frameWatch)
    }

    fun addRemoteCandidate(candidate: IceCandidate) {
        if (hasRemoteDescription) pc.addIceCandidate(candidate) else pendingCandidates += candidate
    }

    /** Chamado depois de aplicar a descrição remota: entrega os candidatos que chegaram antes. */
    fun onRemoteDescriptionSet() {
        hasRemoteDescription = true
        pendingCandidates.forEach { pc.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    fun dispose() {
        remoteVideo?.let { runCatching { it.removeSink(frameWatch) } }
        remoteVideo = null
        remoteAudio.clear()
        runCatching { pc.dispose() }
    }

    /** Marca a hora do último frame: a tela some 3 s depois que o participante para de enviar. */
    private class FrameWatch : VideoSink {
        @Volatile var last = 0L
        override fun onFrame(frame: VideoFrame) {
            last = SystemClock.elapsedRealtime()
        }
    }
}
