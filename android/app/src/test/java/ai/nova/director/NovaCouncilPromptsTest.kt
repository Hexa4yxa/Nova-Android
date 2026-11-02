package ai.nova.director

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaCouncilPromptsTest {
    @Test fun oneModelHasFiniteThreeStageBudgets() {
        assertEquals(200, NovaCouncilPrompts.PLAN_TOKENS)
        assertEquals(160, NovaCouncilPrompts.CRITIC_TOKENS)
        assertEquals(320, NovaCouncilPrompts.FINAL_TOKENS)
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

    @Test fun emptyDraftCannotPassQualityGate() {
        val checks = NovaCouncilPrompts.checkText("Красиво!")
        assertFalse(checks.any { it.passed })
    }
}
