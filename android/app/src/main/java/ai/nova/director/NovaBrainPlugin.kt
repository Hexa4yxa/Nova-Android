package ai.nova.director

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

@CapacitorPlugin(name = "NovaBrain")
class NovaBrainPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val brain: NovaEngine by lazy {
        NovaEngine(context)
    }

    @PluginMethod
    fun status(call: PluginCall) {
        try {
            val result = JSObject()
            result.put("bridge", "ready")
            result.put("engine", brain.state())
            call.resolve(result)
        } catch (e: Exception) {
            call.reject("NOVA status failed", e)
        }
    }

    @PluginMethod
    fun loadModel(call: PluginCall) {
        val path = call.getString("path")
        if (path.isNullOrBlank()) {
            call.reject("Model path required")
            return
        }

        scope.launch {
            try {
                brain.loadModel(path)
                val result = JSObject()
                result.put("loaded", true)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Model loading failed", e)
            }
        }
    }

    @PluginMethod
    fun chat(call: PluginCall) {
        val message = call.getString("message")
        if (message.isNullOrBlank()) {
            call.reject("Message required")
            return
        }

        scope.launch {
            try {
                val answer = brain.chat(message)
                val result = JSObject()
                result.put("answer", answer)
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("NOVA chat failed", e)
            }
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
