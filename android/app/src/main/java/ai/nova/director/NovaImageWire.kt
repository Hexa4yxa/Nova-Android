package ai.nova.director

/**
 * Validates the basic PNG envelope produced by Local Dream before saving it.
 * Full image decoding is owned by Android Gallery; it is not trusted data.
 */
internal object NovaImageWire {
    private val signature = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        13, 10, 26, 10
    )
    private fun u32(value: ByteArray, offset: Int): Long {
        return ((value[offset].toLong() and 255L) shl 24) or
            ((value[offset + 1].toLong() and 255L) shl 16) or
            ((value[offset + 2].toLong() and 255L) shl 8) or
            (value[offset + 3].toLong() and 255L)
    }

    fun validatePng(bytes: ByteArray, width: Int, height: Int) {
        require(width in 128..512 && height in 128..512) { "Unsupported result dimensions" }
        require(bytes.size in 33..8_000_000) { "Invalid PNG length" }
        require(signature.indices.all { bytes[it] == signature[it] }) { "Invalid PNG signature" }
        require(u32(bytes, 8) == 13L &&
            bytes[12] == 'I'.code.toByte() &&
            bytes[13] == 'H'.code.toByte() &&
            bytes[14] == 'D'.code.toByte() &&
            bytes[15] == 'R'.code.toByte()) { "PNG IHDR missing" }
        require(u32(bytes, 16) == width.toLong() && u32(bytes, 20) == height.toLong()) {
            "PNG dimensions do not match generation response"
        }
    }
}
