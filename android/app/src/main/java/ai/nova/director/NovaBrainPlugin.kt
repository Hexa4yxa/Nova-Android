package ai.nova.director
import androidx.activity.result.ActivityResult
import com.getcapacitor.annotation.ActivityCallback
import android.app.Activity
import android.content.Intent

import com.getcapacitor.JSObject
import com.getcapacitor.JSArray
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
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
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var councilPhase = ""

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
            result.put("model", brain.modelInfo())
            result.put("detail", brain.errorDetail())
            result.put("generating", generating.get())
            result.put("councilPhase", councilPhase)
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
        stopRequested.set(false)
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


    /**
     * Director Council v1 uses the ONE loaded GGUF in three bounded, sequential
     * passes. No second AI, model reload, network, or native memory concurrency.
     */
    @PluginMethod
    fun directorCouncil(call: PluginCall) {
        val task = call.getString("task")?.trim().orEmpty()
        if (task.isBlank() || task.length > NovaCouncilPrompts.MAX_TASK_LENGTH) {
            call.reject("Задача должна содержать от 1 до 1200 символов")
            return
        }
        if (!generating.compareAndSet(false, true)) {
            call.reject("NOVA уже выполняет другую задачу")
            return
        }
        stopRequested.set(false)
        val requestId = call.getString("requestId") ?: UUID.randomUUID().toString()

        scope.launch {
            try {
                check(brain.state() == "ModelReady") { "Сначала загрузите GGUF-модель" }

                fun ensureRunning() {
                    if (stopRequested.get()) throw CancellationException("Совет остановлен пользователем")
                }

                councilPhase = "plan"
                val plan = brain.chat(
                    NovaCouncilPrompts.plan(task),
                    NovaCouncilPrompts.PLAN_TOKENS
                ) { token -> emitCouncilToken(requestId, "plan", token) }
                ensureRunning()

                councilPhase = "critic"
                val review = brain.chat(
                    NovaCouncilPrompts.critique(task, plan),
                    NovaCouncilPrompts.CRITIC_TOKENS
                ) { token -> emitCouncilToken(requestId, "critic", token) }
                ensureRunning()

                councilPhase = "final"
                val finalPlan = brain.chat(
                    NovaCouncilPrompts.finalPlan(task, plan, review),
                    NovaCouncilPrompts.FINAL_TOKENS
                ) { token -> emitCouncilToken(requestId, "final", token) }
                ensureRunning()

                val checks = NovaCouncilPrompts.checkText(finalPlan)
                saveCouncil(task, plan, review, finalPlan, checks)

                val response = JSObject()
                response.put("plan", plan)
                response.put("critic", review)
                response.put("final", finalPlan)
                val array = JSArray()
                checks.forEach { check ->
                    array.put(JSObject().apply {
                        put("id", check.id)
                        put("label", check.label)
                        put("passed", check.passed)
                    })
                }
                response.put("checks", array)
                response.put("model", brain.modelInfo())
                response.put("method", "single-model-sequential")
                call.resolve(response)
            } catch (e: CancellationException) {
                call.reject("Совет остановлен. Незавершённый результат не сохранён.")
            } catch (e: Exception) {
                call.reject("Ошибка режиссёрского совета: " + (e.message ?: e.javaClass.simpleName), e)
            } finally {
                councilPhase = ""
                generating.set(false)
            }
        }
    }

    private fun emitCouncilToken(requestId: String, stage: String, token: String) {
        val update = JSObject()
        update.put("requestId", requestId)
        update.put("stage", stage)
        update.put("token", token)
        notifyListeners("councilToken", update)
    }

    private fun saveCouncil(
        task: String,
        plan: String,
        critic: String,
        finalPlan: String,
        checks: List<NovaCouncilPrompts.QualityCheck>
    ) {
        val prefs = context.getSharedPreferences("nova_director_council", 0)
        val previous = try {
            JSONArray(prefs.getString("entries", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val updated = JSONArray()
        updated.put(JSONObject().apply {
            put("createdAt", System.currentTimeMillis())
            put("task", task)
            put("plan", plan)
            put("critic", critic)
            put("final", finalPlan)
            put("checksPassed", checks.count { it.passed })
            put("checksTotal", checks.size)
        })
        for (i in 0 until minOf(previous.length(), 7)) {
            updated.put(previous.getJSONObject(i))
        }
        check(prefs.edit().putString("entries", updated.toString()).commit()) {
            "Не удалось сохранить режиссёрский план"
        }
    }

    @PluginMethod
    fun councilHistory(call: PluginCall) {
        try {
            val prefs = context.getSharedPreferences("nova_director_council", 0)
            val entries = JSONArray(prefs.getString("entries", "[]"))
            val response = JSObject()
            response.put("entries", entries)
            call.resolve(response)
        } catch (e: Exception) {
            call.reject("Не удалось открыть историю проектов", e)
        }
    }

    @PluginMethod
    fun cancelChat(call: PluginCall) {
        stopRequested.set(true)
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
