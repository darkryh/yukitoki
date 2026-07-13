package io.github.darkryh.yukitoki.manager

import android.content.ComponentName
import android.os.Environment
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidDownloadLocationsTest {
    @Test
    fun `manifest initializer exposes a writable default downloads directory`() {
        val application = RuntimeEnvironment.getApplication()
        val component = ComponentName(application, YukitokiInitializer::class.java)
        @Suppress("DEPRECATION")
        val providerInfo = application.packageManager.getProviderInfo(component, 0)
        assertNotNull(providerInfo.authority)
        assertTrue(!providerInfo.exported)

        val initializer = YukitokiInitializer()
        initializer.attachInfo(application, providerInfo)
        assertTrue(initializer.onCreate())
        assertTrue(DownloadLocations.isSupported)

        val expected = application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: application.filesDir
        assertEquals(expected.absolutePath.toPath(), DownloadLocations.downloads)

        val fileSystem = FileSystem.SYSTEM
        val probe = DownloadLocations.downloads / "yukitoki-host-test.tmp"
        try {
            fileSystem.createDirectories(DownloadLocations.downloads)
            fileSystem.write(probe) { writeUtf8("ok") }
            assertEquals("ok", fileSystem.read(probe) { readUtf8() })
        } finally {
            fileSystem.delete(probe, mustExist = false)
        }
    }
}
