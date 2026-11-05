package ai.nova.director

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controls an existing, explicitly started Local Dream instance on the SAME phone.
 * It never downloads models and cannot connect to a user-supplied host or URL.
 * Local Dream must be open with an already loaded model; 8081 must not be used by
 * the older NOVA video server.
 *
 * Local Dream API: POST /tokenize; POST /generate -> SSE with raw RGB base64.
 */
@CapacitorPlugin(name = "NovaImage")
class NovaImagePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generating = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var activeConnection: HttpURLConnection? = null

    private fun connect(path: String, timeoutMs: Int): HttpURLConnection {
        check(path == "/tokenize" || path == "/generate") { "Недопустимый локальный маршрут" }
        return (URL("http://127.0.0.1:8081$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 2500
            readTimeout = timeoutMs
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json, text/event-stream")
        }
    }

    private fun postJson(connection: HttpURLConnection, request: JSONObject) {
        connection.outputStream.use {
            it.write(request.toString().toByteArray(Charsets.UTF_8))
            it.flush()
        }
    }

    private fun requireLocalDream() {
        val probe = connect("/tokenize", 6000)
        try {
            postJson(probe, JSONObject().put("prompt", "NOVA connection test"))
            check(probe.responseCode == 200) {
                "На порту 8081 не найден API Local Dream (HTTP ${probe.responseCode})"
            }
            val reply = probe.inputStream.bufferedReader().use { it.readText().take(4096) }
            val json = JSONObject(reply)
            // Local Dream returns max_length=0 for its unchunked tokenizer.
            // Only the response SHAPE identifies the native tokenizer; the
            // exact context length depends on the selected model.
            check(json.has("count") && json.has("max_length") &&
                json.optInt("count", -1) >= 0 && json.optInt("max_length", -1) >= 0) {
                "Порт 8081 занят другим сервером или модель Local Dream не запущена"
            }
        } finally {
            probe.disconnect()
        }
    }

    @PluginMethod
    fun openLocalDream(call: PluginCall) {
        activity.runOnUiThread {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(
                    "io.github.xororz.localdream"
                ) ?: throw IllegalStateException("Local Dream не установлен или недоступен")
                activity.startActivity(launch)
                call.resolve(JSObject().apply { put("opened", true) })
            } catch (e: Exception) {
                call.reject("Не удалось открыть Local Dream: ${e.message}", e)
            }
        }
    }

    @PluginMethod
    fun status(call: PluginCall) {
        scope.launch {
            try {
                requireLocalDream()
                call.resolve(JSObject().apply {
                    put("ready", true)
                    put("backend", "Local Dream")
                    put("location", "127.0.0.1")
                    put("generating", generating.get())
                })
            } catch (e: Exception) {
                call.resolve(JSObject().apply {
                    put("ready", false)
                    put("generating", generating.get())
                    put("detail", "Открой Local Dream, выбери Absolute Reality и оставь модель загруженной. Проверь, что порт 8081 не занят видеосервером NOVA. ${e.message ?: ""}")
                })
            }
        }
    }

    @PluginMethod
    fun generate(call: PluginCall) {
        val prompt = call.getString("prompt")?.trim().orEmpty()
        val negative = call.getString("negative")?.trim().orEmpty().ifBlank {
            "deformed face, disfigured, bad anatomy, blurry, text, watermark"
        }
        val size = call.getInt("size") ?: 512
        if (prompt.length !in 5..600 || negative.length > 400 || size !in listOf(256, 512)) {
            call.reject("Описание: 5–600 символов, размер 256 или 512")
            return
        }
        if (!generating.compareAndSet(false, true)) {
            call.reject("Изображение уже создаётся. Дождись окончания или останови задачу.")
            return
        }
        cancelRequested.set(false)
        val requestId = call.getString("requestId") ?: UUID.randomUUID().toString()

        scope.launch {
            try {
                requireLocalDream()
                if (cancelRequested.get()) throw IOException("Остановлено пользователем")

                val payload = JSONObject().apply {
                    put("prompt", prompt)
                    put("negative_prompt", negative)
                    put("size", size)
                    put("steps", 20)
                    put("cfg", 7.0)
                    put("scheduler", "dpm")
                    put("use_opencl", false)
                    put("show_diffusion_process", false)
                    // Newer Local Dream versions return ready-to-save PNG.
                    // Older versions silently fall back to raw RGB.
                    put("output_format", "png")
                }
                val connection = connect("/generate", 25 * 60 * 1000)
                activeConnection = connection
                postJson(connection, payload)
                if (connection.responseCode != 200) {
                    val explanation = connection.errorStream?.bufferedReader()?.use {
                        it.readText().take(300)
                    } ?: "HTTP ${connection.responseCode}"
                    throw IOException("Local Dream отклонил запрос: $explanation")
                }

                var completed: JSONObject? = null
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { input ->
                    while (true) {
                        if (cancelRequested.get()) throw IOException("Остановлено пользователем")
                        val line = input.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val message = JSONObject(line.substring(5).trim())
                        when (message.optString("type")) {
                            "progress" -> {
                                val step = message.optInt("step")
                                val total = message.optInt("total_steps")
                                if (step == 1 || step % 2 == 0 || step == total) {
                                    notifyListeners("novaImageProgress", JSObject().apply {
                                        put("requestId", requestId)
                                        put("step", step)
                                        put("total", total)
                                    })
                                }
                            }
                            "complete" -> {
                                completed = message
                                break
                            }
                            "error" -> throw IOException(message.optString("message", "Ошибка генерации"))
                        }
                    }
                }
                if (cancelRequested.get()) throw IOException("Остановлено пользователем")
                val result = requireNotNull(completed) { "Local Dream не вернул готовую картинку" }
                val width = result.getInt("width")
                val height = result.getInt("height")
                check(width in 128..512 && height in 128..512) { "Недопустимый размер полученного изображения" }
                check(result.optInt("channels") == 3) { "Неподдерживаемый формат изображения" }
                val data = Base64.decode(result.getString("image"), Base64.DEFAULT)
                val format = result.optString("format", "raw")
                val png = when (format) {
                    "png" -> {
                        NovaImageWire.validatePng(data, width, height)
                        data
                    }
                    "raw" -> {
                        val pixels = NovaImagePixels.toArgb(data, width, height)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                            ByteArrayOutputStream().use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                    "Не удалось подготовить PNG"
                                }
                                output.toByteArray()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    else -> error("Неожиданный формат от Local Dream: $format")
                }
                val uri = saveToGallery(png)
                call.resolve(JSObject().apply {
                    put("ready", true)
                    put("saved", true)
                    put("imageUri", uri.toString())
                    put("preview", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
                    put("width", width)
                    put("height", height)
                    put("seed", result.optLong("seed"))
                    put("generationTimeMs", result.optLong("generation_time_ms"))
                    put("backend", "Local Dream CPU")
                })
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                call.reject("Создание изображения не завершено: $detail", e)
            } finally {
                activeConnection?.disconnect()
                activeConnection = null
                generating.set(false)
                cancelRequested.set(false)
            }
        }
    }

    private fun saveToGallery(bytes: ByteArray): Uri {
        val name = "NOVA-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NOVA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Галерея недоступна"
        }
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Не удалось сохранить изображение в галерею")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        if (!generating.get()) {
            call.resolve(JSObject().apply { put("cancelRequested", false) })
            return
        }
        cancelRequested.set(true)
        activeConnection?.disconnect()
        call.resolve(JSObject().apply { put("cancelRequested", true) })
    }

    override fun handleOnDestroy() {
        cancelRequested.set(true)
        activeConnection?.disconnect()
        scope.cancel()
        super.handleOnDestroy()
    }
}
