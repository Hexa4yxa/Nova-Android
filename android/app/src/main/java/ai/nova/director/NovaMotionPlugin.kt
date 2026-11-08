package ai.nova.director

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
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
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline NOVA Motion Comic MVP.
 *
 * Single image panel at a time, surrounded by darkness; fade-in, very slow
 * camera drift and fade-out. Encodes a REAL silent H.264 MP4 on the phone
 * using Android MediaCodec + OpenGL ES + MediaMuxer. No network/FFmpeg/server.
 * Narration, sound effects and subtitles are NOT part of this first milestone.
 */
@CapacitorPlugin(name = "NovaMotion")
class NovaMotionPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val working = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @PluginMethod
    fun render(call: PluginCall) {
        if (Build.VERSION.SDK_INT < 29) {
            call.reject("Для экспорта в Галерею требуется Android 10 или новее.")
            return
        }
        val data: JSONArray = call.getArray("frames") ?: run {
            call.reject("Нужны готовые кадры проекта.")
            return
        }
        if (data.length() !in 1..6) {
            call.reject("Нужно от 1 до 6 готовых кадров.")
            return
        }
        val seconds = (call.getInt("secondsPerFrame") ?: 4).coerceIn(3, 7)
        val frames = try {
            (0 until data.length()).map { index ->
                val uri = Uri.parse(data.getString(index))
                require(uri.scheme == "content" && uri.authority == "media" &&
                    (uri.path?.contains("/images/media/") == true)) {
                    "Кадр ${index + 1} не является сохранённым изображением Галереи."
                }
                uri
            }
        } catch (error: Exception) {
            call.reject(error.message ?: "Неверные ссылки на кадры.")
            return
        }
        if (!working.compareAndSet(false, true)) {
            call.reject("NOVA уже создаёт видео. Дождись окончания или останови монтаж.")
            return
        }
        stopRequested.set(false)
        scope.launch {
            var savedUri: Uri? = null
            try {
                savedUri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "NOVA-Comic-${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NOVA")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                ) ?: error("Android не создал файл будущего видео.")
                encode(savedUri, frames, seconds)
                context.contentResolver.update(savedUri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                call.resolve(JSObject().apply {
                    put("saved", true)
                    put("videoUri", savedUri.toString())
                    put("frames", frames.size)
                    put("seconds", frames.size * seconds)
                    put("silent", true)
                })
            } catch (error: Exception) {
                // Never leave a corrupt or unfinished MP4 visible in Gallery.
                savedUri?.let { uri ->
                    try { context.contentResolver.delete(uri, null, null) }
                    catch (_: Exception) {}
                }
                call.reject(error.message ?: "Монтаж не завершён.")
            } finally {
                working.set(false)
                stopRequested.set(false)
            }
        }
    }

    @PluginMethod
    fun openVideo(call: PluginCall) {
        val raw = call.getString("videoUri") ?: ""
        val uri = Uri.parse(raw)
        if (uri.scheme != "content" || uri.authority != "media" ||
            uri.path?.contains("/video/media/") != true) {
            call.reject("В проекте нет доступного видео NOVA.")
            return
        }
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            call.resolve(JSObject().apply { put("opened", true) })
        } catch (error: Exception) {
            call.reject("Не удалось открыть MP4: " + (error.message ?: "нет видеоплеера"))
        }
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        stopRequested.set(true)
        call.resolve(JSObject().apply { put("requested", working.get()) })
    }

    private fun checkActive() {
        if (stopRequested.get()) error("Монтаж остановлен. Незавершённый MP4 удалён; PNG сохранены.")
    }

    private fun frameBitmap(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Не удалось открыть исходный кадр в Галерее.")
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Повреждённый PNG проекта." }
        val sample = generateSequence(1) { if (it * 2 <= min(opts.outWidth, opts.outHeight) / 1536) it * 2 else null }
            .last()
        val read = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, read) }
            ?: error("Кадр удалён из Галереи или доступ к нему закрыт. Исходные проекты сохранены.")
    }

    private fun encode(output: Uri, frames: List<Uri>, seconds: Int) {
        val width = 576
        val height = 1024
        val fps = 24
        val frameCount = seconds * fps
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var codecStarted = false
        var muxerStarted = false
        var surface: Surface? = null
        var egl: PanelRenderer? = null
        var muxer: MediaMuxer? = null
        var descriptor: android.os.ParcelFileDescriptor? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            codecStarted = true
            descriptor = context.contentResolver.openFileDescriptor(output, "w")
                ?: error("Android не выдал доступ к файлу MP4.")
            run {
                muxer = MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val writer = muxer ?: error("Android не открыл MP4-контейнер.")
                val info = MediaCodec.BufferInfo()
                var finished = false
                fun drain(end: Boolean) {
                    var empty = 0
                    while (true) {
                        val index = codec.dequeueOutputBuffer(info, if (end) 20_000L else 0L)
                        when (index) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                if (!end) return
                                if (++empty > 500) error("Видеокодек не закончил MP4.")
                            }
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                check(!muxerStarted) { "Повторная инициализация MP4." }
                                val track = writer.addTrack(codec.outputFormat)
                                check(track >= 0) { "Некорректная видеодорожка." }
                                writer.start()
                                muxerStarted = true
                            }
                            else -> if (index >= 0) {
                                if (info.size > 0) {
                                    check(muxerStarted) { "Видеодорожка ещё не готова." }
                                    val encoded = codec.getOutputBuffer(index)
                                        ?: error("Видеокодек вернул пустой буфер.")
                                    encoded.position(info.offset)
                                    encoded.limit(info.offset + info.size)
                                    writer.writeSampleData(0, encoded, info)
                                }
                                finished = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                codec.releaseOutputBuffer(index, false)
                                if (finished) return
                            }
                        }
                        if (!end && index < 0) return
                    }
                }
                egl = PanelRenderer(surface, width, height)
                val renderer = egl ?: error("Не удалось открыть видеоповерхность.")
                for ((scene, uri) in frames.withIndex()) {
                    checkActive()
                    val bitmap = frameBitmap(uri)
                    try {
                        renderer.setImage(bitmap)
                        for (frame in 0 until frameCount) {
                            checkActive()
                            val progress = frame.toFloat() / frameCount.toFloat()
                            renderer.draw(progress, scene)
                            val index = scene.toLong() * frameCount + frame
                            renderer.present(index * 1_000_000_000L / fps)
                            drain(false)
                        }
                    } finally {
                        renderer.clearImage()
                        bitmap.recycle()
                    }
                    notifyListeners("novaMotionProgress", JSObject().apply {
                        put("done", scene + 1)
                        put("total", frames.size)
                    })
                }
                codec.signalEndOfInputStream()
                drain(true)
                check(finished && muxerStarted) { "Видеокодек не завершил запись." }
            }
        } finally {
            try { egl?.close() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            if (codecStarted) try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            if (muxerStarted) try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { descriptor?.close() } catch (_: Exception) {}
        }
    }

    override fun handleOnDestroy() {
        stopRequested.set(true)
        scope.cancel()
        super.handleOnDestroy()
    }
}

