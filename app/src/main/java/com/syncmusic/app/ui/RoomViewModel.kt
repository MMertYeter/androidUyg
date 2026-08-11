package com.syncmusic.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syncmusic.app.model.PlaybackState
import com.syncmusic.app.model.RoomState
import com.syncmusic.app.model.SourceMeta
import com.syncmusic.app.model.SourceType
import com.syncmusic.app.network.ConnectionStatus
import com.syncmusic.app.network.UploadClient
import com.syncmusic.app.player.SpotifyPlayerController
import com.syncmusic.app.player.YouTubeWebPlayer
import com.syncmusic.app.service.SyncConnectionService
import com.syncmusic.app.util.Prefs
import com.syncmusic.app.util.SpotifyConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class RoomViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = Prefs(application)
    private val uploadClient = UploadClient(application.contentResolver)

    private val _service = MutableStateFlow<SyncConnectionService?>(null)
    val serviceReady: StateFlow<Boolean> = _service
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val room: StateFlow<RoomState?> = _service
        .flatMapLatest { it?.socketManager?.room ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connectionStatus: StateFlow<ConnectionStatus> = _service
        .flatMapLatest { it?.socketManager?.connectionStatus ?: flowOf(ConnectionStatus.DISCONNECTED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.DISCONNECTED)

    private var youtubeController: YouTubeWebPlayer? = null
    private var spotifyController: SpotifyPlayerController? = null
    private var preparedSourceKey: String? = null

    val uploadProgress = MutableStateFlow<Float?>(null)
    val lastError = MutableStateFlow<String?>(null)

    fun onServiceBound(service: SyncConnectionService) {
        if (_service.value === service) return
        _service.value = service
        service.ensureConnected()

        viewModelScope.launch {
            service.socketManager.playbackUpdates.collect { state -> handleRemoteState(service, state) }
        }
        viewModelScope.launch {
            service.socketManager.roomClosed.collect {
                cleanupControllers()
                service.setActiveRoom(null)
                lastError.value = "Host odayı kapattı."
            }
        }
        viewModelScope.launch {
            service.socketManager.errors.collect { msg -> lastError.value = msg }
        }
    }

    fun youtubeWebViewOrNull() = youtubeController?.webView

    fun createRoom(name: String, onResult: (Result<Unit>) -> Unit) {
        val service = _service.value ?: return onResult(Result.failure(IllegalStateException("Servise bağlı değil")))
        prefs.displayName = name
        viewModelScope.launch {
            val result = service.socketManager.createRoom(prefs.clientId, name)
            result.onSuccess { roomState ->
                service.setActiveRoom(roomState.code)
                handleInitialState(service, roomState)
            }
            onResult(result.map { })
        }
    }

    fun joinRoom(code: String, name: String, onResult: (Result<Unit>) -> Unit) {
        val service = _service.value ?: return onResult(Result.failure(IllegalStateException("Servise bağlı değil")))
        prefs.displayName = name
        viewModelScope.launch {
            val result = service.socketManager.joinRoom(code.uppercase(), prefs.clientId, name)
            result.onSuccess { roomState ->
                service.setActiveRoom(roomState.code)
                handleInitialState(service, roomState)
            }
            onResult(result.map { })
        }
    }

    private fun handleInitialState(service: SyncConnectionService, roomState: RoomState) {
        prepareControllerForSource(service, roomState.playback.source, roomState.playback.sourceMeta)
        service.syncEngine.onRemoteState(roomState.playback)
    }

    private fun handleRemoteState(service: SyncConnectionService, state: PlaybackState) {
        prepareControllerForSource(service, state.source, state.sourceMeta)
        service.syncEngine.onRemoteState(state)
    }

    private fun sourceKey(meta: SourceMeta?): String = when (meta) {
        is SourceMeta.Local -> "local:${meta.fileUrl}"
        is SourceMeta.YouTube -> "youtube:${meta.videoId}"
        is SourceMeta.Spotify -> "spotify:${meta.uri}"
        null -> "none"
    }

    private fun prepareControllerForSource(service: SyncConnectionService, source: SourceType?, meta: SourceMeta?) {
        if (source == null || meta == null) return
        val key = sourceKey(meta)
        if (key == preparedSourceKey) return
        preparedSourceKey = key

        when (source) {
            SourceType.LOCAL -> {
                service.syncEngine.setActiveController(SourceType.LOCAL, service.localAudioPlayer)
                service.localAudioPlayer.load(meta, 0)
            }
            SourceType.YOUTUBE -> {
                val controller = youtubeController ?: YouTubeWebPlayer(getApplication()).also { youtubeController = it }
                service.syncEngine.setActiveController(SourceType.YOUTUBE, controller)
                controller.load(meta, 0)
            }
            SourceType.SPOTIFY -> {
                val controller = spotifyController ?: SpotifyPlayerController(
                    getApplication(),
                    SpotifyConfig.CLIENT_ID,
                    SpotifyConfig.REDIRECT_URI,
                ).also {
                    it.onError = { msg -> lastError.value = msg }
                    it.connect()
                    spotifyController = it
                }
                service.syncEngine.setActiveController(SourceType.SPOTIFY, controller)
                controller.load(meta, 0)
            }
        }
    }

    // ---- Host actions ----

    fun hostSetYouTube(videoId: String) {
        val service = _service.value ?: return
        val roomCode = room.value?.code ?: return
        service.socketManager.setSource(roomCode, prefs.clientId, SourceType.YOUTUBE, JSONObject().put("videoId", videoId))
    }

    fun hostSetSpotify(trackUri: String) {
        val service = _service.value ?: return
        val roomCode = room.value?.code ?: return
        service.socketManager.setSource(roomCode, prefs.clientId, SourceType.SPOTIFY, JSONObject().put("uri", trackUri))
    }

    fun hostUploadLocalFile(uri: Uri, filename: String, onDone: (Result<Unit>) -> Unit) {
        val service = _service.value ?: return onDone(Result.failure(IllegalStateException("Servise bağlı değil")))
        val roomCode = room.value?.code ?: return onDone(Result.failure(IllegalStateException("Odada değilsiniz")))
        uploadProgress.value = 0f
        viewModelScope.launch {
            val result = uploadClient.uploadAudio(prefs.serverUrl, roomCode, prefs.clientId, uri, filename) { progress ->
                uploadProgress.value = progress
            }
            result.onSuccess { uploadResult ->
                val absoluteUrl = prefs.serverUrl + uploadResult.fileUrl
                val meta = JSONObject().put("fileUrl", absoluteUrl).put("filename", uploadResult.filename)
                service.socketManager.setSource(roomCode, prefs.clientId, SourceType.LOCAL, meta)
            }
            result.onFailure { lastError.value = "Yükleme başarısız: ${it.message}" }
            uploadProgress.value = null
            onDone(result.map { })
        }
    }

    fun hostTogglePlayPause() {
        val service = _service.value ?: return
        val playback = room.value?.playback ?: return
        val pos = service.syncEngine.currentPositionMs()
        if (playback.isPlaying) service.syncEngine.hostPause(pos) else service.syncEngine.hostPlay(pos)
    }

    fun hostSeekBy(deltaMs: Long) {
        val service = _service.value ?: return
        val pos = (service.syncEngine.currentPositionMs() + deltaMs).coerceAtLeast(0)
        service.syncEngine.hostSeek(pos)
    }

    fun leaveRoom() {
        val service = _service.value ?: return
        val code = room.value?.code ?: return
        service.socketManager.leaveRoom(code, prefs.clientId)
        cleanupControllers()
        service.setActiveRoom(null)
    }

    fun closeRoom(onResult: (Result<Unit>) -> Unit) {
        val service = _service.value ?: return onResult(Result.failure(IllegalStateException("Servise bağlı değil")))
        val code = room.value?.code ?: return onResult(Result.failure(IllegalStateException("Odada değilsiniz")))
        viewModelScope.launch {
            val result = service.socketManager.closeRoom(code, prefs.clientId)
            cleanupControllers()
            service.setActiveRoom(null)
            onResult(result)
        }
    }

    fun isHost(): Boolean {
        val roomState = room.value ?: return false
        return roomState.hostClientId == prefs.clientId
    }

    /** Best-effort "where is playback right now" for driving a seek bar / time label in the UI. */
    fun currentPositionMs(): Long = _service.value?.syncEngine?.currentPositionMs() ?: 0L

    private fun cleanupControllers() {
        youtubeController?.release()
        youtubeController = null
        spotifyController?.release()
        spotifyController = null
        preparedSourceKey = null
    }

    override fun onCleared() {
        cleanupControllers()
        super.onCleared()
    }
}
