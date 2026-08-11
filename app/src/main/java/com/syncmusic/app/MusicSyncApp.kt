package com.syncmusic.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MusicSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Oda Bağlantısı",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Odaya bağlıyken gösterilen kalıcı bildirim"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "sync_listen_connection"
    }
}
