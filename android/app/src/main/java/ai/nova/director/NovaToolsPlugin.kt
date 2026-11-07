package ai.nova.director

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicitly approved resource acquisition. The language model cannot execute
 * arbitrary code, browse the user's files, or silently start a download.
 */
@CapacitorPlugin(name = "NovaTools")
class NovaToolsPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val preferences get() = context.getSharedPreferences("nova_tools_downloads", Context.MODE_PRIVATE)

    private fun downloadDir(): File {
        val base = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "Нет доступа к каталогу загрузок приложения"
        }
        val dir = File(base, "NOVA-Assets")
        check(dir.isDirectory || dir.mkdirs()) { "Не удалось создать каталог ресурсов NOVA" }
        return dir
    }

    private fun stored(id: Long): JSONObject? {
        val value = preferences.getString("download_$id", null) ?: return null
        return JSONObject(value)
    }

    private fun fileFor(record: JSONObject): File {
        val storedName = record.getString("storedName")
        check(Regex("^[a-f0-9-]{36}-[A-Za-z0-9._-]+$").matches(storedName)) {
            "Некорректный ресурс"
        }
        return File(downloadDir(), storedName)
    }

    @PluginMethod
    fun capabilities(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("network", "explicit-approval-only")
            put("downloads", true)
            put("verifySha256", true)
            put("exportViaSystemPicker", true)
            put("automaticExecution", false)
            put("installPackages", false)
        })
    }

    @PluginMethod
    fun requestDownload(call: PluginCall) {
        val asset = try {
            NovaDownloadPolicy.validate(
                call.getString("url"), call.getString("filename"), call.getString("sha256")
            )
        } catch (e: IllegalArgumentException) {
            call.reject(e.message ?: "Неверная ссылка или файл")
            return
        }

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Разрешить скачивание?")
                .setMessage(
                    "Источник: ${asset.host}\n" +
                    "Файл: ${asset.filename}\n" +
                    "SHA-256: ${if (asset.sha256 == null) "не указан" else "будет проверен"}\n\n" +
                    "Размер заранее не известен. Может использоваться мобильный трафик. " +
                    "Файл будет сохранён в памяти приложения, но НЕ запущен и НЕ установлен."
                )
                .setNegativeButton("Отмена") { _, _ -> call.reject("Загрузка не разрешена") }
                .setOnCancelListener { call.reject("Загрузка не разрешена") }
                .setPositiveButton("Скачать") { _, _ ->
                    try {
                        val storedName = "${UUID.randomUUID()}-${asset.filename}"
                        downloadDir()
                        val request = DownloadManager.Request(Uri.parse(asset.url))
                            .setTitle("NOVA: ${asset.filename}")
                            .setDescription("Загрузка ресурса с ${asset.host}")
                            .setAllowedOverRoaming(false)
                            .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            .setDestinationInExternalFilesDir(
                                context, Environment.DIRECTORY_DOWNLOADS, "NOVA-Assets/$storedName"
                            )
                        val id = manager.enqueue(request)
                        val record = JSONObject().apply {
                            put("id", id.toString())
                            put("name", asset.filename)
                            put("storedName", storedName)
                            put("host", asset.host)
                            put("sha256", asset.sha256 ?: "")
                            put("createdAt", System.currentTimeMillis())
                        }
                        val saved = preferences.edit()
                            .putString("download_$id", record.toString()).commit()
                        if (!saved) {
                            manager.remove(id)
                            throw IllegalStateException("Не удалось сохранить запись о загрузке")
                        }
                        call.resolve(JSObject().apply {
                            put("id", id.toString())
                            put("name", asset.filename)
                            put("status", "queued")
                        })
                    } catch (e: Exception) {
                        call.reject("Не удалось начать загрузку: ${e.message}", e)
                    }
                }
                .show()
        }
    }

    private fun status(id: Long): JSObject {
        val record = stored(id) ?: throw IllegalArgumentException("Загрузка не найдена")
        val result = JSObject().apply {
            put("id", id.toString())
            put("name", record.getString("name"))
            put("host", record.getString("host"))
            put("hasExpectedSha256", record.optString("sha256").isNotBlank())
            put("createdAt", record.optLong("createdAt"))
            put("status", "missing")
            put("bytes", 0L)
            put("total", -1L)
        }
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val state = number(DownloadManager.COLUMN_STATUS).toInt()
                val name = when (state) {
                    DownloadManager.STATUS_PENDING -> "queued"
                    DownloadManager.STATUS_RUNNING -> "running"
                    DownloadManager.STATUS_PAUSED -> "paused"
                    DownloadManager.STATUS_SUCCESSFUL -> "complete"
                    DownloadManager.STATUS_FAILED -> "failed"
                    else -> "unknown"
                }
                result.put("status", name)
                result.put("bytes", number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                result.put("total", number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (state == DownloadManager.STATUS_FAILED || state == DownloadManager.STATUS_PAUSED) {
                    result.put("reason", number(DownloadManager.COLUMN_REASON))
                }
                if (name == "complete" && !fileFor(record).isFile) {
                    result.put("status", "missing")
                }
            }
        }
        return result
    }

    @PluginMethod
    fun downloads(call: PluginCall) {
        try {
            val records = preferences.all.keys
                .filter { it.startsWith("download_") }
                .mapNotNull { it.removePrefix("download_").toLongOrNull() }
                .sortedDescending().take(30)
            val items = JSArray()
            records.forEach { id ->
                try { items.put(status(id)) } catch (_: Exception) { /* keep listing others */ }
            }
            call.resolve(JSObject().apply { put("items", items) })
        } catch (e: Exception) {
            call.reject("Не удалось проверить загрузки: ${e.message}", e)
        }
    }

    private fun completeFile(id: Long): Pair<JSONObject, File> {
        check(status(id).getString("status") == "complete") { "Загрузка ещё не завершена" }
        val record = requireNotNull(stored(id))
        val file = fileFor(record)
        check(file.isFile && file.length() > 0L) { "Файл пуст или отсутствует" }
        return record to file
    }

    @PluginMethod
    fun verifySha256(call: PluginCall) {
        val id = call.getString("id")?.toLongOrNull()
        if (id == null) {
            call.reject("Укажите ID загрузки")
            return
        }
        scope.launch {
            try {
                val (record, file) = completeFile(id)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { stream ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = record.optString("sha256")
                call.resolve(JSObject().apply {
                    put("name", record.getString("name"))
                    put("sha256", actual)
                    put("size", file.length())
                    put("verified", expected.isNotEmpty() && actual == expected)
                    put("checksumProvided", expected.isNotEmpty())
                })
            } catch (e: Exception) {
                call.reject("Проверка не удалась: ${e.message}", e)
            }
        }
    }

    @PluginMethod
    fun exportDownload(call: PluginCall) {
        val id = call.getString("id")?.toLongOrNull()
        if (id == null) {
            call.reject("Укажите ID загрузки")
            return
        }
        val record = try { completeFile(id).first } catch (e: Exception) {
            call.reject(e.message ?: "Ресурс недоступен")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, record.getString("name"))
        }
        startActivityForResult(call, intent, "exportSelected")
    }

    @ActivityCallback
    private fun exportSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Экспорт отменён")
            return
        }
        val id = call.getString("id")?.toLongOrNull()
        if (id == null) {
            call.reject("Загрузка не найдена")
            return
        }
        scope.launch {
            try {
                val (_, file) = completeFile(id)
                context.contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "Не удалось открыть место сохранения" }
                    file.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
                }
                call.resolve(JSObject().apply { put("saved", true) })
            } catch (e: Exception) {
                call.reject("Не удалось сохранить файл: ${e.message}", e)
            }
        }
    }

    /**
     * Offline, owner-approved portable story archive. System document picker
     * chooses the destination; NOVA never browses private folders by itself.
     * The JSON contains story text and gallery URIs, not the image bytes.
     */
    @PluginMethod
    fun exportStoryProjects(call: PluginCall) {
        val data = call.getString("payload") ?: ""
        if (data.toByteArray(Charsets.UTF_8).size > MAX_STORY_BACKUP_BYTES ||
            !validStoryArchive(data)) {
            call.reject("Резервная копия историй имеет неверный формат или слишком большая")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "NOVA-stories-backup.json")
            }
            startActivityForResult(call, intent, "storyExportSelected")
        } catch (e: Exception) {
            call.reject("Не удалось открыть выбор файла: ${e.message}", e)
        }
    }

    @ActivityCallback
    private fun storyExportSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Сохранение копии отменено")
            return
        }
        val json = call.getString("payload") ?: ""
        if (json.toByteArray(Charsets.UTF_8).size > MAX_STORY_BACKUP_BYTES ||
            !validStoryArchive(json)) {
            call.reject("Содержимое резервной копии изменилось или повреждено")
            return
        }
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "Нет доступа к выбранному документу" }
                    output.write(json.toByteArray(Charsets.UTF_8))
                }
                call.resolve(JSObject().apply { put("saved", true) })
            } catch (e: Exception) {
                call.reject("Не удалось записать истории: ${e.message}", e)
            }
        }
    }

    @PluginMethod
    fun importStoryProjects(call: PluginCall) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(call, intent, "storyImportSelected")
        } catch (e: Exception) {
            call.reject("Не удалось выбрать резервную копию: ${e.message}", e)
        }
    }

    @ActivityCallback
    private fun storyImportSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Восстановление отменено")
            return
        }
        scope.launch {
            try {
                val bytes = java.io.ByteArrayOutputStream()
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось прочитать документ" }
                    val block = ByteArray(8192)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        if (bytes.size() + count > MAX_STORY_BACKUP_BYTES)
                            error("Копия слишком большая: максимум 2 МиБ")
                        bytes.write(block, 0, count)
                    }
                }
                val data = bytes.toString(Charsets.UTF_8.name())
                require(validStoryArchive(data)) {
                    "Неверный формат. Выбери файл NOVA-stories-backup.json"
                }
                // Only return parsed JSON to the web UI. JS merges by id and
                // never overwrites or deletes any existing owner project.
                call.resolve(JSObject().apply { put("payload", data) })
            } catch (e: Exception) {
                call.reject("Невозможно восстановить истории: ${e.message}", e)
            }
        }
    }

    private fun validStoryArchive(json: String): Boolean = try {
        val archive = JSONObject(json)
        archive.optString("format") == "NOVA_STORIES_V1" &&
            archive.optInt("version") == 1 &&
            archive.optJSONArray("stories") != null
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val MAX_STORY_BACKUP_BYTES = 2 * 1024 * 1024
    }

    @PluginMethod
    fun cancelDownload(call: PluginCall) {
        val id = call.getString("id")?.toLongOrNull()
        if (id == null || stored(id) == null) {
            call.reject("Загрузка не найдена")
            return
        }
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Отменить и удалить загрузку?")
                .setMessage("Удалится только файл из каталога NOVA, другие файлы не затрагиваются.")
                .setNegativeButton("Оставить") { _, _ -> call.reject("Отменено") }
                .setOnCancelListener { call.reject("Отменено") }
                .setPositiveButton("Удалить") { _, _ ->
                    try {
                        val record = requireNotNull(stored(id))
                        manager.remove(id)
                        fileFor(record).delete()
                        preferences.edit().remove("download_$id").apply()
                        call.resolve(JSObject().apply { put("removed", true) })
                    } catch (e: Exception) {
                        call.reject("Не удалось удалить загрузку: ${e.message}", e)
                    }
                }
                .show()
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
