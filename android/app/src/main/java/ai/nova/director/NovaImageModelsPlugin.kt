package ai.nova.director

import android.app.DownloadManager
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.StatFs
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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single curated, owner-approved model download, inside NOVA.
 * DownloadManager owns the long-running transfer; NOVA verifies SHA-256 before activating it.
 * A failed or partial download is never presented as an installed model.
 */
internal object NovaRecommendedImageModel {
    const val ID = "sd15-fp16-verified"
    const val NAME = "Stable Diffusion 1.5 FP16"
    const val FILENAME = "v1-5-pruned-emaonly-fp16.safetensors"
    const val SHA256 = "e9476a13728cd75d8279f6ec8bad753a66a1957ca375a1464dc63b37db6e3916"
    const val URL = "https://huggingface.co/Comfy-Org/stable-diffusion-1.5/resolve/82ddc01b8d77adc5b4a7c6b5e8f2ddf1a6fe0c08/v1-5-pruned-emaonly-fp16.safetensors"
    const val LICENSE = "creativeml-openrail-m"
    const val APPROX_BYTES = 2_130_000_000L
    private const val PREFS = "nova_image_native"
    private const val KEY_DIGEST = "recommended_digest"
    private const val KEY_LENGTH = "recommended_length"
    private const val KEY_MODIFIED = "recommended_modified"

    fun preferences(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun target(context: Context): File {
        val external = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "Память телефона сейчас недоступна"
        }
        return File(external, "NOVA/models/$FILENAME")
    }

    fun installed(context: Context): File? {
        val file = target(context)
        val prefs = preferences(context)
        if (!file.isFile) return null
        return file.takeIf {
            prefs.getString(KEY_DIGEST, null) == SHA256 &&
            prefs.getLong(KEY_LENGTH, -1L) == it.length() &&
            prefs.getLong(KEY_MODIFIED, -1L) == it.lastModified()
        }
    }

    fun invalidate(context: Context) {
        preferences(context).edit().remove(KEY_DIGEST)
            .remove(KEY_LENGTH).remove(KEY_MODIFIED).apply()
    }

    fun activate(context: Context, file: File) {
        preferences(context).edit()
            .putString(KEY_DIGEST, SHA256)
            .putLong(KEY_LENGTH, file.length())
            .putLong(KEY_MODIFIED, file.lastModified())
            .putString("selected_id", ID)
            .apply()
    }
}

