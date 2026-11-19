package io.github.rudolfhk.audiosaver.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TimeCodesTest {
    @Test fun parsing() {
        assertNull(TimeCodes.parse("  "))
        assertEquals(83_000, TimeCodes.parse("83"))
        assertEquals(83_000, TimeCodes.parse("1:23"))
        assertEquals(83_500, TimeCodes.parse("1:23.5"))
        assertEquals(83_250, TimeCodes.parse("1:23,25"))
        assertEquals(3_723_000, TimeCodes.parse("1:02:03"))
        assertEquals(600_000, TimeCodes.parse("10:00"))
        listOf("1:75", "abc", "1:2:3:4", "-5", "1:60:00").forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { TimeCodes.parse(bad) }
        }
    }

    @Test fun formatting() {
        assertEquals("1:23", TimeCodes.format(83_000))
        assertEquals("1:23.5", TimeCodes.format(83_500))
        assertEquals("1:02:03", TimeCodes.format(3_723_000))
        listOf("0:00", "1:23.25", "59:59", "2:00:00").forEach { assertEquals(it, TimeCodes.format(TimeCodes.parse(it)!!)) }
    }

    @Test fun ranges() {
        assertEquals(TimeRange.ALL, TimeCodes.range("", ""))
        assertEquals(TimeRange(5_000, null), TimeCodes.range("0:05", ""))
        assertEquals(TimeRange(0, 200_000), TimeCodes.range("", "3:20"))
        assertFailsWith<IllegalArgumentException> { TimeCodes.range("2:00", "1:00") }
    }
}
