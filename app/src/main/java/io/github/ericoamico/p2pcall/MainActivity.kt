package io.github.ericoamico.p2pcall

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.ericoamico.p2pcall.call.CallManager
import io.github.ericoamico.p2pcall.ui.CallActions
import io.github.ericoamico.p2pcall.ui.CallScreen
import io.github.ericoamico.p2pcall.ui.P2PTheme
import io.github.ericoamico.p2pcall.update.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), CallActions {
    private val vm: MainViewModel by viewModels()
    private lateinit var call: CallManager

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCall() else call.announce("Permita o microfone para entrar.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) CallService.shareScreen(this, data)
        else call.announce("Compartilhamento cancelado.")
    }

    // Fone ou microfone USB conectado/removido: atualiza a lista.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = vm.refreshMics()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = vm.refreshMics()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        call = CallManager.get(this)
        if (savedInstanceState == null) handleLink(intent)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, null)
        lifecycleScope.launch {
            Updater.installMessage.collect { msg ->
                if (msg != null) {
                    vm.updateMessage = msg
                    Updater.installMessage.value = null
                }
            }
        }
        setContent { P2PTheme { CallScreen(vm, call, this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    /** Link de sala aberto pelo navegador ou outro app: preenche servidor e sala. */
    private fun handleLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        if (call.inCall.value) call.announce("Saia da sala atual para entrar em outra.") else vm.openLink(link)
    }

    override fun join() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        call.join(vm.server, vm.room, vm.name, vm.bitrate, vm.selectedMic())
        CallService.start(this)
    }

    override fun leave() = call.leave()

    override fun setScreenShare(on: Boolean) {
        if (on) {
            screenCapture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else {
            call.stopScreenShare()
        }
    }

    override fun copyLink() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Link da sala", call.roomLink()))
        call.announce("Link copiado.")
    }

    override fun update() {
        if (!Updater.canInstall(this)) {
            vm.updateMessage = "Permita a instalação e toque em Atualizar."
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        val manager = call
        vm.startUpdate(this) { manager.leave() }
    }
}
