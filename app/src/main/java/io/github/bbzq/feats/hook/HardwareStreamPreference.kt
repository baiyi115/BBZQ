package io.github.bbzq.feats.hook

/** Reorder only equivalent slots; never remove fallback or move placeholders / other quality tiers. */
internal object HardwareStreamPreference {
    fun <T, K : Any> reorder(
        streams: List<T>,
        key: (T) -> K?,
        preferred: (T) -> Boolean,
    ): List<T> {
        val replacements = streams.groupBy(key).filterKeys { it != null }.mapValues { (_, group) ->
            group.sortedByDescending(preferred).iterator()
        }
        return streams.map { item -> replacements[key(item)]?.next() ?: item }
    }

    fun frameRate(value: String): Double? {
        val parts = value.split('/')
        val parsed = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val numerator = parts[0].toDoubleOrNull() ?: return null
                val denominator = parts[1].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
                numerator / denominator
            }
            else -> null
        }
        return parsed?.takeIf { it.isFinite() && it > 0 }
    }
}
