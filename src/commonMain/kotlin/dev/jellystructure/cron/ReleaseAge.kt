package dev.jellystructure.cron

/** The unit of a release-age tier (DEB-4387, FR-5.2). A month is 30 days and a year 365, as the tiers have always meant them. */
enum class AgeUnit(val key: String, val days: Int) {
    DAYS("days", 1), WEEKS("weeks", 7), MONTHS("months", 30), YEARS("years", 365);

    companion object {
        fun of(key: String): AgeUnit? = entries.firstOrNull { it.key == key }
    }
}

/** An age as the picker's *[N] [days | weeks | months | years]* half says it. */
data class ReleaseAge(val n: Int, val unit: AgeUnit) {
    val days: Int get() = n * unit.days

    /** The word to store (FR-5.2.2): today's word whenever the choice is one, `<N><unit>` only for an age no word says. */
    val word: String get() = WORDS.entries.firstOrNull { it.value == this }?.key ?: "$n${unit.key}"

    companion object {
        const val MIN_N = 1
        const val MAX_N = 100
        const val NEVER = "never"

        /** Today's words and the age each one says. */
        val WORDS: Map<String, ReleaseAge> = linkedMapOf(
            "daily" to ReleaseAge(1, AgeUnit.DAYS), "weekly" to ReleaseAge(1, AgeUnit.WEEKS), "monthly" to ReleaseAge(1, AgeUnit.MONTHS),
            "6months" to ReleaseAge(6, AgeUnit.MONTHS), "yearly" to ReleaseAge(1, AgeUnit.YEARS),
            "2years" to ReleaseAge(2, AgeUnit.YEARS), "5years" to ReleaseAge(5, AgeUnit.YEARS),
        )

        /**
         * A stored tier as an age, or null for `never` and for anything unreadable. Reads today's words and `<N>days|weeks|months|years` with N from
         * 1 to 100. [cadenceMs] is the one place an age becomes a duration; this is only the grammar, so the admin can show what is stored.
         */
        fun parse(word: String): ReleaseAge? {
            val w = word.trim().lowercase()
            WORDS[w]?.let { return it }
            val unit = AgeUnit.entries.firstOrNull { w.endsWith(it.key) } ?: return null
            val n = w.removeSuffix(unit.key).takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toIntOrNull() ?: return null
            return if (n in MIN_N..MAX_N) ReleaseAge(n, unit) else null
        }
    }
}
