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
    }

    suspend fun setPersonality(prompt: String) {
        engine.setSystemPrompt(prompt)
    }

    suspend fun chat(message: String): String {
        return engine.sendUserPrompt(message, 512)
            .toList()
            .joinToString("")
    }

    fun state(): String =
        engine.state.value.javaClass.simpleName
}
