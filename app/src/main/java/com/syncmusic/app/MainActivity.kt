package com.syncmusic.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.syncmusic.app.service.SyncConnectionService
import com.syncmusic.app.ui.HomeScreen
import com.syncmusic.app.ui.RoomScreen
import com.syncmusic.app.ui.RoomViewModel
import com.syncmusic.app.ui.theme.SyncListenTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RoomViewModel by viewModels()
    private var boundService: SyncConnectionService? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as SyncConnectionService.LocalBinder).getService()
            boundService = service
            viewModel.onServiceBound(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // startService (in addition to bind) so the connection/playback survives
        // even if the Activity briefly unbinds (e.g. during a configuration change).
        val intent = Intent(this, SyncConnectionService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            SyncListenTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
    }
}

@Composable
private fun AppRoot(viewModel: RoomViewModel) {
    val serviceReady by viewModel.serviceReady.collectAsState()
    val room by viewModel.room.collectAsState()

    if (!serviceReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (room == null) {
        HomeScreen(viewModel = viewModel)
    } else {
        RoomScreen(viewModel = viewModel)
    }
}
