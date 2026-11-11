package io.github.ericoamico.p2pcall

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ericoamico.p2pcall.call.Participant
import io.github.ericoamico.p2pcall.call.RoomLink
import io.github.ericoamico.p2pcall.update.ReleaseInfo
import io.github.ericoamico.p2pcall.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class MicOption(val id: Int?, val label: String)
data class QualityOption(val bitrate: Int, val label: String)

val QUALITIES = listOf(
    QualityOption(32_000, "32 kbps · economia"),
    QualityOption(64_000, "64 kbps · voz"),
    QualityOption(128_000, "128 kbps · alta"),
    QualityOption(256_000, "256 kbps · estúdio"),
    QualityOption(510_000, "510 kbps · máxima"),
)

const val DEFAULT_SERVER = "https://voz.tempsharing.com.br"

/** Campos do formulário e estado da atualização (sobrevivem à rotação da tela). */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("p2pcall", Context.MODE_PRIVATE)
    private val audioManager = app.getSystemService(AudioManager::class.java)

    var server by mutableStateOf(prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER)
    var room by mutableStateOf("")
        private set
    var name by mutableStateOf(prefs.getString("name", "") ?: "")
        private set
    var bitrate by mutableIntStateOf(prefs.getInt("bitrate", 128_000))
    var micId by mutableStateOf<Int?>(null)
    var mics by mutableStateOf(listOf(MicOption(null, "Padrão do aparelho")))
        private set

    var serverError by mutableStateOf<String?>(null)
    var roomError by mutableStateOf<String?>(null)

    var update by mutableStateOf<ReleaseInfo?>(null)
        private set
    var downloadProgress by mutableStateOf<Float?>(null)
        private set
    var updateMessage by mutableStateOf<String?>(null)
    private var downloadJob: Job? = null

    init {
        refreshMics()
        viewModelScope.launch {
            update = runCatching { Updater.check() }.getOrNull() // sem internet: tenta na próxima vez
        }
    }

    /** Colar um link de sala deixa só o nome; se for de outro servidor, ajusta o servidor. */
    fun onRoomChange(text: String) {
        roomError = null
        val link = RoomLink.parse(text)
        if (link == null) {
            room = text
            return
        }
        room = link.second
        if (!RoomLink.sameServer(server, link.first)) server = link.first
    }

    fun onNameChange(text: String) {
        name = text.take(Participant.MAX_NAME_LENGTH)
    }

    fun openLink(link: String) {
        RoomLink.parse(link)?.let { (s, r) -> server = s; room = r; roomError = null }
    }

    /** Valida e grava as preferências. Retorna o campo com erro, ou null se está tudo certo. */
    fun validate(): Field? {
        val s = server.trim()
        serverError = if (s.startsWith("http://") || s.startsWith("https://")) null else "Use http:// ou https://"
        roomError = if (room.isBlank()) "Informe a sala" else null
        if (serverError != null) return Field.Server
        if (roomError != null) return Field.Room
        room = room.trim()
        prefs.edit().putString("server", s).putString("name", name).putInt("bitrate", bitrate).apply()
        return null
    }

    fun selectedMic(): AudioDeviceInfo? =
        micId?.let { id -> audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id } }

    fun refreshMics() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in MIC_TYPES }
        val counts = mutableMapOf<String, Int>()
        val options = devices.map { d ->
            val base = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone do aparelho"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
                else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Microfone USB"
            }
            val n = (counts[base] ?: 0) + 1
            counts[base] = n
            MicOption(d.id, if (n > 1) "$base $n" else base)
        }
        mics = listOf(MicOption(null, "Padrão do aparelho")) + options
        if (mics.none { it.id == micId }) micId = null
    }

    fun startUpdate(context: Context, onReady: () -> Unit) {
        val release = update ?: return
        if (downloadJob != null) return
        downloadJob = viewModelScope.launch {
            var lastQuarter = 0
            downloadProgress = 0f
            updateMessage = "Baixando versão ${release.version}…"
            try {
                val apk = Updater.download(context.applicationContext, release) { p ->
                    downloadProgress = p
                    // Anuncia a cada 25% para não inundar o leitor de tela.
                    val quarter = (p * 4).toInt()
                    if (quarter in (lastQuarter + 1)..3) {
                        lastQuarter = quarter
                        updateMessage = "Baixando: ${quarter * 25}%"
                    }
                }
                updateMessage = "Instalando…"
                onReady()
                Updater.install(context.applicationContext, apk)
            } catch (e: CancellationException) {
                updateMessage = "Download cancelado."
            } catch (e: Exception) {
                updateMessage = "Falha na atualização."
            } finally {
                downloadProgress = null
                downloadJob = null
            }
        }
    }

    fun cancelUpdate() {
        downloadJob?.cancel()
    }

    enum class Field { Server, Room }

    private companion object {
        val MIC_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
