package com.nadi.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

// ══════════════════════════════════════════════════════════════════════
//  Markdown → readable text.
//
//  The model answers in markdown; the screen must never show raw
//  `**asterisks**`, `#hashes` or bare dashes. This renders a small,
//  safe subset: headings, bullets, bold, italic, inline code, quotes
//  and horizontal rules — with no HTML, no links, no recursion.
// ══════════════════════════════════════════════════════════════════════

/** Strips inline markdown decoration from a single value (JSON leaves, labels). */
fun stripInlineMarkdown(text: String): String =
    text
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("""~~(?=.+~~)"""), "")

private val bulletPrefix = Regex("""^\s*([-*•]|\d+[.)])\s+""")
private val headingPrefix = Regex("""^\s*#{1,6}\s+""")
private val quotePrefix = Regex("""^\s*>\s?""")

/**
 * Builds a styled [AnnotatedString] from model markdown.
 * Line-level structure first (heading / bullet / quote), then inline
 * spans (bold / italic / code) inside each line.
 */
fun renderMarkdown(
    text: String,
    accent: Color
): AnnotatedString = buildAnnotatedString {
    val lines = text.replace("\r\n", "\n").split("\n")
    lines.forEachIndexed { index, rawLine ->
        var line = rawLine
        var isHeading = false
        var isBullet = false
        var isQuote = false

        when {
            headingPrefix.containsMatchIn(line) -> {
                line = headingPrefix.replaceFirst(line, "")
                isHeading = true
            }
            bulletPrefix.containsMatchIn(line) -> {
                line = bulletPrefix.replaceFirst(line, "")
                isBullet = true
            }
            quotePrefix.containsMatchIn(line) -> {
                line = quotePrefix.replaceFirst(line, "")
                isQuote = true
            }
            line.trim().matches(Regex("""(-{3,}|\*{3,}|_{3,})""")) -> {
                line = "────────────────"
            }
        }

        val prefix = when {
            isHeading -> ""
            isBullet -> "•  "
            isQuote -> "│  "
            else -> ""
        }

        if (isHeading) {
            withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = accent)
            ) { append(line.uppercase()) }
        } else {
            if (prefix.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(prefix) }
            }
            if (isQuote) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(line) }
            } else {
                appendInline(line)
            }
        }

        if (index != lines.lastIndex) append("\n")
    }
}

/** Appends one line with **bold**, *italic* and `code` spans resolved. */
private fun AnnotatedString.Builder.appendInline(line: String) {
    var rest = line
    while (rest.isNotEmpty()) {
        val bold = rest.indexOf("**")
        // A lone '*' that is not half of a '**' pair is the italic marker.
        val italic = rest.indices.firstOrNull { i ->
            rest[i] == '*' &&
                (i == 0 || rest[i - 1] != '*') &&
                (i + 1 >= rest.length || rest[i + 1] != '*')
        } ?: -1
        val code = rest.indexOf('`')

        val candidates = listOf(
            bold to 2,
            italic to 1,
            code to 1
        ).filter { (index, _) -> index >= 0 }.minByOrNull { it.first }

        if (candidates == null) {
            append(rest)
            return
        }
        val (markerIndex, markerLen) = candidates
        if (markerIndex > 0) append(rest.substring(0, markerIndex))

        val marker = rest[markerIndex]
        val closer = when {
            marker == '*' && markerLen == 2 -> "**"
            marker == '*' -> "*"
            else -> "`"
        }
        val end = rest.indexOf(closer, startIndex = markerIndex + markerLen)
        if (end < 0) {
            // Unclosed marker: emit it verbatim and stop mangling.
            append(rest.substring(markerIndex))
            return
        }
        val inner = rest.substring(markerIndex + markerLen, end)
        when {
            closer == "**" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inner) }
            closer == "*" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inner) }
            else -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            ) { append(inner) }
        }
        rest = rest.substring(end + closer.length)
    }
}

/**
 * Renders markdown model output as a plain Material [androidx.compose.material3.Text].
 * Used everywhere model prose meets the screen: result dialogs, saved
 * reports, chat bubbles and streaming previews.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = androidx.compose.material3.LocalContentColor.current,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE
) {
    androidx.compose.material3.Text(
        text = renderMarkdown(text, MaterialTheme.colorScheme.primary),
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
