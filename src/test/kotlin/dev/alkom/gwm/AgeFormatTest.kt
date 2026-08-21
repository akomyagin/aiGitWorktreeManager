package dev.alkom.gwm

import dev.alkom.gwm.ui.AgeFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [AgeFormat.relative] (Этап 8, plan §6 cases 41-45). Deterministic: we pass an
 * explicit `now`, so no wall clock is involved. Thresholds are pinned here so a refactor can't
 * silently shift the buckets.
 */
class AgeFormatTest {

    private val MIN = 60L
    private val HOUR = 60L * MIN
    private val DAY = 24L * HOUR
    private val WEEK = 7L * DAY
    private val MONTH = 30L * DAY
    private val YEAR = 365L * DAY

    private fun ago(seconds: Long): String = AgeFormat.relative(nowEpochSec = 1_000_000_000L, commitEpochSec = 1_000_000_000L - seconds)

    @Test // 41 — minutes
    fun `sub-hour renders minutes`() {
        assertEquals("5м", ago(5 * MIN))
        assertEquals("59м", ago(59 * MIN))
    }

    @Test // 42 — hours
    fun `hours`() {
        assertEquals("1ч", ago(HOUR))
        assertEquals("3ч", ago(3 * HOUR))
        assertEquals("23ч", ago(23 * HOUR))
    }

    @Test // 43 — days
    fun `days`() {
        assertEquals("1д", ago(DAY))
        assertEquals("2д", ago(2 * DAY))
        assertEquals("6д", ago(6 * DAY))
    }

    @Test // 44 — weeks / months / years thresholds
    fun `weeks months years thresholds`() {
        assertEquals("1нед", ago(WEEK))
        assertEquals("4нед", ago(29 * DAY)) // still weeks, just under a month
        assertEquals("1мес", ago(MONTH))
        assertEquals("6мес", ago(6 * MONTH))
        assertEquals("12мес", ago(360 * DAY)) // 360/30 = 12 months, still under a 365-day year
        assertEquals("1г", ago(YEAR))
        assertEquals("2г", ago(2 * YEAR))
    }

    @Test // 45 — just now / clock skew clamps to 0м, never negative
    fun `zero and future clamp to zero minutes`() {
        assertEquals("0м", ago(0))
        assertEquals("0м", ago(-100)) // commit "in the future" (skew) → 0м, not a negative
    }
}
