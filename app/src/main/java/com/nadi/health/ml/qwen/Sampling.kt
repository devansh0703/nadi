package com.nadi.health.ml.qwen

/** Sampler settings for a generation. See GUIDE.md §4. */
data class Sampling(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val seed: Long
) {
    companion object {
        /** Grounded, factual answers: reports, plans, Q&A over the user's data. */
        val GROUNDED = Sampling(temperature = 0.5f, topK = 20, topP = 0.85f, seed = 7)

        /** Explanations and coaching copy where a little variety helps. */
        val BALANCED = Sampling(temperature = 0.65f, topK = 30, topP = 0.9f, seed = 21)

        /** Strictly deterministic extraction (JSON / structured pulls). */
        val PRECISE = Sampling(temperature = 0.25f, topK = 8, topP = 0.7f, seed = 11)

        /** Same as [BALANCED] but with a drifting seed, for chat replies. */
        fun varied(): Sampling = BALANCED.copy(seed = System.currentTimeMillis() % 100_000L)
    }
}
