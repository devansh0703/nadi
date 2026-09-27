package com.nadi.health.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nadi.health.ui.theme.NadiTheme

/**
 * Explains why Nadi asks for Health Connect access.
 *
 * Android 14+ requires apps that use Health Connect to ship this screen; the
 * Health Connect permission UI links straight to it. It is also reachable from
 * the app's own Health tab.
 */
class HealthConnectRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NadiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RationaleContent(
                        onOpenHealthConnect = {
                            runCatching {
                                startActivity(
                                    HealthConnectManager(this).settingsIntent()
                                )
                            }
                            finish()
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun RationaleContent(
    onOpenHealthConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Nadi and Health Connect",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Health Connect is Android's on-device health store. Watches, " +
                "chest straps and health apps sync their data into it, and Nadi " +
                "reads from it so your readings sit in one place.",
            style = MaterialTheme.typography.bodyMedium
        )

        ReasonCard(
            title = "What Nadi reads",
            body = "Heart rate, steps, distance and exercise sessions that your " +
                "other devices and apps have already recorded."
        )

        ReasonCard(
            title = "What Nadi writes",
            body = "Heart rate measured by the camera pipeline and the workouts you " +
                "finish here, so other apps can see them."
        )

        ReasonCard(
            title = "Where it stays",
            body = "Everything Nadi measures is stored locally in its own database " +
                "first. Syncing to Health Connect is optional and only happens when " +
                "you press Sync. Nothing is uploaded to a server."
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onOpenHealthConnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Health Connect")
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Not now")
        }
    }
}

@Composable
private fun ReasonCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
