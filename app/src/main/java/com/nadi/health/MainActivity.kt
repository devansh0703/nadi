package com.nadi.health

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.nadi.health.ui.AiScreen
import com.nadi.health.ui.HealthScreen
import com.nadi.health.ui.MainScreen
import com.nadi.health.ui.WorkoutScreen


import com.nadi.health.ui.theme.NadiTheme


class MainActivity : ComponentActivity() {

    /** Recomposes the UI when the camera grant changes. */
    private var cameraGranted by mutableStateOf(false)

    private var uiReady = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Camera permission result: $isGranted")
        cameraGranted = isGranted
        if (!isGranted) {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
        }
        // The workout dashboard needs no camera, so the app starts either way.
        if (!uiReady) setupUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        // Check if permission is already granted
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Camera permission already granted")
                cameraGranted = true
                setupUI()
            }
            else -> {
                Log.d(TAG, "Requesting camera permission")
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun setupUI() {
        Log.d(TAG, "Setting up UI")
        uiReady = true
        try {
            setContent {
                NadiTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NadiApp(
                            cameraGranted = cameraGranted,
                            onRequestCamera = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    }
                }
            }
            Log.d(TAG, "UI setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI", e)
            showError("Failed to initialize: ${e.message}")
        }
    }

    private fun showError(message: String) {
        setContent {
            NadiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            text = "Error: $message",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

/**
 * Four dashboards: the camera rPPG screen, the sensor-only workout screen, the
 * Health tab (Health Connect + the local store) and the on-device AI tab.
 */
@Composable
private fun NadiApp(cameraGranted: Boolean, onRequestCamera: () -> Unit) {
    var tab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> MainScreen(
                    cameraGranted = cameraGranted,
                    onRequestCamera = onRequestCamera
                )
                1 -> WorkoutScreen()
                2 -> HealthScreen()
                else -> AiScreen()
            }
        }

        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                label = { Text("Heart") }
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) },
                label = { Text("Workout") }
            )
            NavigationBarItem(
                selected = tab == 2,
                onClick = { tab = 2 },
                icon = { Icon(Icons.Default.MonitorHeart, contentDescription = null) },
                label = { Text("Health") }
            )
            NavigationBarItem(
                selected = tab == 3,
                onClick = { tab = 3 },
                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                label = { Text("AI") }
            )
        }
    }
}
