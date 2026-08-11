package com.syncmusic.app.player

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.syncmusic.app.model.SourceMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Drives playback through the Spotify app installed on THIS device via the
 * official App Remote SDK. Requires:
 *  1) The Spotify app installed and the user logged in (Premium for full
 *     remote-control playback, per Spotify's own restrictions).
 *  2) A Client ID registered on the Spotify Developer Dashboard with this
 *     app's package name + signing certificate fingerprint allow-listed.
 *
 * Because each device talks to its OWN local Spotify app, "sync" here means:
 * our backend tells every device in the room "play this URI at this
 * position", and each device's Spotify app executes that independently -
 * there is no direct device-to-device audio streaming for Spotify tracks
 * (nor could there legally be one).
 */
class SpotifyPlayerController(
    private val context: Context,
    private val clientId: String,
    private val redirectUri: String,
) : PlaybackController {

    private var appRemote: SpotifyAppRemote? = null

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady

    override var onEnded: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    @Volatile
    private var lastKnownPositionMs: Long = 0
    private var lastKnownTrackUri: String? = null

    override val currentPositionMs: Long
        get() = lastKnownPositionMs

    private var pendingLoad: Pair<String, Long>? = null

    fun connect() {
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(remote: SpotifyAppRemote) {
                appRemote = remote
                _isReady.value = true
                remote.playerApi.subscribeToPlayerState().setEventCallback { state ->
                    lastKnownPositionMs = state.playbackPosition
                    val trackUri = state.track?.uri
                    if (lastKnownTrackUri != null && trackUri != lastKnownTrackUri && state.playbackPosition == 0L) {
                        // Best-effort "track changed on its own" -> treat previous as ended.
                        onEnded?.invoke()
                    }
                    lastKnownTrackUri = trackUri
                }
                pendingLoad?.let { (uri, startMs) ->
                    doLoad(uri, startMs)
                    pendingLoad = null
                }
            }

            override fun onFailure(throwable: Throwable) {
                _isReady.value = false
                onError?.invoke(throwable.message ?: "Spotify'a bağlanılamadı. Spotify uygulamasının yüklü ve giriş yapılmış olduğundan emin olun.")
            }
        })
    }

    private fun doLoad(uri: String, startMs: Long) {
        appRemote?.playerApi?.play(uri)
        if (startMs > 0) {
            // Give Spotify a brief moment to start the track before seeking into it.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                appRemote?.playerApi?.seekTo(startMs)
            }, 400)
        }
    }

    override fun load(meta: SourceMeta, startPositionMs: Long) {
        val spotify = meta as? SourceMeta.Spotify ?: return
        if (appRemote != null) {
            doLoad(spotify.uri, startPositionMs)
        } else {
            pendingLoad = spotify.uri to startPositionMs
        }
    }

    override fun play() {
        appRemote?.playerApi?.resume()
    }

    override fun pause() {
        appRemote?.playerApi?.pause()
    }

    override fun seekTo(positionMs: Long) {
        lastKnownPositionMs = positionMs
        appRemote?.playerApi?.seekTo(positionMs)
    }

    override fun release() {
        appRemote?.let { SpotifyAppRemote.disconnect(it) }
        appRemote = null
        _isReady.value = false
    }
}
