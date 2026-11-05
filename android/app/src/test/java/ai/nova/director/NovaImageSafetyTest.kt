package ai.nova.director

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaImageSafetyTest {
    @Test
    fun allowsUsableMemoryForQuickImage() {
        assertNull(NovaImageSafety.warning(3200, false, false, 256))
    }

    @Test
    fun blocksLowMemoryEvenWhenAndroidDoesNotSignalLowMemory() {
        val warning = NovaImageSafety.warning(1500, false, false, 256)
        assertNotNull(warning)
        assertTrue(warning!!.contains("RAM"))
    }

    @Test
    fun qualityModeRequiresMoreRamThanQuickMode() {
        assertNull(NovaImageSafety.warning(2500, false, false, 256))
        assertNotNull(NovaImageSafety.warning(2500, false, false, 512))
    }

    @Test
    fun systemLowMemoryOverridesAppEstimate() {
        assertNotNull(NovaImageSafety.warning(6000, true, false, 256))
    }

    @Test
    fun overheatingBlocksBothImageSizes() {
        assertNotNull(NovaImageSafety.warning(6000, false, true, 256))
        assertNotNull(NovaImageSafety.warning(6000, false, true, 512))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnsupportedResolutions() {
        NovaImageSafety.warning(6000, false, false, 1024)
    }
}
