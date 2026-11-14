package ai.nova.director

/**
 * Normalized center crop to fill a vertical output without black bands or stretching.
 * Uses the image inside a selected comic tile, not the entire comic sheet.
 */
internal data class VideoCrop(val left: Float, val top: Float, val width: Float, val height: Float)

internal object NovaVideoFraming {
    fun cover(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): VideoCrop {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0) {
            "Размер изображения и видео должен быть положительным."
        }
        val inputAspect = sourceWidth.toDouble() / sourceHeight
        val outputAspect = targetWidth.toDouble() / targetHeight
        return if (inputAspect > outputAspect) {
            val cropWidth = (outputAspect / inputAspect).toFloat()
            VideoCrop((1f - cropWidth) / 2f, 0f, cropWidth, 1f)
        } else {
            val cropHeight = (inputAspect / outputAspect).toFloat()
            VideoCrop(0f, (1f - cropHeight) / 2f, 1f, cropHeight)
        }
    }
}
