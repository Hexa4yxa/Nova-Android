package ai.nova.director

import org.junit.Assert.*
import org.junit.Test

class NovaDownloadPolicyTest {
    @Test fun acceptsHuggingFaceModel() {
        val a = NovaDownloadPolicy.validate(
            "https://huggingface.co/author/model/resolve/main/model.gguf?download=true",
            "model.gguf", null
        )
        assertEquals("huggingface.co", a.host)
        assertNull(a.sha256)
    }

    @Test fun acceptsVerifiedMedia() {
        val a = NovaDownloadPolicy.validate(
            "https://github.com/example/project/releases/download/v1/clip.mp4",
            "clip.mp4", "A".repeat(64)
        )
        assertEquals("a".repeat(64), a.sha256)
    }

    @Test fun blocksHttpLocalLanAndCredentials() {
        val links = listOf(
            "http://huggingface.co/model.gguf",
            "https://127.0.0.1/model.gguf",
            "https://huggingface.co.evil.test/model.gguf",
            "https://user:password@github.com/a.zip",
            "https://github.com:8443/a.zip",
            "https://192.168.1.4/a.zip"
        )
        links.forEach { url ->
            try {
                NovaDownloadPolicy.validate(url, "model.gguf", null)
                fail("Should block $url")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun blocksExecutableAndPathTraversal() {
        val names = listOf("../x.gguf", "model.apk", "setup.sh", "a/../b.gguf", ".gguf")
        names.forEach { filename ->
            try {
                NovaDownloadPolicy.validate("https://github.com/downloads/a", filename, null)
                fail("Should block $filename")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadChecksum() {
        NovaDownloadPolicy.validate("https://github.com/downloads/a", "a.zip", "abcd")
    }
}
