package com.nadi.health.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ══════════════════════════════════════════════════════════════════════
//  Heart tab — every chart here derives from the live PPG buffer and the
//  current readings; nothing is invented.
// ══════════════════════════════════════════════════════════════════════

/** Down-samples a buffer to at most [n] points for small canvases. */
internal fun sample(points: List<Float>, n: Int): List<Float> {
    if (points.size <= n) return points
    val step = points.size.toFloat() / n
    return (0 until n).map { points[(it * step).toInt().coerceIn(points.indices)] }
}

@Composable
fun HeartAnalyticsSection(
    signalData: List<Float>,
    confidence: Float,
    heartRate: Float,
    spo2: Int,
    bloodPressure: Pair<Int, Int>,
    currentPhase: Int
) {
    // 1 — live buffer analytics
    MetroChartCard(
        title = "Signal analytics",
        subtitle = "live PPG buffer · this session"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetroWaveform(
                points = sample(signalData, 140),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetroSparkline(
                    points = sample(signalData, 60),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                )
                MetroHistogram(
                    values = signalData,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetroLineChart(
                    series = listOf(sample(signalData, 60)),
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                )
                MetroScatter(
                    points = signalData.zipWithNext { a, b -> a to b },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetroDonut(
                    segments = listOf(confidence.coerceIn(0f, 1f), (1f - confidence).coerceIn(0f, 1f)),
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.size(84.dp),
                    strokeWidth = 10.dp,
                    centerText = "${(confidence * 100).toInt()}%",
                    centerSubtext = "quality"
                )
                MetroGauge(
                    fraction = confidence,
                    valueText = getConfidenceLabel(confidence),
                    label = "confidence",
                    modifier = Modifier.size(84.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    MetroBullet(
                        valueNorm = (if (heartRate > 0) heartRate else 0f) / 200f,
                        bands = listOf(0.3f, 0.5f),
                        targetNorm = null,
                        caption = "Resting band 60–100 · current " +
                            (if (heartRate > 0) "${heartRate.toInt()} bpm" else "—")
                    )
                }
            }
        }
    }

    // 2 — vitals radar
    MetroChartCard(
        title = "Vitals snapshot",
        subtitle = "readings not yet taken sit at the centre"
    ) {
        MetroRadar(
            items = listOf(
                "Heart rate" to (if (heartRate > 0f) (heartRate / 180f) else 0f),
                "SpO₂" to (if (spo2 > 0) spo2 / 100f else 0f),
                "Signal" to confidence.coerceIn(0f, 1f),
                "BP sys" to (if (bloodPressure.first > 0) bloodPressure.first / 200f else 0f)
            )
        )
    }

    // 3 — rPPG pipeline
    MetroChartCard(
        title = "How Nadi reads your pulse",
        subtitle = "on-device rPPG · camera to number"
    ) {
        MetroPipeline(
            nodes = listOf(
                "Camera frames",
                "Face & ROI",
                "Green channel",
                "Peak detection",
                "HR · HRV · stress"
            )
        )
    }

    // 4 — measurement phase stepper
    MetroChartCard(
        title = "Measurement phases",
        subtitle = "where the current reading is in the pipeline"
    ) {
        MetroStepFlow(
            steps = listOf(
                FlowStep("Warm up", "Camera stream coming up"),
                FlowStep("Find face", "Waiting for a detectable face"),
                FlowStep("Track", "Locking the ROI, checking lighting"),
                FlowStep("Measure", "Accumulating pulse cycles"),
                FlowStep("Done", "Reading written to the on-device store")
            ),
            current = currentPhase
        )
    }
}

private fun getConfidenceLabel(confidence: Float): String = when {
    confidence >= 0.8f -> "Good"
    confidence >= 0.5f -> "Fair"
    else -> "Weak"
}
