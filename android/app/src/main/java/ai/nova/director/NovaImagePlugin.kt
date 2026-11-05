package ai.nova.director

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.getcapacitor.JSArray
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
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Proxy
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
        return (URL("http://127.0.0.1:8081$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
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

    private fun tokenizeCount(prompt: String): Pair<Int, Int> {
        val probe = connect("/tokenize", 6000)
        try {
            postJson(probe, JSONObject().put("prompt", prompt))
            check(probe.responseCode == 200) {
                "Порт 8081 не отвечает как Local Dream (HTTP ${probe.responseCode})"
            }
            val reply = probe.inputStream.bufferedReader().use { it.readText().take(4096) }
            val json = JSONObject(reply)
            val max = json.optInt("max_length", 0)
            val count = json.optInt("count", 0)
            check(max == 77 && count > 0) {
                "Порт 8081 занят другим сервисом или отвечает неверно"
            }
            return count to max
        } finally {
            probe.disconnect()
        }
    }

    private fun requireLocalDream() {
        tokenizeCount("NOVA connection test")
    }


    // Local Dream 3.x optional controlled mode (the user enables it ONCE in Local Dream).
    // Port 8808 is local-only from NOVA's perspective; no arbitrary host/URL can be passed in.
    // Some versions of Local Dream do not implement this API; manual mode is retained.
    private fun control(path: String, method: String = "GET"): HttpURLConnection {
        require(path in listOf("/info", "/models", "/status", "/select")) {
            "Неизвестный маршрут управления"
        }
        return (URL("http://127.0.0.1:8808" + path)
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2500
            readTimeout = 6500
            instanceFollowRedirects = false
            doOutput = method == "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }

    private fun controlGet(path: String): JSONObject {
        val conn = control(path)
        try {
            check(conn.responseCode == 200) { "Control API HTTP " + conn.responseCode }
            return conn.inputStream.bufferedReader().use {
                JSONObject(it.readText().take(256_000))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun controlPostSelect(id: String, size: Int) {
        val conn = control("/select", "POST")
        try {
            postJson(conn, JSONObject().apply {
                put("model_id", id)
                put("width", size)
                put("height", size)
            })
            check(conn.responseCode == 200) {
                "Local Dream отказал в активации: " + conn.responseCode
            }
            val response = conn.inputStream.bufferedReader().use {
                JSONObject(it.readText().take(4096))
            }
            check(response.optBoolean("ok", false)) { "Local Dream не подтвердил выбор модели" }
        } finally {
            conn.disconnect()
        }
    }

    private fun managedCatalog(): JSONArray {
        val info = controlGet("/info")
        check(info.optString("app") == "localdream" && info.optInt("protocol") == 1) {
            "Порт управления занят неизвестным сервисом"
        }
        return controlGet("/models").optJSONArray("models") ?: JSONArray()
    }

    private fun preferredImageId(catalog: JSONArray): String? {
        val models = (0 until catalog.length())
            .mapNotNull { catalog.optJSONObject(it) }
        val saved = context.getSharedPreferences("nova_image_manager", 0)
            .getString("image_id", null)
        val preferred = models.firstOrNull { it.optString("id") == saved }
            ?: models.firstOrNull {
                it.optString("name").contains("Absolute Reality", ignoreCase = true)
            }
            ?: models.firstOrNull { it.optBoolean("run_on_cpu", false) }
            ?: models.firstOrNull()
        return preferred?.optString("id")?.takeIf { it.isNotBlank() }
    }

    @PluginMethod
    fun imageModels(call: PluginCall) {
        scope.launch {
            try {
                val catalog = managedCatalog()
                val native = controlGet("/status")
                val entries = JSArray()
                for (i in 0 until catalog.length()) {
                    val model = catalog.optJSONObject(i) ?: continue
                    entries.put(JSObject().apply {
                        put("id", model.optString("id"))
                        put("name", model.optString("name"))
                        put("cpu", model.optBoolean("run_on_cpu", false))
                        put("selected", model.optString("id") == preferredImageId(catalog))
                    })
                }
                call.resolve(JSObject().apply {
                    put("managed", true)
                    put("models", entries)
                    put("selectedId", preferredImageId(catalog) ?: "")
                    put("activeId", native.optString("serving_model_id"))
                    put("state", native.optString("state"))
                    put("generating", generating.get())
                })
            } catch (_: Exception) {
                call.resolve(JSObject().apply {
                    put("managed", false)
                    put("models", JSArray())
                    put("state", "manual")
                    put("detail", "Для автоматического выбора моделей открой новую версию Local Dream и включи режим управления устройством. В старой версии выбери модель вручную; NOVA подключится к уже запущенной модели.")
                })
            }
        }
    }

    @PluginMethod
    fun selectImageModel(call: PluginCall) {
        val id = call.getString("id").orEmpty()
        if (generating.get()) {
            call.reject("Нельзя переключать модель во время генерации")
            return
        }
        scope.launch {
            try {
                val catalog = managedCatalog()
                check((0 until catalog.length()).any {
                    catalog.optJSONObject(it)?.optString("id") == id
                }) { "Такой модели нет среди установленных в Local Dream" }
                controlPostSelect(id, 512)
                context.getSharedPreferences("nova_image_manager", 0).edit()
                    .putString("image_id", id).apply()
                call.resolve(JSObject().apply {
                    put("selected", true)
                    put("id", id)
                    put("state", "starting")
                })
            } catch (e: Exception) {
                call.reject("Не удалось подключить модель изображений: " + e.message, e)
            }
        }
    }

    private suspend fun ensureImageReady(size: Int) {
        // Managed mode: verify the SELECTED model, not just any live /tokenize service.
        // Manual mode: keep supporting older Local Dream builds after user opens one model.
        val catalog = try { managedCatalog() } catch (_: Exception) { null }
        if (catalog == null) {
            requireLocalDream()
            return
        }

        val selected = preferredImageId(catalog)
            ?: error("В Local Dream ещё нет установленных моделей")
        val current = controlGet("/status")
        val sameModel = current.optString("state") == "running" &&
            current.optString("serving_model_id") == selected &&
            current.optInt("width", size) == size
        if (sameModel) {
            try {
                requireLocalDream()
                return
            } catch (_: Exception) { /* backend may still be warming up */ }
        } else {
            controlPostSelect(selected, size)
        }

        repeat(90) {
            if (cancelRequested.get()) throw IOException("Остановлено")
            delay(1000L)
            val status = controlGet("/status")
            if (status.optString("state") == "error") {
                throw IOException("Local Dream: " + status.optString("message"))
            }
            if (status.optString("state") == "running" &&
                status.optString("serving_model_id") == selected &&
                status.optInt("width", size) == size) {
                try {
                    requireLocalDream()
                    return
                } catch (_: Exception) { /* the token endpoint may not be ready yet */ }
            }
        }
        throw IOException("Local Dream не запустил нужную модель. Проверь режим управления и ограничения батареи.")
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
                ensureImageReady(size)
                if (cancelRequested.get()) throw IOException("Остановлено пользователем")
                val (tokens, capacity) = tokenizeCount(prompt)
                require(tokens <= capacity) {
                    "Описание содержит $tokens токенов, а модель понимает только $capacity. Сократи описание и попробуй снова."
                }
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
                val rgb = Base64.decode(result.getString("image"), Base64.DEFAULT)
                check(rgb.size == width * height * 3) { "Неполные данные изображения" }

                val pixels = NovaImagePixels.toArgb(rgb, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    val png = ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "Не удалось подготовить PNG"
                        }
                        output.toByteArray()
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
                } finally {
                    bitmap.recycle()
                }
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
    fun openLocalDream(call: PluginCall) {
        activity.runOnUiThread {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(
                    "io.github.xororz.localdream"
                )
                if (launch == null) {
                    call.reject("Local Dream не обнаружен на телефоне")
                } else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    call.resolve(JSObject().apply { put("opened", true) })
                }
            } catch (e: Exception) {
                call.reject("Не удалось открыть Local Dream: ${e.message}", e)
            }
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
