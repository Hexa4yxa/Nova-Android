package ai.nova.director

/**
 * One ownership gate shared by text inference and image diffusion.
 *
 * The phone cannot safely keep a multi-GB Gemma context and Stable Diffusion
 * resident at the same time. Admission is atomic across both Capacitor plugins,
 * not inferred from JavaScript buttons or from separate plugin busy flags.
 */
internal object NovaComputeGate {
    private val lock = Any()
    private var textUsers = 0
    private var imageReserved = false
    private var imageRunning = false

    fun enterText(): Boolean = synchronized(lock) {
        if (imageReserved) false
        else {
            textUsers += 1
            true
        }
    }

    fun leaveText() = synchronized(lock) {
        check(textUsers > 0) { "NOVA compute gate: unbalanced text release" }
        textUsers -= 1
    }

    fun reserveImage(): Boolean = synchronized(lock) {
        if (imageReserved || textUsers > 0) false
        else {
            imageReserved = true
            true
        }
    }

    /** This succeeds once, after the caller has reserved image memory. */
    fun beginImage(): Boolean = synchronized(lock) {
        if (!imageReserved || imageRunning) false
        else {
            imageRunning = true
            true
        }
    }

    fun finishImage() = synchronized(lock) {
        imageRunning = false
        imageReserved = false
    }

    /** Called on early abort before the native generator starts. */
    fun releaseUnusedImage(): Boolean = synchronized(lock) {
        if (imageRunning) false
        else {
            imageReserved = false
            true
        }
    }

    fun isImageReserved(): Boolean = synchronized(lock) { imageReserved }
    fun isImageRunning(): Boolean = synchronized(lock) { imageRunning }
}
