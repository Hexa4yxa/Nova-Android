package ai.nova.director

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaCouncilPromptsTest {
    @Test fun oneModelHasFiniteThreeStageBudgets() {
        assertEquals(280, NovaCouncilPrompts.PLAN_TOKENS)
        assertEquals(150, NovaCouncilPrompts.CRITIC_TOKENS)
        assertEquals(420, NovaCouncilPrompts.FINAL_TOKENS)
        assertTrue(NovaCouncilPrompts.PLAN_TOKENS + NovaCouncilPrompts.CRITIC_TOKENS +
            NovaCouncilPrompts.FINAL_TOKENS < 1000)
    }

    @Test fun stagePromptsUseExplicitlyDifferentRoles() {
        val plan = NovaCouncilPrompts.plan("Зеркало оживает")
        val criticism = NovaCouncilPrompts.critique("Зеркало оживает", "кадр за кадром")
        val result = NovaCouncilPrompts.finalPlan(
            "Зеркало оживает", "кадр за кадром", "не хватает интриги")
        assertTrue(plan.contains("РЕЖИССЁР"))
        assertTrue(criticism.contains("ВНУТРЕННИЙ ГОЛОС"))
        assertTrue(criticism.contains("не выдумывай недостатки"))
        assertTrue(criticism.contains("Твоя собственная критика может ошибаться"))
        assertTrue(result.contains("ИСПРАВЛЕННЫЙ ПЛАН"))
        assertTrue(result.contains("не хватает интриги"))
        assertTrue(result.contains("Зеркало оживает"))
    }

    @Test fun textCriticOnlyChecksVisibleTextFeatures() {
        val plan = "Видео 9:16. Сцена 0–5 секунд: зеркало, тишина. " +
            "Затем камера медленно раскрывает отражение, которое не повторяет движение героя. " +
            "К финалу напряжение нарастает, но не превращается в дешёвый скример."
        val checks = NovaCouncilPrompts.checkText(plan)
        assertEquals(4, checks.size)
        assertTrue(checks.all { it.passed })
        assertEquals(listOf("story","timing","video","audio"), checks.map { it.id })
    }

    @Test fun durationFromUserIsReflectedInBothPrompts() {
        val task = "Придумай хоррор на 30 секунд: отражение отстаёт"
        assertEquals(30, NovaCouncilPrompts.requestedDuration(task))
        assertTrue(NovaCouncilPrompts.plan(task).contains("Формат 9:16, 30 секунд"))
        assertTrue(NovaCouncilPrompts.plan(task).contains("0–3, 3–10, 10–20, 20–30"))
        assertTrue(NovaCouncilPrompts.finalPlan(task, "Идея", "Убрать писк")
            .contains("20–30"))
        assertEquals(null, NovaCouncilPrompts.requestedDuration("Психологический хоррор"))
    }

    @Test fun textCriticDetectsIncompleteOrGapFilledRuntime() {
        val task = "Сценарий на 30 секунд"
        val full = "Формат 9:16. 0–3 сек: зеркало, звук. " +
            "3–10 сек: взгляд. 10–20 сек: отражение отстаёт. " +
            "20–30 сек: финал, шёпот."
        val incomplete = "Формат 9:16. 0–3 сек: зеркало, звук. " +
            "3–10 сек: взгляд. 10–22 сек: отражение и финал."
        val gap = "Формат 9:16. 0–3 сек: зеркало, звук. " +
            "10–20 сек: отражение. 20–30 сек: финал."
        fun covers(text: String) = NovaCouncilPrompts.checkText(text, task)
            .first { it.id == "coverage" }.passed
        assertTrue(covers(full))
        assertFalse(covers(incomplete))
        assertFalse(covers(gap))
        assertEquals(5, NovaCouncilPrompts.checkText(full, task).size)
    }

    @Test fun emptyDraftCannotPassQualityGate() {
        val checks = NovaCouncilPrompts.checkText("Красиво!")
        assertFalse(checks.any { it.passed })
    }
}
