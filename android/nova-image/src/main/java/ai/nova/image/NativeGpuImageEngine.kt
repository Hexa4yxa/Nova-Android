package ai.nova.image

/**
 * OPTIONAL native OpenCL renderer. It is isolated from NativeImageEngine (CPU).
 * A driver/load failure here must never prevent CPU images or chat from working.
 */
object NativeGpuImageEngine {
    // Merely opening NOVA or Brains must never load the experimental driver.
    // Only the owner-approved GPU test may attempt native initialization.
    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null
    private var loadAttempted = false

    @Synchronized
    fun enableForApprovedTest(): Boolean {
        if (!loadAttempted) {
            loadAttempted = true
            try {
                System.loadLibrary("nova-image-gpu")
                loaded = true
            } catch (t: Throwable) {
                loadError = (t.message ?: t.javaClass.simpleName).take(350)
            }
        }
        return loaded
    }

    fun libraryReady(): Boolean = loaded
    fun libraryError(): String? = loadError

    /** The real OpenCL backend registered in stable-diffusion.cpp, not merely an Android driver. */
    fun gpuDevice(): String = if (libraryReady()) try {
        nativeGpuDevice().trim()
    } catch (_: Throwable) { "" } else ""

    @Synchronized
    fun generate(
        modelPath: String,
        prompt: String,
        negativePrompt: String,
        size: Int,
        steps: Int,
        seed: Long
    ): ByteArray {
        check(libraryReady()) { libraryError() ?: "GPU тест ещё не запускался" }
        check(gpuDevice().isNotBlank()) { "OpenCL обнаружен телефоном, но движок SD не зарегистрировал GPU" }
        require(size in listOf(256, 512))
        require(steps in 1..30)
        return nativeGenerate(modelPath, prompt, negativePrompt, size, steps, seed)
    }

    fun progress(): IntArray = if (libraryReady()) nativeProgress() else intArrayOf(0, 0)
    fun timings(): LongArray = if (libraryReady()) nativeTimings() else longArrayOf(0L, 0L)
    fun cancel(): Boolean = libraryReady() && nativeCancel()

    private external fun nativeGpuDevice(): String
    private external fun nativeGenerate(
        modelPath: String, prompt: String, negativePrompt: String,
        size: Int, steps: Int, seed: Long
    ): ByteArray
    private external fun nativeCancel(): Boolean
    private external fun nativeProgress(): IntArray
    private external fun nativeTimings(): LongArray
}
