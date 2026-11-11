package io.github.ericoamico.p2pcall

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.ericoamico.p2pcall.call.CallManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Mantém a chamada ativa com o app em segundo plano ou a tela apagada (equivalente a manter o PC
 * acordado no desktop) e é exigido pelo Android para o microfone e a captura de tela.
 */
class CallService : Service() {
    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null
    private var withProjection = false
    private lateinit var call: CallManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        call = CallManager.get(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "P2PCall:chamada")
            .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }

        scope.launch {
            call.inCall.collect { inCall ->
                if (!inCall) {
                    ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        scope.launch {
            combine(call.sharing, call.muted) { sharing, muted -> sharing to muted }.collect { (sharing, _) ->
                if (!sharing && withProjection) promote(projection = false) else if (call.inCall.value) promote(withProjection)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHARE -> {
                val data = intent.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
                if (data != null && promote(projection = true)) call.startScreenShare(data)
            }
            ACTION_LEAVE -> call.leave()
            else -> promote(projection = withProjection)
        }
        return START_NOT_STICKY
    }

    private fun promote(projection: Boolean): Boolean {
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (projection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            withProjection = projection
            true
        } catch (e: Exception) {
            Log.e("CallService", "startForeground falhou", e)
            if (projection) call.announce("Não foi possível compartilhar a tela.")
            false
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val leave = PendingIntent.getService(
            this, 1, Intent(this, CallService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildString {
            append("Sala ").append(call.room)
            if (call.muted.value) append(" · microfone silenciado")
            if (call.sharing.value) append(" · tela compartilhada")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Em chamada")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, "Sair", leave)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "chamada"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_SHARE = "compartilhar"
        private const val ACTION_LEAVE = "sair"
        private const val EXTRA_DATA = "dados"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))

        /** Passa a permissão de captura ao serviço, que precisa virar mediaProjection antes de usá-la. */
        fun shareScreen(context: Context, permissionData: Intent) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).setAction(ACTION_SHARE).putExtra(EXTRA_DATA, permissionData),
            )
    }
}
