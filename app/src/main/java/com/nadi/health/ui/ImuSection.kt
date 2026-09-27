package com.nadi.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadi.health.ui.charts.MetroBullet
import com.nadi.health.ui.charts.MetroChartCard
import com.nadi.health.ui.charts.MetroGauge
import com.nadi.health.ui.charts.MetroHistogram
import com.nadi.health.ui.charts.MetroPipeline
import com.nadi.health.ui.charts.MetroSparkline
import com.nadi.health.ui.charts.sample
import com.nadi.health.viewmodel.ImuViewModel

/**
 * "Body Signals" section: phone-contact physiological measurements from the
 * inertial sensors - respiration (chest), heart rate + HRV (sternum SCG/GCG),
 * hand tremor test and walking gait metrics. Algorithms per ImuViewModel docs.
 */
@Composable
fun ImuSection() {
    val vm: ImuViewModel = viewModel()
    val mode by vm.mode.collectAsState()
    val message by vm.message.collectAsState()
    val imuError by vm.imuError.collectAsState()

    val respiration by vm.respiration.collectAsState()
    val respirationWave by vm.respirationWave.collectAsState()
    val imuHr by vm.imuHeartRate.collectAsState()
    val imuSdnn by vm.imuSdnn.collectAsState()
    val imuRmssd by vm.imuRmssd.collectAsState()
    val imuViaGyro by vm.imuViaGyro.collectAsState()
    val cardioWave by vm.cardioWave.collectAsState()
    val tremorHz by vm.tremorHz.collectAsState()
    val tremorMg by vm.tremorMg.collectAsState()
    val tremorClass by vm.tremorClass.collectAsState()
    val tremorProgress by vm.tremorProgress.collectAsState()
    val tremorWave by vm.tremorWave.collectAsState()
    val dieTempC by vm.dieTempC.collectAsState()

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Body Signals",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "PHONE-CONTACT · IMU",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(10.dp))

            // Mode selector chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip("Respiration", mode == ImuViewModel.Mode.CHEST) {
                    vm.setMode(if (mode == ImuViewModel.Mode.CHEST) ImuViewModel.Mode.OFF else ImuViewModel.Mode.CHEST)
                }
                ModeChip("Heart (chest)", mode == ImuViewModel.Mode.CARDIO) {
                    vm.setMode(if (mode == ImuViewModel.Mode.CARDIO) ImuViewModel.Mode.OFF else ImuViewModel.Mode.CARDIO)
                }
                ModeChip("Tremor", mode == ImuViewModel.Mode.TREMOR) {
                    vm.setMode(if (mode == ImuViewModel.Mode.TREMOR) ImuViewModel.Mode.OFF else ImuViewModel.Mode.TREMOR)
                }
            }

            imuError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // IMU die temperature (chip temp, not body temp). Steps are
            // deliberately NOT shown here - they move to a future dashboard.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniStat("IMU temp", if (dieTempC > 0f) "%.1f°C".format(dieTempC) else "--")
            }

            if (message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            when (mode) {
                ImuViewModel.Mode.CHEST -> {
                    BigMetric(
                        value = if (respiration > 0) "$respiration" else "--",
                        unit = "breaths/min",
                        label = "Respiration"
                    )
                    WaveChart(respirationWave, MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(8.dp))
                    MetroChartCard(
                        title = "Breathing vs rest band",
                        subtitle = "12–20 breaths/min is the adult resting range"
                    ) {
                        MetroBullet(
                            valueNorm = respiration / 30f,
                            bands = listOf(12f / 30f, 20f / 30f),
                            targetNorm = null,
                            caption = if (respiration > 0) {
                                "$respiration breaths/min · band 12–20"
                            } else {
                                "Hold still — waiting for a clean breathing cycle"
                            }
                        )
                    }
                }
                ImuViewModel.Mode.CARDIO -> {
                    BigMetric(
                        value = if (imuHr > 0) "$imuHr" else "--",
                        unit = "BPM · ${if (imuViaGyro) "gyro" else "seismo"}",
                        label = "Heart rate (chest contact)"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MiniStat("SDNN", if (imuSdnn > 0f) "${imuSdnn.toInt()} ms" else "--")
                        MiniStat("RMSSD", if (imuRmssd > 0f) "${imuRmssd.toInt()} ms" else "--")
                    }
                    WaveChart(cardioWave, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    MetroChartCard(
                        title = "Chest HR vs resting band",
                        subtitle = "60–100 bpm at rest · seismo/gyro trace"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetroBullet(
                                valueNorm = imuHr / 200f,
                                bands = listOf(0.3f, 0.5f),
                                targetNorm = null,
                                caption = if (imuHr > 0) {
                                    "$imuHr bpm · band 60–100"
                                } else {
                                    "Press the phone against your chest to read a pulse"
                                }
                            )
                            if (cardioWave.size >= 2) {
                                MetroSparkline(
                                    points = sample(cardioWave, 60),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                )
                            }
                        }
                    }
                }
                ImuViewModel.Mode.TREMOR -> {
                    if (tremorProgress > 0f && tremorProgress < 1f) {
                        LinearProgressIndicator(
                            progress = tremorProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Capturing ${(tremorProgress * 10).toInt()} / 10 s — hold steady",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MiniStat(
                                "Peak freq",
                                if (tremorHz > 0f) "%.1f Hz".format(tremorHz) else "--"
                            )
                            MiniStat(
                                "Amplitude",
                                if (tremorMg > 0f) "%.0f mg".format(tremorMg) else "--"
                            )
                        }
                        if (tremorClass.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(tremorClass, style = MaterialTheme.typography.bodyMedium)
                        }
                        WaveChart(tremorWave, MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(8.dp))
                        MetroChartCard(
                            title = "Tremor spectrum",
                            subtitle = "10 s capture · frequency + amplitude"
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    MetroGauge(
                                        fraction = tremorHz / 12f,
                                        valueText = if (tremorHz > 0f) {
                                            "%.1f".format(tremorHz)
                                        } else {
                                            "--"
                                        },
                                        label = "Hz",
                                        modifier = Modifier.size(84.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        MetroBullet(
                                            valueNorm = tremorMg / 500f,
                                            bands = emptyList(),
                                            targetNorm = null,
                                            caption = "Peak amplitude " +
                                                if (tremorMg > 0f) "%.0f mg".format(tremorMg)
                                                else "—"
                                        )
                                    }
                                }
                                if (tremorWave.size >= 4) {
                                    MetroHistogram(
                                        values = tremorWave,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                ImuViewModel.Mode.OFF -> {
                    Text(
                        "Pick a mode above. These use the accelerometer & gyroscope " +
                            "directly against your body — they work in the dark, where the " +
                            "camera cannot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tremorWave.isNotEmpty()) {
                        WaveChart(tremorWave, MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    MetroChartCard(
                        title = "Signal path",
                        subtitle = "how the IMU becomes a number"
                    ) {
                        MetroPipeline(
                            nodes = listOf(
                                "Accel / gyro",
                                "Band-pass filter",
                                "Peak or FFT",
                                "Confidence gate",
                                "Breaths · BPM · Hz"
                            )
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
    )
}

@Composable
private fun BigMetric(value: String, unit: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(unit, style = MaterialTheme.typography.labelMedium)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WaveChart(data: List<Float>, color: Color) {
    if (data.size < 2) return
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(top = 6.dp)
    ) {
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (v in data) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = (maxV - minV).coerceAtLeast(1e-5f)
        val path = Path()
        for (i in data.indices) {
            val x = size.width * i / (data.size - 1)
            val y = size.height * (1f - (data[i] - minV) / range)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
