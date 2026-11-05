package ai.nova.director

import java.net.URI
import java.util.Locale

/**
 * Network access is only for explicitly approved asset downloads.
 * No application packages, scripts, arbitrary hosts, or local-network URLs.
 */
internal object NovaDownloadPolicy {
    data class Asset(val url: String, val filename: String, val host: String, val sha256: String?)

    private val extensions = setOf(
        "gguf", "safetensors", "onnx", "zip", "png", "jpg", "jpeg", "webp",
        "mp4", "mp3", "wav", "json"
    )
    private val exactHosts = setOf(
        "huggingface.co", "github.com", "raw.githubusercontent.com",
        "cdn-lfs.huggingface.co", "cdn-lfs.hf.co"
    )

    fun validate(url: String?, filename: String?, sha256: String?): Asset {
        val raw = url?.trim().orEmpty()
        require(raw.length in 12..2048) { "Укажите ссылку HTTPS на разрешённый источник" }
        val uri = try { URI(raw) } catch (_: Exception) { null }
        require(uri != null && uri.scheme.equals("https", true)) {
            "Разрешены только ссылки HTTPS"
        }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(host in exactHosts && uri.userInfo == null && uri.port == -1) {
            "Источник не разрешён. Используйте GitHub или Hugging Face"
        }
        require(!uri.path.isNullOrBlank() && uri.path != "/") { "Ссылка не содержит файл" }

        val name = filename?.trim().orEmpty()
        require(name.length in 4..128 && Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(name)) {
            "Название файла: только латинские буквы, цифры, точка, дефис и _"
        }
        require(!name.contains("..") && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions) {
            "Формат не разрешён. APK и исполняемые файлы скачивать нельзя"
        }
        val hash = sha256?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { null }
        require(hash == null || Regex("^[0-9a-f]{64}$").matches(hash)) {
            "SHA-256 должен содержать ровно 64 шестнадцатеричных символа"
        }
        return Asset(raw, name, host, hash)
    }
}
