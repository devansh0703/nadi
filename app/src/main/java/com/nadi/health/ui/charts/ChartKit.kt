package com.nadi.health.ui.charts

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ══════════════════════════════════════════════════════════════════════
//  ChartKit — Metrolist-styled charts, zero new dependencies.
//  Geometry-heavy charts are Canvas; label-heavy charts are layouts.
//  Every chart reads its palette from MaterialTheme so light-mode coral
//  stays consistent everywhere.
// ══════════════════════════════════════════════════════════════════════

/** Standard chart chrome: tonal card, uppercase kicker, hairline, body slot. */
@Composable
fun MetroChartCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    chart: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Spacer(Modifier.height(10.dp))
            chart()
        }
    }
}

/** Small legend dot + label row used under radars/donuts. */
@Composable
fun MetroLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (label, color) ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Layout-based charts
// ─────────────────────────────────────────────────────────────────────

/** Vertical bar chart with labels under each bar. */
@Composable
fun MetroBarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    highlightIndex: Int = -1,
    barHeight: Dp = 84.dp
) {
    if (values.isEmpty()) return
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.primaryContainer
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        values.forEachIndexed { index, value ->
            val fraction = (value / max).coerceIn(0.06f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (value == value.toInt().toFloat()) "${value.toInt()}" else String.format("%.1f", value),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == highlightIndex) primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((barHeight * fraction))
                            .align(Alignment.BottomCenter)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (index == highlightIndex) primary else muted)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    labels.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Horizontal bar rows: label on top, track + value below. */
@Composable
fun MetroHBarChart(
    rows: List<Triple<String, Float, String>>,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return
    val max = rows.maxOf { it.second }.takeIf { it > 0f } ?: 1f
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, value, valueText) ->
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        valueText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = primary
                    )
                }
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(track)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (value / max).coerceIn(0.03f, 1f))
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(primary)
                    )
                }
            }
        }
    }
}

/** Single stacked horizontal bar + legend — part-to-whole in one strip. */
@Composable
fun MetroStackedBar(
    segments: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null
) {
    if (segments.isEmpty()) return
    val total = segments.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.001f)
    val palette = colors ?: listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primaryContainer
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        segments.forEachIndexed { index, (_, value) ->
            Box(
                modifier = Modifier
                    .weight(value.coerceAtLeast(0.001f))
                    .height(14.dp)
                    .background(palette[index % palette.size])
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    MetroLegend(
        segments.mapIndexed { index, (label, _) -> label to palette[index % palette.size] }
    )
}

/** 7-column calendar heatmap; cells is a flat list of 0..1 intensities. */
@Composable
fun MetroHeatmap(
    cells: List<Float>,
    modifier: Modifier = Modifier,
    columns: Int = 7
) {
    if (cells.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { value ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (value <= 0f) empty
                                else primary.copy(alpha = (0.2f + 0.8f * value.coerceIn(0f, 1f)))
                            )
                    )
                }
                repeat(columns - row.size) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(empty)
                    )
                }
            }
        }
    }
}

