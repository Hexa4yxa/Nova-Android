package ai.nova.director

import org.junit.Assert.assertTrue
import org.junit.Test

class NovaImageWireTest {
    private fun stub(width: Int = 512, height: Int = 512): ByteArray {
        val bytes = ByteArray(40)
        byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
            .copyInto(bytes, 0)
        bytes[11] = 13 // first chunk length: 13
        byteArrayOf(73, 72, 68, 82).copyInto(bytes, 12)
        for ((offset, value) in listOf(16 to width, 20 to height)) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        return bytes
    }

    @Test fun acceptsMatchingPngHeader() {
        NovaImageWire.validatePng(stub(), 512, 512)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDifferentDimensions() {
        NovaImageWire.validatePng(stub(256, 256), 512, 512)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCorruptedSignature() {
        val bytes = stub()
        bytes[0] = 0
        NovaImageWire.validatePng(bytes, 512, 512)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedResponse() {
        NovaImageWire.validatePng(ByteArray(8_000_001), 512, 512)
    }
}
