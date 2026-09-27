package com.nadi.health.ui.charts

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ══════════════════════════════════════════════════════════════════════
//  Flow diagrams — numbered steps, pipelines and timelines. These show
//  *how* a feature works (steps, phases, data routes), never fake data.
// ══════════════════════════════════════════════════════════════════════

data class FlowStep(val title: String, val detail: String = "")

/** Vertical numbered stepper; highlights the current step when given. */
@Composable
fun MetroStepFlow(
    steps: List<FlowStep>,
    modifier: Modifier = Modifier,
    current: Int = -1
) {
    val primary = MaterialTheme.colorScheme.primary
    val done = MaterialTheme.colorScheme.primaryContainer
    Column(modifier = modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            val active = index == current
            val passed = current >= 0 && index < current
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    active -> primary
                                    passed -> done
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (index != steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(28.dp)
                                .background(
                                    if (passed || active) primary.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    Text(
                        step.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (active) primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (step.detail.isNotEmpty()) {
                        Text(
                            step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Horizontal pipeline of labelled nodes joined by chevrons.
 * Best for 3–5 stages; wraps to the next line beyond that.
 */
@Composable
fun MetroPipeline(
    nodes: List<String>,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth()) {
        nodes.chunked(3).forEach { rowNodes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowNodes.forEachIndexed { index, node ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            node,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (index != rowNodes.lastIndex) {
                        Text(
                            "→",
                            style = MaterialTheme.typography.labelLarge,
                            color = primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                repeat(3 - rowNodes.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Horizontal phase strip — dots + labels, current phase filled. */
@Composable
fun MetroPhaseStrip(
    phases: List<String>,
    current: Int,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        phases.forEachIndexed { index, phase ->
            val active = index == current
            val passed = index < current
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> primary
                            passed -> primary.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            )
            Spacer(Modifier.width(5.dp))
            Text(
                phase,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (index != phases.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

/** Timeline rows — timestamp left, event right, coral rail down the side. */
@Composable
fun MetroTimeline(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    highlightLast: Boolean = false
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, (when_, what) ->
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (highlightLast && index == items.lastIndex) primary
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                    )
                    if (index != items.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        when_,
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(
                        what,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Coloured status pill used inside flow explanations. */
@Composable
fun MetroPill(text: String, modifier: Modifier = Modifier, tone: Color? = null) {
    val container = tone?.copy(alpha = 0.16f) ?: MaterialTheme.colorScheme.primaryContainer
    val content = tone ?: MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