/** Waffle / dot matrix — filled dots of a total, one row per [columns]. */
@Composable
fun MetroDotMatrix(
    filled: Int,
    total: Int,
    modifier: Modifier = Modifier,
    columns: Int = 10,
    label: ((Int) -> String)? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth()) {
        (0 until total).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { index ->
                    val isFilled = index < filled
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isFilled) primary else empty)
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
        }
        label?.let {
            Text(
                it(filled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Bullet chart: value bar against banded reference ranges + a target tick. */
@Composable
fun MetroBullet(
    valueNorm: Float,
    bands: List<Float>,
    targetNorm: Float?,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bandColor = MaterialTheme.colorScheme.tertiaryContainer
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(50))
                .background(track)
        ) {
            // reference bands, painted under the value
            Row(modifier = Modifier.fillMaxSize()) {
                var previous = 0f
                bands.forEach { edge ->
                    val segment = (edge - previous).coerceAtLeast(0f)
                    Box(
                        modifier = Modifier
                            .weight(segment.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(bandColor.copy(alpha = 0.7f))
                    )
                    previous = edge
                }
                if (previous < 1f) {
                    Box(
                        modifier = Modifier
                            .weight((1f - previous).coerceAtLeast(0.001f))
                            .fillMaxSize()
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = valueNorm.coerceIn(0f, 1f))
                    .height(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(primary.copy(alpha = 0.85f))
            )
            // target tick: spacer to the target fraction, then a dark rule
            if (targetNorm != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                ) {
                    Spacer(Modifier.fillMaxWidth(targetNorm.coerceIn(0f, 1f)))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Funnel: shrinking centered bars, label inside each stage. */
@Composable
fun MetroFunnel(
    stages: List<Pair<String, Float>>,
    modifier: Modifier = Modifier
) {
    if (stages.isEmpty()) return
    val max = stages.maxOf { it.second }.takeIf { it > 0f } ?: 1f
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        stages.forEachIndexed { index, (label, value) ->
            val fraction = (value / max).coerceIn(0.18f, 1f)
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(primary.copy(alpha = 1f - index * 0.16f)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        "  $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Two-point slope comparison — before → after. */
@Composable
fun MetroSlope(
    beforeLabel: String,
    before: Float,
    afterLabel: String,
    after: Float,
    modifier: Modifier = Modifier,
    betterWhenHigher: Boolean = true
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.primaryContainer
    val max = maxOf(before, after, 0.001f)
    val improved = if (betterWhenHigher) after >= before else after <= before
    val endColor = if (improved) primary else MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (before == before.toInt().toFloat()) "${before.toInt()}" else String.format("%.1f", before),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                beforeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Canvas(modifier = Modifier.weight(1f).height(46.dp)) {
            val y1 = size.height * (1f - before / max) * 0.7f + size.height * 0.15f
            val y2 = size.height * (1f - after / max) * 0.7f + size.height * 0.15f
            drawLine(
                color = endColor,
                start = Offset(0f, y1),
                end = Offset(size.width, y2),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
            drawCircle(color = endColor, radius = 7f, center = Offset(4f, y1))
            drawCircle(color = endColor, radius = 7f, center = Offset(size.width - 4f, y2))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (after == after.toInt().toFloat()) "${after.toInt()}" else String.format("%.1f", after),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = endColor
            )
            Text(
                afterLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Canvas charts
// ─────────────────────────────────────────────────────────────────────

/** Tiny inline line — sparklines for KPI tiles. */
@Composable
fun MetroSparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    fill: Boolean = true
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val min = points.minOrNull() ?: 0f
        val max = points.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1e-6f)
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - min) / range) * size.height * 0.9f - size.height * 0.05f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.16f))
        }
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

/** Live waveform trace with a midline — sensor feeds. */
@Composable
fun MetroWaveform(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        drawLine(
            color = color.copy(alpha = 0.25f),
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
        )
        val min = points.minOrNull() ?: 0f
        val max = points.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1e-6f)
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - min) / range) * size.height * 0.92f - size.height * 0.04f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

/** Grid-backed line chart, one or more series, optional area fill. */
@Composable
fun MetroLineChart(
    series: List<List<Float>>,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
    area: Boolean = true,
    gridLines: Int = 3
) {
    if (series.isEmpty() || series.all { it.size < 2 }) return
    val palette = colors ?: listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary
    )
    Canvas(modifier = modifier) {
        // grid
        for (i in 0..gridLines) {
            val y = size.height * i / gridLines
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5f
            )
        }
        val globalMax = series.maxOf { it.maxOrNull() ?: 0f }.takeIf { it > 0f } ?: 1f
        val globalMin = minOf(0f, series.minOf { it.minOrNull() ?: 0f })
        val range = (globalMax - globalMin).coerceAtLeast(1e-6f)

        series.forEachIndexed { sIndex, points ->
            if (points.size < 2) return@forEachIndexed
            val color = palette[sIndex % palette.size]
            val stepX = size.width / (points.size - 1)
            val path = Path()
            points.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height - ((value - globalMin) / range) * size.height * 0.9f - size.height * 0.05f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (area && sIndex == 0) {
                val filled = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(filled, color.copy(alpha = 0.14f))
            }
            drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
    }
}

/** Bar-style histogram of raw values. */
@Composable
fun MetroHistogram(
    values: List<Float>,
    modifier: Modifier = Modifier,
    bins: Int = 16,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        if (values.size < 4) return@Canvas
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1e-6f)
        val counts = IntArray(bins)
        values.forEach { v ->
            val bin = (((v - min) / range) * bins).toInt().coerceIn(0, bins - 1)
            counts[bin]++
        }
        val peak = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
        val barW = size.width / bins
        counts.forEachIndexed { index, count ->
            val h = size.height * (count.toFloat() / peak)
            drawRect(
                color = color.copy(alpha = 0.25f + 0.6f * (count.toFloat() / peak)),
                topLeft = Offset(index * barW + 1.5f, size.height - h),
                size = Size(barW - 3f, h)
            )
        }
    }
}

/** XY scatter — correlation checks. */
@Composable
fun MetroScatter(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val maxX = points.maxOf { it.first }.takeIf { it > 0f } ?: 1f
        val maxY = points.maxOf { it.second }.takeIf { it > 0f } ?: 1f
        points.forEach { (x, y) ->
            drawCircle(
                color = color.copy(alpha = 0.75f),
                radius = 8f,
                center = Offset(
                    (x / maxX) * (size.width - 16f) + 8f,
                    size.height - (y / maxY) * (size.height - 16f) - 8f
                )
            )
        }
    }
}

/** Donut with legend; segments are raw weights. */
@Composable
fun MetroDonut(
    segments: List<Float>,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
    strokeWidth: Dp = 16.dp,
    centerText: String? = null,
    centerSubtext: String? = null
) {
    val palette = colors ?: listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primaryContainer
    )
    val total = segments.sum().coerceAtLeast(0.001f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            var startAngle = -90f
            val stroke = strokeWidth.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            segments.forEachIndexed { index, raw ->
                val sweep = 360f * (raw / total)
                drawArc(
                    color = palette[index % palette.size],
                    startAngle = startAngle,
                    sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }
        if (centerText != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (centerSubtext != null) {
                    Text(
                        centerSubtext,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Radial gauge, 270° sweep from bottom-left. */
@Composable
fun MetroGauge(
    fraction: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.12f
            val pad = stroke
            val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (valueText != null) {
                Text(valueText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Radar / spider chart — values 0..1 with a legend underneath. */
@Composable
fun MetroRadar(
    items: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    if (items.size < 3) return
    val muted = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(cx, cy) - 8f
            val n = items.size

            fun point(index: Int, scale: Float): Offset {
                val angle = (Math.PI * 2 * index / n) - Math.PI / 2
                return Offset(
                    (cx + radius * scale * kotlin.math.cos(angle)).toFloat(),
                    (cy + radius * scale * kotlin.math.sin(angle)).toFloat()
                )
            }

            // web rings
            for (ring in 1..3) {
                val path = Path()
                for (i in 0 until n) {
                    val p = point(i, ring / 3f)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, muted, style = Stroke(width = 2f))
            }
            // spokes
            for (i in 0 until n) {
                val p = point(i, 1f)
                drawLine(color = muted, start = Offset(cx, cy), end = p, strokeWidth = 2f)
            }
            // data polygon
            val data = Path()
            items.forEachIndexed { index, (_, value) ->
                val p = point(index, value.coerceIn(0f, 1f))
                if (index == 0) data.moveTo(p.x, p.y) else data.lineTo(p.x, p.y)
            }
            data.close()
            drawPath(data, color.copy(alpha = 0.28f))
            drawPath(data, color, style = Stroke(width = 4f))
            items.forEachIndexed { index, (_, value) ->
                drawCircle(color = color, radius = 6f, center = point(index, value.coerceIn(0f, 1f)))
            }
        }
        MetroLegend(items.map { (label, _) -> label to color })
    }
}

/** Deterministic hysteresis teaching curve: threshold, release band, rep marks. */
@Composable
fun MetroHysteresisDiagram(
    threshold: Float,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val amp = size.height * 0.34f
        val tY = midY - amp * (threshold / (threshold * 1.6f))
        val rY = midY + amp * (threshold * 0.4f / (threshold * 1.6f)) * 1.4f

        // threshold line
        drawLine(
            color = error.copy(alpha = 0.8f),
            start = Offset(0f, tY),
            end = Offset(size.width, tY),
            strokeWidth = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))
        )
        // release line (threshold * 0.4)
        drawLine(
            color = muted,
            start = Offset(0f, rY),
            end = Offset(size.width, rY),
            strokeWidth = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f))
        )
        // synthetic rep waveform: two full cycles of a smooth pulse
        val path = Path()
        val steps = 160
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            // two bell-ish pulses
            fun pulse(center: Float, width: Float): Float {
                val d = (t - center) / width
                return if (kotlin.math.abs(d) > 2.5f) 0f
                else kotlin.math.exp(-d * d) * (if (d < 0) 1f else 1f)
            }
            val v = pulse(0.28f, 0.07f) + pulse(0.68f, 0.07f)
            val y = midY + amp * 0.9f - v * (amp * 1.55f)
            val x = t * size.width
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, primary, style = Stroke(width = 6f, cap = StrokeCap.Round))
        // rep marks where the pulses peak
        listOf(0.28f, 0.68f).forEach { center ->
            val y = midY + amp * 0.9f - 1f * (amp * 1.55f)
            drawCircle(color = primary, radius = 9f, center = Offset(center * size.width, y))
        }
    }
}