/** EGL → MediaCodec input surface; only one panel is visible at any time. */
private class PanelRenderer(private val target: Surface, private val width: Int, private val height: Int) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val eglSurface: android.opengl.EGLSurface
    private val context: android.opengl.EGLContext
    private val program: Int
    private val vertices: FloatBuffer
    private var texture = 0
    private var imageWidth = 1
    private var imageHeight = 1
    private val positionLocation: Int
    private val uvLocation: Int
    private val zoomLocation: Int
    private val offsetLocation: Int
    private val fadeLocation: Int

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL-дисплей недоступен." }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "EGL не запустился." }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // EGL_RECORDABLE_ANDROID: required for MediaCodec's encoder surface.
            0x3142, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "Нет совместимой EGL-конфигурации."
        }
        val config = configs[0] ?: error("EGL не выдал конфигурацию.")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Не создан OpenGL ES 2 контекст." }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Видеоповерхность EGL недоступна." }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "OpenGL не активирован." }
        val vertex = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { gl_Position = vec4(aPos,0.0,1.0); vUv = aUv; }
        """.trimIndent()
        val fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uZoom;
            uniform vec2 uOffset;
            uniform float uFade;
            void main() {
                vec2 uv = (vUv - 0.5) / uZoom + vec2(0.5) + uOffset;
                gl_FragColor = vec4(texture2D(uTexture,clamp(uv,0.0,1.0)).rgb*uFade,1.0);
            }
        """.trimIndent()
        program = GLES20.glCreateProgram()
        val v = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val link = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        check(link[0] == GLES20.GL_TRUE) { "Шейдер видеокомикса не собрался." }
        positionLocation = GLES20.glGetAttribLocation(program, "aPos")
        uvLocation = GLES20.glGetAttribLocation(program, "aUv")
        zoomLocation = GLES20.glGetUniformLocation(program, "uZoom")
        offsetLocation = GLES20.glGetUniformLocation(program, "uOffset")
        fadeLocation = GLES20.glGetUniformLocation(program, "uFade")
        vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f,-1f, 0f,1f,
                 1f,-1f, 1f,1f,
                -1f, 1f, 0f,0f,
                 1f, 1f, 1f,0f
            ))
            position(0)
        }
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Ошибка OpenGL: ${GLES20.glGetShaderInfoLog(id)}" }
        return id
    }

    fun setImage(bitmap: Bitmap) {
        clearImage()
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        check(texture != 0) { "Нет памяти для текстуры кадра." }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Не удалось загрузить кадр в GPU." }
    }

    fun draw(progress: Float, scene: Int) {
        val t = progress.coerceIn(0f, 1f)
        GLES20.glViewport(0,0,width,height)
        GLES20.glClearColor(0f,0f,0f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val ratio = imageWidth.toFloat() / imageHeight.toFloat()
        val pw = min(width * 0.86f, height * 0.70f * ratio)
        val ph = pw / ratio
        val panelWidth = pw.roundToInt()
        val panelHeight = ph.roundToInt()
        GLES20.glViewport((width-panelWidth)/2,(height-panelHeight)/2,panelWidth,panelHeight)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLES20.glUniform1f(zoomLocation,1.02f+0.07f*t)
        GLES20.glUniform2f(offsetLocation,(if(scene%2==0) 1f else -1f)*0.014f*(t-0.5f),0.007f*(0.5f-t))
        val appearance = (t/0.17f).coerceIn(0f,1f)
        val departure = ((1f-t)/0.16f).coerceIn(0f,1f)
        val fade = (min(appearance,departure)).let { it*it*(3-2*it) }
        GLES20.glUniform1f(fadeLocation,fade)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation,2,GLES20.GL_FLOAT,false,16,vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(uvLocation)
        GLES20.glVertexAttribPointer(uvLocation,2,GLES20.GL_FLOAT,false,16,vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(uvLocation)
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "OpenGL не нарисовал кадр." }
    }

    fun present(nanoseconds: Long) {
        EGLExt.eglPresentationTimeANDROID(display,eglSurface,nanoseconds)
        check(EGL14.eglSwapBuffers(display,eglSurface)) { "Не удалось передать кадр видеокодеку." }
    }

    fun clearImage() {
        if(texture!=0){
            GLES20.glDeleteTextures(1,intArrayOf(texture),0)
            texture=0
        }
    }

    fun close() {
        clearImage()
        GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,eglSurface)
        EGL14.eglDestroyContext(display,context)
        EGL14.eglTerminate(display)
    }
}
