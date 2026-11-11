package ai.nova.director

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaVideoFramingTest {
    @Test fun squareSourceCropsSidesToFitPortraitShort() {
        val crop = NovaVideoFraming.cover(512, 512, 576, 1024)
        assertEquals(0.21875f, crop.left, 0.00001f)
        assertEquals(0f, crop.top, 0.00001f)
        assertEquals(0.5625f, crop.width, 0.00001f)
        assertEquals(1f, crop.height, 0.00001f)
    }

    @Test fun alreadyVerticalSourceUsesWholeImage() {
        val crop = NovaVideoFraming.cover(576, 1024, 576, 1024)
        assertEquals(0f, crop.left, 0.00001f)
        assertEquals(0f, crop.top, 0.00001f)
        assertEquals(1f, crop.width, 0.00001f)
        assertEquals(1f, crop.height, 0.00001f)
    }

    @Test fun tallSourceCropsHeightWithoutDistortingSubject() {
        val crop = NovaVideoFraming.cover(400, 1000, 576, 1024)
        assertEquals(0f, crop.left, 0.00001f)
        assertEquals(1f, crop.width, 0.00001f)
        assertTrue(crop.height < 1f)
        assertEquals((1f - crop.height) / 2f, crop.top, 0.00001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDimensionIsRejected() {
        NovaVideoFraming.cover(0, 512, 576, 1024)
    }
}
