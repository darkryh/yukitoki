package io.github.darkryh.yukitoki.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProgressReporterTest {
    private var now = 1_000L

    private fun reporter(
        granularity: ProgressGranularity = ProgressGranularity(minInterval = 0.milliseconds),
        totalBytes: Long? = null,
    ) = ProgressReporter(granularity, totalBytes) { now }

    @Test
    fun `speed follows the recent rate rather than the lifetime average`() {
        val reporter = reporter()
        reporter.sample(0)
        now += 1_000
        reporter.sample(10_000_000) // A fast first second, at 10 MB/s.
        now += 1_000
        reporter.sample(10_001_000) // Then 1 KB/s.
        now += 1_000

        assertEquals(1_000L, assertNotNull(reporter.sample(10_002_000)).speedBytesPerSecond)
    }

    @Test
    fun `eta is the remaining bytes at the current speed`() {
        val reporter = reporter(totalBytes = 3_000)
        reporter.sample(0)
        now += 1_000

        val progress = assertNotNull(reporter.sample(1_000))

        assertEquals(1_000L, progress.speedBytesPerSecond)
        assertEquals(2.seconds, progress.eta)
    }

    @Test
    fun `the first sample has no speed yet`() {
        val progress = assertNotNull(reporter().sample(100))

        assertNull(progress.speedBytesPerSecond)
        assertNull(progress.eta)
    }

    @Test
    fun `an update needs both the byte delta and the interval`() {
        val reporter = reporter(ProgressGranularity(minBytesDelta = 100, minInterval = 1.seconds))
        assertNotNull(reporter.sample(1))

        now += 2_000
        assertNull(reporter.sample(50), "the interval passed but not the byte delta")

        now -= 1_500
        assertNull(reporter.sample(500), "the byte delta passed but not the interval")

        now += 1_000
        assertNotNull(reporter.sample(500))
    }

    @Test
    fun `the final byte is always reported`() {
        val reporter = reporter(ProgressGranularity(minInterval = 1.hours), totalBytes = 10)
        assertNotNull(reporter.sample(1))
        assertNull(reporter.sample(5))

        assertEquals(10L, assertNotNull(reporter.sample(10)).bytesDownloaded)
    }
}
