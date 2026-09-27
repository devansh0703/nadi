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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private data class MetroTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val MetroTabs = listOf(
    MetroTab("Heart", Icons.Default.Favorite),
    MetroTab("Workout", Icons.Default.FitnessCenter),
    MetroTab("Health", Icons.Default.MonitorHeart),
    MetroTab("AI", Icons.Default.AutoAwesome)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NadiApp(cameraGranted: Boolean, onRequestCamera: () -> Unit) {
    var tab by remember { mutableStateOf(0) }

    // Cross-tab user flow: an AI plan can hand an exercise straight to the
    // workout recorder (AI → Workout), which then opens on the READY phase.
    var pendingExercise by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        MetroTopBar(kicker = MetroTabs[tab].title)

        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> MainScreen(
                    cameraGranted = cameraGranted,
                    onRequestCamera = onRequestCamera
                )
                1 -> WorkoutScreen(
                    pendingExerciseId = pendingExercise,
                    onPendingConsumed = { pendingExercise = null }
                )
                2 -> HealthScreen()
                else -> AiScreen(
                    onStartExercise = { exerciseId ->
                        pendingExercise = exerciseId
                        tab = 1
                    }
                )
            }
        }

        Divider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            MetroTabs.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = tab == index,
                    onClick = { tab = index },
                    icon = {
                        Icon(item.icon, contentDescription = item.title)
                    },
                    label = { Text(item.title) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

/** Brand bar: coral mark, wordmark, and the active tab's kicker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetroTopBar(kicker: String) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Text(
                    text = "Nadi",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = kicker.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.4.sp
                )
            }
        },
        actions = {
            Text(
                text = "ON-DEVICE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(end = 16.dp)
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}
