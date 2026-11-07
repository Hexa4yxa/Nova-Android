package ai.nova.director

import ai.nova.image.NativeImageEngine
import ai.nova.image.NativeGpuImageEngine
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Native, offline, same-APK image generation. No Local Dream dependency. */
@CapacitorPlugin(name = "NovaImage")
class NovaImagePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generating = AtomicBoolean(false)
    private val importing = AtomicBoolean(false)
    private val prefs get() = context.getSharedPreferences("nova_image_native", 0)
    private fun verified(id: String?) =
        id != null && prefs.getString("last_success_model_id", null) == id
    private fun lastError(id: String?) =
        if (id != null && prefs.getString("last_error_model_id", null) == id)
            prefs.getString("last_error", null) else null
    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val service = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { service.getMemoryInfo(it) }
    }

    private fun availableMemoryMib(): Long = memoryInfo().availMem / (1024 * 1024)

    /** Never assume a model is safe to load just because its file exists. */
    private fun resourceWarning(size: Int): String? {
        val memory = memoryInfo()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val tooHot = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        return NovaImageSafety.warning(
            memory.availMem / (1024 * 1024), memory.lowMemory, tooHot, size
        )
    }

    private fun dir() = File(context.filesDir, "visual-models").also {
        check(it.isDirectory || it.mkdirs()) { "Не удаётся создать хранилище" }
    }

    private fun find(id: String): File? {
        if (id == NovaRecommendedImageModel.ID) return NovaRecommendedImageModel.installed(context)
        if (!Regex("^[a-f0-9-]{36}$").matches(id)) return null
        return listOf("safetensors", "gguf")
            .map { File(dir(), "$id.$it") }
            .firstOrNull { it.isFile }
    }

    private fun installed(): List<Pair<String, File>> {
        val custom = dir().listFiles()?.filter {
            it.isFile && it.extension in listOf("gguf", "safetensors")
        }?.mapNotNull { file ->
            val id = file.name.substringBeforeLast('.')
            if (find(id) == file) id to file else null
        }?.sortedBy { it.first } ?: emptyList()
        val curated = NovaRecommendedImageModel.installed(context)?.let {
            listOf(NovaRecommendedImageModel.ID to it)
        } ?: emptyList()
        return curated + custom
    }

    private fun selected(): String? {
        val saved = prefs.getString("selected_id", null)
        if (saved != null && find(saved) != null) return saved
        return installed().firstOrNull()?.first
    }

    private fun validate(file: File, ext: String) {
        require(file.length() in 40_000_000L..8_000_000_000L) {
            "Неверный размер графической модели"
        }
        FileInputStream(file).use { stream ->
            val first = ByteArray(9)
            require(stream.read(first) == 9) { "Файл пустой" }
            if (ext == "gguf") {
                require(String(first, 0, 4, Charsets.US_ASCII) == "GGUF") {
                    "GGUF повреждена. Текстовая GGUF не подходит для рисования."
                }
            } else {
                val length = ByteBuffer.wrap(first, 0, 8)
                    .order(ByteOrder.LITTLE_ENDIAN).long
                require(length in 2..10_000_000L && first[8] == '{'.code.toByte()) {
                    "Неверный заголовок safetensors"
                }
            }
        }
    }

    @PluginMethod
    fun status(call: PluginCall) {
        try {
            val model = selected()
            val error = NativeImageEngine.libraryError()
            call.resolve(JSObject().apply {
                put("ready", error == null && model != null)
                put("backend", "NOVA Native Image CPU")
                put("gpuProbe", NativeImageEngine.gpuProbe())
                put("gpuEngineAvailable", NativeGpuImageEngine.libraryReady())
                put("gpuEngineDevice", NativeGpuImageEngine.gpuDevice())
                put("gpuEngineError", NativeGpuImageEngine.libraryError() ?: "")
                put("generating", generating.get())
                put("selectedId", model ?: "")
                put("testedOnDevice", verified(model))
                put("lastError", lastError(model) ?: "")
                put("freeMemoryMib", availableMemoryMib())
                put("resourceWarning", resourceWarning(256) ?: "")
                put("detail", when {
                    error != null -> "Нативная библиотека недоступна: $error"
                    model == null -> "Добавь модель изображений через «Мозги». Нужен совместимый checkpoint SD 1.5; текстовая Gemma GGUF не подходит."
                    verified(model) -> "✅ Эта графическая модель уже создала PNG внутри NOVA на этом устройстве."
                    lastError(model) != null -> "Последний тест неудачен: " + lastError(model)
                    else -> "Модель установлена, но генерация на этом телефоне ещё не проверена."
                })
            })
        } catch (e: Exception) { call.reject("Проверка: $e", e) }
    }

    @PluginMethod
    fun imageModels(call: PluginCall) {
        try {
            val items = JSArray()
            val id = selected()
            for ((key, file) in installed()) {
                items.put(JSObject().apply {
                    put("id", key)
                    put("name", if (key == NovaRecommendedImageModel.ID)
                        NovaRecommendedImageModel.NAME
                        else prefs.getString("name_$key", file.name))
                    put("bytes", file.length())
                    put("selected", id == key)
                    put("cpu", true)
                    put("validated", key == NovaRecommendedImageModel.ID)
                    put("testedOnDevice", verified(key))
                    put("lastError", lastError(key) ?: "")
                })
            }
            call.resolve(JSObject().apply {
                put("managed", true)
                put("backend", "NOVA Native Image")
                put("installed", NativeImageEngine.libraryReady())
                put("models", items)
                put("selectedId", id ?: "")
                put("activeId", id ?: "")
                put("state", if (generating.get()) "generating"
                    else if (id != null) "installed" else "missing_model")
                put("generating", generating.get())
            })
        } catch (e: Exception) { call.reject("Каталог: $e", e) }
    }

    @PluginMethod
    fun selectImageModel(call: PluginCall) {
        val id = call.getString("id").orEmpty()
        if (find(id) == null || generating.get() || importing.get()) {
            call.reject("Модель не найдена или движок занят")
            return
        }
        prefs.edit().putString("selected_id", id).apply()
        call.resolve(JSObject().apply { put("selected", true); put("id", id) })
    }

    @PluginMethod
    fun importImageModel(call: PluginCall) {
        if (generating.get() || !importing.compareAndSet(false, true)) {
            call.reject("Сначала закончи текущую задачу")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(call, intent, "imageModelSelected")
        } catch (e: Exception) {
            importing.set(false)
            call.reject("Не удалось открыть выбор модели: $e", e)
        }
    }

    @ActivityCallback
    private fun imageModelSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            importing.set(false)
            call.reject("Импорт отменён")
            return
        }
        scope.launch {
            var partial: File? = null
            var output: File? = null
            try {
                val filename = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "image-model.safetensors"
                val ext = filename.substringAfterLast('.', "").lowercase()
                require(ext in listOf("gguf", "safetensors")) {
                    "Выбери модель изображений safetensors или GGUF"
                }
                val id = UUID.randomUUID().toString()
                partial = File(dir(), "$id.part")
                output = File(dir(), "$id.$ext")
                val available = StatFs(dir().absolutePath).availableBytes
                val reported = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    it.statSize
                } ?: -1L
                require(available > 2_000_000_000L) {
                    "На телефоне осталось менее 2 ГБ свободного места"
                }
                if (reported > 0) require(available - reported > 2_000_000_000L) {
                    "Недостаточно места. Оставь хотя бы 2 ГБ после импорта."
                }
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть файл" }
                    partial!!.outputStream().buffered().use { dest ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 8_000_000_000L) { "Модель больше 8 ГБ" }
                            if (total % (16L * 1024 * 1024) < n) {
                                require(StatFs(dir().absolutePath).availableBytes > 2_000_000_000L) {
                                    "Недостаточно места для безопасного импорта. Частичный файл будет удалён."
                                }
                            }
                            dest.write(buffer, 0, n)
                        }
                    }
                }
                validate(partial!!, ext)
                check(partial!!.renameTo(requireNotNull(output))) { "Не удалось завершить импорт" }
                prefs.edit().putString("name_$id", filename.take(120))
                    .putString("selected_id", id).apply()
                call.resolve(JSObject().apply {
                    put("imported", true)
                    put("id", id)
                    put("name", filename.take(120))
                })
            } catch (e: Exception) {
                output?.delete()
                call.reject("Не удалось импортировать модель: $e", e)
            } finally {
                partial?.delete()
                importing.set(false)
            }
        }
    }

    @PluginMethod
    fun generate(call: PluginCall) {
        val prompt = call.getString("prompt")?.trim().orEmpty()
        val negative = call.getString("negative").orEmpty()
            .ifBlank { "blurred, distorted face, bad anatomy, watermark, text" }
        val size = call.getInt("size") ?: 256
        val steps = call.getInt("steps") ?: 12
        // Optional deterministic seed for CPU/GPU quality comparison. Default behavior is unchanged.
        val seed = call.getInt("seed")?.toLong() ?: System.currentTimeMillis()
        if (prompt.length !in 5..600 || negative.length > 400 ||
            size !in listOf(256, 512) || steps !in 1..30) {
            call.reject("Проверь описание, размер 256/512 и число шагов 1–30")
            return
        }
        val file = selected()?.let { find(it) }
        if (file == null || !NativeImageEngine.libraryReady()) {
            call.reject("Графический мозг не готов. Импортируй совместимую модель в «Мозги».")
            return
        }
        if (!NovaComputeGate.beginImage()) {
            call.reject("Сначала нужно освободить память от текстового мозга NOVA")
            return
        }
        // Image admission owns the global compute gate, so a failure here
        // releases only our own reservation and leaves Gemma untouched.
        val unsafe = try { resourceWarning(size) }
        catch (e: Exception) {
            NovaComputeGate.finishImage()
            call.reject("Не удалось проверить RAM или нагрев: " + e.message, e)
            return
        }
        if (unsafe != null) {
            NovaComputeGate.finishImage()
            call.reject(unsafe)
            return
        }
        if (importing.get() || !generating.compareAndSet(false, true)) {
            NovaComputeGate.finishImage()
            call.reject("Генератор уже занят")
            return
        }
        scope.launch {
            val monitor = launch {
                while (generating.get()) {
                    val p = NativeImageEngine.progress()
                    notifyListeners("novaImageProgress", JSObject().apply {
                        put("step", p.getOrElse(0) { 0 })
                        put("total", p.getOrElse(1) { steps })
                    })
                    delay(1500L)
                }
            }
            try {
                val rgb = NativeImageEngine.generate(
                    file.absolutePath, prompt, negative, size, steps, seed
                )
                require(rgb.size == size * size * 3) { "Неполные пиксели изображения" }
                val timing = NativeImageEngine.timings()
                val pixels = NovaImagePixels.toArgb(rgb, size, size)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                    val png = java.io.ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "Не получилось создать PNG"
                        }
                        output.toByteArray()
                    }
                    val uri = savePng(png)
                    call.resolve(JSObject().apply {
                        put("saved", true)
                        put("width", size)
                        put("height", size)
                        put("imageUri", uri.toString())
                        put("preview", "data:image/png;base64," +
                            Base64.encodeToString(png, Base64.NO_WRAP))
                        put("backend", "NOVA Native CPU")
                        put("seed", seed)
                        put("modelLoadMs", timing.getOrElse(0) { 0L })
                        put("samplingMs", timing.getOrElse(1) { 0L })
                    })
                    prefs.edit().putString("last_success_model_id", selected())
                        .remove("last_error_model_id").remove("last_error").apply()
                } finally { bitmap.recycle() }
            } catch (e: Exception) {
                prefs.edit()
                    .putString("last_error_model_id", selected())
                    .putString("last_error", (e.message ?: e.javaClass.simpleName).take(350))
                    .apply()
                call.reject("Нативная генерация не завершена: $e", e)
            } finally {
                generating.set(false)
                monitor.cancel()
                NovaComputeGate.finishImage()
            }
        }
    }

    /**
     * Explicit opt-in smoke test: one step, 256px, fixed seed, English prompt.
     * Never automatically substitute CPU if GPU initialization fails: that
     * would hide backend errors and could waste 20 minutes on CPU.
     */
    @PluginMethod
    fun gpuQuickTest(call: PluginCall) {
        if (call.getBoolean("approved", false) != true) {
            call.reject("GPU-тест запускается только после подтверждения")
            return
        }
        val file = selected()?.let { find(it) }
        if (file == null) { call.reject("Сначала подключи SD 1.5"); return }
        if (!NativeGpuImageEngine.enableForApprovedTest()) {
            call.reject("GPU-библиотека недоступна: " + (NativeGpuImageEngine.libraryError() ?: "неизвестная ошибка"))
            return
        }
        val device = NativeGpuImageEngine.gpuDevice()
        if (device.isBlank()) {
            call.reject("Adreno доступен Android, но вычислительный OpenCL backend пока не зарегистрирован")
            return
        }
        if (!NovaComputeGate.beginImage()) {
            call.reject("Сначала освободи память Gemma")
            return
        }
        val unsafe = try { resourceWarning(256) }
        catch (e: Exception) {
            NovaComputeGate.finishImage()
            call.reject("Проверка ресурсов: " + e.message, e)
            return
        }
        if (unsafe != null) {
            NovaComputeGate.finishImage()
            call.reject(unsafe)
            return
        }
        if (importing.get() || !generating.compareAndSet(false, true)) {
            NovaComputeGate.finishImage()
            call.reject("Генератор занят")
            return
        }
        scope.launch {
            val monitor = launch {
                while (generating.get()) {
                    val p = NativeGpuImageEngine.progress()
                    notifyListeners("novaImageProgress", JSObject().apply {
                        put("step", p.getOrElse(0) { 0 })
                        put("total", p.getOrElse(1) { 1 })
                        put("backend", "OpenCL GPU")
                    })
                    delay(1500L)
                }
            }
            try {
                val testPrompt = "photorealistic red apple on a wooden table, natural light, clean background"
                val rgb = NativeGpuImageEngine.generate(file.absolutePath,
                    testPrompt, "blurry, watermark, text", 256, 1, 4242L)
                require(rgb.size == 256 * 256 * 3) { "GPU не вернул полный RGB-кадр" }
                val timing = NativeGpuImageEngine.timings()
                val pixels = NovaImagePixels.toArgb(rgb, 256, 256)
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 256)
                    val png = java.io.ByteArrayOutputStream().use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Ошибка GPU PNG" }
                        stream.toByteArray()
                    }
                    val uri = savePng(png)
                    call.resolve(JSObject().apply {
                        put("saved", true)
                        put("backend", "OpenCL GPU")
                        put("gpuDevice", device)
                        put("width", 256)
                        put("height", 256)
                        put("steps", 1)
                        put("seed", 4242)
                        put("imageUri", uri.toString())
                        put("preview", "data:image/png;base64," +
                            Base64.encodeToString(png, Base64.NO_WRAP))
                        put("modelLoadMs", timing.getOrElse(0) { 0L })
                        put("samplingMs", timing.getOrElse(1) { 0L })
                    })
                } finally { bitmap.recycle() }
            } catch (e: Throwable) {
                // Even a JNI linkage error should be reported without changing CPU renderer.
                val cause = if (e is Exception) e else IllegalStateException(e)
                call.reject("OpenCL тест не завершён: " + (e.message ?: e.javaClass.simpleName), cause)
            } finally {
                generating.set(false)
                monitor.cancel()
                NovaComputeGate.finishImage()
            }
        }
    }

    private fun savePng(bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,
                "NOVA-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/NOVA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )) { "Галерея недоступна" }
        try {
            resolver.openOutputStream(uri).use { stream ->
                requireNotNull(stream) { "Нет доступа к галерее" }
                stream.write(bytes)
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        val cpuCancelled = NativeImageEngine.cancel()
        val gpuCancelled = NativeGpuImageEngine.cancel()
        call.resolve(JSObject().apply {
            put("cancelRequested", cpuCancelled || gpuCancelled)
        })
    }

    override fun handleOnDestroy() {
        NativeImageEngine.cancel()
        NativeGpuImageEngine.cancel()
        scope.cancel()
        super.handleOnDestroy()
    }
}
