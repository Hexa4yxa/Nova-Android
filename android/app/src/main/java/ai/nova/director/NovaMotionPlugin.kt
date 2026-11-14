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
/** A shot references a saved Gallery image. It never modifies the PNG. */
private data class ComicShot(
    val uri: Uri, val seconds: Int, val plan: String, val motion: String,
    val panelIndex: Int = -1, val panelCount: Int = 0, val effect: String = "none"
)

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
        // Backward compatible: previous APKs use frames[] + secondsPerFrame.
        // New Storyboard Director uses validated shots[] (up to 16 edits).
        val shots = try {
            val editList = call.getArray("shots")
            if (editList != null) {
                require(editList.length() in 1..16) { "Нужно от 1 до 16 режиссёрских планов." }
                (0 until editList.length()).map { index ->
                    val item = editList.getJSONObject(index)
                    val uri = checkedImageUri(item.getString("imageUri"), index)
                    val seconds = item.optInt("durationSeconds", 3)
                    require(seconds in 2..5) { "План ${index + 1}: длительность от 2 до 5 секунд." }
                    val plan = item.optString("plan", "medium")
                    val motion = item.optString("motion", "hold")
                    require(plan in setOf("wide", "medium", "close")) { "Неподдерживаемый размер плана." }
                    require(motion in setOf("hold", "push", "pull", "pan-left", "pan-right", "tilt-up", "tilt-down")) {
                        "Неподдерживаемое движение камеры."
                    }
                    val panelIndex = item.optInt("panelIndex", -1)
                    val panelCount = item.optInt("panelCount", 0)
                    require((panelIndex == -1 && panelCount == 0) ||
                        (panelCount in 1..6 && panelIndex in 0 until panelCount)) {
                        "Неверный номер панели комикса."
                    }
                    val effect=item.optString("effect", "none")
                    require(effect in setOf("none","rain","torch","glow","fog")) {
                        "Неподдерживаемый эффект сцены."
                    }
                    ComicShot(uri,seconds,plan,motion,panelIndex,panelCount,effect)
                }
            } else {
                val frames: JSONArray = call.getArray("frames")
                    ?: error("Нужны готовые кадры проекта.")
                require(frames.length() in 1..6) { "Нужно от 1 до 6 готовых кадров." }
                val seconds = (call.getInt("secondsPerFrame") ?: 4).coerceIn(3, 7)
                (0 until frames.length()).map { index ->
                    ComicShot(checkedImageUri(frames.getString(index), index), seconds, "wide",
                        if (index % 2 == 0) "pan-right" else "pan-left")
                }
            }
        } catch (error: Exception) {
            call.reject(error.message ?: "Некорректная раскадровка.")
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
                encode(savedUri, shots)
                context.contentResolver.update(savedUri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                call.resolve(JSObject().apply {
                    put("saved", true)
                    put("videoUri", savedUri.toString())
                    put("frames", shots.map { it.uri }.distinct().size)
                    put("shots", shots.size)
                    put("seconds", shots.sumOf { it.seconds })
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

    private fun checkedImageUri(raw: String, index: Int): Uri {
        val uri = Uri.parse(raw)
        require(uri.scheme == "content" && uri.authority == "media" &&
            uri.path?.contains("/images/media/") == true) {
            "Кадр ${index + 1} не является изображением из Галереи."
        }
        return uri
    }

    private fun checkActive() {
        if (stopRequested.get()) error("Монтаж остановлен. Незавершённый MP4 удалён; PNG сохранены.")
    }

    private fun frameBitmap(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val source=resolver.openInputStream(uri)
            ?: error("Не удалось открыть исходный кадр в Галерее.")
        source.use { BitmapFactory.decodeStream(it, null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Повреждённый PNG проекта." }
        val sample = generateSequence(1) { if (it * 2 <= min(opts.outWidth, opts.outHeight) / 1536) it * 2 else null }
            .last()
        val read = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, read) }
            ?: error("Кадр удалён из Галереи или доступ к нему закрыт. Исходные проекты сохранены.")
    }

    private fun encode(output: Uri, shots: List<ComicShot>) {
        val width = 576
        val height = 1024
        val fps = 24
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
            val inputSurface = codec.createInputSurface()
            surface = inputSurface
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
                egl = PanelRenderer(inputSurface, width, height)
                val renderer = egl ?: error("Не удалось открыть видеоповерхность.")
                var frameIndex = 0L
                for ((scene, shot) in shots.withIndex()) {
                    checkActive()
                    val bitmap = frameBitmap(shot.uri)
                    try {
                        renderer.setImage(bitmap,shot.panelIndex,shot.panelCount)
                        val frameCount = shot.seconds * fps
                        for (frame in 0 until frameCount) {
                            checkActive()
                            val progress = frame.toFloat() / frameCount.toFloat()
                            renderer.draw(progress,scene,shot.plan,shot.motion,shot.effect)
                            renderer.present(frameIndex * 1_000_000_000L / fps)
                            frameIndex++
                            drain(false)
                        }
                    } finally {
                        renderer.clearImage()
                        bitmap.recycle()
                    }
                    notifyListeners("novaMotionProgress", JSObject().apply {
                        put("done", scene + 1)
                        put("total", shots.size)
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
    private val cropLocation: Int
    private val effectLocation: Int
    private val timeLocation: Int
    private var cropLeft = 0f
    private var cropTop = 0f
    private var cropWidth = 1f
    private var cropHeight = 1f

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
            uniform vec4 uCrop;
            uniform float uEffect;
            uniform float uTime;
            void main() {
                vec2 local = clamp((vUv - 0.5) / uZoom + vec2(0.5) + uOffset,0.0,1.0);
                vec2 uv = uCrop.xy + local*uCrop.zw;
                vec3 color=texture2D(uTexture,uv).rgb;
                // Lightweight optical effects on the currently visible panel.
                if(uEffect>0.5 && uEffect<1.5) {
                    float band=floor(vUv.y*25.0);
                    float random=fract(sin(band*12.9898)*43758.5453);
                    float streak=1.0-smoothstep(0.0,0.040,
                        abs(fract(vUv.x*20.0+random+uTime*1.8)-0.5));
                    color=mix(color,vec3(0.83,0.89,1.0),streak*0.17);
                } else if(uEffect>1.5 && uEffect<2.5) {
                    float flicker=0.04+0.04*sin(uTime*15.0)+0.025*sin(uTime*29.0);
                    color=clamp(color+vec3(flicker*1.5,flicker*0.65,0.0),0.0,1.0);
                } else if(uEffect>2.5 && uEffect<3.5) {
                    float shine=0.08+0.07*sin(uTime*4.0);
                    color=mix(color,vec3(0.79,0.87,1.0),shine);
                } else if(uEffect>3.5 && uEffect<4.5) {
                    float haze=0.04+0.06*sin(vUv.y*8.0+vUv.x*4.0+uTime*0.6);
                    color=mix(color,vec3(0.73,0.76,0.84),max(haze,0.0));
                }
                gl_FragColor=vec4(color*uFade,1.0);
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
        cropLocation = GLES20.glGetUniformLocation(program, "uCrop")
        effectLocation = GLES20.glGetUniformLocation(program, "uEffect")
        timeLocation = GLES20.glGetUniformLocation(program, "uTime")
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

    fun setImage(bitmap: Bitmap, panelIndex: Int = -1, panelCount: Int = 0) {
        clearImage()
        if(panelIndex>=0) {
            val tile=NovaComicLayout.panel(panelIndex,panelCount)
            // Use the saved SINGLE comic PNG, but display only one tile at a time.
            cropLeft=tile.left.toFloat()/NovaComicLayout.WIDTH
            cropTop=tile.top.toFloat()/NovaComicLayout.HEIGHT
            cropWidth=tile.width().toFloat()/NovaComicLayout.WIDTH
            cropHeight=tile.height().toFloat()/NovaComicLayout.HEIGHT
            imageWidth=tile.width()
            imageHeight=tile.height()
        } else {
            cropLeft=0f;cropTop=0f;cropWidth=1f;cropHeight=1f
            imageWidth=bitmap.width
            imageHeight=bitmap.height
        }
        // Preserve aspect ratio while filling 576x1024; don't stretch faces.
        // For a comic tile, apply the cover crop *within* the tile's UV rect.
        val fill=NovaVideoFraming.cover(imageWidth,imageHeight,width,height)
        val originalLeft=cropLeft
        val originalTop=cropTop
        val originalWidth=cropWidth
        val originalHeight=cropHeight
        cropLeft=originalLeft+originalWidth*fill.left
        cropTop=originalTop+originalHeight*fill.top
        cropWidth=originalWidth*fill.width
        cropHeight=originalHeight*fill.height
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

    fun draw(progress: Float, scene: Int, plan: String, motion: String, effect: String = "none") {
        val t = progress.coerceIn(0f, 1f)
        GLES20.glViewport(0,0,width,height)
        GLES20.glClearColor(0f,0f,0f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Full-frame video: already center-cropped to 9:16 during setImage().
        // Previous letterboxed comic viewport used only 70% of the display height.
        // Keep the viewport at the encoder's full dimensions.
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        val startZoom = when (plan) {
            "close" -> 1.65f
            "medium" -> 1.30f
            else -> 1.06f
        }
        val zoomMotion = when (motion) {
            "pull" -> -0.14f
            "push" -> 0.14f
            else -> 0.03f
        }
        GLES20.glUniform1f(zoomLocation, startZoom + zoomMotion*t)
        val distance = 0.06f*(t-0.5f)
        val x = when (motion) {
            "pan-left" -> -distance
            "pan-right" -> distance
            else -> if (motion=="hold") 0f else (if(scene%2==0) 0.008f else -0.008f)*(t-0.5f)
        }
        val y = when (motion) {
            "tilt-up" -> -distance
            "tilt-down" -> distance
            else -> 0f
        }
        GLES20.glUniform2f(offsetLocation,x,y)
        val appearance = (t/0.17f).coerceIn(0f,1f)
        val departure = ((1f-t)/0.16f).coerceIn(0f,1f)
        val fade = (min(appearance,departure)).let { it*it*(3-2*it) }
        GLES20.glUniform1f(fadeLocation,fade)
        GLES20.glUniform4f(cropLocation,cropLeft,cropTop,cropWidth,cropHeight)
        GLES20.glUniform1f(timeLocation,scene*4f+t*4f)
        val effectId=when(effect) {
            "rain"->1f;"torch"->2f;"glow"->3f;"fog"->4f;else->0f
        }
        GLES20.glUniform1f(effectLocation,effectId)
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
