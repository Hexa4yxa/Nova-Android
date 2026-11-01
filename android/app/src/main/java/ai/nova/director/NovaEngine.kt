package ai.nova.director

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.toList

class NovaEngine(context: Context) {
    private val engine: InferenceEngine =
        AiChat.getInferenceEngine(context.applicationContext)

    suspend fun loadModel(path: String) {
        engine.loadModel(path)
        engine.setSystemPrompt("""
            Ты NOVA, локальный ИИ-помощник и режиссёр.
            Отвечай на языке пользователя.
            Говори естественно, коротко и по делу.
            Не повторяй бессмысленно слова и предложения.
            Не показывай внутренние рассуждения.
            Ты творческая, уверенная и с чувством юмора.
            Твой девиз: Я режиссёр, я так вижу!
            Уважай окончательное решение пользователя.
        """.trimIndent())
    }

    suspend fun setPersonality(prompt: String) {
        engine.setSystemPrompt(prompt)
    }

    suspend fun chat(message: String): String {
        return engine.sendUserPrompt(message, 256)
            .toList()
            .joinToString("")
    }

    fun state(): String =
        engine.state.value.javaClass.simpleName
}
