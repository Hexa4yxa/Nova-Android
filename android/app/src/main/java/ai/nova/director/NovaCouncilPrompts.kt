package ai.nova.director

/**
 * First offline Director Council prototype. All three roles run SEQUENTIALLY
 * through the single loaded model. This does NOT claim a separate second LLM.
 *
 * Pure functions so the rules can be tested without Android or JNI.
 */
internal object NovaCouncilPrompts {
    const val MAX_TASK_LENGTH = 1200
    // Bounded for a phone; enough tokens for four complete, concise time blocks.
    const val PLAN_TOKENS = 280
    const val CRITIC_TOKENS = 150
    const val FINAL_TOKENS = 420

    fun requestedDuration(task: String): Int? =
        Regex("""(\d{1,2})\s*(?:секунд[а-я]*|сек\.?|с\b)""", RegexOption.IGNORE_CASE)
            .find(task)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 12..90 }

    private fun timeGrid(task: String): String {
        val total = requestedDuration(task) ?: 30
        val first = (total / 10).coerceAtLeast(2)
        val second = (total / 3).coerceAtLeast(first + 2)
        val third = (total * 2 / 3).coerceAtLeast(second + 2)
        return "0–$first, $first–$second, $second–$third, $third–$total сек."
    }

    fun plan(task: String): String = """
        [РЕЖИССЁРСКИЙ СОВЕТ — ЭТАП 1/3: РЕЖИССЁР]
        Ты NOVA, режиссёр. Предложи конкретный сценарно-монтажный план.
        Задача пользователя:
        {{TASK}}
        Результат должен быть КОРОТКИМ и ПОЛНЫМ.
        Первая строка: «Формат 9:16, {{DURATION}} секунд».
        Затем ЧЕТЫРЕ коротких пункта с таймкодами: {{TIMELINE}}
        В каждом — одно действие и один приём камеры ИЛИ звука.
        Последний пункт — развязка и конец на {{DURATION}}-й секунде.
        Никаких незавершённых пунктов, длинных вступлений и пересказов задачи.
        Если исходных видеокадров нет, не утверждай, что ты их посмотрела.
        Учитывай реальные ограничения телефона и доступные простые эффекты.
        Дай реализуемую версию с 3–6 сценами даже при невысоком качестве изображений.
        Это пока план, не готовое видео. Пиши по-русски, без внутренних рассуждений.
    """.trimIndent()
        .replace("{{TASK}}", task.trim())
        .replace("{{DURATION}}", (requestedDuration(task) ?: 30).toString())
        .replace("{{TIMELINE}}", timeGrid(task))

    fun critique(task: String, draft: String): String = """
        [РЕЖИССЁРСКИЙ СОВЕТ — ЭТАП 2/3: ВНУТРЕННИЙ ГОЛОС]
        Теперь выступи как строгий коллега-режиссёр, не соглашайся автоматически.
        Задача: 
        {{TASK}}
        Черновой план:
        {{DRAFT}}
        Проверь план независимо: найди не более двух РЕАЛЬНЫХ художественных
        слабостей и один вопрос, который может изменить режиссёрское решение.
        Если план разумен, так и скажи: не выдумывай недостатки ради критики.
        Объясни риск для зрителя и предложи выполнимое исправление.
        Твоя собственная критика может ошибаться: отличай гипотезу от факта.
        Не придумывай просмотренное видео, измеренный звук или качество рендера:
        сейчас проверяется только текст. Не задерживай работу из-за мелочей.
        Не останавливай проект: режиссёр обязан закончить лучший доступный план.
    """.trimIndent().replace("{{TASK}}", task.trim()).replace("{{DRAFT}}", draft.take(4500))

    fun finalPlan(task: String, draft: String, review: String): String = """
        [РЕЖИССЁРСКИЙ СОВЕТ — ЭТАП 3/3: РЕЖИССЁР]
        Ты NOVA, главный режиссёр. Прими или обоснованно отклони предложения критика.
        Верни готовый, чёткий ИСПРАВЛЕННЫЙ ПЛАН, а не сообщение о размышлениях.
        Задача: 
        {{TASK}}
        Изначальный план:
        {{DRAFT}}
        Замечания второго голоса:
        {{REVIEW}}
        Ответ максимально краткий, без длинных вступлений:
        «Формат 9:16, {{DURATION}} секунд»;
        ЧЕТЫРЕ законченных строки сцен по таймкодам {{TIMELINE}}
        (в каждой действие + камера/звук); затем по одной короткой строке
        «Улучшено» и «Нужно для производства».
        Финальная сцена ОБЯЗАТЕЛЬНО заканчивается на {{DURATION}}-й секунде.
        Не бросай сцену посреди предложения.
        Нет бесконечных доработок: максимум две осмысленные правки,
        затем финализируй выполнимый план со списком ближайших действий.
        Этот совет проверяет только текст, не кадры и не звук.
        В «Проектах» есть отдельный офлайн-рендер видеокомиксов, но
        сам режиссёрский совет не запускает его автоматически.
        Не утверждай, что видео уже смонтировано. Решение за пользователем.
    """.trimIndent()
        .replace("{{TASK}}", task.trim())
        .replace("{{DRAFT}}", draft.take(2200))
        .replace("{{REVIEW}}", review.take(1100))
        .replace("{{DURATION}}", (requestedDuration(task) ?: 30).toString())
        .replace("{{TIMELINE}}", timeGrid(task))

    data class QualityCheck(val id: String, val label: String, val passed: Boolean)

    /**
     * These are objective TEXT checklist checks, not claims about any rendered video.
     */
    fun checkText(text: String, task: String = ""): List<QualityCheck> {
        val content = text.lowercase()
        val checks = mutableListOf(
            QualityCheck("story", "Есть достаточно подробный план", text.trim().length >= 160),
            QualityCheck("timing", "Указаны секунды или таймкоды",
                Regex("""\d+\s*(?:[–-]\s*\d+\s*)?(?:с\b|сек\b|секунд)|\d{1,2}:\d{2}""").containsMatchIn(content)),
            QualityCheck("video", "Учтён вертикальный формат",
                content.contains("9:16") || content.contains("вертикал")),
            QualityCheck("audio", "Указаны звук или атмосфера",
                listOf("звук", "тишин", "музык", "озвуч", "шорох", "шум", "атмосфер").any { content.contains(it) })
        )
        // Unlike a single timecode, this verifies no missing ending or large gaps.
        val requested = requestedDuration(task)
        if (requested != null) {
            val ranges = Regex("""(\d{1,2})\s*[–—-]\s*(\d{1,2})""")
                .findAll(content)
                .mapNotNull { match ->
                    val start = match.groupValues[1].toIntOrNull()
                    val end = match.groupValues[2].toIntOrNull()
                    if (start != null && end != null && end > start) start to end else null
                }.sortedBy { it.first }.toList()
            var coveredThrough = -1
            var complete = ranges.isNotEmpty() && ranges.first().first == 0
            for ((start, end) in ranges) {
                if (start > coveredThrough + 1) complete = false
                coveredThrough = maxOf(coveredThrough, end)
            }
            checks.add(QualityCheck("coverage", "Сцены охватывают все $requested секунд",
                complete && coveredThrough == requested))
        }
        return checks
    }
}
