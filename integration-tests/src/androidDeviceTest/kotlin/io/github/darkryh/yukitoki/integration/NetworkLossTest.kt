package io.github.darkryh.yukitoki.integration

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.darkryh.yukitoki.manager.DownloadDestination
import io.github.darkryh.yukitoki.manager.DownloadManager
import io.github.darkryh.yukitoki.manager.DownloadRequest
import io.github.darkryh.yukitoki.manager.DownloadResult
import io.github.darkryh.yukitoki.manager.DownloadSegmentation
import io.github.darkryh.yukitoki.manager.RetryPolicy
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Turns the emulator's radios off mid-download, against the real CDN. */
@RunWith(AndroidJUnit4::class)
class NetworkLossTest {
    private val patientRetries = RetryPolicy(
        maxAttempts = 10,
        initialDelay = 2.seconds,
        maxDelay = 5.seconds,
        multiplier = 1.5,
        jitterRatio = 0.0,
    )

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun setNetwork(wifi: Boolean, data: Boolean) {
        shell("svc wifi ${if (wifi) "enable" else "disable"}")
        shell("svc data ${if (data) "enable" else "disable"}")
    }

    @After
    fun restoreNetwork() = setNetwork(wifi = true, data = true)

    @Test
    fun `losing the network mid-download recovers when it comes back`() = realTest(10.minutes) { dir ->
        if (enabledChecks()["realCdn"] != "true") return@realTest skip("network loss", "needs --real-cdn")
        val manager = newManager()
        try {
            val path = dir / "interrupted.bin"
            val task = manager.enqueue(
                DownloadRequest(
                    BIG_CDN_URL,
                    DownloadDestination.File(path),
                    retryPolicy = patientRetries,
                    segmentation = DownloadSegmentation.Single,
                ),
            )
            manager.awaitBytes(task.id, 5 * MIB)
            setNetwork(wifi = false, data = false)
            delay(10.seconds)
            setNetwork(wifi = true, data = true)
            val result = task.await()
            println("[$platformName] MEASURE network loss: $result")
            assertIs<DownloadResult.Success>(result, "$result")
            assertEquals(BIG_CDN_SHA, fileSha(path))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `switching from wifi to mobile data mid-download finishes the file`() = realTest(10.minutes) { dir ->
        if (enabledChecks()["realCdn"] != "true") return@realTest skip("network switch", "needs --real-cdn")
        val manager: DownloadManager = newManager()
        try {
            val path = dir / "switched.bin"
            val task = manager.enqueue(
                DownloadRequest(
                    BIG_CDN_URL,
                    DownloadDestination.File(path),
                    retryPolicy = patientRetries,
                    segmentation = DownloadSegmentation.Single,
                ),
            )
            manager.awaitBytes(task.id, 5 * MIB)
            setNetwork(wifi = false, data = true)
            val result = task.await()
            println("[$platformName] MEASURE network switch: $result")
            assertIs<DownloadResult.Success>(result, "$result")
            assertEquals(BIG_CDN_SHA, fileSha(path))
        } finally {
            manager.close()
        }
    }
}
