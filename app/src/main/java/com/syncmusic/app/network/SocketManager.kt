package com.syncmusic.app.network

import com.syncmusic.app.model.Member
import com.syncmusic.app.model.PlaybackState
import com.syncmusic.app.model.RoomState
import com.syncmusic.app.model.SourceMeta
import com.syncmusic.app.model.SourceType
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Owns the single Socket.IO connection used for the whole app session.
 * Emits room / playback / connection state as Kotlin StateFlows that the UI
 * (and the foreground service) observe.
 */
class SocketManager {

    private var socket: Socket? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _room = MutableStateFlow<RoomState?>(null)
    val room: StateFlow<RoomState?> = _room

    /** Emits every time the server pushes a fresh playback_state (play/pause/seek/source change). */
    val playbackUpdates = MutableSharedFlow<PlaybackState>(replay = 0, extraBufferCapacity = 8)

    val roomClosed = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val errors = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)

    var latencyTracker: com.syncmusic.app.sync.LatencyTracker? = null
        private set

    fun connect(serverUrl: String) {
        if (socket != null) return
        val opts = IO.Options().apply {
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1000
            reconnectionDelayMax = 8000
            timeout = 10000
            transports = arrayOf("websocket", "polling")
        }
        val s = IO.socket(serverUrl, opts)
        socket = s
        latencyTracker = com.syncmusic.app.sync.LatencyTracker(s)

        s.on(Socket.EVENT_CONNECT) { _connectionStatus.value = ConnectionStatus.CONNECTED }
        s.on(Socket.EVENT_DISCONNECT) { _connectionStatus.value = ConnectionStatus.DISCONNECTED }
        s.on(Socket.EVENT_CONNECT_ERROR) { _connectionStatus.value = ConnectionStatus.CONNECTING }

        s.on("member_update") { args ->
            val array = args.getOrNull(0) as? org.json.JSONArray ?: return@on
            val members = parseMembers(array)
            _room.value = _room.value?.copy(members = members)
        }

        s.on("playback_state") { args ->
            val obj = args.getOrNull(0) as? JSONObject ?: return@on
            val state = parsePlaybackState(obj)
            _room.value = _room.value?.copy(playback = state)
            playbackUpdates.tryEmit(state)
        }

        s.on("room_closed") {
            roomClosed.tryEmit(Unit)
            _room.value = null
        }

        _connectionStatus.value = ConnectionStatus.CONNECTING
        s.connect()
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        latencyTracker?.stop()
        socket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun beginLatencyTracking(scope: kotlinx.coroutines.CoroutineScope) {
        latencyTracker?.start(scope)
    }

    // ---- Request/response style calls (server uses an ack callback) ----

    suspend fun createRoom(clientId: String, name: String): Result<RoomState> = emitWithAck(
        "create_room",
        JSONObject().put("clientId", clientId).put("name", name),
    )

    suspend fun joinRoom(roomCode: String, clientId: String, name: String): Result<RoomState> = emitWithAck(
        "join_room",
        JSONObject().put("roomCode", roomCode).put("clientId", clientId).put("name", name),
    )

    suspend fun closeRoom(roomCode: String, clientId: String): Result<Unit> {
        val s = socket ?: return Result.failure(IllegalStateException("not connected"))
        return suspendCancellableCoroutine { cont ->
            s.emit(
                "close_room",
                JSONObject().put("roomCode", roomCode).put("clientId", clientId),
            ) { args ->
                val resp = args.getOrNull(0) as? JSONObject
                if (resp?.optBoolean("ok") == true) {
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(Exception(resp?.optString("error") ?: "unknown error")))
                }
            }
        }
    }

    fun leaveRoom(roomCode: String, clientId: String) {
        socket?.emit("leave_room", JSONObject().put("roomCode", roomCode).put("clientId", clientId))
    }

    fun setSource(roomCode: String, clientId: String, source: SourceType, sourceMeta: JSONObject) {
        socket?.emit(
            "set_source",
            JSONObject()
                .put("roomCode", roomCode)
                .put("clientId", clientId)
                .put("source", source.wireValue())
                .put("sourceMeta", sourceMeta)
                .put("positionMs", 0),
        )
    }

    fun sendPlaybackControl(roomCode: String, clientId: String, action: String, positionMs: Long) {
        socket?.emit(
            "playback_control",
            JSONObject()
                .put("roomCode", roomCode)
                .put("clientId", clientId)
                .put("action", action)
                .put("positionMs", positionMs),
        )
    }

    fun sendPlaybackEnded(roomCode: String, clientId: String) {
        socket?.emit("playback_ended", JSONObject().put("roomCode", roomCode).put("clientId", clientId))
    }

    fun setRoomState(room: RoomState) {
        _room.value = room
    }

    // ---- helpers ----

    private suspend fun emitWithAck(event: String, payload: JSONObject): Result<RoomState> {
        val s = socket ?: return Result.failure(IllegalStateException("not connected"))
        return suspendCancellableCoroutine { cont ->
            s.emit(event, payload) { args ->
                val resp = args.getOrNull(0) as? JSONObject
                if (resp?.optBoolean("ok") == true) {
                    val roomJson = resp.optJSONObject("room")
                    val roomState = roomJson?.let { parseRoom(it) }
                    if (roomState != null) {
                        _room.value = roomState
                        cont.resume(Result.success(roomState))
                    } else {
                        cont.resume(Result.failure(Exception("Malformed room response")))
                    }
                } else {
                    cont.resume(Result.failure(Exception(resp?.optString("error") ?: "unknown error")))
                }
            }
        }
    }

    private fun parseRoom(obj: JSONObject): RoomState {
        return RoomState(
            code = obj.optString("code"),
            hostClientId = obj.optString("hostClientId"),
            members = parseMembers(obj.optJSONArray("members") ?: org.json.JSONArray()),
            playback = parsePlaybackState(obj.optJSONObject("playback") ?: JSONObject()),
        )
    }

    private fun parseMembers(array: org.json.JSONArray): List<Member> {
        val list = mutableListOf<Member>()
        for (i in 0 until array.length()) {
            val m = array.optJSONObject(i) ?: continue
            list.add(
                Member(
                    clientId = m.optString("clientId"),
                    name = m.optString("name"),
                    isHost = m.optBoolean("isHost"),
                    connected = m.optBoolean("connected"),
                ),
            )
        }
        return list
    }

    private fun parsePlaybackState(obj: JSONObject): PlaybackState {
        val sourceType = SourceType.fromWire(obj.optString("source", null))
        val metaObj = obj.optJSONObject("sourceMeta")
        val meta: SourceMeta? = when (sourceType) {
            SourceType.LOCAL -> metaObj?.let {
                SourceMeta.Local(fileUrl = it.optString("fileUrl"), displayName = it.optString("filename"))
            }
            SourceType.YOUTUBE -> metaObj?.let {
                SourceMeta.YouTube(videoId = it.optString("videoId"), title = it.optString("title", null))
            }
            SourceType.SPOTIFY -> metaObj?.let {
                SourceMeta.Spotify(
                    uri = it.optString("uri"),
                    title = it.optString("title", null),
                    artist = it.optString("artist", null),
                )
            }
            null -> null
        }
        return PlaybackState(
            source = sourceType,
            sourceMeta = meta,
            isPlaying = obj.optBoolean("isPlaying"),
            positionMs = obj.optLong("positionMs"),
            serverTimeMs = obj.optLong("serverTime"),
        )
    }

    private fun Array<Any>.getOrNull(index: Int): Any? = if (index in indices) this[index] else null
}
