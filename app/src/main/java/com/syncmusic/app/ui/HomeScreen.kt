package com.syncmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(viewModel: RoomViewModel) {
    var name by remember { mutableStateOf(viewModel.prefs.displayName) }
    var serverUrl by remember { mutableStateOf(viewModel.prefs.serverUrl) }
    var joinCode by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    val error by viewModel.lastError.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.height(56.dp))
        Spacer(Modifier.height(8.dp))
        Text("SyncListen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Arkadaşlarınla senkron müzik dinle", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Adın") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Sunucu adresi") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (name.isBlank()) return@Button
                viewModel.prefs.serverUrl = serverUrl
                isBusy = true
                viewModel.createRoom(name) { result ->
                    isBusy = false
                    result.onFailure { viewModel.lastError.value = it.message }
                }
            },
            enabled = !isBusy && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Groups, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("  Oda Oluştur (Host)")
        }

        Spacer(Modifier.height(20.dp))
        Divider()
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = joinCode,
            onValueChange = { joinCode = it.uppercase().take(5) },
            label = { Text("Oda Kodu") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                if (name.isBlank() || joinCode.isBlank()) return@OutlinedButton
                viewModel.prefs.serverUrl = serverUrl
                isBusy = true
                viewModel.joinRoom(joinCode, name) { result ->
                    isBusy = false
                    result.onFailure { viewModel.lastError.value = it.message }
                }
            },
            enabled = !isBusy && name.isNotBlank() && joinCode.length == 5,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Odaya Katıl")
        }

        if (isBusy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }

    LaunchedEffect(Unit) {
        // Clear stale errors when arriving fresh at the home screen.
        viewModel.lastError.value = null
    }
}
