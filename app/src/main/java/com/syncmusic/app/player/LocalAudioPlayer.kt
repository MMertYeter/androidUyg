package com.syncmusic.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.syncmusic.app.model.SourceMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays audio files streamed from our own backend (HTTP Range requests let
 * ExoPlayer start playback well before the whole file has been transferred).
 */
class LocalAudioPlayer(context: Context) : PlaybackController {

    private val player = ExoPlayer.Builder(context.applicationContext).build()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady

    override var onEnded: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override val currentPositionMs: Long
        get() = player.currentPosition

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> _isReady.value = true
                    Player.STATE_ENDED -> onEnded?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error.message ?: "Yerel oynatıcı hatası")
            }
        })
    }

    /** [meta.fileUrl] must already be an ABSOLUTE url (serverBaseUrl + the relative path). */
    override fun load(meta: SourceMeta, startPositionMs: Long) {
        val local = meta as? SourceMeta.Local ?: return
        _isReady.value = false
        val item = MediaItem.fromUri(local.fileUrl)
        player.setMediaItem(item, startPositionMs)
        player.prepare()
    }

    override fun play() {
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun release() {
        player.release()
    }
}
