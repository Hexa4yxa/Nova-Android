package ai.nova.director

import org.junit.Assert.*
import org.junit.Test

class NovaImagePixelsTest {
    @Test fun rgbDecodesWithOpaqueAlphaAndCorrectChannels() {
        val rgb = ByteArray(128*128*3)
        rgb[0] = 255.toByte()
        rgb[1] = 2
        rgb[2] = 128.toByte()
        val result = NovaImagePixels.toArgb(rgb, 128, 128)
        assertEquals(0xffff0280.toInt(), result[0])
        assertEquals(0xff000000.toInt(), result[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedRGB() {
        NovaImagePixels.toArgb(ByteArray(12), 128, 128)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversize() {
        NovaImagePixels.toArgb(ByteArray(1), 1024, 1024)
    }
}
