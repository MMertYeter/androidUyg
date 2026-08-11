package com.syncmusic.app.util

/** Accepts a raw YouTube video ID, a youtube.com/watch?v=... URL, or a youtu.be/... short URL. */
object YouTubeUrlParser {
    private val ID_PATTERN = Regex("^[a-zA-Z0-9_-]{11}$")

    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (ID_PATTERN.matches(trimmed)) return trimmed

        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host ?: return null

        return when {
            host.contains("youtu.be") -> uri.path.trim('/').takeIf { ID_PATTERN.matches(it) }
            host.contains("youtube.com") -> {
                val query = uri.query ?: return extractFromShortsOrEmbed(uri.path)
                query.split("&")
                    .mapNotNull { part -> part.split("=").let { if (it.size == 2) it[0] to it[1] else null } }
                    .firstOrNull { it.first == "v" }
                    ?.second
                    ?.takeIf { ID_PATTERN.matches(it) }
                    ?: extractFromShortsOrEmbed(uri.path)
            }
            else -> null
        }
    }

    private fun extractFromShortsOrEmbed(path: String?): String? {
        if (path == null) return null
        val segments = path.trim('/').split("/")
        val candidate = segments.lastOrNull() ?: return null
        return candidate.takeIf { ID_PATTERN.matches(it) }
    }
}
