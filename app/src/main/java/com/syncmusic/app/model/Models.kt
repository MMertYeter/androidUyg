package com.syncmusic.app.model

/** Which platform is currently providing audio for the room. */
enum class SourceType {
    LOCAL, YOUTUBE, SPOTIFY;

    fun wireValue(): String = name.lowercase()

    companion object {
        fun fromWire(value: String?): SourceType? = when (value) {
            "local" -> LOCAL
            "youtube" -> YOUTUBE
            "spotify" -> SPOTIFY
            else -> null
        }
    }
}

data class Member(
    val clientId: String,
    val name: String,
    val isHost: Boolean,
    val connected: Boolean,
)

/** Metadata describing what's loaded, independent of playback position. */
sealed class SourceMeta {
    data class Local(val fileUrl: String, val displayName: String) : SourceMeta()
    data class YouTube(val videoId: String, val title: String? = null) : SourceMeta()
    data class Spotify(val uri: String, val title: String? = null, val artist: String? = null) : SourceMeta()
}

data class PlaybackState(
    val source: SourceType?,
    val sourceMeta: SourceMeta?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val serverTimeMs: Long,
)

data class RoomState(
    val code: String,
    val hostClientId: String,
    val members: List<Member>,
    val playback: PlaybackState,
) {
    val amIHost: (String) -> Boolean get() = { clientId -> hostClientId == clientId }
}

data class RoomError(val message: String)
