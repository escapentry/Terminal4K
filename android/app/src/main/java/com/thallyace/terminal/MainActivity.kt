package com.thallyace.terminal

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.thallyace.terminal.service.TerminalService
import com.thallyace.terminal.ui.TerminalScreen

class MainActivity : ComponentActivity() {

    private var terminalService by mutableStateOf<TerminalService?>(null)
    private var serviceBound = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Service is already started and bound; no blocking required
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TerminalService.LocalBinder
            terminalService = binder?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Start and bind the persistent terminal service immediately without blocking on permission UI
        startAndBindTerminalService()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val activeService = terminalService
            if (activeService == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101214)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Starting terminal service...",
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            } else {
                TerminalScreen(
                    service = activeService,
                    onThemeChanged = { isLight, barColor ->
                        @Suppress("DEPRECATION")
                        window.statusBarColor = barColor
                        @Suppress("DEPRECATION")
                        window.navigationBarColor = barColor
                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                        controller.isAppearanceLightStatusBars = isLight
                        controller.isAppearanceLightNavigationBars = isLight
                    }
                )
            }
        }
    }

    private fun startAndBindTerminalService() {
        val serviceIntent = Intent(this, TerminalService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (_: Exception) {
            try {
                startService(serviceIntent)
            } catch (_: Exception) {
            }
        }
        if (!serviceBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            serviceBound = true
        }
    }

    override fun onStart() {
        super.onStart()
        if (!serviceBound) {
            val serviceIntent = Intent(this, TerminalService::class.java)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            serviceBound = true
        }
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onStop()
    }
}
