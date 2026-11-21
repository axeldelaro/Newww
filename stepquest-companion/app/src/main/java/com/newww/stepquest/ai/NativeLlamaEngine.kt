package com.newww.stepquest.ai

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

interface TokenCallback {
    fun onToken(piece: String)
}

enum class LlmStatus { IDLE, PREPARING_ASSETS, LOADING_MODEL, READY, FALLBACK_ONLY }

class NativeLlamaEngine(private val context: Context) {

    private external fun nativeLoadModel(modelPath: String, nThreads: Int, nCtx: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, callback: TokenCallback?): String
    private external fun nativeStop()
    private external fun nativeUnload()

    private val mutex = Mutex()
    private var nativeLibAvailable = false

    private val _status = MutableStateFlow(LlmStatus.IDLE)
    val status: StateFlow<LlmStatus> = _status.asStateFlow()

    private val _prepProgress = MutableStateFlow(0f)
    val prepProgress: StateFlow<Float> = _prepProgress.asStateFlow()

    private val _statusLabel = MutableStateFlow("IA locale prête à démarrer")
    val statusLabel: StateFlow<String> = _statusLabel.asStateFlow()

    init {
        nativeLibAvailable = runCatching {
            System.loadLibrary("stepquest_llm")
            true
        }.getOrDefault(false)
    }

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_status.value == LlmStatus.READY) return@withLock true
            if (!nativeLibAvailable) {
                _status.value = LlmStatus.FALLBACK_ONLY
                _statusLabel.value = "Mode narratif léger actif"
                return@withLock false
            }

            val modelFile = prepareBundledModel() ?: run {
                _status.value = LlmStatus.FALLBACK_ONLY
                _statusLabel.value = "Modèle hors-ligne indisponible (mode secours)"
                return@withLock false
            }

            _status.value = LlmStatus.LOADING_MODEL
            _statusLabel.value = "Chargement de Gemma-3 1B en mémoire locale…"
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val ok = runCatching { nativeLoadModel(modelFile.absolutePath, threads, 2048) }.getOrDefault(false)
            if (ok) {
                _status.value = LlmStatus.READY
                _prepProgress.value = 1f
                _statusLabel.value = "IA Locale Gemma-3 1B active (100 % hors-ligne)"
            } else {
                _status.value = LlmStatus.FALLBACK_ONLY
                _statusLabel.value = "Mode narratif hors-ligne actif"
            }
            ok
        }
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 220,
        temperature: Float = 0.72f,
        onToken: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (_status.value != LlmStatus.READY) {
            ensureLoaded()
        }
        if (_status.value != LlmStatus.READY) return@withContext ""
        mutex.withLock {
            val cb = object : TokenCallback {
                override fun onToken(piece: String) {
                    onToken(piece)
                }
            }
            runCatching {
                nativeGenerate(prompt, maxTokens, temperature, cb).trim()
            }.getOrDefault("")
        }
    }

    fun stopGeneration() {
        if (nativeLibAvailable) runCatching { nativeStop() }
    }

    fun release() {
        if (nativeLibAvailable) runCatching { nativeUnload() }
    }

    private fun prepareBundledModel(): File? {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(modelDir, MODEL_FILENAME)
        val assetPath = "models/$MODEL_FILENAME"

        val assetSize = runCatching {
            context.assets.openFd(assetPath).use { it.length }
        }.getOrDefault(-1L)

        if (target.exists() && (assetSize <= 0L || target.length() == assetSize) && target.length() > 10_000_000L) {
            _prepProgress.value = 1f
            return target
        }

        _status.value = LlmStatus.PREPARING_ASSETS
        _statusLabel.value = "Installation unique du cerveau IA intégré (0 Mo téléchargé)…"
        val tmp = File(modelDir, "$MODEL_FILENAME.part")
        return runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (assetSize > 0) {
                            _prepProgress.value = (copied.toFloat() / assetSize.toFloat()).coerceIn(0f, 0.99f)
                        }
                    }
                    output.flush()
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return null
            _prepProgress.value = 1f
            target
        }.getOrNull()
    }

    companion object {
        const val MODEL_FILENAME = "gemma-3-1b-it-Q4_K_M.gguf"
    }
}
