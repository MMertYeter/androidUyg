package com.syncmusic.app.network

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class UploadResult(val fileUrl: String, val filename: String)

class UploadClient(private val contentResolver: ContentResolver) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads the audio file at [uri] (picked via Storage Access Framework) to the
     * room's upload endpoint. [onProgress] receives 0f..1f.
     */
    suspend fun uploadAudio(
        serverUrl: String,
        roomCode: String,
        clientId: String,
        uri: Uri,
        filename: String,
        onProgress: (Float) -> Unit,
    ): Result<UploadResult> {
        val length = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

        val fileBody = object : RequestBody() {
            override fun contentType() = guessMimeType(filename).toMediaTypeOrNull()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                contentResolver.openInputStream(uri)?.use { input ->
                    val source = input.source()
                    var totalWritten = 0L
                    val bufferSize = 8L * 1024
                    while (true) {
                        val read = source.read(sink.buffer, bufferSize)
                        if (read == -1L) break
                        totalWritten += read
                        sink.flush()
                        if (length > 0) onProgress(totalWritten.toFloat() / length.toFloat())
                    }
                } ?: throw IOException("Could not open picked file")
            }
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", filename, fileBody)
            .build()

        val url = "$serverUrl/api/upload/$roomCode?clientId=$clientId"
        val request = Request.Builder().url(url).post(multipart).build()

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            if (cont.isActive) cont.resume(Result.failure(IOException("Upload failed: HTTP ${resp.code} $bodyStr")))
                            return
                        }
                        try {
                            val json = JSONObject(bodyStr)
                            val result = UploadResult(
                                fileUrl = json.getString("fileUrl"),
                                filename = json.optString("filename", filename),
                            )
                            if (cont.isActive) cont.resume(Result.success(result))
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(Result.failure(e))
                        }
                    }
                }
            })
        }
    }

    private fun guessMimeType(filename: String): String = when {
        filename.endsWith(".mp3", true) -> "audio/mpeg"
        filename.endsWith(".m4a", true) -> "audio/mp4"
        filename.endsWith(".aac", true) -> "audio/aac"
        filename.endsWith(".wav", true) -> "audio/wav"
        filename.endsWith(".ogg", true) -> "audio/ogg"
        filename.endsWith(".flac", true) -> "audio/flac"
        filename.endsWith(".opus", true) -> "audio/opus"
        else -> "application/octet-stream"
    }
}
