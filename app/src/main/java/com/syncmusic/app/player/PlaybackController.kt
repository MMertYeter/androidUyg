package com.syncmusic.app.player

import com.syncmusic.app.model.SourceMeta
import kotlinx.coroutines.flow.StateFlow

/**
 * Common contract implemented by every playback backend (local/streamed file,
 * YouTube WebView, Spotify App Remote). The SyncEngine only ever talks to this
 * interface, so it doesn't need to know or care which platform is currently
 * playing - it just issues play/pause/seekTo and reads currentPositionMs.
 */
interface PlaybackController {

    /** True once media is loaded and can accept play/pause/seek commands. */
    val isReady: StateFlow<Boolean>

    /** Best-effort current playback position in milliseconds. */
    val currentPositionMs: Long

    /** Fired when the underlying player naturally reaches the end of the track. */
    var onEnded: (() -> Unit)?

    /** Fired on unrecoverable playback errors (bad URL, DRM issue, app not installed, etc). */
    var onError: ((String) -> Unit)?

    fun load(meta: SourceMeta, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}
