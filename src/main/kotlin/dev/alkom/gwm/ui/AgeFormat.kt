package dev.alkom.gwm.ui

/**
 * Compact, locale-independent "time since last commit" strings for the overview's Возраст column
 * (Этап 8, plan §Р3).
 *
 * We deliberately do NOT use git's `%cr` ("2 hours ago"): it is localized (Russian on this host)
 * and its width/wording are unstable, which breaks both column budgeting and deterministic tests.
 * Instead we take the raw commit timestamp (`git log -1 --format=%ct`) and format the delta
 * ourselves, as a pure function of (now, commit epoch) — no I/O, no locale, fully unit-testable.
 *
 * The suffixes are single Latin/Cyrillic letters chosen to stay narrow (the column is tight and
 * degrades early): `м` minutes, `ч` hours, `д` days, `нед` weeks, `мес` months, `г` years.
 */
object AgeFormat {

    private const val MINUTE = 60L
    private const val HOUR = 60L * MINUTE
    private const val DAY = 24L * HOUR
    private const val WEEK = 7L * DAY
    private const val MONTH = 30L * DAY
    private const val YEAR = 365L * DAY

    /**
     * Relative age of [commitEpochSec] as seen from [nowEpochSec], both unix seconds.
     *
     * Buckets (largest unit that fits, floored): `<1ч → Nм`, `<1д → Nч`, `<1нед → Nд`,
     * `<1мес → Nнед`, `<1г → Nмес`, else `Nг`. A future or zero delta clamps to `"0м"`
     * (clock skew / just-now), never a negative number.
     */
    fun relative(nowEpochSec: Long, commitEpochSec: Long): String {
        val delta = (nowEpochSec - commitEpochSec).coerceAtLeast(0)
        return when {
            delta < HOUR -> "${delta / MINUTE}м"
            delta < DAY -> "${delta / HOUR}ч"
            delta < WEEK -> "${delta / DAY}д"
            delta < MONTH -> "${delta / WEEK}нед"
            delta < YEAR -> "${delta / MONTH}мес"
            else -> "${delta / YEAR}г"
        }
    }
}
