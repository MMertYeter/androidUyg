# Socket.IO / engine.io use reflection on these
-keep class io.socket.** { *; }
-keep class org.json.** { *; }
-dontwarn org.json.**

# Spotify App Remote SDK
-keep class com.spotify.protocol.** { *; }
-keep class com.spotify.android.appremote.** { *; }

# Media3
-dontwarn androidx.media3.**
