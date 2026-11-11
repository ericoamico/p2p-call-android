package io.github.ericoamico.p2pcall.call

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import io.github.ericoamico.p2pcall.audio.AudioPipeline
import io.github.ericoamico.p2pcall.audio.CallRecorder
import io.github.ericoamico.p2pcall.audio.SAMPLE_RATE
import io.github.ericoamico.p2pcall.audio.SystemAudioCapture
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** Tela compartilhada por um participante. */
data class RemoteScreen(val peerId: String, val label: String, val track: VideoTrack)

/**
 * Sinalização via Socket.IO (mesmo protocolo do client.js e do desktop) e uma conexão WebRTC P2P
 * por participante. Áudio em Opus estéreo; tela em VP8 numa trilha de vídeo que existe desde o
 * início (ligar/desligar a tela não renegocia), com o áudio do aparelho somado ao microfone.
 */
class CallManager private constructor(private val context: Context) {

    val eglBase: EglBase = EglBase.create()

    private val pipeline = AudioPipeline()
    private val stereoInput = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
    ) > 0
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val localAudio: AudioTrack

    // Todos os eventos de sinalização passam por aqui, em ordem (equivale ao _signalingLock).
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val lock = Mutex()
    private val peers = ConcurrentHashMap<String, PeerSession>()
    // Candidatos que chegam antes da oferta do participante.
    private val earlyCandidates = HashMap<String, MutableList<IceCandidate>>()
    private var socket: Socket? = null
    private var screenWatch: Job? = null

    private var capturer: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    @Volatile private var screenTrack: VideoTrack? = null
    private var recorder: CallRecorder? = null

    @Volatile var room: String = ""
        private set
    @Volatile var serverUrl: String = ""
        private set
    private var displayName = ""
    @Volatile private var bitrate = 128_000

    private val _inCall = MutableStateFlow(false)
    val inCall: StateFlow<Boolean> = _inCall.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()
    private val _screens = MutableStateFlow<List<RemoteScreen>>(emptyList())
    val screens: StateFlow<List<RemoteScreen>> = _screens.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _sharing = MutableStateFlow(false)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    // Para anunciar entradas e saídas pelo nome.
    private var announcedLabels = mapOf<String, String>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        // Áudio cru como no web/desktop: sem cancelamento de eco, supressão de ruído ou AGC.
        // Saída como mídia (alto-falante ou fone), não como ligação pelo fone de ouvido.
        adm = JavaAudioDeviceModule.builder(context)
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoInput(stereoInput)
            .setUseStereoOutput(true)
            .setSampleRate(SAMPLE_RATE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioRecordDataCallback(pipeline)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val constraints = MediaConstraints().apply {
            listOf(
                "googEchoCancellation", "googEchoCancellation2", "googAutoGainControl",
                "googAutoGainControl2", "googNoiseSuppression", "googNoiseSuppression2",
                "googHighpassFilter", "googTypingNoiseDetection",
            ).forEach { mandatory.add(MediaConstraints.KeyValuePair(it, "false")) }
        }
        audioSource = factory.createAudioSource(constraints)
        localAudio = factory.createAudioTrack("mic", audioSource)
    }

    // ---------- Ações da interface ----------

    fun join(server: String, room: String, name: String, bitrateBps: Int, microphone: AudioDeviceInfo?) {
        if (_inCall.value) return
        this.room = room
        this.serverUrl = server.trim().trimEnd('/')
        displayName = Participant.cleanName(name) ?: ""
        bitrate = bitrateBps
        adm.setPreferredInputDevice(microphone)
        pipeline.muted = _muted.value
        announcedLabels = emptyMap()
        _participants.value = emptyList()
        _screens.value = emptyList()
        _inCall.value = true
        setStatus("Conectando…")

        val s = try {
            IO.socket(URI.create(serverUrl), IO.Options().apply { reconnection = true })
        } catch (e: Exception) {
            _inCall.value = false
            setStatus("Endereço do servidor inválido.")
            return
        }
        socket = s
        var wasConnected = false

        s.on(Socket.EVENT_CONNECT) {
            wasConnected = true
            // Após reconectar, entra de novo na sala para receber os novos participantes.
            s.emit("join", room)
            setStatus("Conectado à sala $room.")
        }
        s.on(Socket.EVENT_DISCONNECT) { if (_inCall.value) setStatus("Desconectado do servidor.") }
        s.on(Socket.EVENT_CONNECT_ERROR) {
            if (_inCall.value && !wasConnected) setStatus("Sem conexão com o servidor. Tentando de novo…")
        }
        s.io().on(Manager.EVENT_RECONNECT_ATTEMPT) { if (wasConnected) setStatus("Reconectando…") }

        s.on("user-joined") { args -> (args.getOrNull(0) as? String)?.let { id -> signal { onUserJoined(id) } } }
        s.on("offer") { args -> (args.getOrNull(0) as? JSONObject)?.let { signal { onOffer(it) } } }
        s.on("answer") { args -> (args.getOrNull(0) as? JSONObject)?.let { signal { onAnswer(it) } } }
        s.on("ice-candidate") { args -> (args.getOrNull(0) as? JSONObject)?.let { signal { onIceCandidate(it) } } }
        s.on("user-disconnected") { args ->
            (args.getOrNull(0) as? String)?.let { id -> signal { earlyCandidates.remove(id); removePeer(id) } }
        }
        s.connect()

        screenWatch = scope.launch { watchScreens() }
    }

    fun leave() {
        if (!_inCall.value) return
        _inCall.value = false
        screenWatch?.cancel()
        val s = socket
        socket = null
        scope.launch {
            lock.withLock {
                stopScreenShareLocked()
                stopRecordingLocked()
                peers.keys.toList().forEach { removePeer(it, announce = false) }
                earlyCandidates.clear()
                s?.off()
                s?.disconnect()
                s?.close()
                _participants.value = emptyList()
                _screens.value = emptyList()
                setStatus("Você saiu da sala.")
            }
        }
    }

    fun setMuted(muted: Boolean) {
        pipeline.muted = muted
        _muted.value = muted
    }

    /** Troca o microfone sem derrubar as conexões. null = padrão do aparelho. */
    fun setMicrophone(device: AudioDeviceInfo?) = adm.setPreferredInputDevice(device)

    /** Equivalente ao maxBitrate do sender no navegador; vale também durante a chamada. */
    fun setBitrate(bitrateBps: Int) {
        bitrate = bitrateBps
        scope.launch { lock.withLock { peers.values.forEach(::applySenderParameters) } }
    }

    fun announce(message: String) = setStatus(message)

    fun roomLink(): String = RoomLink.build(serverUrl, room)

    // ---------- Compartilhamento de tela ----------

    /**
     * Compartilha a tela com o resultado do pedido de captura. O serviço em primeiro plano já
     * precisa estar com o tipo mediaProjection (Android 14+).
     */
    fun startScreenShare(permissionData: Intent) {
        scope.launch {
            lock.withLock {
                if (!_inCall.value || capturer != null) return@withLock
                try {
                    startScreenShareLocked(permissionData)
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao compartilhar a tela", e)
                    stopScreenShareLocked()
                    setStatus("Não foi possível compartilhar a tela.")
                }
            }
        }
    }

    fun stopScreenShare() {
        scope.launch {
            lock.withLock {
                if (capturer == null) return@withLock
                stopScreenShareLocked()
                setStatus("Tela não compartilhada.")
            }
        }
    }

    private fun startScreenShareLocked(permissionData: Intent) {
        lateinit var cap: ScreenCapturerAndroid
        cap = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            // O usuário pode encerrar pelo próprio sistema (notificação ou bloqueio de tela).
            override fun onStop() {
                scope.launch {
                    lock.withLock {
                        if (capturer === cap) {
                            stopScreenShareLocked()
                            setStatus("Tela não compartilhada.")
                        }
                    }
                }
            }
        })
        val helper = SurfaceTextureHelper.create("Tela", eglBase.eglBaseContext)
        val source = factory.createVideoSource(true)
        capturer = cap
        screenHelper = helper
        screenSource = source
        cap.initialize(helper, context, source.capturerObserver)
        val (width, height) = captureSize()
        cap.startCapture(width, height, SCREEN_FPS)

        val track = factory.createVideoTrack("screen", source)
        screenTrack = track
        peers.values.forEach { attachScreen(it, track) }
        _sharing.value = true

        val system = cap.mediaProjection?.let { SystemAudioCapture.start(it, if (stereoInput) 2 else 1) }
        pipeline.systemAudio = system
        setStatus(if (system != null) "Compartilhando tela e áudio." else "Compartilhando tela sem áudio.")
    }

    private fun stopScreenShareLocked() {
        val cap = capturer
        capturer = null
        pipeline.systemAudio?.let { pipeline.systemAudio = null; it.stop() }
        val track = screenTrack
        screenTrack = null
        peers.values.forEach { attachScreen(it, null) }
        runCatching { cap?.stopCapture() }
        runCatching { cap?.dispose() }
        runCatching { track?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenHelper?.dispose() }
        screenSource = null
        screenHelper = null
        _sharing.value = false
    }

    /** Tela principal reduzida para no máximo 1600 px no lado maior (como no desktop). */
    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val scale = minOf(1.0, SCREEN_MAX_SIZE.toDouble() / max(w, h))
        fun even(v: Double) = max(2, (v.roundToInt() / 2) * 2)
        return even(w * scale) to even(h * scale)
    }

    // ---------- Gravação ----------

    fun setRecording(on: Boolean) {
        scope.launch {
            lock.withLock {
                if (on) {
                    if (recorder != null || !_inCall.value) return@withLock
                    val rec = CallRecorder(context, room)
                    recorder = rec
                    pipeline.recorder = rec
                    peers.values.forEach { p -> p.remoteAudio.forEach(rec::attach) }
                    _recording.value = true
                    setStatus("Gravando.")
                } else {
                    stopRecordingLocked()
                }
            }
        }
    }

    private suspend fun stopRecordingLocked() {
        val rec = recorder ?: return
        recorder = null
        pipeline.recorder = null
        _recording.value = false
        val saved = withContext(Dispatchers.IO) { rec.finish() }
        setStatus(if (saved) "Gravação salva em Música/P2PCall." else "Falha ao salvar a gravação.")
    }

    // ---------- Sinalização ----------

    private fun signal(block: suspend () -> Unit) {
        scope.launch {
            lock.withLock {
                if (!_inCall.value) return@withLock
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro na sinalização", e)
                }
            }
        }
    }

    /** Quem já está na sala oferece para quem chegou. */
    private suspend fun onUserJoined(id: String) {
        val peer = createPeer(id)
        val pc = peer.pc
        pc.addTransceiver(localAudio, RtpTransceiver.RtpTransceiverInit(RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)))
        val video = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)),
        )
        preferVp8(video)
        video.sender.setTrack(screenTrack, false)

        try {
            val offer = pc.awaitCreate(offer = true)
            val sdp = Sdp.tuneOpus(offer.description)
            pc.awaitSetLocal(SessionDescription(SessionDescription.Type.OFFER, sdp))
            applySenderParameters(peer)
            emit("offer", JSONObject().put("to", id).put("offer", description("offer", sdp)))
        } catch (e: Exception) {
            setStatus("Erro ao chamar participante.")
            removePeer(id, announce = false)
        }
    }

    private suspend fun onOffer(data: JSONObject) {
        val from = data.getString("from")
        val offerJson = data.getJSONObject("offer")
        val remoteSdp = Sdp.tuneOpus(offerJson.getString("sdp"), bitrate)
        val offer = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)

        // Renegociação (ex.: o participante web começou a enviar o áudio do PC): reaproveita
        // a conexão existente, como o client.js faz.
        var peer = peers[from]?.takeIf { it.hasRemoteDescription }
        if (peer != null && runCatching { peer!!.pc.awaitSetRemote(offer) }.isFailure) peer = null
        if (peer == null) {
            peer = createPeer(from)
            try {
                peer.pc.awaitSetRemote(offer)
            } catch (e: Exception) {
                setStatus("Oferta incompatível.")
                removePeer(from, announce = false)
                return
            }
        }
        peer.onRemoteDescriptionSet()
        configureTransceivers(peer)
        learnName(peer, offerJson)

        val answer = peer.pc.awaitCreate(offer = false)
        val sdp = Sdp.tuneOpus(answer.description)
        peer.pc.awaitSetLocal(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        applySenderParameters(peer)
        emit("answer", JSONObject().put("to", from).put("answer", description("answer", sdp)))
    }

    private suspend fun onAnswer(data: JSONObject) {
        val from = data.getString("from")
        val peer = peers[from] ?: return
        val answerJson = data.getJSONObject("answer")
        learnName(peer, answerJson)
        try {
            peer.pc.awaitSetRemote(
                SessionDescription(SessionDescription.Type.ANSWER, Sdp.tuneOpus(answerJson.getString("sdp"), bitrate)),
            )
            peer.onRemoteDescriptionSet()
            applySenderParameters(peer)
        } catch (e: Exception) {
            setStatus("Resposta incompatível.")
        }
    }

    private fun onIceCandidate(data: JSONObject) {
        val from = data.optString("from").ifEmpty { return }
        val c = data.optJSONObject("candidate") ?: return
        val sdp = c.optString("candidate")
        if (sdp.isEmpty()) return // fim dos candidatos
        val candidate = IceCandidate(
            if (c.isNull("sdpMid")) "" else c.optString("sdpMid"),
            c.optInt("sdpMLineIndex", 0),
            sdp,
        )
        // Candidatos podem chegar antes da oferta: guarda até o participante existir.
        val peer = peers[from]
        if (peer != null) peer.addRemoteCandidate(candidate)
        else earlyCandidates.getOrPut(from) { mutableListOf() } += candidate
    }

    /**
     * O nome vai como campo extra ("name") no objeto da oferta/resposta: o servidor repassa sem
     * alterar e o navegador ignora. Clientes web não mandam; renegociação sem nome mantém o atual.
     */
    private fun description(type: String, sdp: String) =
        JSONObject().put("type", type).put("sdp", sdp).put("name", displayName)

    private fun learnName(peer: PeerSession, description: JSONObject) {
        if (!description.has("name") || description.isNull("name")) return
        val name = Participant.cleanName(description.optString("name")) ?: return
        if (name == peer.displayName) return
        peer.displayName = name
        notifyParticipants()
    }

    private fun emit(event: String, payload: JSONObject) {
        socket?.emit(event, payload)
    }

    // ---------- Conexões P2P ----------

    private fun createPeer(id: String): PeerSession {
        peers.remove(id)?.let { old -> detachRecorder(old); old.dispose() }
        val early = earlyCandidates.remove(id)

        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val observer = PeerObserver(id)
        val pc = factory.createPeerConnection(config, observer) ?: error("Falha ao criar a conexão")
        val peer = PeerSession(id, pc)
        observer.peer = peer
        early?.forEach(peer::addRemoteCandidate)
        peers[id] = peer
        return peer
    }

    /**
     * Na resposta, a primeira trilha de áudio envia o microfone; as extras (áudio do PC de um
     * participante web) só recebem. A trilha de vídeo (só existe se o outro lado ofereceu,
     * ou seja, é desktop ou Android) fica pronta para a tela.
     */
    private fun configureTransceivers(peer: PeerSession) {
        var audioAssigned = false
        for (t in peer.pc.transceivers) {
            if (t.isStopped) continue
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> if (!audioAssigned) {
                    audioAssigned = true
                    if (t.sender.track() == null) t.sender.setTrack(localAudio, false)
                    t.sender.setStreams(listOf(STREAM_ID))
                    t.setDirection(RtpTransceiverDirection.SEND_RECV)
                }
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    t.sender.setStreams(listOf(STREAM_ID))
                    t.setDirection(RtpTransceiverDirection.SEND_RECV)
                    t.sender.setTrack(screenTrack, false)
                }
                else -> Unit
            }
        }
    }

    private fun attachScreen(peer: PeerSession, track: VideoTrack?) {
        runCatching {
            peer.pc.transceivers
                .filter { !it.isStopped && it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                .forEach { it.sender.setTrack(track, false) }
            applySenderParameters(peer)
        }
    }

    private fun applySenderParameters(peer: PeerSession) {
        for (t in peer.pc.transceivers) {
            if (t.isStopped) continue
            val sender = t.sender
            val params = sender.parameters
            if (params.encodings.isEmpty()) continue
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> params.encodings.forEach { it.maxBitrateBps = bitrate }
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> params.encodings.forEach {
                    it.maxBitrateBps = SCREEN_BITRATE
                    it.maxFramerate = SCREEN_FPS
                }
                else -> continue
            }
            sender.setParameters(params)
        }
    }

    /** O desktop (SIPSorcery) só decodifica VP8: ele vai primeiro na oferta. */
    private fun preferVp8(transceiver: RtpTransceiver) {
        runCatching {
            val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            transceiver.setCodecPreferences(codecs.sortedBy { if (it.name.equals("VP8", true)) 0 else 1 })
        }
    }

    private fun removePeer(id: String, announce: Boolean = true) {
        val peer = peers.remove(id) ?: return
        _screens.value = _screens.value.filterNot { it.peerId == id }
        detachRecorder(peer)
        peer.dispose()
        if (announce) notifyParticipants() else announcedLabels = announcedLabels - id
        if (!announce) _participants.value = currentParticipants()
    }

    private fun detachRecorder(peer: PeerSession) {
        val rec = recorder ?: return
        peer.remoteAudio.forEach(rec::detach)
    }

    private fun currentParticipants() =
        peers.values.map { Participant(it.id, it.displayName, it.connected) }.sortedBy { it.label.lowercase() }

    private fun notifyParticipants() {
        val list = currentParticipants()
        _participants.value = list
        val connected = list.filter { it.connected }.associate { it.id to it.label }
        val joined = connected.filterKeys { it !in announcedLabels }.values
        val left = announcedLabels.filterKeys { it !in connected }.values
        announcedLabels = connected
        val messages = joined.map { "$it entrou." } + left.map { "$it saiu." }
        if (messages.isNotEmpty()) setStatus(messages.joinToString(" "))
    }

    /** Mostra a tela de quem está enviando e a remove 3 s depois que os frames param. */
    private suspend fun watchScreens() {
        while (scope.isActive) {
            delay(500)
            val now = SystemClock.elapsedRealtime()
            val active = peers.values
                .filter { it.remoteVideo != null && now - it.lastFrameAt < SCREEN_GONE_MS }
                .mapNotNull { p -> p.remoteVideo?.let { RemoteScreen(p.id, p.displayName ?: "Participante ${p.id.take(4)}", it) } }
                .sortedBy { it.label.lowercase() }
            val before = _screens.value
            if (active.map { it.peerId to it.label } == before.map { it.peerId to it.label }) continue
            _screens.value = active
            val started = active.filter { a -> before.none { it.peerId == a.peerId } }.map { "${it.label} compartilhou a tela." }
            val stopped = before.filter { b -> active.none { it.peerId == b.peerId } && peers.containsKey(b.peerId) }
                .map { "${it.label} parou de compartilhar a tela." }
            if (started.isNotEmpty() || stopped.isNotEmpty()) setStatus((started + stopped).joinToString(" "))
        }
    }

    private fun setStatus(text: String) {
        _status.value = text
    }

    private inner class PeerObserver(private val id: String) : PeerConnection.Observer {
        @Volatile var peer: PeerSession? = null

        private fun current(): PeerSession? = peer?.takeIf { peers[id] === it }

        override fun onIceCandidate(candidate: IceCandidate) {
            val c = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            emit("ice-candidate", JSONObject().put("target", id).put("to", id).put("candidate", c))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            scope.launch {
                lock.withLock {
                    val p = current() ?: return@withLock
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            p.connected = true
                            notifyParticipants()
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            setStatus("Falha na conexão com ${p.displayName ?: "um participante"}.")
                            removePeer(id)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            p.connected = false
                            notifyParticipants()
                        }
                        else -> Unit
                    }
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track() ?: return
            scope.launch {
                lock.withLock {
                    val p = current() ?: return@withLock
                    when (track) {
                        is AudioTrack -> {
                            p.remoteAudio += track
                            recorder?.attach(track)
                        }
                        is VideoTrack -> p.setRemoteVideo(track)
                    }
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    companion object {
        private const val TAG = "CallManager"
        private const val STREAM_ID = "p2pcall"
        private const val SCREEN_MAX_SIZE = 1600
        private const val SCREEN_FPS = 12
        private const val SCREEN_BITRATE = 2_500_000
        private const val SCREEN_GONE_MS = 3000L

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )

        @Volatile private var instance: CallManager? = null

        fun get(context: Context): CallManager =
            instance ?: synchronized(this) {
                instance ?: CallManager(context.applicationContext).also { instance = it }
            }
    }
}

// ---------- SdpObserver como funções suspensas ----------

private const val SDP_TIMEOUT_MS = 10_000L

private suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription = withTimeout(SDP_TIMEOUT_MS) {
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        }
        if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
    }
}

private suspend fun PeerConnection.awaitSet(local: Boolean, description: SessionDescription) = withTimeout(SDP_TIMEOUT_MS) {
    suspendCancellableCoroutine<Unit> { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
        }
        if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
    }
}

private suspend fun PeerConnection.awaitSetLocal(description: SessionDescription) = awaitSet(true, description)
private suspend fun PeerConnection.awaitSetRemote(description: SessionDescription) = awaitSet(false, description)
