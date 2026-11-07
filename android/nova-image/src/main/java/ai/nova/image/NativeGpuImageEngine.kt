package ai.nova.image

/**
 * OPTIONAL native OpenCL renderer. It is isolated from NativeImageEngine (CPU).
 * A driver/load failure here must never prevent CPU images or chat from working.
 */
object NativeGpuImageEngine {
    private val error: String? = try {
        System.loadLibrary("nova-image-gpu")
        null
    } catch (t: Throwable) {
        (t.message ?: t.javaClass.simpleName).take(350)
    }

    fun libraryReady(): Boolean = error == null
    fun libraryError(): String? = error

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
        check(libraryReady()) { error ?: "Библиотека GPU недоступна" }
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
