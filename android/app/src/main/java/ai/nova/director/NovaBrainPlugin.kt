package ai.nova.director
import androidx.activity.result.ActivityResult
import com.getcapacitor.annotation.ActivityCallback
import android.app.Activity
import android.content.Intent

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@CapacitorPlugin(name = "NovaBrain")
class NovaBrainPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generating = AtomicBoolean(false)

    private val brain: NovaEngine by lazy {
        NovaEngine(context)
    }

    @PluginMethod
    fun prepare(call: PluginCall) {
        val model = java.io.File(context.filesDir, "nova-model.gguf")

        if (!model.isFile) {
            importModel(call)
            return
        }

        scope.launch {
            try {
                brain.loadModel(model.absolutePath)
                val result = JSObject()
                result.put("loaded", true)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Ошибка запуска модели: " + e.toString(), e)
            }
        }
    }

    @PluginMethod
    fun status(call: PluginCall) {
        try {
            val result = JSObject()
            result.put("bridge", "ready")
            result.put("engine", brain.state())
            result.put("detail", brain.errorDetail())
            result.put("generating", generating.get())
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("NOVA status failed", e)
        }
    }

    @PluginMethod
    fun importModel(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(call, intent, "modelSelected")
    }

    @ActivityCallback
    private fun modelSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Выбор модели отменён")
            return
        }

        scope.launch {
            try {
                val destination = java.io.File(context.filesDir, "nova-model.gguf")
                val temporary = java.io.File(context.filesDir, "nova-model.tmp")

                try {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Не удалось открыть GGUF" }
                        temporary.outputStream().use { output ->
                            input.copyTo(output, 1024 * 1024)
                        }
                    }

                    if (!temporary.renameTo(destination)) {
                        throw IllegalStateException("Не удалось сохранить модель")
                    }
                } finally {
                    temporary.delete()
                }

                brain.loadModel(destination.absolutePath)

                val response = JSObject()
                response.put("loaded", true)
                response.put("path", destination.absolutePath)
                call.resolve(response)
            } catch (e: Exception) {
                call.reject("Ошибка импорта модели: " + e.toString(), e)
            }
        }
    }

    @PluginMethod
    fun loadModel(call: PluginCall) {
        val path = call.getString("path")
        if (path.isNullOrBlank()) {
            call.reject("Model path required")
            return
        }

        scope.launch {
            try {
                brain.loadModel(path)
                val result = JSObject()
                result.put("loaded", true)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Model loading failed", e)
            }
        }
    }

    @PluginMethod
    fun chat(call: PluginCall) {
        val message = call.getString("message")
        if (message.isNullOrBlank()) {
            call.reject("Message required")
            return
        }
        if (!generating.compareAndSet(false, true)) {
            call.reject("NOVA is already generating a reply")
            return
        }
        val requestId = call.getString("requestId") ?: UUID.randomUUID().toString()

        scope.launch {
            try {
                val answer = brain.chat(message) { token ->
                    val update = JSObject()
                    update.put("requestId", requestId)
                    update.put("token", token)
                    notifyListeners("token", update)
                }
                val result = JSObject()
                result.put("answer", answer)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("NOVA chat failed: " + (e.message ?: e.javaClass.simpleName), e)
            } finally {
                generating.set(false)
            }
        }
    }

    @PluginMethod
    fun cancelChat(call: PluginCall) {
        brain.cancelChat()
        val result = JSObject()
        result.put("cancelRequested", true)
        call.resolve(result)
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
