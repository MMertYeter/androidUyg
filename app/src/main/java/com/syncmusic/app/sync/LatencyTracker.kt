package com.syncmusic.app.sync

import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Continuously estimates:
 *  - [offsetMs]: how far the SERVER's clock is ahead of this device's clock
 *  - [rttMs]: round-trip time to the server
 *
 * Both are needed to translate a playback snapshot (position + serverTime) that
 * arrived a little while ago into "where playback should be RIGHT NOW" on this
 * specific device, compensating for however much network latency it has.
 */
class LatencyTracker(private val socket: Socket) {

    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs

    private val samples = ArrayDeque<Long>(SAMPLE_WINDOW)
    private var job: Job? = null

    init {
        socket.on("sync_pong") { args ->
            val obj = args.getOrNull(0) as? JSONObject ?: return@on
            val clientSentAt = obj.optLong("clientSentAt")
            val serverTime = obj.optLong("serverTime")
            val receivedAt = System.currentTimeMillis()
            val rtt = receivedAt - clientSentAt
            if (rtt < 0 || rtt > MAX_ACCEPTABLE_RTT_MS) return@on // discard outliers (e.g. app was backgrounded mid-ping)

            val estimatedOffset = serverTime - (clientSentAt + rtt / 2)
            samples.addLast(estimatedOffset)
            if (samples.size > SAMPLE_WINDOW) samples.removeFirst()
            _offsetMs.value = samples.sorted().let { it[it.size / 2] } // median, robust to jitter spikes
            _rttMs.value = rtt
        }
    }

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            while (true) {
                if (socket.connected()) {
                    socket.emit("sync_ping", System.currentTimeMillis())
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val SAMPLE_WINDOW = 6
        private const val PING_INTERVAL_MS = 4000L
        private const val MAX_ACCEPTABLE_RTT_MS = 5000L
    }
}

private fun Array<Any>.getOrNull(index: Int): Any? = if (index in indices) this[index] else null
