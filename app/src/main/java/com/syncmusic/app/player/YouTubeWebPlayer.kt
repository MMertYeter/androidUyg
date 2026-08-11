package com.syncmusic.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.syncmusic.app.model.SourceMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays YouTube content via the official IFrame Player API inside a WebView.
 * This is the ToS-compliant way to embed YouTube playback in a third-party app -
 * there is no official API for extracting/downloading raw audio, and doing so
 * would violate YouTube's Terms of Service, so we control the real YouTube
 * player embedded on the page instead.
 *
 * NOTE: because this is a WebView, reliable playback needs the view to be
 * attached and the app in the foreground. If the app is backgrounded for a
 * long time, playback may be paused by the OS/WebView - this is a platform
 * limitation, not a bug in this code.
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeWebPlayer(context: Context) : PlaybackController {

    val webView: WebView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        addJavascriptInterface(JsBridge(), "Android")
        loadUrl("file:///android_asset/youtube_player.html")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady

    override var onEnded: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    @Volatile
    private var lastKnownPositionMs: Long = 0

    override val currentPositionMs: Long
        get() = lastKnownPositionMs

    private var pendingLoad: Pair<String, Long>? = null

    inner class JsBridge {
        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                _isReady.value = true
                pendingLoad?.let { (videoId, startMs) ->
                    evalJs("loadVideo('$videoId', ${startMs / 1000.0})")
                    pendingLoad = null
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            // YT.PlayerState: ENDED=0, PLAYING=1, PAUSED=2, BUFFERING=3, CUED=5
            if (state == 0) mainHandler.post { onEnded?.invoke() }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            mainHandler.post { onError?.invoke("YouTube player error code: $code") }
        }

        @JavascriptInterface
        fun onTimeUpdate(seconds: Double) {
            lastKnownPositionMs = (seconds * 1000).toLong()
        }
    }

    private fun evalJs(script: String) {
        mainHandler.post { webView.evaluateJavascript(script, null) }
    }

    override fun load(meta: SourceMeta, startPositionMs: Long) {
        val yt = meta as? SourceMeta.YouTube ?: return
        if (_isReady.value) {
            evalJs("loadVideo('${yt.videoId}', ${startPositionMs / 1000.0})")
        } else {
            pendingLoad = yt.videoId to startPositionMs
        }
    }

    override fun play() = evalJs("playVideo()")
    override fun pause() = evalJs("pauseVideo()")
    override fun seekTo(positionMs: Long) {
        lastKnownPositionMs = positionMs
        evalJs("seekTo(${positionMs / 1000.0})")
    }

    override fun release() {
        mainHandler.post {
            webView.stopLoading()
            webView.destroy()
        }
    }
}
