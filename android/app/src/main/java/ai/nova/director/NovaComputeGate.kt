package ai.nova.director

/**
 * Atomic ownership of heavyweight offline model memory.
 *
 * Text inference and diffusion may not overlap. An image *reservation* expires
 * after a short idle interval if its WebView request disappears before JNI runs,
 * e.g. after page reload. Once native inference has started, it NEVER expires
 * automatically: only its real completion may free image memory.
 */
internal object NovaComputeGate {
    private val lock = Any()
    private var textUsers = 0
    private var imageReserved = false
    private var imageRunning = false
    private var imageReservedAtNs = 0L

    // The small gap between releasing Gemma and starting diffusion should take
    // seconds. A stale reservation cannot permanently lock the chat.
    private const val IDLE_RESERVATION_NS = 120_000_000_000L

    private fun expireAbandonedLocked() {
        if (imageReserved && !imageRunning &&
            imageReservedAtNs != 0L &&
            System.nanoTime() - imageReservedAtNs > IDLE_RESERVATION_NS) {
            imageReserved = false
            imageReservedAtNs = 0L
        }
    }

    fun enterText(): Boolean = synchronized(lock) {
        expireAbandonedLocked()
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
        expireAbandonedLocked()
        if (imageReserved || textUsers > 0) false
        else {
            imageReserved = true
            imageReservedAtNs = System.nanoTime()
            true
        }
    }

    /** Start only from a live reservation, never while a text task is active. */
    fun beginImage(): Boolean = synchronized(lock) {
        expireAbandonedLocked()
        if (!imageReserved || imageRunning || textUsers > 0) false
        else {
            imageRunning = true
            true
        }
    }

    fun finishImage() = synchronized(lock) {
        imageRunning = false
        imageReserved = false
        imageReservedAtNs = 0L
    }

    /**
     * Used only when generation never started; an active native generation
     * cannot be unlocked by a stale WebView.
     */
    fun releaseUnusedImage(): Boolean = synchronized(lock) {
        if (imageRunning) false
        else {
            imageReserved = false
            imageReservedAtNs = 0L
            true
        }
    }

    fun isImageReserved(): Boolean = synchronized(lock) {
        expireAbandonedLocked()
        imageReserved
    }

    fun isImageRunning(): Boolean = synchronized(lock) { imageRunning }
}
