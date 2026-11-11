package io.github.ericoamico.p2pcall

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class P2PApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CallService.CHANNEL_ID, "Chamada", NotificationManager.IMPORTANCE_LOW),
        )
    }
}
