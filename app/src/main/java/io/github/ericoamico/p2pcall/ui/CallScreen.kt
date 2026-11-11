package io.github.ericoamico.p2pcall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ericoamico.p2pcall.MainViewModel
import io.github.ericoamico.p2pcall.QUALITIES
import io.github.ericoamico.p2pcall.call.CallManager
import io.github.ericoamico.p2pcall.call.RemoteScreen
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Ações que precisam da Activity (permissões, captura de tela, área de transferência). */
interface CallActions {
    fun join()
    fun leave()
    fun setScreenShare(on: Boolean)
    fun copyLink()
    fun update()
}

@Composable
fun CallScreen(vm: MainViewModel, call: CallManager, actions: CallActions) {
    val inCall by call.inCall.collectAsStateWithLifecycle()
    val status by call.status.collectAsStateWithLifecycle()
    val muted by call.muted.collectAsStateWithLifecycle()
    val sharing by call.sharing.collectAsStateWithLifecycle()
    val recording by call.recording.collectAsStateWithLifecycle()
    val participants by call.participants.collectAsStateWithLifecycle()
    val screens by call.screens.collectAsStateWithLifecycle()

    val joinFocus = remember { FocusRequester() }
    val muteFocus = remember { FocusRequester() }
    val roomFocus = remember { FocusRequester() }
    val serverFocus = remember { FocusRequester() }
    var expanded by remember { mutableStateOf<RemoteScreen?>(null) }

    // O foco nunca se perde: ao entrar vai para "Silenciar microfone"; ao sair, para "Entrar".
    var previousInCall by rememberSaveable { mutableStateOf(inCall) }
    LaunchedEffect(inCall) {
        if (inCall == previousInCall) return@LaunchedEffect
        previousInCall = inCall
        delay(150)
        runCatching { (if (inCall) muteFocus else joinFocus).requestFocus() }
    }

    fun tryJoin() {
        when (vm.validate()) {
            MainViewModel.Field.Server -> runCatching { serverFocus.requestFocus() }
            MainViewModel.Field.Room -> runCatching { roomFocus.requestFocus() }
            null -> actions.join()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            // Ctrl+M silencia/ativa o microfone com teclado físico.
            .onPreviewKeyEvent {
                if (inCall && it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.M) {
                    call.setMuted(!muted)
                    true
                } else {
                    false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "P2P Call",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )

        UpdateBanner(vm, actions)

        // Região "ao vivo": o TalkBack lê as mudanças sem mover o foco.
        Text(
            status.ifEmpty { if (inCall) "" else "Pronto para entrar." },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )

        if (!inCall) {
            val serverError = vm.serverError
            val roomError = vm.roomError
            OutlinedTextField(
                value = vm.server,
                onValueChange = { vm.server = it; vm.serverError = null },
                label = { Text("Servidor") },
                singleLine = true,
                isError = vm.serverError != null,
                supportingText = if (serverError != null) { { Text(serverError) } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().focusRequester(serverFocus),
            )
            OutlinedTextField(
                value = vm.room,
                onValueChange = vm::onRoomChange,
                label = { Text("Sala") },
                placeholder = { Text("Nome ou link") },
                singleLine = true,
                isError = vm.roomError != null,
                supportingText = if (roomError != null) { { Text(roomError) } } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().focusRequester(roomFocus),
            )
            OutlinedTextField(
                value = vm.name,
                onValueChange = vm::onNameChange,
                label = { Text("Seu nome (opcional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { tryJoin() }),
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            )
        }

        Picker(
            label = "Microfone",
            options = vm.mics,
            selected = vm.mics.firstOrNull { it.id == vm.micId } ?: vm.mics.first(),
            text = { it.label },
            onSelect = {
                vm.micId = it.id
                if (inCall) call.setMicrophone(vm.selectedMic())
            },
        )
        Picker(
            label = "Qualidade",
            options = QUALITIES,
            selected = QUALITIES.firstOrNull { it.bitrate == vm.bitrate } ?: QUALITIES[2],
            text = { it.label },
            onSelect = {
                vm.bitrate = it.bitrate
                if (inCall) call.setBitrate(it.bitrate)
            },
        )

        if (!inCall) {
            ActionButton(
                "Entrar", Icons.AutoMirrored.Filled.Login, ::tryJoin,
                modifier = Modifier.padding(top = 8.dp).focusRequester(joinFocus), primary = true,
            )
        } else {
            ToggleAction("Silenciar microfone", muted, Icons.Filled.MicOff, call::setMuted, Modifier.focusRequester(muteFocus))
            ToggleAction("Compartilhar tela", sharing, Icons.AutoMirrored.Filled.ScreenShare, actions::setScreenShare)
            ToggleAction("Gravar", recording, Icons.Filled.FiberManualRecord, call::setRecording)
            ActionButton("Copiar link", Icons.Filled.ContentCopy, actions::copyLink)
            ActionButton("Sair", Icons.AutoMirrored.Filled.Logout, actions::leave)

            SectionHeading("Na sala (${participants.count { it.connected } + 1})")
            ParticipantRow(if (vm.name.isBlank()) "Você" else "Você · ${vm.name.trim()}")
            participants.forEach { p ->
                ParticipantRow(if (p.connected) p.label else "${p.label} · conectando")
            }

            if (screens.isNotEmpty()) {
                SectionHeading("Telas")
                screens.forEach { screen ->
                    ScreenTile(screen, call.eglBase.eglBaseContext) { expanded = screen }
                }
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }

    // A tela ampliada fecha sozinha quando o participante para de compartilhar.
    val shown = expanded?.let { e -> screens.firstOrNull { it.peerId == e.peerId } }
    if (shown != null) FullScreen(shown, call.eglBase.eglBaseContext) { expanded = null }
}

@Composable
private fun UpdateBanner(vm: MainViewModel, actions: CallActions) {
    val release = vm.update ?: return
    var confirm by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                vm.updateMessage ?: "Versão ${release.version} disponível.",
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val progress = vm.downloadProgress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
                ActionButton("Cancelar", Icons.Filled.Close, vm::cancelUpdate)
            } else {
                ActionButton("Atualizar", Icons.Filled.SystemUpdate, { confirm = true }, primary = true)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Atualizar agora?") },
            text = { Text("O app será reiniciado. Uma chamada em andamento será encerrada.") },
            confirmButton = { TextButton({ confirm = false; actions.update() }) { Text("Atualizar") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Agora não") } },
        )
    }
}

@Composable
private fun ParticipantRow(text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ScreenTile(screen: RemoteScreen, egl: EglBase.Context, onOpen: () -> Unit) {
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clickable(onClickLabel = "ampliar", onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "Tela de ${screen.label}"
            },
    ) {
        VideoView(
            screen.track, egl,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
        )
        Text(
            screen.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FullScreen(screen: RemoteScreen, egl: EglBase.Context, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoView(
                screen.track, egl,
                Modifier.fillMaxSize().semantics { contentDescription = "Tela de ${screen.label}" },
            )
            Row(
                Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionButton("Fechar", Icons.Filled.Close, onClose, Modifier.width(160.dp), primary = true)
            }
        }
    }
}

/** Exibe uma trilha de vídeo remota (tela compartilhada). */
@Composable
private fun VideoView(track: VideoTrack, egl: EglBase.Context, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    // Declarado depois: é desfeito antes do release acima.
    DisposableEffect(track, renderer) {
        runCatching { track.addSink(renderer) }
        onDispose { runCatching { track.removeSink(renderer) } }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
