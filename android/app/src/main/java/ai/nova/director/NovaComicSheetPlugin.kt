package ai.nova.director

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
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
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.AtomicBoolean

/** One physical PNG sheet; the video reader crops its panels from THIS image. */
internal object NovaComicLayout {
    const val WIDTH = 1080
    const val HEIGHT = 1620
    private const val MARGIN = 32
    private const val HEADER = 114
    private const val BOTTOM = 28
    private const val GAP = 18

    fun panel(index: Int, count: Int): Rect {
        require(count in 1..6 && index in 0 until count)
        val columns = 2
        val rows = (count + 1) / 2
        val w = (WIDTH - 2 * MARGIN - GAP) / columns
        val h = (HEIGHT - HEADER - BOTTOM - GAP * (rows - 1)) / rows
        val col = index % columns
        val row = index / columns
        val x = MARGIN + col * (w + GAP)
        val y = HEADER + row * (h + GAP)
        return Rect(x,y,x+w,y+h)
    }
}

@CapacitorPlugin(name = "NovaComicSheet")
class NovaComicSheetPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val working = AtomicBoolean(false)

    @PluginMethod
    fun build(call: PluginCall) {
        val input: JSONArray = call.getArray("frames") ?: run {
            call.reject("NOVA не нашла кадры для страницы комикса.")
            return
        }
        if (input.length() !in 1..6) {
            call.reject("Для страницы нужны от 1 до 6 готовых кадров.")
            return
        }
        if (!working.compareAndSet(false,true)) {
            call.reject("Страница комикса уже создаётся.")
            return
        }
        val frames = try {
            (0 until input.length()).map { i ->
                val item = input.getJSONObject(i)
                val uri = checked(item.getString("imageUri"))
                val beat = item.optString("beat","Сцена ${i+1}").take(135)
                uri to beat
            }
        } catch (error: Exception) {
            working.set(false)
            call.reject("Неверные кадры комикса: " + (error.message ?: "ошибка"))
            return
        }
        val title = (call.getString("title") ?: "История NOVA")
            .trim().take(72).ifEmpty { "История NOVA" }
        scope.launch {
            var saved: Uri? = null
            try {
                val sheet = Bitmap.createBitmap(
                    NovaComicLayout.WIDTH,NovaComicLayout.HEIGHT,Bitmap.Config.ARGB_8888)
                try {
                    paintSheet(sheet,title,frames)
                    val outputUri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME,"NOVA-Comic-${System.currentTimeMillis()}.png")
                            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/NOVA")
                            put(MediaStore.Images.Media.IS_PENDING,1)
                        }
                    ) ?: error("Не получилось создать комикс в Галерее.")
                    saved = outputUri
                    context.contentResolver.openOutputStream(outputUri)?.use { out ->
                        check(sheet.compress(Bitmap.CompressFormat.PNG,100,out)) {
                            "Не удалось записать PNG комикса."
                        }
                    } ?: error("Android не открыл файл комикса.")
                    context.contentResolver.update(outputUri,ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING,0)
                    },null,null)
                    call.resolve(JSObject().apply {
                        put("saved",true)
                        put("pageUri",outputUri.toString())
                        put("panels",frames.size)
                        put("width",NovaComicLayout.WIDTH)
                        put("height",NovaComicLayout.HEIGHT)
                    })
                } finally { sheet.recycle() }
            } catch (error: Exception) {
                saved?.let { try {context.contentResolver.delete(it,null,null)}catch(_:Exception){} }
                call.reject("Комикс не создан: " + (error.message ?: "неизвестная ошибка"))
            } finally { working.set(false) }
        }
    }

    private fun checked(raw: String): Uri {
        val uri = Uri.parse(raw)
        require(uri.scheme=="content" && uri.authority=="media" &&
                Regex("^/[^/]+/images/media/[0-9]+$").matches(uri.path?:"")) {
            "NOVA принимает только сохранённые PNG из Галереи."
        }
        return uri
    }

    private fun readBitmap(uri: Uri): Bitmap {
        val opts=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        val boundsStream=context.contentResolver.openInputStream(uri)
            ?: error("Нет доступа к PNG сцены.")
        boundsStream.use { input -> BitmapFactory.decodeStream(input,null,opts) }
        require(opts.outWidth>0 && opts.outHeight>0) {"Повреждённый кадр проекта."}
        var sample=1
        while (max(opts.outWidth,opts.outHeight)/sample>1536) sample*=2
        val actual=BitmapFactory.Options().apply {inSampleSize=sample}
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input,null,actual)
        } ?: error("Не удалось прочитать кадр из Галереи.")
    }

    private fun paintSheet(sheet: Bitmap,title:String,frames:List<Pair<Uri,String>>) {
        val canvas=Canvas(sheet)
        val brush=Paint(Paint.ANTI_ALIAS_FLAG).apply {isFilterBitmap=true}
        canvas.drawColor(Color.rgb(245,238,221))
        brush.color=Color.rgb(47,36,51)
        brush.textSize=45f;brush.isFakeBoldText=true
        canvas.drawText("NOVA  /  КИНОКОМИКС",34f,58f,brush)
        brush.textSize=25f;brush.isFakeBoldText=false
        canvas.drawText(title.take(58),35f,95f,brush)
        for ((i,pair) in frames.withIndex()) {
            val card=NovaComicLayout.panel(i,frames.size)
            brush.color=Color.rgb(255,252,243)
            canvas.drawRoundRect(RectF(card),12f,12f,brush)
            brush.style=Paint.Style.STROKE
            brush.strokeWidth=3f;brush.color=Color.rgb(77,62,65)
            canvas.drawRoundRect(RectF(card),12f,12f,brush)
            brush.style=Paint.Style.FILL
            brush.color=Color.rgb(61,49,61)
            brush.textSize=31f;brush.isFakeBoldText=true
            canvas.drawText(String.format("%02d",i+1),card.left+16f,card.top+36f,brush)
            brush.isFakeBoldText=false
            brush.textSize=21f
            canvas.drawText("СЦЕНА "+(i+1),card.left+80f,card.top+35f,brush)
            val imageBounds=Rect(card.left+8,card.top+50,card.right-8,card.bottom-87)
            val bitmap=readBitmap(pair.first)
            try {
                val targetRatio=imageBounds.width().toFloat()/imageBounds.height()
                val imageRatio=bitmap.width.toFloat()/bitmap.height
                val source=if(imageRatio>targetRatio) {
                    val w=(bitmap.height*targetRatio).toInt()
                    val left=(bitmap.width-w)/2
                    Rect(left,0,left+w,bitmap.height)
                } else {
                    val h=(bitmap.width/targetRatio).toInt()
                    val top=(bitmap.height-h)/2
                    Rect(0,top,bitmap.width,top+h)
                }
                brush.color=Color.WHITE
                canvas.drawBitmap(bitmap,source,imageBounds,brush)
            } finally { bitmap.recycle() }
            brush.color=Color.rgb(47,36,51)
            brush.textSize=22f
            val words=pair.second.replace(Regex("[\\r\\n]+")," ").trim()
            var remaining=words
            for(line in 0..1) {
                if(remaining.isEmpty()) break
                val width=card.width()-35f
                var take=brush.breakText(remaining,true,width,null).coerceAtLeast(1)
                if(take<remaining.length) {
                    val space=remaining.lastIndexOf(' ',take)
                    if(space>0)take=space
                }
                val text=remaining.take(take).trim()
                canvas.drawText(text,card.left+16f,card.bottom-48f+line*26f,brush)
                remaining=remaining.drop(take).trim()
            }
        }
    }

    @PluginMethod
    fun openPage(call: PluginCall) {
        val uri=try { checked(call.getString("pageUri")?:"") }
                catch(error:Exception){call.reject("Комикс не найден.");return}
        try {
            val intent=Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri,"image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            call.resolve(JSObject().apply {put("opened",true)})
        } catch(error:Exception){call.reject("Галерея не открыла комикс: "+(error.message?:"ошибка"))}
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
