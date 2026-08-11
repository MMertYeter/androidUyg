package com.syncmusic.app.sync

import com.syncmusic.app.model.PlaybackState
import com.syncmusic.app.model.SourceType
import com.syncmusic.app.network.SocketManager
import com.syncmusic.app.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Reconciles the authoritative playback state broadcast by the server with
 * whatever PlaybackController is currently active on this device, compensating
 * for network latency using [LatencyTracker]'s clock-offset estimate.
 *
 * Guests never call play/pause/seek on the controller directly in response to
 * user taps (only the host's taps do that, via [sendHostAction]) - guests are
 * purely driven by [applyRemoteState].
 */
class SyncEngine(
    private val socketManager: SocketManager,
    private val roomCodeProvider: () -> String?,
    private val clientIdProvider: () -> String,
) {
    private var activeController: PlaybackController? = null
    private var activeSourceType: SourceType? = null
    private var driftCorrectionJob: Job? = null
    private var lastAppliedState: PlaybackState? = null

    /** Swap in the controller responsible for the room's current source. The old one is NOT
     * released here - callers own each controller's lifecycle since some (Local) may need
     * to keep running (e.g. hosted in a foreground service) even when not "active". */
    fun setActiveController(type: SourceType, controller: PlaybackController) {
        activeController = controller
        activeSourceType = type
        lastAppliedState?.let { applyRemoteState(it, force = true) }
    }

    fun clearActiveController() {
        activeController = null
        activeSourceType = null
    }

    fun currentPositionMs(): Long = activeController?.currentPositionMs ?: 0L

    fun isControllerReady(): Boolean = activeController?.isReady?.value ?: false

    /**
     * Starts the periodic drift-correction loop only. Incoming server events are NOT
     * auto-collected here on purpose - the caller (RoomViewModel) is the single
     * subscriber of [SocketManager.playbackUpdates] so it can prepare/swap the correct
     * PlaybackController for a new source BEFORE handing the state to [onRemoteState].
     * Collecting independently in both places would race: this engine might try to
     * apply a "youtube" state to a still-active "local" controller.
     */
    fun start(scope: CoroutineScope) {
        driftCorrectionJob = scope.launch {
            while (true) {
                delay(DRIFT_CHECK_INTERVAL_MS)
                lastAppliedState?.let { if (it.isPlaying) applyRemoteState(it) }
            }
        }
    }

    fun stop() {
        driftCorrectionJob?.cancel()
    }

    /** Call this for every playback_state event / initial room snapshot, AFTER
     * ensuring the right controller is attached via [setActiveController]. */
    fun onRemoteState(state: PlaybackState) {
        lastAppliedState = state
        applyRemoteState(state)
    }

    private fun applyRemoteState(state: PlaybackState, force: Boolean = false) {
        val controller = activeController ?: return
        if (state.source != null && state.source != activeSourceType) return // waiting for the right controller to be attached

        val offset = socketManager.latencyTracker?.offsetMs?.value ?: 0L
        val estimatedServerNow = System.currentTimeMillis() + offset
        val elapsedSincePush = if (state.isPlaying) (estimatedServerNow - state.serverTimeMs).coerceAtLeast(0) else 0
        val targetPositionMs = state.positionMs + elapsedSincePush

        if (state.isPlaying) controller.play() else controller.pause()

        val drift = abs(controller.currentPositionMs - targetPositionMs)
        if (force || drift > DRIFT_TOLERANCE_MS) {
            controller.seekTo(targetPositionMs)
        }
    }

    // ---- Called from the HOST's UI when the user interacts with playback controls ----

    fun hostPlay(positionMs: Long) = sendControl("play", positionMs)
    fun hostPause(positionMs: Long) = sendControl("pause", positionMs)
    fun hostSeek(positionMs: Long) = sendControl("seek", positionMs)

    fun hostTrackEnded() {
        val roomCode = roomCodeProvider() ?: return
        socketManager.sendPlaybackEnded(roomCode, clientIdProvider())
    }

    private fun sendControl(action: String, positionMs: Long) {
        val roomCode = roomCodeProvider() ?: return
        socketManager.sendPlaybackControl(roomCode, clientIdProvider(), action, positionMs)
    }

    companion object {
        private const val DRIFT_TOLERANCE_MS = 350L
        private const val DRIFT_CHECK_INTERVAL_MS = 2500L
    }
}
