package com.syncmusic.app.util

import android.content.Context
import java.util.UUID

/**
 * Thin wrapper over SharedPreferences.
 *
 * The single most important value here is [clientId]: a UUID generated ONCE per
 * install and never changed again. The backend uses this (not the ephemeral
 * socket.id) to recognize "this is the same person reconnecting" after a network
 * drop, app background, or process death - which is what makes seamless
 * reconnection possible at all.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("sync_listen_prefs", Context.MODE_PRIVATE)

    val clientId: String
        get() {
            var id = sp.getString(KEY_CLIENT_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sp.edit().putString(KEY_CLIENT_ID, id).apply()
            }
            return id
        }

    var displayName: String
        get() = sp.getString(KEY_NAME, null) ?: "Kullanıcı"
        set(value) = sp.edit().putString(KEY_NAME, value).apply()

    /** Base URL of the backend, e.g. https://your-app.onrender.com (no trailing slash). */
    var serverUrl: String
        get() = sp.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = sp.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    /** Last room the user was in, so we can offer "rejoin" right after a cold start. */
    var lastRoomCode: String?
        get() = sp.getString(KEY_LAST_ROOM, null)
        set(value) = sp.edit().putString(KEY_LAST_ROOM, value).apply()

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_NAME = "display_name"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LAST_ROOM = "last_room_code"

        // Change this to your deployed Render URL before shipping a release build.
        const val DEFAULT_SERVER_URL = "https://MsyncYSL.onrender.com"
    }
}
