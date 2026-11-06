package ai.nova.director
import androidx.activity.result.ActivityResult
import com.getcapacitor.annotation.ActivityCallback
import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream

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
    private val modelLoading = AtomicBoolean(false)
    private val exportingModel = AtomicBoolean(false)
    @Volatile private var activeModelId: String = ""
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var councilPhase = ""

    private val brain: NovaEngine by lazy {
        NovaEngine(context)
    }


    // NOVA Brain Manager: each GGUF is copied once, then loaded locally on demand.
    // Other installed Android apps' private data cannot be accessed here.
    private val modelPrefs get() =
        context.getSharedPreferences("nova_brain_manager", 0)

    private fun modelDirectory(): File {
        val dir = File(context.filesDir, "nova_brains")
        check(dir.isDirectory || dir.mkdirs()) { "Не удалось создать папку моделей" }
        return dir
    }

    private fun resolveModel(id: String): File? {
        if (id == "legacy") {
            val file = File(context.filesDir, "nova-model.gguf")
            return file.takeIf { it.isFile }
        }
        if (!Regex("^[0-9a-f-]{36}$").matches(id)) return null
        return File(modelDirectory(), id + ".gguf").takeIf { it.isFile }
    }

    private fun selectedModelId(): String? {
        // A large renamed download is not necessarily a GGUF. Do not autoload invalid files.
        fun usable(id: String): Boolean = resolveModel(id)?.let { isValidGguf(it) } == true
        val id = modelPrefs.getString("selected_id", null)
        if (id != null && usable(id)) return id
        if (usable("legacy")) return "legacy"
        return modelDirectory().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf") }
            ?.map { it.name.removeSuffix(".gguf") }
            ?.filter { usable(it) }
            ?.sorted()
            ?.firstOrNull()
    }

    private fun isValidGguf(file: File): Boolean = try {
        checkGguf(file)
        true
    } catch (_: Exception) {
        false
    }

    private fun checkGguf(file: File) {
        require(file.length() in 100_000L..8_000_000_000L) {
            "Некорректный размер GGUF или недостаточно места"
        }
        val magic = ByteArray(4)
        FileInputStream(file).use { input ->
            require(input.read(magic) == 4) { "Пустой файл модели" }
        }
        require(String(magic, Charsets.US_ASCII) == "GGUF") {
            "Выбранный файл не является GGUF"
        }
    }

    private suspend fun activateModel(id: String) {
        val file = resolveModel(id) ?: error("Модель не найдена в памяти NOVA")
        checkGguf(file)
        if (brain.state() == "ModelReady" && activeModelId == id) return
        val previousId = activeModelId.takeIf { it.isNotEmpty() }
        val oldReady = brain.state() == "ModelReady"
        try {
            when (brain.state()) {
                "ModelReady", "Error" -> brain.unloadModel()
                "Initialized" -> Unit
                else -> error("Движок пока занят: " + brain.state())
            }
            brain.loadModel(file.absolutePath)
            activeModelId = id
            modelPrefs.edit().putString("selected_id", id).apply()
        } catch (failure: Exception) {
            activeModelId = ""
            if (oldReady && previousId != null && previousId != id) {
                try {
                    if (brain.state() == "Error") brain.unloadModel()
                    val oldFile = resolveModel(previousId)
                    if (oldFile != null) {
                        brain.loadModel(oldFile.absolutePath)
                        activeModelId = previousId
                        modelPrefs.edit().putString("selected_id", previousId).apply()
                    }
                } catch (_: Exception) { /* original error is reported */ }
            }
            throw failure
        }
    }

    /**
     * Free the text GGUF before heavy CPU image generation.
     * Selected model stays on disk and is reloaded by prepare() on the next chat.
     */
    @PluginMethod
    fun releaseForImage(call: PluginCall) {
        if (generating.get() || !modelLoading.compareAndSet(false, true)) {
            call.reject("Сначала заверши разговор или режиссёрский совет")
            return
        }
        if (!NovaComputeGate.reserveImage()) {
            modelLoading.set(false)
            call.reject("NOVA уже выполняет другую задачу. Дождись завершения.")
            return
        }
        scope.launch {
            var released = false
            try {
                if (brain.state() == "ModelReady" || brain.state() == "Error") {
                    brain.unloadModel()
                }
                activeModelId = ""
                released = true
                call.resolve(JSObject().apply { put("released", true) })
            } catch (e: Exception) {
                call.reject("Не удалось освободить память: " + e.message, e)
            } finally {
                if (!released) NovaComputeGate.releaseUnusedImage()
                modelLoading.set(false)
            }
        }
    }

    /**
     * Release reservation if the web UI fails before starting native inference.
     * Never unlock memory while a native image job is still running.
     */
    @PluginMethod
    fun finishImageWork(call: PluginCall) {
        val released = NovaComputeGate.releaseUnusedImage()
        call.resolve(JSObject().apply { put("released", released) })
    }

    @PluginMethod
    fun listModels(call: PluginCall) {
        try {
            val selected = selectedModelId()
            val models = JSArray()
            val files = ArrayList<Pair<String, File>>()
            resolveModel("legacy")?.let { files.add("legacy" to it) }
            modelDirectory().listFiles()
                ?.filter { it.isFile && it.name.endsWith(".gguf") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val id = file.name.removeSuffix(".gguf")
                    if (resolveModel(id) != null) files.add(id to file)
                }
            files.forEach { (id, file) ->
                models.put(JSObject().apply {
                    put("id", id)
                    put("name", if (id == "legacy") "Основная сохранённая GGUF"
                        else modelPrefs.getString("name_" + id, file.name))
                    put("bytes", file.length())
                    put("valid", isValidGguf(file))
                    put("selected", id == selected)
                    put("active", activeModelId == id && brain.state() == "ModelReady")
                })
            }
            call.resolve(JSObject().apply {
                put("models", models)
                put("selectedId", selected ?: "")
                put("ready", brain.state() == "ModelReady")
                put("engine", brain.state())
                put("loading", modelLoading.get())
                put("activeId", activeModelId)
            })
        } catch (e: Exception) {
            call.reject("Не удалось получить список моделей: " + e.message, e)
        }
    }

    /**
     * Owner chooses a visible destination (for example Download/NOVA-Models).
     * Export is a COPY: the model in NOVA is never moved or deleted.
     */
    @PluginMethod
    fun exportSavedModel(call: PluginCall) {
        val id = call.getString("id") ?: selectedModelId().orEmpty()
        val model = resolveModel(id)
        if (model == null || !isValidGguf(model)) {
            call.reject("Эта модель не является исправной GGUF. Её нельзя экспортировать как рабочую.")
            return
        }
        if (!exportingModel.compareAndSet(false, true)) {
            call.reject("Экспорт другой модели уже выполняется")
            return
        }
        try {
            val preferredName = if (id == "legacy") "NOVA-text-model.gguf"
                else modelPrefs.getString("name_" + id, "NOVA-text-model.gguf").orEmpty()
            val safeName = preferredName.substringAfterLast('/').substringAfterLast('\\').take(90)
                .let { if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf" }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/octet-stream"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, safeName)
            }
            startActivityForResult(call, intent, "modelExportSelected")
        } catch (e: Exception) {
            exportingModel.set(false)
            call.reject("Не удалось открыть сохранение модели: " + e.message, e)
        }
    }

    @ActivityCallback
    private fun modelExportSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            exportingModel.set(false)
            call.reject("Экспорт отменён")
            return
        }
        scope.launch {
            try {
                val id = call.getString("id") ?: selectedModelId().orEmpty()
                val model = resolveModel(id) ?: error("Исходная модель больше недоступна")
                checkGguf(model)
                model.inputStream().buffered(1024 * 1024).use { input ->
                    context.contentResolver.openOutputStream(uri, "w").use { output ->
                        requireNotNull(output) { "Не удалось создать копию модели" }
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                call.resolve(JSObject().apply {
                    put("exported", true)
                    put("bytes", model.length())
                    put("sourcePreserved", true)
                })
            } catch (e: Exception) {
                call.reject("Экспорт не завершён. Исходная модель сохранена: " + e.message, e)
            } finally {
                exportingModel.set(false)
            }
        }
    }

    @PluginMethod
    fun activateSavedModel(call: PluginCall) {
        val id = call.getString("id").orEmpty()
        if (resolveModel(id) == null) {
            call.reject("Эта GGUF пока не импортирована в NOVA")
            return
        }
        if (!NovaComputeGate.enterText()) {
            call.reject("NOVA рисует изображение. Текстовая модель подождёт.")
            return
        }
        if (generating.get() || !modelLoading.compareAndSet(false, true)) {
            NovaComputeGate.leaveText()
            call.reject("Сначала заверши текущую задачу NOVA")
            return
        }
        scope.launch {
            try {
                activateModel(id)
                call.resolve(JSObject().apply {
                    put("ready", true)
                    put("activeId", id)
                    put("model", brain.modelInfo())
                })
            } catch (e: Exception) {
                call.reject("Не удалось подключить модель: " + e.message, e)
            } finally {
                modelLoading.set(false)
                NovaComputeGate.leaveText()
            }
        }
    }

    @PluginMethod
    fun importAdditionalModel(call: PluginCall) {
        if (!NovaComputeGate.enterText()) {
            call.reject("Графический мозг занят. Импорт GGUF начнём после него.")
            return
        }
        if (generating.get() || !modelLoading.compareAndSet(false, true)) {
            NovaComputeGate.leaveText()
            call.reject("Сначала заверши текущую задачу")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(call, intent, "additionalModelSelected")
        } catch (e: Exception) {
            modelLoading.set(false)
            NovaComputeGate.leaveText()
            call.reject("Не удалось открыть выбор файла: " + e.message, e)
        }
    }

    @ActivityCallback
    private fun additionalModelSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            modelLoading.set(false)
            NovaComputeGate.leaveText()
            call.reject("Выбор модели отменён")
            return
        }
        scope.launch {
            var newFile: File? = null
            var temp: File? = null
            try {
                val filename = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "imported.gguf"
                require(filename.lowercase().endsWith(".gguf")) {
                    "Выбери файл модели с расширением .gguf"
                }
                val id = UUID.randomUUID().toString()
                temp = File(modelDirectory(), id + ".partial")
                val destination = File(modelDirectory(), id + ".gguf")
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Нельзя открыть модель" }
                    temp!!.outputStream().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                checkGguf(temp!!)
                check(temp!!.renameTo(destination)) { "Не удалось завершить импорт" }
                newFile = destination
                modelPrefs.edit().putString("name_" + id, filename.take(120)).apply()
                activateModel(id)
                call.resolve(JSObject().apply {
                    put("imported", true)
                    put("activeId", id)
                    put("name", filename.take(120))
                })
            } catch (e: Exception) {
                newFile?.delete()
                call.reject("Импорт модели не выполнен: " + e.message, e)
            } finally {
                temp?.delete()
                modelLoading.set(false)
                NovaComputeGate.leaveText()
            }
        }
    }

    @PluginMethod
    fun prepare(call: PluginCall) {
        if (!NovaComputeGate.enterText()) {
            call.reject("Сначала дождись окончания генерации картинки")
            return
        }
        var gatePassedToWorker = false
        var gateReleasedForPicker = false
        var loaderAcquired = false
        try {
            if (brain.state() == "ModelReady") {
                call.resolve(JSObject().apply {
                    put("loaded", true)
                    put("model", brain.modelInfo())
                })
                return
            }
            val id = selectedModelId()
            if (id == null) {
                // The picker acquires its own admission lock.
                NovaComputeGate.leaveText()
                gateReleasedForPicker = true
                importModel(call)
                return
            }
            if (generating.get() || !modelLoading.compareAndSet(false, true)) {
                call.reject("NOVA уже загружает модель или выполняет задачу")
                return
            }
            loaderAcquired = true
            scope.launch {
                try {
                    activateModel(id)
                    call.resolve(JSObject().apply {
                        put("loaded", true)
                        put("model", brain.modelInfo())
                    })
                } catch (e: Exception) {
                    call.reject("Ошибка запуска модели: " + e.message, e)
                } finally {
                    modelLoading.set(false)
                    NovaComputeGate.leaveText()
                }
            }
            gatePassedToWorker = true
        } catch (e: Exception) {
            if (loaderAcquired && !gatePassedToWorker) modelLoading.set(false)
            call.reject("Не удалось подготовить текстовую модель: " + e.message, e)
        } finally {
            if (!gatePassedToWorker && !gateReleasedForPicker) {
                NovaComputeGate.leaveText()
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
            result.put("modelLoading", modelLoading.get())
            result.put("selectedId", selectedModelId() ?: "")
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("NOVA status failed", e)
        }
    }

    @PluginMethod
    fun importModel(call: PluginCall) {
        if (!NovaComputeGate.enterText()) {
            call.reject("NOVA генерирует картинку. Импорт GGUF сейчас недоступен.")
            return
        }
        if (generating.get() || !modelLoading.compareAndSet(false, true)) {
            NovaComputeGate.leaveText()
            call.reject("NOVA уже загружает модель или выполняет задачу")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(call, intent, "modelSelected")
        } catch (e: Exception) {
            modelLoading.set(false)
            NovaComputeGate.leaveText()
            call.reject("Не удалось открыть выбор модели: " + e.message, e)
        }
    }

    @ActivityCallback
    private fun modelSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            modelLoading.set(false)
            NovaComputeGate.leaveText()
            call.reject("Выбор модели отменён")
            return
        }

        scope.launch {
            var temporary: File? = null
            try {
                // Never overwrite the old nova-model.gguf before validating the replacement.
                val id = UUID.randomUUID().toString()
                temporary = File(modelDirectory(), "$id.partial")
                val destination = File(modelDirectory(), "$id.gguf")
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть GGUF" }
                    temporary!!.outputStream().buffered().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                checkGguf(temporary!!)
                check(temporary!!.renameTo(destination)) { "Не удалось сохранить модель" }
                modelPrefs.edit().putString("name_" + id, "Импортированная GGUF").apply()
                activateModel(id)
                call.resolve(JSObject().apply {
                    put("loaded", true)
                    put("path", destination.absolutePath)
                })
            } catch (e: Exception) {
                call.reject("Ошибка импорта модели: " + e.message, e)
            } finally {
                temporary?.delete()
                modelLoading.set(false)
                NovaComputeGate.leaveText()
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
        if (!NovaComputeGate.enterText()) {
            call.reject("Дождись завершения генерации изображения.")
            return
        }
        if (generating.get() || !modelLoading.compareAndSet(false, true)) {
            NovaComputeGate.leaveText()
            call.reject("NOVA уже выполняет другую задачу")
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
            } finally {
                modelLoading.set(false)
                NovaComputeGate.leaveText()
            }
        }
    }

    /**
     * Optional offline prompt planner: translate a user-described image to
     * precise English SD1.5 text before releasing the loaded Gemma model.
     * It does not generate or claim a picture; native NovaImage does that.
     */
    @PluginMethod
    fun planImagePrompt(call: PluginCall) {
        val raw = call.getString("prompt")?.trim().orEmpty()
        if (raw.length !in 3..500) {
            call.reject("Опиши изображение (3–500 символов)")
            return
        }
        if (!NovaComputeGate.enterText()) {
            call.reject("NOVA сейчас создаёт изображение")
            return
        }
        if (generating.get() || modelLoading.get() ||
            brain.state() != "ModelReady" ||
            !generating.compareAndSet(false, true)) {
            NovaComputeGate.leaveText()
            call.resolve(JSObject().apply {
                put("prompt", raw)
                put("optimized", false)
            })
            return
        }
        scope.launch {
            try {
                val instruction = """
                    Convert this visual description to ONE English prompt for
                    a photorealistic Stable Diffusion image.
                    Preserve the requested subject, clothing, setting and action.
                    Do not change nationality or add unrequested symbols.
                    Use concise photographic detail, realistic natural colors,
                    accurate anatomy and cinematic lighting.
                    Output ONE English line only; no commentary, no markdown.
                    Description: ${raw.take(500)}
                    English prompt:
                """.trimIndent()
                val answer = brain.chat(instruction, 160) { _ -> }
                    .trim().trim('"', '\'', '\n')
                    .lineSequence().firstOrNull()?.trim().orEmpty()
                    .removePrefix("English prompt:").trim()
                val latin = answer.count { it in 'a'..'z' || it in 'A'..'Z' }
                val safe = answer.length in 20..540 && latin >= 20 &&
                    !answer.contains("markdown") && !answer.startsWith("I ")
                call.resolve(JSObject().apply {
                    put("prompt", if (safe) answer.take(540) else raw)
                    put("optimized", safe)
                })
            } catch (_: Exception) {
                call.resolve(JSObject().apply { put("prompt", raw); put("optimized", false) })
            } finally {
                generating.set(false)
                NovaComputeGate.leaveText()
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
        if (!NovaComputeGate.enterText()) {
            call.reject("NOVA сейчас генерирует изображение. Дождись завершения.")
            return
        }
        if (modelLoading.get() || !generating.compareAndSet(false, true)) {
            NovaComputeGate.leaveText()
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
                NovaComputeGate.leaveText()
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
        if (!NovaComputeGate.enterText()) {
            call.reject("NOVA сейчас рисует изображение. Совет подождёт.")
            return
        }
        if (modelLoading.get() || !generating.compareAndSet(false, true)) {
            NovaComputeGate.leaveText()
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
                NovaComputeGate.leaveText()
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
