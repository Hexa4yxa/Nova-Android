package ai.nova.director

/**
 * Preflight for a multi-GB offline diffusion model running on a phone.
 *
 * This is a conservative admission check, NOT a promise that inference
 * cannot run out of memory. The actual Android low-memory flag always wins.
 * The heavy model is unloaded from memory after every image generation.
 */
internal object NovaImageSafety {
    const val MIN_FREE_MIB_256 = 1800L
    const val MIN_FREE_MIB_512 = 3000L

    fun warning(freeMib: Long, systemLowMemory: Boolean, thermalSevere: Boolean, size: Int): String? {
        require(size == 256 || size == 512) { "Unsupported image size" }
        if (thermalSevere) {
            return "Телефон слишком нагрелся для генерации. Дай ему остыть и попробуй снова."
        }
        if (systemLowMemory) {
            return "Android сообщает о нехватке оперативной памяти. Закрой тяжёлые приложения и повтори."
        }
        val minFree = if (size == 512) MIN_FREE_MIB_512 else MIN_FREE_MIB_256
        if (freeMib < minFree) {
            return "Свободно $freeMib МиБ RAM, а для безопасного запуска " +
                "${size}×${size} требуется хотя бы $minFree МиБ. " +
                "Освободи память или выбери 256×256."
        }
        return null
    }
}
