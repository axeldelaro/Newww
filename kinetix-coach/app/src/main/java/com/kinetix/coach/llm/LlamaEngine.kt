package com.kinetix.coach.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

sealed class EngineStatus {
    data object Unloaded : EngineStatus()
    data class Loading(val label: String) : EngineStatus()
    data class Ready(val modelPath: String, val modelName: String, val threads: Int) : EngineStatus()
    data class Error(val message: String) : EngineStatus()
}

data class GenerationResult(
    val fullText: String,
    val tokenCount: Int,
    val tokensPerSec: Float,
    val cancelled: Boolean
)

class LlamaEngine {

    companion object {
        private const val TAG = "KinetixLlamaEngine"
        const val DEFAULT_CTX_SIZE = 2048

        fun recommendedThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return min(4, max(2, cores / 2))
        }
    }

    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var activeThreads: Int = recommendedThreads()

    @Volatile
    private var cancelRequested: Boolean = false

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Unloaded)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    suspend fun loadModel(
        modelPath: String,
        displayName: String,
        threads: Int = activeThreads,
        nCtx: Int = DEFAULT_CTX_SIZE
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!LlamaBridge.isNativeLoaded) {
                _status.value = EngineStatus.Error("Moteur natif libkinetix_llama introuvable.")
                return@withContext false
            }
            if (handle != 0L && loadedModelPath == modelPath) {
                val clamped = threads.coerceIn(2, 6)
                if (clamped != activeThreads) {
                    activeThreads = clamped
                    LlamaBridge.nativeUpdateThreads(handle, clamped)
                    _status.value = EngineStatus.Ready(modelPath, displayName, clamped)
                }
                return@withContext true
            }

            if (handle != 0L) {
                LlamaBridge.nativeUnloadModel(handle)
                handle = 0L
                loadedModelPath = null
            }

            val file = File(modelPath)
            if (!file.exists()) {
                _status.value = EngineStatus.Error("Modèle introuvable : $modelPath")
                return@withContext false
            }

            val clamped = threads.coerceIn(2, 6)
            activeThreads = clamped
            _status.value = EngineStatus.Loading("Chargement de $displayName…")
            LlamaBridge.nativeInitBackend()

            val newHandle = LlamaBridge.nativeLoadModel(modelPath, nCtx, clamped)
            if (newHandle == 0L) {
                _status.value = EngineStatus.Error("Impossible d'initialiser le modèle GGUF.")
                return@withContext false
            }

            handle = newHandle
            loadedModelPath = modelPath
            _status.value = EngineStatus.Ready(modelPath, displayName, clamped)
            Log.i(TAG, "Loaded $displayName on $clamped threads")
            true
        }
    }

    fun updateThreads(threads: Int) {
        val clamped = threads.coerceIn(2, 6)
        activeThreads = clamped
        val h = handle
        if (h != 0L) {
            LlamaBridge.nativeUpdateThreads(h, clamped)
            val current = _status.value
            if (current is EngineStatus.Ready) {
                _status.value = current.copy(threads = clamped)
            }
        }
    }

    fun cancelGeneration() {
        cancelRequested = true
        val h = handle
        if (h != 0L) {
            LlamaBridge.nativeCancel(h)
        }
    }

    suspend fun generate(
        userTurnPayload: String,
        maxTokens: Int = 512,
        temperature: Float = 0.45f,
        topP: Float = 0.90f,
        topK: Int = 40,
        repeatPenalty: Float = 1.12f,
        onTokenStream: (accumulatedText: String, tokPerSec: Float) -> Unit
    ): GenerationResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val h = handle
            if (h == 0L) {
                return@withContext GenerationResult(
                    fullText = "",
                    tokenCount = 0,
                    tokensPerSec = 0f,
                    cancelled = false
                )
            }

            cancelRequested = false
            val formattedPrompt = formatPromptForModel(h, userTurnPayload)
            val sb = StringBuilder(1024)
            var count = 0
            val startNs = System.nanoTime()
            var firstTokenNs = 0L

            val generatedCount = LlamaBridge.nativeGenerate(
                handle = h,
                prompt = formattedPrompt,
                maxTokens = maxTokens,
                temp = temperature,
                topP = topP,
                topK = topK,
                repeatPenalty = repeatPenalty,
                callback = TokenCallback { piece ->
                    if (cancelRequested) return@TokenCallback false
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    sb.append(piece)
                    count++
                    val elapsedSec = max(0.05f, (System.nanoTime() - firstTokenNs) / 1_000_000_000f)
                    val tps = if (count <= 1) 0f else (count - 1) / elapsedSec
                    onTokenStream(sb.toString(), tps)
                    !cancelRequested
                }
            )

            val totalElapsedSec = if (firstTokenNs > 0L) {
                max(0.05f, (System.nanoTime() - firstTokenNs) / 1_000_000_000f)
            } else {
                max(0.05f, (System.nanoTime() - startNs) / 1_000_000_000f)
            }
            val finalTokens = max(count, generatedCount.coerceAtLeast(0))
            val finalTps = if (finalTokens > 1) finalTokens / totalElapsedSec else 0f

            GenerationResult(
                fullText = sb.toString().trim(),
                tokenCount = finalTokens,
                tokensPerSec = finalTps,
                cancelled = cancelRequested
            )
        }
    }

    private fun formatPromptForModel(h: Long, payload: String): String {
        val pathLower = loadedModelPath?.lowercase().orEmpty()
        // Native Gemma-3 IT turn template (strictly verified for gemma-3-1b-it)
        if (pathLower.contains("gemma")) {
            return "<start_of_turn>user\n$payload<end_of_turn>\n<start_of_turn>model\n"
        }
        // For any custom imported GGUF (Llama-3, Qwen, Mistral, Phi, etc.), apply the embedded GGUF chat template first
        val fromModelTemplate = runCatching {
            LlamaBridge.nativeFormatChat(h, arrayOf("user"), arrayOf(payload))
        }.getOrNull()
        if (!fromModelTemplate.isNullOrBlank()) {
            return fromModelTemplate
        }
        // Universal fallback
        return "<start_of_turn>user\n$payload<end_of_turn>\n<start_of_turn>model\n"
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val h = handle
            if (h != 0L) {
                LlamaBridge.nativeUnloadModel(h)
                handle = 0L
                loadedModelPath = null
            }
            _status.value = EngineStatus.Unloaded
        }
    }
}
