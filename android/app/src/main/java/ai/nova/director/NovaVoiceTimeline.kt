package ai.nova.director

/**
 * Places measured offline TTS clips on the existing film timeline.
 *
 * TTS speed varies across installed engines. A spoken line is not necessarily
 * five seconds long merely because its scene is five seconds long. Keep every
 * synthesized word, allow a small pre-roll at scene changes, and preserve a
 * natural breath between utterances. Never truncate speech to fit one panel.
 */
internal object NovaVoiceTimeline {
    const val OUTPUT_RATE = 44100
    private const val BREATH_FRAMES = OUTPUT_RATE / 4   // 250 ms
    private const val EARLY_FRAMES = OUTPUT_RATE * 3 / 5 // 600 ms
    private const val TAIL_FRAMES = OUTPUT_RATE / 10    // 100 ms

    fun plan(anchorsSeconds: List<Int>, lengths: List<Int>, movieFrames: Int): IntArray {
        require(movieFrames > 0 && anchorsSeconds.isNotEmpty() &&
            anchorsSeconds.size == lengths.size) { "Неверные параметры голосовой дорожки." }
        require(anchorsSeconds.first() >= 0 &&
            anchorsSeconds.zipWithNext().all { (a, b) -> a <= b } &&
            lengths.all { it > 0 }) { "Неверная длительность реплик." }
        val needed = lengths.sumOf { it.toLong() } +
            (lengths.size - 1).toLong() * BREATH_FRAMES + TAIL_FRAMES
        require(needed <= movieFrames.toLong()) {
            "Текст озвучки не помещается в фильм без обрезания слов. Сократи реплики."
        }

        val starts = IntArray(lengths.size)
        for (i in lengths.indices) {
            val anchor = anchorsSeconds[i].toLong() * OUTPUT_RATE
            val desired = if (i == 0) anchor else
                (anchor - EARLY_FRAMES).coerceAtLeast(0)
            val previousEnd = if (i == 0) 0L else
                starts[i - 1].toLong() + lengths[i - 1] + BREATH_FRAMES
            starts[i] = maxOf(desired, previousEnd).toInt()
        }
        // If a long sentence would pass the end of the movie, adjust from the
        // end backwards while keeping speech order and the breathing gaps.
        if (starts.last().toLong() + lengths.last() > movieFrames - TAIL_FRAMES) {
            for (i in lengths.indices.reversed()) {
                val latest = if (i == lengths.lastIndex)
                    movieFrames - TAIL_FRAMES - lengths[i]
                else starts[i + 1] - BREATH_FRAMES - lengths[i]
                starts[i] = minOf(starts[i], latest)
            }
        }
        require(starts.first() >= 0 &&
            lengths.indices.all { i ->
                starts[i].toLong() + lengths[i] <= movieFrames - TAIL_FRAMES &&
                (i == 0 || starts[i] >= starts[i - 1] + lengths[i - 1] + BREATH_FRAMES)
            }) { "Не удалось разместить речь без наложения или обрезания слов." }
        return starts
    }
}
