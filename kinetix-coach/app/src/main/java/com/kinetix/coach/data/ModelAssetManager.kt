package com.kinetix.coach.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class ModelPrepareState {
    data object Idle : ModelPrepareState()
    data class Extracting(val progressPct: Int, val label: String) : ModelPrepareState()
    data class Ready(val modelPath: String, val displayName: String, val sizeMb: Int, val isCustom: Boolean) : ModelPrepareState()
    data class Error(val message: String) : ModelPrepareState()
}

class ModelAssetManager(
    private val context: Context,
    private val userDb: UserDatabaseHelper
) {
    companion object {
        private const val TAG = "KinetixModelMgr"
        const val EMBEDDED_ASSET_PATH = "models/gemma-3-1b-it-Q4_K_M.gguf"
        const val EMBEDDED_FILE_NAME = "gemma-3-1b-it-Q4_K_M.gguf"
        const val EMBEDDED_DISPLAY_NAME = "Gemma-3 1B Q4_K_M (Intégré)"
        private const val PREF_CUSTOM_MODEL_PATH = "custom_model_path"
        private const val PREF_CUSTOM_MODEL_NAME = "custom_model_name"
        private const val COPY_BUFFER_BYTES = 4 * 1024 * 1024 // 4 MB high-throughput buffer
    }

    private val _state = MutableStateFlow<ModelPrepareState>(ModelPrepareState.Idle)
    val state: StateFlow<ModelPrepareState> = _state.asStateFlow()

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    suspend fun ensureActiveModelReady(): ModelPrepareState = withContext(Dispatchers.IO) {
        val customPath = userDb.getPref(PREF_CUSTOM_MODEL_PATH, "")
        if (customPath.isNotBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.length() > 10 * 1024 * 1024L) {
                val name = userDb.getPref(PREF_CUSTOM_MODEL_NAME, customFile.name)
                val ready = ModelPrepareState.Ready(
                    modelPath = customFile.absolutePath,
                    displayName = name,
                    sizeMb = (customFile.length() / (1024 * 1024)).toInt(),
                    isCustom = true
                )
                _state.value = ready
                return@withContext ready
            }
        }
        extractEmbeddedModelIfNeeded()
    }

    suspend fun switchToEmbeddedModel(): ModelPrepareState = withContext(Dispatchers.IO) {
        userDb.setPref(PREF_CUSTOM_MODEL_PATH, "")
        userDb.setPref(PREF_CUSTOM_MODEL_NAME, "")
        extractEmbeddedModelIfNeeded()
    }

    private fun extractEmbeddedModelIfNeeded(): ModelPrepareState {
        val target = File(modelsDir, EMBEDDED_FILE_NAME)
        val expectedLength = try {
            context.assets.openFd(EMBEDDED_ASSET_PATH).use { it.length }
        } catch (e: Exception) {
            Log.w(TAG, "openFd unavailable, falling back to stream copy: ${e.message}")
            -1L
        }

        if (target.exists() && expectedLength > 0 && target.length() == expectedLength) {
            val ready = ModelPrepareState.Ready(
                modelPath = target.absolutePath,
                displayName = EMBEDDED_DISPLAY_NAME,
                sizeMb = (target.length() / (1024 * 1024)).toInt(),
                isCustom = false
            )
            _state.value = ready
            return ready
        }

        val temp = File(modelsDir, "$EMBEDDED_FILE_NAME.part")
        return try {
            _state.value = ModelPrepareState.Extracting(0, "Initialisation du Coach IA local…")
            context.assets.open(EMBEDDED_ASSET_PATH).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    var lastPct = -1
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (expectedLength > 0) {
                            val pct = ((copied * 100L) / expectedLength).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = ModelPrepareState.Extracting(
                                    progressPct = pct,
                                    label = "Préparation du modèle Gemma-3 1B ($pct%)"
                                )
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            val ready = ModelPrepareState.Ready(
                modelPath = target.absolutePath,
                displayName = EMBEDDED_DISPLAY_NAME,
                sizeMb = (target.length() / (1024 * 1024)).toInt(),
                isCustom = false
            )
            _state.value = ready
            ready
        } catch (e: Exception) {
            temp.delete()
            Log.e(TAG, "Failed extracting embedded GGUF", e)
            val err = ModelPrepareState.Error("Erreur d'extraction du modèle : ${e.message}")
            _state.value = err
            err
        }
    }

    suspend fun importCustomModel(uri: Uri): ModelPrepareState = withContext(Dispatchers.IO) {
        val resolvedName = resolveDisplayName(uri) ?: "custom_model.gguf"
        if (!resolvedName.lowercase().endsWith(".gguf")) {
            val err = ModelPrepareState.Error("Veuillez sélectionner un fichier .gguf valide.")
            _state.value = err
            return@withContext err
        }
        val safeName = "custom_" + resolvedName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val target = File(modelsDir, safeName)
        val temp = File(modelsDir, "$safeName.part")
        val expectedLength = resolveFileSize(uri)

        return@withContext try {
            _state.value = ModelPrepareState.Extracting(0, "Import de $resolvedName…")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var copied = 0L
                    var lastPct = -1
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (expectedLength > 0) {
                            val pct = ((copied * 100L) / expectedLength).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = ModelPrepareState.Extracting(pct, "Import de $resolvedName ($pct%)")
                            }
                        }
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("Impossible d'ouvrir le fichier sélectionné.")

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }

            // Clean older custom models so storage doesn't bloat
            modelsDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("custom_") && f.absolutePath != target.absolutePath) {
                    f.delete()
                }
            }

            userDb.setPref(PREF_CUSTOM_MODEL_PATH, target.absolutePath)
            userDb.setPref(PREF_CUSTOM_MODEL_NAME, resolvedName)

            val ready = ModelPrepareState.Ready(
                modelPath = target.absolutePath,
                displayName = resolvedName,
                sizeMb = (target.length() / (1024 * 1024)).toInt(),
                isCustom = true
            )
            _state.value = ready
            ready
        } catch (e: Exception) {
            temp.delete()
            val err = ModelPrepareState.Error("Échec d'import GGUF : ${e.message}")
            _state.value = err
            err
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun resolveFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return -1L
    }
}
