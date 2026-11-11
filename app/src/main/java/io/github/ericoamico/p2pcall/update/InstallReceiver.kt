package io.github.ericoamico.p2pcall.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Resultado da sessão de instalação: abre a confirmação do Android ou informa a falha. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // o app é reiniciado pelo sistema
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.installMessage.value = "Atualização cancelada."
            else -> Updater.installMessage.value = "Falha ao instalar a atualização."
        }
    }
}
