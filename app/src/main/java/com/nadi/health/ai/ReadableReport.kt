package com.nadi.health.ai

import com.nadi.health.ui.stripInlineMarkdown
import org.json.JSONArray
import org.json.JSONObject

// ══════════════════════════════════════════════════════════════════════
//  Result rendering — models answer in JSON, humans read label/value
// ══════════════════════════════════════════════════════════════════════

/**
 * Turns a model answer into something a person can read. Structured answers
 * come back as raw JSON text; instead of dumping that on screen we walk the
 * parsed tree and print `Label: value` lines with nesting, so a dish estimate
 * reads like a food label rather than a payload.
 *
 * @param json already-parsed payload from the model, when the feature produced one
 * @param body raw model output — parsed here only when it actually looks like JSON
 */
fun readableBody(json: JSONObject?, body: String): String {
    json?.let { return renderReadable(it) }
    val trimmed = body.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
            return renderReadable(if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed))
        } catch (_: Exception) {
            // Not valid JSON after all — fall through to the raw text.
        }
    }
    return body
}

internal fun renderReadable(value: Any?): String = buildString { appendReadable(value, 0) }.trimEnd()

private fun StringBuilder.appendReadable(value: Any?, depth: Int) {
    val pad = "  ".repeat(depth)
    when (value) {
        is JSONObject -> {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // JSONObject.NULL is the JSON null sentinel, not Kotlin null —
                // normalise so the screen shows an em dash, never "null".
                val child = value.opt(key).takeIf { it !== JSONObject.NULL }
                val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
                when (child) {
                    is JSONObject, is JSONArray -> {
                        append("$pad$label:\n")
                        appendReadable(child, depth + 1)
                    }
                    else -> append("$pad$label: ${child?.let { stripInlineMarkdown(it.toString()) } ?: "—"}\n")
                }
            }
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val child = value.opt(index).takeIf { it !== JSONObject.NULL }
                if (child is JSONObject || child is JSONArray) {
                    append("$pad•\n")
                    appendReadable(child, depth + 1)
                } else {
                    append("$pad• ${child?.let { stripInlineMarkdown(it.toString()) } ?: "—"}\n")
                }
            }
        }
        else -> append("$pad${value ?: "—"}\n")
    }
}
