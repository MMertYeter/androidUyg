package com.syncmusic.app.util

/**
 * Fill these in after registering an app at https://developer.spotify.com/dashboard :
 *  - CLIENT_ID: shown on your app's dashboard page.
 *  - REDIRECT_URI: must exactly match one of the Redirect URIs you add in the
 *    dashboard's "Redirect URIs" field, AND match the intent-filter scheme/host
 *    declared in AndroidManifest.xml (scheme="syncmusic" host="callback").
 *
 * See SETUP_GUIDE.md for the full step-by-step walkthrough.
 */
object SpotifyConfig {
    const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
    const val REDIRECT_URI = "syncmusic://callback"
}
