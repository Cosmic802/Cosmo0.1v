package com.cosmic802.cosmo

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File

/** Runs any .gguf model on-device (llama.cpp). Picks a chat template from the file name. */
object LlamaEngine {

    private class StopSignal : RuntimeException()

    private class Template(val stops: List<String>, val build: (String, String) -> String)

    private val CHATML = Template(listOf("<|im_end|>", "<|endoftext|>")) { sys, user ->
        "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }
    private val LLAMA3 = Template(listOf("<|eot_id|>", "<|end_of_text|>")) { sys, user ->
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$sys<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
    }
    private val PHI = Template(listOf("<|end|>", "<|endoftext|>")) { sys, user ->
        "<|system|>\n$sys<|end|>\n<|user|>\n$user<|end|>\n<|assistant|>\n"
    }
    private val GEMMA = Template(listOf("<end_of_turn>")) { sys, user ->
        "<start_of_turn>user\n$sys\n\n$user<end_of_turn>\n<start_of_turn>model\n"
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: LlamaModel? = null
    private var loadedPath = ""

    @Volatile
    var lastError: String = ""

    /** Called on the main thread with the text generated so far. */
    @Volatile
    var onPartial: ((String) -> Unit)? = null

    fun modelFile(ctx: Context): File = File(File(ctx.filesDir, "models"), "local_model.gguf")

    fun isReady(): Boolean =
        Prefs.modelPath.endsWith(".gguf", ignoreCase = true) &&
            File(Prefs.modelPath).let { it.exists() && it.length() > 0 }

    private fun templateFor(path: String): Template {
        val n = File(path).name.lowercase()
        return when {
            n.contains("gemma") -> GEMMA
            n.contains("llama-3") || n.contains("llama3") -> LLAMA3
            n.contains("phi") -> PHI
            else -> CHATML
        }
    }

    @Synchronized
    fun release() {
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
        loadedPath = ""
    }

    private fun ensureLoaded(): LlamaModel {
        val path = Prefs.modelPath
        val cur = model
        if (cur != null && loadedPath == path) return cur
        release()
        val cpu = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val m = runBlocking {
            LlamaModel.load(path) {
                contextSize = 2048
                threads = cpu
                temperature = 0.7f
                maxTokens = 384
                useMmap = true
            }
        }
        model = m
        loadedPath = path
        return m
    }

    /** Returns null when no model is installed or it fails. Call from a background thread. */
    @Synchronized
    fun generate(ctx: Context, system: String, userPrompt: String): String? {
        if (!isReady()) return null
        return try {
            val m = ensureLoaded()
            val tpl = templateFor(Prefs.modelPath)
            val prompt = tpl.build(system, userPrompt)
            val out = StringBuilder()
            try {
                runBlocking {
                    m.generateStream(prompt).collect { token ->
                        out.append(token)
                        var cut = -1
                        for (s in tpl.stops) {
                            val i = out.indexOf(s)
                            if (i >= 0 && (cut < 0 || i < cut)) cut = i
                        }
                        if (cut >= 0) {
                            out.setLength(cut)
                            throw StopSignal()
                        }
                        val snapshot = out.toString()
                        main.post { onPartial?.invoke(snapshot) }
                    }
                }
            } catch (_: StopSignal) {
                try {
                    m.cancelGeneration()
                } catch (_: Throwable) {
                }
            }
            lastError = ""
            out.toString().trim().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(120)
            release()
            null
        }
    }
}
