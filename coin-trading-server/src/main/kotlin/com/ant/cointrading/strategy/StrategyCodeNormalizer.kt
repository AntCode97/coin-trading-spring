package com.ant.cointrading.strategy

object StrategyCodeNormalizer {
    const val DCA = "DCA"
    const val GUIDED_TRADING = "GUIDED_TRADING"
    const val MEME_SCALPER = "MEME_SCALPER"
    const val VOLUME_SURGE = "VOLUME_SURGE"

    private val aliasToCanonical = mapOf(
        "DCA" to DCA,
        "GUIDED" to GUIDED_TRADING,
        "GUIDEDTRADING" to GUIDED_TRADING,
        "GUIDED_TRADING" to GUIDED_TRADING,
        "MEMESCALPER" to MEME_SCALPER,
        "MEME_SCALPER" to MEME_SCALPER,
        "VOLUMESURGE" to VOLUME_SURGE,
        "VOLUME_SURGE" to VOLUME_SURGE,
    )

    fun canonicalize(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return "UNKNOWN"

        val key = trimmed.uppercase().replace(Regex("[^A-Z0-9]"), "")
        aliasToCanonical[key]?.let { return it }

        return trimmed.uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .ifBlank { "UNKNOWN" }
    }
}
