package ai.nova.image

/**
 * Separate native image engine. Never assumes that a text GGUF is an image model.
 * A model is supplied by the user once through NOVA's own document picker.
 */
object NativeImageEngine {
    private val loadError: String?
    init {
        loadError = try {
            System.loadLibrary("nova-image")
            null
        } catch (e: UnsatisfiedLinkError) {
            e.message ?: "Native image library missing"
        }
    }

    fun libraryReady(): Boolean = loadError == null
    fun libraryError(): String? = loadError

    /** Returns packed RGB pixels, row major, exactly width*height*3 bytes. */
    @Synchronized
    fun generate(
        modelPath: String,
        prompt: String,
        negativePrompt: String,
        size: Int,
        steps: Int,
        seed: Long
    ): ByteArray {
        check(libraryReady()) { loadError ?: "Native image engine missing" }
        require(size == 256 || size == 512) { "Supported sizes: 256 or 512" }
        require(steps in 1..30) { "Supported steps: 1..30" }
        return nativeGenerate(modelPath, prompt, negativePrompt, size, steps, seed)
    }

    /** Last completed or interrupted request: [model loading ms, sampling ms]. */
    fun timings(): LongArray = if (libraryReady()) nativeTimings() else longArrayOf(0L, 0L)

    fun cancel(): Boolean = libraryReady() && nativeCancel()
    fun progress(): IntArray = if (libraryReady()) nativeProgress() else intArrayOf(0, 0)
    private external fun nativeGenerate(
        modelPath: String, prompt: String, negativePrompt: String,
        size: Int, steps: Int, seed: Long
    ): ByteArray
    private external fun nativeTimings(): LongArray
    private external fun nativeCancel(): Boolean
    private external fun nativeProgress(): IntArray
}
