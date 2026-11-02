package ai.nova.director

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect

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

    // Stream tokens as generated instead of waiting for the whole reply.
    suspend fun chat(message: String, onToken: (String) -> Unit): String {
        val answer = StringBuilder()
        engine.sendUserPrompt(message, 256).collect { token ->
            answer.append(token)
            onToken(token)
        }
        return answer.toString()
    }

    fun cancelChat() = engine.requestCancel()

    fun modelInfo(): String = engine.modelInfo()

    fun errorDetail(): String {
        val current = engine.state.value
        return if (current is InferenceEngine.State.Error) {
            current.exception.message ?: current.exception.javaClass.simpleName
        } else ""
    }

    fun state(): String =
        engine.state.value.javaClass.simpleName
}
