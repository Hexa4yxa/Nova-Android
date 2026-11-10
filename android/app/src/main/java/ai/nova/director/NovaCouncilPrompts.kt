package ai.nova.director

/**
 * First offline Director Council prototype. All three roles run SEQUENTIALLY
 * through the single loaded model. This does NOT claim a separate second LLM.
 *
 * Pure functions so the rules can be tested without Android or JNI.
 */
internal object NovaCouncilPrompts {
    const val MAX_TASK_LENGTH = 1200
    const val PLAN_TOKENS = 200
    const val CRITIC_TOKENS = 160
    const val FINAL_TOKENS = 320

    fun plan(task: String): String = """
        [РЕЖИССЁРСКИЙ СОВЕТ — ЭТАП 1/3: РЕЖИССЁР]
        Ты NOVA, режиссёр. Предложи конкретный сценарно-монтажный план.
        Задача пользователя:
        {{TASK}}
        Укажи хук в начале, последовательность сцен с примерными секундами,
        движение камеры, звук и финальный эмоциональный эффект.
        Для TikTok/Shorts предлагай вертикальный кадр 9:16.
        Если исходных видеокадров нет, не утверждай, что ты их посмотрела.
        Учитывай реальные ограничения телефона и доступные простые эффекты.
        Дай реализуемую версию с 3–6 сценами даже при невысоком качестве изображений.
        Это пока план, не готовое видео. Пиши по-русски, без внутренних рассуждений.
    """.trimIndent().replace("{{TASK}}", task.trim())

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
        Формат ответа: 1) хук, 2) сцены с секундами, 3) звук и атмосфера,
        4) что улучшено, 5) что для реализации ещё нужно.
        Нет бесконечных доработок: максимум две осмысленные правки,
        затем финализируй выполнимый план со списком ближайших действий.
        Этот совет проверяет только текст, не кадры и не звук.
        В «Проектах» есть отдельный офлайн-рендер видеокомиксов, но
        сам режиссёрский совет не запускает его автоматически.
        Не утверждай, что видео уже смонтировано. Решение за пользователем.
    """.trimIndent()
        .replace("{{TASK}}", task.trim())
        .replace("{{DRAFT}}", draft.take(4500))
        .replace("{{REVIEW}}", review.take(3500))

    data class QualityCheck(val id: String, val label: String, val passed: Boolean)

    /**
     * These are objective TEXT checklist checks, not claims about any rendered video.
     */
    fun checkText(text: String): List<QualityCheck> {
        val content = text.lowercase()
        return listOf(
            QualityCheck("story", "Есть достаточно подробный план", text.trim().length >= 160),
            QualityCheck("timing", "Указаны секунды или таймкоды",
                Regex("""\d+\s*(?:[–-]\s*\d+\s*)?(?:с\b|сек\b|секунд)|\d{1,2}:\d{2}""").containsMatchIn(content)),
            QualityCheck("video", "Учтён вертикальный формат",
                content.contains("9:16") || content.contains("вертикал")),
            QualityCheck("audio", "Указаны звук или атмосфера",
                listOf("звук", "тишин", "музык", "озвуч", "шорох", "шум", "атмосфер").any { content.contains(it) })
        )
    }
}
