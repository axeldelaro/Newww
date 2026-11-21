package com.newww.auracompanion.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

interface AuraTokenCallback {
    fun onToken(piece: String)
}

enum class EngineInitState { IDLE, EXTRACTING, LOADING, READY, FALLBACK }

class AuraLlamaEngine(private val context: Context) {

    private external fun nativeLoadModel(modelPath: String, nThreads: Int, nCtx: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, callback: AuraTokenCallback?): String
    private external fun nativeStop()
    private external fun nativeUnload()

    private val mutex = Mutex()
    private val nativeReady: Boolean = runCatching {
        System.loadLibrary("aura_llm")
        true
    }.getOrDefault(false)

    private val _state = MutableStateFlow(EngineInitState.IDLE)
    val state: StateFlow<EngineInitState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initialisation de Llama-3.2 3B FR…")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_state.value == EngineInitState.READY) return@withLock true
            if (!nativeReady) {
                _state.value = EngineInitState.FALLBACK
                _statusMessage.value = "Mode léger actif (lib native absente)"
                return@withLock false
            }

            val modelFile = extractAssetModelIfNeeded() ?: run {
                _state.value = EngineInitState.FALLBACK
                _statusMessage.value = "Modèle local introuvable"
                return@withLock false
            }

            _state.value = EngineInitState.LOADING
            _statusMessage.value = "Chargement du cerveau Llama-3.2 3B FR en mémoire…"
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val ok = runCatching { nativeLoadModel(modelFile.absolutePath, threads, 2048) }.getOrDefault(false)
            if (ok) {
                _state.value = EngineInitState.READY
                _progress.value = 1f
                _statusMessage.value = "IA Locale Llama-3.2 3B FR active ✓ (100 % hors-ligne)"
            } else {
                _state.value = EngineInitState.FALLBACK
                _statusMessage.value = "Mémoire insuffisante — mode secours actif"
            }
            ok
        }
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 160,
        temperature: Float = 0.65f,
        onToken: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (_state.value != EngineInitState.READY) ensureLoaded()
        if (_state.value != EngineInitState.READY) return@withContext ""
        mutex.withLock {
            val cb = object : AuraTokenCallback {
                override fun onToken(piece: String) {
                    onToken(piece)
                }
            }
            runCatching { nativeGenerate(prompt, maxTokens, temperature, cb).trim() }.getOrDefault("")
        }
    }

    fun stop() {
        if (nativeReady) runCatching { nativeStop() }
    }

    fun release() {
        if (nativeReady) runCatching { nativeUnload() }
    }

    private fun extractAssetModelIfNeeded(): File? {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, MODEL_NAME)
        val assetPath = "models/$MODEL_NAME"

        val assetSize = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrDefault(-1L)
        if (target.exists() && (assetSize <= 0L || target.length() == assetSize) && target.length() > 50_000_000L) {
            _progress.value = 1f
            return target
        }

        // Supprime d'éventuels anciens modèles v1 pour libérer l'espace
        dir.listFiles()?.filter { it.name.endsWith(".gguf") && it.name != MODEL_NAME }?.forEach { it.delete() }

        _state.value = EngineInitState.EXTRACTING
        _statusMessage.value = "Installation unique du cerveau Llama-3.2 3B intégré (0 Mo téléchargé)…"
        val tmp = File(dir, "$MODEL_NAME.part")
        return runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(2 * 1024 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (assetSize > 0) {
                            _progress.value = (copied.toFloat() / assetSize.toFloat()).coerceIn(0f, 0.99f)
                        }
                    }
                    output.flush()
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return null
            _progress.value = 1f
            target
        }.getOrNull()
    }

    companion object {
        const val MODEL_NAME = "Llama-3.2-3B-Instruct-IQ3_M.gguf"
    }
}
