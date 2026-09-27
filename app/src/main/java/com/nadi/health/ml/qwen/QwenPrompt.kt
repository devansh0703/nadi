package com.nadi.health.ml.qwen

/**
 * Qwen3 / ChatML prompt construction.
 *
 * The model is an *instruct* checkpoint, so the template is not optional: raw
 * text puts it into continuation mode and it degenerates into a repetition loop
 * (observed, and the reason the app template lives here rather than at call
 * sites). See GUIDE.md §2.
 */
object QwenPrompt {

    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    /**
     * Builds a full ChatML conversation.
     *
     * The returned string ends with the assistant header and no closing token,
     * which is what makes the model continue as the assistant.
     *
     * @param system system/instruction block
     * @param turns alternating (role, content) pairs, oldest first
     * @param assistantPrefill optional text to force the answer to start with
     *   (e.g. `"{"` when the caller needs JSON)
     */
    fun chat(
        system: String,
        turns: List<Pair<String, String>>,
        assistantPrefill: String = ""
    ): String = buildString {
        append(IM_START).append("system\n").append(system.trim()).append(IM_END).append('\n')
        for ((role, content) in turns) {
            append(IM_START).append(role).append('\n')
            append(content.trim()).append(IM_END).append('\n')
        }
        append(IM_START).append("assistant\n")
        if (assistantPrefill.isNotEmpty()) append(assistantPrefill)
    }

    /** Convenience for a single-shot system + user request. */
    fun single(system: String, user: String, assistantPrefill: String = ""): String =
        chat(system, listOf("user" to user), assistantPrefill)

    /**
     * Pulls the first balanced JSON object out of free-form model output.
     *
     * A 4B model will wrap JSON in prose or code fences; rather than trusting
     * a fence, this scans for the first `{` and matches braces while respecting
     * string literals and escapes. Returns null when there is no object, so the
     * caller can fall back to a non-structured answer instead of showing
     * garbage.
     */
    fun extractJson(raw: String): String? {
        val text = raw.trim()
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Removes the structural noise a small model adds around a list answer:
     * leading bullets, markdown headers, and stray quotes.
     */
    fun cleanProse(raw: String): String = raw
        .trim()
        .removePrefix("```markdown").removePrefix("```")
        .removeSuffix("```")
        .trim()
}
