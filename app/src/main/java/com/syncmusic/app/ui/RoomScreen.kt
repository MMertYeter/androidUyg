package com.syncmusic.app.ui

import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.syncmusic.app.model.SourceMeta
import com.syncmusic.app.model.SourceType
import com.syncmusic.app.util.SpotifyUriParser
import com.syncmusic.app.util.YouTubeUrlParser
import kotlinx.coroutines.delay

@Composable
fun RoomScreen(viewModel: RoomViewModel) {
    val room by viewModel.room.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val error by viewModel.lastError.collectAsState()
    val roomState = room ?: return
    val isHost = viewModel.isHost()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        RoomHeader(roomCode = roomState.code, connectionStatus = connectionStatus.name, isHost = isHost, viewModel = viewModel)

        Spacer(Modifier.height(12.dp))
        MembersRow(members = roomState.members)

        Spacer(Modifier.height(20.dp))

        if (isHost) {
            SourcePicker(viewModel = viewModel)
            Spacer(Modifier.height(16.dp))
        }

        PlayerArea(viewModel = viewModel, sourceType = roomState.playback.source, sourceMeta = roomState.playback.sourceMeta)

        Spacer(Modifier.height(16.dp))

        PlaybackControls(
            isHost = isHost,
            isPlaying = roomState.playback.isPlaying,
            viewModel = viewModel,
        )

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RoomHeader(roomCode: String, connectionStatus: String, isHost: Boolean, viewModel: RoomViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Oda: $roomCode", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                when (connectionStatus) {
                    "CONNECTED" -> "Bağlı"
                    "CONNECTING" -> "Bağlanıyor..."
                    else -> "Bağlantı kesildi"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (connectionStatus == "CONNECTED") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = {
            if (isHost) viewModel.closeRoom { } else viewModel.leaveRoom()
        }) {
            Text(if (isHost) "Odayı Kapat" else "Ayrıl")
        }
    }
}

@Composable
private fun MembersRow(members: List<com.syncmusic.app.model.Member>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(members) { member ->
            AssistChip(
                onClick = { },
                label = { Text(member.name + if (member.isHost) " 👑" else "") },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (member.connected) Color(0xFF2E7D32) else Color.Gray),
                    )
                },
            )
        }
    }
}

@Composable
private fun SourcePicker(viewModel: RoomViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Yerel Dosya", "YouTube", "Spotify")

    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> LocalFileTab(viewModel)
                1 -> YouTubeTab(viewModel)
                2 -> SpotifyTab(viewModel)
            }
        }
    }
}

@Composable
private fun LocalFileTab(viewModel: RoomViewModel) {
    val context = LocalContext.current
    val progress by viewModel.uploadProgress.collectAsState()
    var pickedName by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "audio.mp3"
        pickedName = name
        viewModel.hostUploadLocalFile(uri, name) { }
    }

    Column {
        Text("Telefonundan bir müzik dosyası seç ve odadaki herkese anında stream et.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { launcher.launch(arrayOf("audio/*")) }) {
            Icon(Icons.Filled.CloudUpload, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("  Dosya Seç ve Yükle")
        }
        pickedName?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        progress?.let {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun YouTubeTab(viewModel: RoomViewModel) {
    var input by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("YouTube linki veya video ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val id = YouTubeUrlParser.extractVideoId(input)
            if (id != null) {
                viewModel.hostSetYouTube(id)
            } else {
                viewModel.lastError.value = "Geçerli bir YouTube linki/ID'si girin."
            }
        }) {
            Text("Yükle ve Oynat")
        }
    }
}

@Composable
private fun SpotifyTab(viewModel: RoomViewModel) {
    var input by remember { mutableStateOf("") }
    Column {
        Text("Spotify Premium ve yüklü Spotify uygulaması gerekir.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Spotify şarkı linki veya URI") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val uri = SpotifyUriParser.extractTrackUri(input)
            if (uri != null) {
                viewModel.hostSetSpotify(uri)
            } else {
                viewModel.lastError.value = "Geçerli bir Spotify şarkı linki/URI'si girin."
            }
        }) {
            Text("Yükle ve Oynat")
        }
    }
}

@Composable
private fun PlayerArea(viewModel: RoomViewModel, sourceType: SourceType?, sourceMeta: SourceMeta?) {
    when (sourceType) {
        SourceType.YOUTUBE -> {
            val webView = viewModel.youtubeWebViewOrNull()
            if (webView != null) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            } else {
                EmptyPlayerPlaceholder("YouTube oynatıcı hazırlanıyor...")
            }
        }
        SourceType.LOCAL -> {
            val filename = (sourceMeta as? SourceMeta.Local)?.displayName ?: "Ses dosyası"
            NowPlayingCard(title = filename, subtitle = "Yerel dosya - stream ediliyor")
        }
        SourceType.SPOTIFY -> {
            val meta = sourceMeta as? SourceMeta.Spotify
            NowPlayingCard(title = meta?.title ?: meta?.uri ?: "Spotify", subtitle = "Spotify üzerinden çalıyor")
        }
        null -> EmptyPlayerPlaceholder("Henüz bir şarkı seçilmedi")
    }
}

@Composable
private fun EmptyPlayerPlaceholder(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NowPlayingCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlaybackControls(isHost: Boolean, isPlaying: Boolean, viewModel: RoomViewModel) {
    var displayPositionMs by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            displayPositionMs = viewModel.currentPositionMs()
            delay(500)
        }
    }

    Column {
        Text(formatTime(displayPositionMs), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { viewModel.hostSeekBy(-10_000) }, enabled = isHost) {
                Icon(Icons.Filled.Replay10, contentDescription = "10 saniye geri")
            }
            Spacer(Modifier.height(0.dp))
            OutlinedButton(onClick = { viewModel.hostTogglePlayPause() }, enabled = isHost) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
            }
            IconButton(onClick = { viewModel.hostSeekBy(10_000) }, enabled = isHost) {
                Icon(Icons.Filled.Forward10, contentDescription = "10 saniye ileri")
            }
        }
        if (!isHost) {
            Text(
                "Oynatma sadece host tarafından kontrol edilir.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return null
}
