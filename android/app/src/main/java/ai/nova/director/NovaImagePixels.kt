package ai.nova.director

/** The Local Dream /generate complete event contains RGB, not PNG/JPG. */
internal object NovaImagePixels {
    fun toArgb(rgb: ByteArray, width: Int, height: Int): IntArray {
        require(width in 128..512 && height in 128..512) { "Недопустимое разрешение" }
        require(rgb.size == width * height * 3) { "Неверная длина массива RGB" }
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val j = i * 3
            pixels[i] = (0xff shl 24) or
                ((rgb[j].toInt() and 255) shl 16) or
                ((rgb[j + 1].toInt() and 255) shl 8) or
                (rgb[j + 2].toInt() and 255)
        }
        return pixels
    }
}
