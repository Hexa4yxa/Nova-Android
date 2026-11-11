package ai.nova.director

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * NOVA offline comic narration: on-device voices ONLY (never a network TTS
 * voice) and original synthesized ambient rain/torch/fog/glow effects.
 *
 * Muxes an AAC mono track into a COPY of the already rendered H.264 movie:
 * source silent MP4 and all original images/projects remain untouched.
 * No microphone, external server, ffmpeg or model download required.
 */
@CapacitorPlugin(name = "NovaVoice")
class NovaVoicePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val initialised = CompletableDeferred<Boolean>()
    private val utterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile private var engine: TextToSpeech? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { id?.let { utterances.remove(it)?.complete(true) } }
        override fun onError(id: String?) { id?.let { utterances.remove(it)?.complete(false) } }
    }

    override fun load() {
        super.load()
        engine = TextToSpeech(context) { code ->
            if (!initialised.isCompleted) initialised.complete(code == TextToSpeech.SUCCESS)
        }
    }

    private suspend fun availableEngine(): TextToSpeech? {
        val ready = withTimeoutOrNull(8000) { initialised.await() } ?: false
        val t = engine
        if (!ready || t == null) return null
        t.setOnUtteranceProgressListener(listener)
        return t
    }


    // An installed offline system TTS engine (e.g. HayaiTTS) can be used for
    // this project WITHOUT changing the user's system-wide default engine.
    // Only registered Android TTS services are accepted. No cloud synthesis.
    private suspend fun <T> withSelectedEngine(
        packageName: String?, action: suspend (TextToSpeech) -> T
    ): T {
        val original=availableEngine() ?: error("Системный синтезатор Android недоступен.")
        val requested=packageName?.trim().orEmpty()
        if(requested.isEmpty() || requested==original.defaultEngine) return action(original)
        require(requested.length <= 180 && original.engines.any { it.name==requested }) {
            "Выбранный движок TTS не установлен. Установи офлайн-голос и проверь его в NOVA."
        }
        val ready=CompletableDeferred<Boolean>()
        val chosen=TextToSpeech(context,{ code ->
            if(!ready.isCompleted)ready.complete(code==TextToSpeech.SUCCESS)
        },requested)
        try {
            val initialized=withTimeoutOrNull(12000){ready.await()} ?: false
            check(initialized) {
                "Офлайн-движок не запустился. Проверь, что модель голоса установлена."
            }
            chosen.setOnUtteranceProgressListener(listener)
            return action(chosen)
        } finally { chosen.shutdown() }
    }

    private fun localVoices(t: TextToSpeech): List<Voice> =
        (t.voices ?: emptySet()).filter {
            !it.isNetworkConnectionRequired &&
                (it.locale.language == "uk" || it.locale.language == "ru")
        }.sortedWith(compareByDescending<Voice> { it.quality }
            .thenBy { it.latency }
            .thenBy { it.name })

    @PluginMethod
    fun status(call: PluginCall) {
        scope.launch {
            try {
                withSelectedEngine(call.getString("engineId")) { t ->
                    val voices=localVoices(t)
                    call.resolve(JSObject().apply {
                        put("available",voices.isNotEmpty())
                        put("count",voices.size)
                        put("engine",call.getString("engineId")?.trim()?.takeIf { it.isNotEmpty() } ?: t.defaultEngine ?: "")
                        put("systemDefaultEngine",t.defaultEngine ?: "")
                        put("detail",if(voices.isEmpty())
                            "В этом движке нет установленных офлайн-голосов ru/uk."
                            else "Готовы локальные голоса Android. Интернет не нужен.")
                        put("engines",JSArray().apply {
                            t.engines.forEach { info ->
                                put(JSObject().apply {
                                    put("id",info.name)
                                    put("label",info.label.toString())
                                })
                            }
                        })
                        put("voices",JSArray().apply {
                            voices.forEach { voice ->
                                put(JSObject().apply {
                                    put("id",voice.name)
                                    put("language",voice.locale.language)
                                    put("quality",voice.quality)
                                    put("latency",voice.latency)
                                })
                            }
                        })
                    })
                }
            } catch (error: Exception) {
                call.reject("Не удалось проверить офлайн-голоса: " + (error.message ?: "ошибка"))
            }
        }
    }

    @PluginMethod
    fun preview(call: PluginCall) {
        scope.launch {
            try {
                check(!busy.get()) { "Сейчас NOVA озвучивает фильм. Прослушать голос можно после завершения." }
                withSelectedEngine(call.getString("engineId")) { t ->
                val line = (call.getString("text") ?: "Привет! Я Нова, твой офлайн-режиссёр.").take(140)
                val lang = if (Regex("[ІіЇїЄєҐґ]").containsMatchIn(line)) "uk" else "ru"
                val voices = localVoices(t)
                val requestedId=call.getString("voiceId")?.trim().orEmpty()
                val matched=voices.filter { it.locale.language == lang }
                val voice = matched.firstOrNull { it.name == requestedId }
                    ?:matched.firstOrNull()
                    ?:voices.firstOrNull()
                    ?:error("Нет установленных локальных голосов. Интернет-голоса запрещены.")
                check(line.isNotBlank()) { "Нужен текст для озвучивания." }
                t.voice = voice
                t.setSpeechRate(0.96f)
                t.setPitch(1.0f)
                val id="nova-preview-"+UUID.randomUUID()
                val completed=CompletableDeferred<Boolean>()
                utterances[id]=completed
                val result=t.speak(line,TextToSpeech.QUEUE_FLUSH,Bundle(),id)
                if(result!=TextToSpeech.SUCCESS)utterances.remove(id)
                check(result==TextToSpeech.SUCCESS) { "Android не запустил локальную озвучку." }
                val spoken=withTimeoutOrNull(30000){completed.await()} ?: false
                utterances.remove(id)
                check(spoken) { "Не удалось прослушать выбранный офлайн-голос." }
                call.resolve(JSObject().apply {
                    put("started",true);put("voice",voice.name);put("engine",call.getString("engineId")?.trim()?.takeIf { it.isNotEmpty() } ?: t.defaultEngine ?: "")
                })
                }
            } catch(error: Exception) {
                call.reject("Проверка голоса: " + (error.message ?: "ошибка"))
            }
        }
    }

    private data class Line(
        val text: String, val start: Int, val duration: Int, val effect: String,
        val character: Boolean
    )
    private fun parseLines(array: JSONArray): List<Line> {
        require(array.length() in 1..16) { "Нужно от 1 до 16 озвученных сцен." }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val text = item.optString("text", "").trim().take(120)
            require(text.isNotEmpty()) { "Сцена ${index+1}: нет текста для озвучки." }
            val start = item.optInt("startSeconds", -1)
            val duration = item.optInt("durationSeconds", -1)
            require(start in 0..89 && duration in 2..12 && start + duration <= 90) {
                "Сцена ${index+1}: неверное время."
            }
            val effect = item.optString("effect", "none")
            require(effect in setOf("none","rain","torch","glow","fog")) {
                "Сцена ${index+1}: неизвестный звук."
            }
            val speaker = item.optString("speaker", "narrator")
            require(speaker in setOf("narrator", "character")) { "Неизвестный тип голоса." }
            Line(text, start, duration, effect, speaker=="character")
        }.also {
            require(it.zipWithNext().all { (a,b) -> a.start+a.duration <= b.start }) {
                "Реплики разных сцен не должны накладываться."
            }
        }
    }

    @PluginMethod
    fun dubMovie(call: PluginCall) {
        val rawUri = call.getString("videoUri") ?: ""
        val source = Uri.parse(rawUri)
        if (source.scheme != "content" || source.authority != "media" ||
            !Regex("^/[^/]+/video/media/[0-9]+$").matches(source.path ?: "")) {
            call.reject("Нужен MP4, уже сохранённый в Галерею NOVA.")
            return
        }
        val selectedLanguage=call.getString("language") ?: "ru"
        if(selectedLanguage!="ru" && selectedLanguage!="uk"){
            call.reject("Нужно выбрать русский или украинский локальный голос.")
            return
        }
        val lines = try { parseLines(call.getArray("lines") ?: JSONArray()) }
                    catch(error:Exception){ call.reject(error.message ?: "Ошибка сценария озвучки.");return }
        val cyrillic=Regex("[А-Яа-яЁёІіЇїЄєҐґ]")
        val latin=Regex("[A-Za-z]")
        if(lines.any { !cyrillic.containsMatchIn(it.text) || latin.containsMatchIn(it.text) }){
            call.reject("NOVA не разрешает читать английские описания русским или украинским голосом.")
            return
        }
        if(!busy.compareAndSet(false,true)) {
            call.reject("Другая озвучка NOVA ещё выполняется.")
            return
        }
        scope.launch {
            var resultUri:Uri?=null
            try {
                withSelectedEngine(call.getString("engineId")) { t ->
                val voices=localVoices(t).filter { it.locale.language==selectedLanguage }
                require(voices.isNotEmpty()) {
                    "На устройстве нет локального голоса языка "+selectedLanguage+". Немой MP4 сохранён."
                }
                // A requested voice is selected only from installed OFFLINE voices of the target language.
                val voiceId=call.getString("voiceId")?.trim().orEmpty()
                val narrator=voices.firstOrNull { it.name==voiceId } ?: voices.first()
                val character=voices.firstOrNull { it.name!=narrator.name && it.locale.language==narrator.locale.language }
                    ?:voices.firstOrNull { it.name!=narrator.name }
                    ?:narrator
                val sourceInfo=readVideoInfo(source)
                val seconds=sourceInfo.second
                require(seconds in 2..90) { "Продолжительность MP4 должна быть от 2 до 90 секунд." }
                require(lines.all { it.start+it.duration <= seconds+1 }) {
                    "Озвучка выходит за пределы видеокомикса."
                }
                val samples=ShortArray(seconds*44100)
                for ((i,line) in lines.withIndex()) {
                    // Procedural sound design is local and copyright-free.
                    addEnvironment(samples,line,i)
                    val selected=if(line.character)character else narrator
                    t.voice=selected
                    // Slightly more measured narration, without slowing past the 4-5s window.
                    t.setSpeechRate(if(line.character) 0.98f else 0.96f)
                    t.setPitch(if(line.character && selected.name==narrator.name) 1.04f else 1.0f)
                    val wav=File(context.cacheDir,"nova-voice-${UUID.randomUUID()}.wav")
                    try {
                        synthesize(t,line.text,wav)
                        addVoice(samples,line,readWav(wav))
                    } finally { wav.delete() }
                    notifyListeners("novaVoiceProgress", JSObject().apply {
                        put("done",i+1)
                        put("total",lines.size)
                    })
                }
                val encoded=encodeAac(samples)
                resultUri=context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME,"NOVA-Comic-Voice-${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/NOVA")
                        put(MediaStore.Video.Media.IS_PENDING,1)
                    }) ?: error("Android не создал звуковой MP4.")
                mux(source,resultUri,sourceInfo.first,encoded)
                context.contentResolver.update(resultUri,ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING,0)
                },null,null)
                call.resolve(JSObject().apply {
                    put("saved",true)
                    put("videoUri",resultUri.toString())
                    put("seconds",seconds)
                    put("voicedLines",lines.size)
                    put("voice",narrator.name)
                    put("engine",call.getString("engineId")?.trim()?.takeIf { it.isNotEmpty() } ?: t.defaultEngine ?: "")
                    put("language",selectedLanguage)
                    put("distinctCharacterVoice",character.name!=narrator.name)
                    put("audio",true)
                })
                }
            } catch (error:Exception) {
                resultUri?.let { try{context.contentResolver.delete(it,null,null)}catch(_:Exception){} }
                call.reject("Озвучка не завершена: "+(error.message ?: "ошибка")+
                    ". Исходный MP4 и картинки не изменены.")
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun synthesize(t:TextToSpeech,text:String,file:File) {
        val id="nova-file-"+UUID.randomUUID().toString()
        val result=CompletableDeferred<Boolean>()
        utterances[id]=result
        try {
            check(t.synthesizeToFile(text,Bundle(),file,id)==TextToSpeech.SUCCESS) {
                "Android отказался синтезировать речь локально."
            }
            val success=withTimeoutOrNull(30_000L){result.await()} ?: false
            check(success && file.isFile && file.length()>44) {
                "Локальный синтезатор не вернул готовый WAV."
            }
        } finally { utterances.remove(id) }
    }

    private data class Wav(val data:ShortArray,val rate:Int)
    private fun readWav(file:File):Wav {
        val bytes=file.readBytes()
        require(bytes.size in 45..12_000_000){"Неподдерживаемый размер WAV."}
        val bb=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun name(pos:Int)=String(bytes,pos,4,Charsets.US_ASCII)
        require(name(0)=="RIFF" && name(8)=="WAVE"){"Android вернул не PCM WAV."}
        var pos=12
        var channels=0
        var rate=0
        var bits=0
        var kind=0
        var dataAt=-1
        var dataSize=0
        while(pos+8<=bytes.size){
            val chunk=name(pos)
            val length=bb.getInt(pos+4)
            require(length>=0 && pos.toLong()+8L+length.toLong()<=bytes.size) {
                "Повреждённая секция WAV."
            }
            if(chunk=="fmt " && length>=16){
                kind=bb.getShort(pos+8).toInt() and 0xffff
                channels=bb.getShort(pos+10).toInt() and 0xffff
                rate=bb.getInt(pos+12)
                bits=bb.getShort(pos+22).toInt() and 0xffff
            }
            if(chunk=="data"){dataAt=pos+8;dataSize=length;break}
            pos+=8+length+(length and 1)
        }
        require(kind==1 && channels in 1..2 && bits==16 && rate in 8000..48000 &&
            dataAt>=0 && dataSize>0) {
            "Офлайн-движок вернул неподдерживаемый аудиоформат."
        }
        val frames=dataSize/(2*channels)
        val pcm=ShortArray(frames)
        for(i in 0 until frames){
            var sum=0
            for(ch in 0 until channels)sum+=bb.getShort(dataAt+(i*channels+ch)*2).toInt()
            pcm[i]=(sum/channels).toShort()
        }
        return Wav(pcm,rate)
    }

    private fun addVoice(out:ShortArray,line:Line,wav:Wav) {
        val start=line.start*44100
        val allowed=min(line.duration*44100-4410,out.size-start)
        val actualFrames=(wav.data.size.toLong()*44100/wav.rate).toInt()
        // Do not quietly cut words in half: the user prefers a safe silent
        // movie to an unintelligible dubbed movie.
        require(actualFrames<=allowed+2205) {
            "Реплика не помещается в отведённые ${line.duration} секунд. Сделай фразу короче."
        }
        val clipFrames=min(allowed,actualFrames)
        for(i in 0 until clipFrames){
            val source=i.toLong()*wav.rate/44100
            val sourceIndex=source.toInt().coerceIn(0,wav.data.size-1)
            val softStart=min(1.0,i/1323.0)
            val softEnd=min(1.0,(clipFrames-i)/2205.0)
            val value=(wav.data[sourceIndex].toInt()*0.85*softStart*softEnd).toInt()
            // Ambient sound stays in the background while narration speaks.
            out[start+i]=clamp((out[start+i].toInt()*0.24).toInt()+value)
        }
    }

    private fun addEnvironment(out:ShortArray,line:Line,index:Int) {
        if(line.effect=="none")return
        val from=line.start*44100
        val to=min(out.size,(line.start+line.duration)*44100)
        var seed=0x13572468+xorShift(index+1)
        for(i in from until to){
            seed=xorShift(seed)
            val noise=(seed and 0xffff)-32768
            val t=(i-from)/44100.0
            val envelope=min(1.0,t/0.12)*min(1.0,(to-i)/4410.0)
            val value=when(line.effect){
                "rain" -> (noise*0.011).toInt()
                "torch" -> (noise*0.006*(0.45+0.55*kotlin.math.sin(t*12.0)*kotlin.math.sin(t*12.0))).toInt()
                "fog" -> (noise*0.003).toInt()
                "glow" -> (kotlin.math.sin(2.0*Math.PI*550.0*t)*300.0*kotlin.math.exp(-t*1.2)).toInt()
                else -> 0
            }
            out[i]=clamp(out[i].toInt()+(value*envelope).toInt())
        }
    }
    private fun xorShift(value:Int):Int {
        var x=value
        x=x xor (x shl 13)
        x=x xor (x ushr 17)
        x=x xor (x shl 5)
        return x
    }
    private fun clamp(v:Int):Short = v.coerceIn(-32768,32767).toShort()

    private fun readVideoInfo(uri:Uri):Pair<MediaFormat,Int> {
        val ex=MediaExtractor()
        try{
            ex.setDataSource(context,uri,null)
            val video=(0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
            } ?: error("MP4 не содержит видеодорожку.")
            val format=ex.getTrackFormat(video)
            val duration=if(format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else -1L
            require(duration>0){"У MP4 нет продолжительности."}
            val secs=((duration+999_999L)/1_000_000L).toInt()
            return format to secs
        } finally{ex.release()}
    }

    private data class Packet(val pts:Long,val flags:Int,val bytes:ByteArray)
    private data class Aac(val format:MediaFormat,val packets:List<Packet>)
    private fun encodeAac(pcm:ShortArray):Aac {
        val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,44100,1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,96000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
        }
        val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var started=false
        val packets=ArrayList<Packet>()
        var outputFormat:MediaFormat?=null
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start();started=true
            var read=0
            var queuedEos=false
            var receivedEos=false
            var turns=0
            val info=MediaCodec.BufferInfo()
            while(!receivedEos){
                if(++turns>15000)error("AAC-кодек не завершил озвучку.")
                if(!queuedEos){
                    val index=codec.dequeueInputBuffer(10_000L)
                    if(index>=0){
                        val input=codec.getInputBuffer(index) ?: error("AAC вход недоступен.")
                        input.clear()
                        val take=min((input.remaining()/2).coerceAtMost(2048),pcm.size-read)
                        val pts=read.toLong()*1_000_000L/44100
                        for(i in 0 until take)input.putShort(pcm[read+i])
                        codec.queueInputBuffer(index,0,take*2,pts,
                            if(take==0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        read+=take
                        if(take==0)queuedEos=true
                    }
                }
                while(true){
                    val out=codec.dequeueOutputBuffer(info,10_000L)
                    if(out==MediaCodec.INFO_TRY_AGAIN_LATER)break
                    if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        outputFormat=codec.outputFormat
                        continue
                    }
                    if(out>=0){
                        if(info.size>0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                            val buffer=codec.getOutputBuffer(out) ?: error("AAC буфер пуст.")
                            buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            val bytes=ByteArray(info.size);buffer.get(bytes)
                            packets.add(Packet(info.presentationTimeUs,info.flags,bytes))
                        }
                        if((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)
                            receivedEos=true
                        codec.releaseOutputBuffer(out,false)
                        if(receivedEos)break
                    }
                }
            }
            return Aac(outputFormat ?: error("AAC-кодек не создал формат."),packets)
        } finally{
            if(started) try{codec.stop()}catch(_:Exception){}
            codec.release()
        }
    }

    private fun mux(source:Uri,destination:Uri,videoFormat:MediaFormat,aac:Aac) {
        val extractor=MediaExtractor()
        var muxer:MediaMuxer?=null
        var descriptor:android.os.ParcelFileDescriptor?=null
        var started=false
        try {
            extractor.setDataSource(context,source,null)
            val videoTrack=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
            } ?: error("В исходном MP4 отсутствует видео.")
            extractor.selectTrack(videoTrack)
            descriptor=context.contentResolver.openFileDescriptor(destination,"w")
                ?: error("Не удалось открыть новый MP4.")
            val writer=MediaMuxer(descriptor.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer=writer
            val videoIndex=writer.addTrack(videoFormat)
            val voiceIndex=writer.addTrack(aac.format)
            writer.start();started=true
            val buf=ByteBuffer.allocateDirect(4*1024*1024)
            val info=MediaCodec.BufferInfo()
            while(true){
                buf.clear()
                val read=extractor.readSampleData(buf,0)
                if(read<0)break
                info.set(0,read,extractor.sampleTime,
                    if((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)!=0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                buf.position(0);buf.limit(read)
                writer.writeSampleData(videoIndex,buf,info)
                if(!extractor.advance())break
            }
            for(packet in aac.packets){
                info.set(0,packet.bytes.size,packet.pts,packet.flags)
                writer.writeSampleData(voiceIndex,ByteBuffer.wrap(packet.bytes),info)
            }
        } finally{
            extractor.release()
            if(started)try{muxer?.stop()}catch(_:Exception){}
            try{muxer?.release()}catch(_:Exception){}
            try{descriptor?.close()}catch(_:Exception){}
        }
    }

    override fun handleOnDestroy() {
        try { engine?.stop() } catch(_:Exception){}
        try { engine?.shutdown() } catch(_:Exception){}
        utterances.values.forEach { if(!it.isCompleted)it.complete(false) }
        utterances.clear()
        scope.cancel()
        super.handleOnDestroy()
    }
}
