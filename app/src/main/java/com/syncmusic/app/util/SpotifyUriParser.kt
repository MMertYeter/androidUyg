package com.syncmusic.app.util

/** Accepts a raw "spotify:track:ID" URI or an "https://open.spotify.com/track/ID" link. */
object SpotifyUriParser {
    fun extractTrackUri(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWith("spotify:track:")) return trimmed

        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (uri.host?.contains("open.spotify.com") != true) return null
        val segments = uri.path.trim('/').split("/")
        val idx = segments.indexOf("track")
        if (idx == -1 || idx + 1 >= segments.size) return null
        val id = segments[idx + 1].substringBefore("?")
        return "spotify:track:$id"
    }
}
