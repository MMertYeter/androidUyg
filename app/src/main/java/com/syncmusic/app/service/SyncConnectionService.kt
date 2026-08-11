package com.syncmusic.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.syncmusic.app.MainActivity
import com.syncmusic.app.MusicSyncApp
import com.syncmusic.app.model.RoomState
import com.syncmusic.app.network.ConnectionStatus
import com.syncmusic.app.network.SocketManager
import com.syncmusic.app.player.LocalAudioPlayer
import com.syncmusic.app.sync.SyncEngine
import com.syncmusic.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the room connection (and, when the active source is a local/streamed
 * file, actual audio playback) alive when the user backgrounds the app,
 * turns the screen off, or briefly loses network. This is what makes the
 * "resilient room" requirement actually hold up on a real device instead of
 * just in theory - Android aggressively kills background work unless a
 * foreground service with a visible notification is holding it open.
 */
class SyncConnectionService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var socketManager: SocketManager
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var localAudioPlayer: LocalAudioPlayer
        private set
    private lateinit var prefs: Prefs

    var currentRoomCode: String? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): SyncConnectionService = this@SyncConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        socketManager = SocketManager()
        localAudioPlayer = LocalAudioPlayer(this)
        syncEngine = SyncEngine(
            socketManager = socketManager,
            roomCodeProvider = { currentRoomCode },
            clientIdProvider = { prefs.clientId },
        )
        syncEngine.start(serviceScope)

        localAudioPlayer.onEnded = { syncEngine.hostTrackEnded() }

        serviceScope.launch {
            combine(socketManager.connectionStatus, socketManager.room) { status, room -> status to room }
                .collect { (status, room) ->
                    if (currentRoomCode != null) {
                        startForeground(NOTIFICATION_ID, buildNotification(status, room))
                    }
                }
        }
    }

    /** Ensures the socket is connected using the currently configured server URL. */
    fun ensureConnected() {
        socketManager.connect(prefs.serverUrl)
        socketManager.beginLatencyTracking(serviceScope)
    }

    fun setActiveRoom(roomCode: String?) {
        currentRoomCode = roomCode
        prefs.lastRoomCode = roomCode
        if (roomCode != null) {
            startForeground(NOTIFICATION_ID, buildNotification(socketManager.connectionStatus.value, socketManager.room.value))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        syncEngine.stop()
        socketManager.disconnect()
        localAudioPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(status: ConnectionStatus, room: RoomState?): Notification {
        val statusText = when (status) {
            ConnectionStatus.CONNECTED -> if (room != null) "Oda ${room.code} - bağlı (${room.members.count { it.connected }} kişi)" else "Bağlı"
            ConnectionStatus.CONNECTING -> "Yeniden bağlanılıyor..."
            ConnectionStatus.DISCONNECTED -> "Bağlantı kesildi, tekrar deneniyor..."
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, MusicSyncApp.CHANNEL_ID)
            .setContentTitle("SyncListen")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