@CapacitorPlugin(name = "NovaImageModels")
class NovaImageModelsPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verifying = AtomicBoolean(false)

    private val prefs get() = context.getSharedPreferences("nova_image_downloads", 0)
    private val manager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val downloadId get() = prefs.getLong("download_id", -1L)

    // Explicitly state which network constraints are preventing a large model
    // download. A Wi-Fi icon alone does not mean Android considers it unmetered.
    private fun networkDescription(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return "Нет активной сети Android. Проверь Wi-Fi и авиарежим."
        val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return when {
            !usable -> "Android не подтвердил доступ в интернет. Проверь Wi-Fi и авиарежим."
            !wifi -> "Есть интернет, но не через Wi-Fi. Для модели 2,13 ГБ NOVA намеренно не использует мобильные данные."
            !unmetered -> "Wi-Fi подключён, но Android считает сеть лимитной. В настройках этой Wi-Fi-сети выбери «Безлимитная»/«Не тарифицируемая», если тариф действительно безлимитный."
            else -> "Wi-Fi подключён и не лимитный. Android должен автоматически продолжить загрузку."
        }
    }

    private fun pausedExplanation(reason: Long): String = when (reason.toInt()) {
        DownloadManager.PAUSED_WAITING_TO_RETRY ->
            "Система ожидает повторного соединения с сервером. Обычно загрузка продолжается автоматически."
        DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
            "Ожидание сети, разрешённой для загрузки. " + networkDescription()
        DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
            "Android ждёт подходящий Wi-Fi для большого файла. " + networkDescription()
        else -> "Android временно остановил загрузку. " + networkDescription()
    }

    private fun query(id: Long): JSObject {
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) {
                return JSObject().apply { put("state", "not_found") }
            }
            fun number(column: String): Long {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0) cursor.getLong(index) else -1L
            }
            val status = number(DownloadManager.COLUMN_STATUS).toInt()
            val reason = number(DownloadManager.COLUMN_REASON)
            return JSObject().apply {
                put("state", when (status) {
                    DownloadManager.STATUS_PENDING -> "pending"
                    DownloadManager.STATUS_RUNNING -> "downloading"
                    DownloadManager.STATUS_PAUSED -> "paused"
                    DownloadManager.STATUS_SUCCESSFUL -> "downloaded"
                    DownloadManager.STATUS_FAILED -> "failed"
                    else -> "unknown"
                })
                put("received", number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                put("total", number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                put("reason", reason)
                if (status == DownloadManager.STATUS_PAUSED) {
                    put("pauseExplanation", pausedExplanation(reason))
                }
            }
        }
    }

    private fun verifyDownloaded(id: Long) {
        if (!verifying.compareAndSet(false, true)) return
        scope.launch {
            try {
                val file = NovaRecommendedImageModel.target(context)
                require(file.isFile && file.length() in 1_500_000_000L..3_000_000_000L) {
                    "Размер модели не совпадает с ожидаемым диапазоном"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered(1024 * 1024).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
                require(actual == NovaRecommendedImageModel.SHA256) {
                    "Контрольная сумма модели не совпала. Файл не активирован."
                }
                NovaRecommendedImageModel.activate(context, file)
                prefs.edit().putLong("download_id", -1L)
                    .remove("download_error").apply()
            } catch (e: Exception) {
                NovaRecommendedImageModel.invalidate(context)
                try { manager.remove(id) } catch (_: Exception) {}
                try { NovaRecommendedImageModel.target(context).delete() } catch (_: Exception) {}
                prefs.edit().putLong("download_id", -1L)
                    .putString("download_error", e.message ?: "Ошибка проверки SHA-256").apply()
            } finally {
                verifying.set(false)
            }
        }
    }

    @PluginMethod
    fun recommendedStatus(call: PluginCall) {
        try {
            val installed = NovaRecommendedImageModel.installed(context)
            val id = downloadId
            val result = when {
                installed != null -> JSObject().apply { put("state", "installed"); put("total", installed.length()) }
                verifying.get() -> JSObject().apply { put("state", "verifying") }
                id > 0 -> query(id)
                else -> JSObject().apply {
                    put("state", if (prefs.contains("download_error")) "failed" else "available")
                }
            }
            if (id > 0 && result.getString("state") == "downloaded") {
                verifyDownloaded(id)
                result.put("state", "verifying")
            }
            result.put("name", NovaRecommendedImageModel.NAME)
            result.put("approxBytes", NovaRecommendedImageModel.APPROX_BYTES)
            result.put("license", NovaRecommendedImageModel.LICENSE)
            result.put("source", "Comfy-Org / Hugging Face")
            result.put("error", prefs.getString("download_error", "") ?: "")
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("Не удалось проверить загрузку модели: " + e.message, e)
        }
    }


    /**
     * Every network acquisition requires an Android-native, owner-visible confirmation.
     * A message generated by the LLM or a JS bridge request cannot silently grant it.
     */
    @PluginMethod
    fun downloadRecommended(call: PluginCall) {
        if (call.getBoolean("approved", false) != true) {
            call.reject("Скачивание требует подтверждения владельца")
            return
        }
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Разрешить загрузку графического мозга?")
                .setMessage(
                    "Stable Diffusion 1.5 FP16 · примерно 2,13 ГБ\n" +
                    "Источник: Comfy-Org / Hugging Face\n" +
                    "Только Wi-Fi, нужно около 4,2 ГБ свободного места.\n\n" +
                    "После скачивания NOVA проверит SHA-256. " +
                    "Модель останется на телефоне для полностью офлайн-работы."
                )
                .setNegativeButton("Отмена") { _, _ ->
                    call.reject("Владелец отменил скачивание")
                }
                .setOnCancelListener {
                    call.reject("Владелец отменил скачивание")
                }
                .setPositiveButton("Разрешить скачивание") { _, _ ->
                    scope.launch {
                        try {
                            val result = enqueueRecommended()
                            call.resolve(result)
                        } catch (e: Exception) {
                            call.reject("Загрузка модели не началась: " + e.message, e)
                        }
                    }
                }
                .show()
        }
    }

    private fun enqueueRecommended(): JSObject {
        if (NovaRecommendedImageModel.installed(context) != null) {
            return JSObject().apply { put("state", "installed") }
        }
        if (verifying.get()) {
            error("Сначала дождись проверки загруженной модели")
        }
        val current = downloadId
        if (current > 0) {
            val state = query(current).getString("state")
            if (state in listOf("pending", "downloading", "paused", "downloaded")) {
                return JSObject().apply {
                    put("state", state)
                    put("downloadId", current)
                }
            }
        }
        val file = NovaRecommendedImageModel.target(context)
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "Нельзя создать папку модели"
        }
        val free = StatFs(file.parentFile!!.absolutePath).availableBytes
        require(free > NovaRecommendedImageModel.APPROX_BYTES + 2_000_000_000L) {
            "Для безопасной установки нужно примерно 4,2 ГБ свободного места"
        }
        NovaRecommendedImageModel.invalidate(context)
        if (file.exists()) check(file.delete()) {
            "Не удалось удалить старую частичную загрузку"
        }
        val request = DownloadManager.Request(Uri.parse(NovaRecommendedImageModel.URL))
            .setTitle("NOVA • Stable Diffusion 1.5")
            .setDescription("Встроенная модель для офлайн-картинок · 2,13 ГБ")
            .setAllowedOverRoaming(false)
            .setAllowedOverMetered(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS,
                "NOVA/models/" + NovaRecommendedImageModel.FILENAME
            )
        val id = manager.enqueue(request)
        val saved = prefs.edit().putLong("download_id", id)
            .remove("download_error").commit()
        if (!saved) {
            manager.remove(id)
            error("Не удалось сохранить очередь скачивания")
        }
        return JSObject().apply {
            put("state", "pending")
            put("downloadId", id)
        }
    }

    @PluginMethod
    fun cancelRecommended(call: PluginCall) {
        if (verifying.get()) {
            call.reject("Завершается проверка SHA-256, отмена сейчас недоступна")
            return
        }
        val id = downloadId
        if (id > 0) manager.remove(id)
        prefs.edit().putLong("download_id", -1L).remove("download_error").apply()
        try {
            if (NovaRecommendedImageModel.installed(context) == null) {
                NovaRecommendedImageModel.target(context).delete()
            }
        } catch (_: Exception) {}
        call.resolve(JSObject().apply { put("cancelled", true) })
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
