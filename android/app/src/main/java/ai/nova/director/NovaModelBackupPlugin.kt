package ai.nova.director

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResult
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
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recover the user's already-downloaded SD1.5 image model from any public
 * Documents/Download folder after a ONE-TIME app-signature transition.
 *
 * All access goes through Android's owner-controlled document picker.
 * No MANAGE_EXTERNAL_STORAGE, no unsafe /Android/data traversal, no network.
 * The source backup is always read-only and NEVER deleted or renamed.
 */
@CapacitorPlugin(name = "NovaModelBackup")
class NovaModelBackupPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    @PluginMethod
    fun restoreRecommended(call: PluginCall) {
        if (!busy.compareAndSet(false, true)) {
            call.reject("NOVA уже проверяет или восстанавливает модель")
            return
        }
        cancelRequested.set(false)
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(call, intent, "backupSelected")
        } catch (error: Exception) {
            busy.set(false)
            call.reject("Не удалось открыть системный выбор файла: ${error.message}", error)
        }
    }

    @PluginMethod
    fun cancelRestore(call: PluginCall) {
        cancelRequested.set(true)
        call.resolve(JSObject().apply { put("requested", busy.get()) })
    }

    @ActivityCallback
    private fun backupSelected(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            busy.set(false)
            call.reject("Выбор резервной копии отменён")
            return
        }
        scope.launch {
            try {
                val info = restore(uri)
                call.resolve(info)
            } catch (error: Exception) {
                call.reject("Не удалось восстановить графическую модель: ${error.message}", error)
            } finally {
                cancelRequested.set(false)
                busy.set(false)
            }
        }
    }

    private fun restore(uri: Uri): JSObject {
        // Never race a partial DownloadManager write against a restore.
        require(!NovaComputeGate.isImageReserved()) {
            "NOVA сейчас выполняет графическую задачу. Дождись её завершения."
        }
        val downloads = context.getSharedPreferences("nova_image_downloads", 0)
        val activeDownload = downloads.getLong("download_id", -1L)
        if (activeDownload > 0) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val unfinished = dm.query(DownloadManager.Query().setFilterById(activeDownload))
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) false else {
                        val col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = if (col >= 0) cursor.getInt(col) else 0
                        status in listOf(
                            DownloadManager.STATUS_RUNNING,
                            DownloadManager.STATUS_PENDING,
                            DownloadManager.STATUS_PAUSED,
                            DownloadManager.STATUS_SUCCESSFUL
                        )
                    }
                } ?: false
            require(!unfinished) {
                "NOVA ещё скачивает или проверяет графическую модель. Заверши эту операцию перед восстановлением."
            }
        }
        // Explicitly matching the filename stops accidental imports of text GGUF
        // or an arbitrary file selected from Downloads.
        val filename = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: error("Не удалось прочитать имя файла")
        require(filename == NovaRecommendedImageModel.FILENAME) {
            "Выбери ${NovaRecommendedImageModel.FILENAME} из Загрузки/NOVA-Models"
        }
        val current = NovaRecommendedImageModel.installed(context)
        if (current != null) {
            return JSObject().apply {
                put("restored", true)
                put("alreadyInstalled", true)
                put("verified", true)
                put("bytes", current.length())
                put("name", NovaRecommendedImageModel.NAME)
            }
        }

        val target = NovaRecommendedImageModel.target(context)
        val parent = requireNotNull(target.parentFile) { "Недоступна папка моделей NOVA" }
        check(parent.isDirectory || parent.mkdirs()) { "Нельзя создать папку для модели" }
        val temp = File(parent, target.name + ".restoring")
        val available = StatFs(parent.absolutePath).availableBytes
        val reported = context.contentResolver.openFileDescriptor(uri, "r")?.use {
            if (it.statSize > 0) it.statSize else -1L
        } ?: -1L
        val expectedSpace = if (reported in 1_500_000_000L..3_000_000_000L)
            reported + 1_000_000_000L else 4_000_000_000L
        require(available > expectedSpace) {
            "Недостаточно места для восстановления. Нужен запас не менее 1 ГБ сверх размера модели."
        }
        check(!temp.exists() || temp.delete()) {
            "Остался незавершённый файл восстановления. Попробуй снова."
        }

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastProgress = 0L
            context.contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "Android не дал прочитать резервную копию" }
                temp.outputStream().buffered(1024 * 1024).use { dest ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (cancelRequested.get()) error("Восстановление отменено")
                        val n = source.read(buffer)
                        if (n == -1) break
                        total += n
                        require(total <= 3_000_000_000L) { "Файл превышает допустимый размер" }
                        digest.update(buffer, 0, n)
                        dest.write(buffer, 0, n)
                        if (total - lastProgress >= 32L * 1024 * 1024) {
                            lastProgress = total
                            require(StatFs(parent.absolutePath).availableBytes > 1_000_000_000L) {
                                "Недостаточно свободного места. Резервная копия не затронута."
                            }
                            notifyListeners("novaModelRestoreProgress", JSObject().apply {
                                put("copied", total)
                                put("total", if (reported > 0) reported else NovaRecommendedImageModel.APPROX_BYTES)
                            })
                        }
                    }
                    dest.flush()
                }
            }
            require(total in 1_500_000_000L..3_000_000_000L) {
                "Неполная резервная копия модели"
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual == NovaRecommendedImageModel.SHA256) {
                "SHA-256 не совпала! Резервная копия осталась нетронутой, файл не подключён."
            }
            if (cancelRequested.get()) error("Восстановление отменено")
            // Only a fully verified model may replace a stale incomplete target.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            NovaRecommendedImageModel.activate(context, target)
            return JSObject().apply {
                put("restored", true)
                put("alreadyInstalled", false)
                put("verified", true)
                put("bytes", target.length())
                put("name", NovaRecommendedImageModel.NAME)
                put("sourcePreserved", true)
            }
        } finally {
            // No failure path removes the user's Download/NOVA-Models backup.
            if (temp.exists()) temp.delete()
        }
    }

    override fun handleOnDestroy() {
        cancelRequested.set(true)
        scope.cancel()
        super.handleOnDestroy()
    }
}
