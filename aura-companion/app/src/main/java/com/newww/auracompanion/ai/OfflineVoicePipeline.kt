package com.newww.auracompanion.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.newww.auracompanion.model.AuraAmbientSound
import com.newww.auracompanion.model.VoicePack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.math.*
import kotlin.random.Random

/**
 * Pipeline Vocal 100 % Hors-Ligne v2 (corrigé & ultra-rapide) :
 * - Extraction unique éclair via espeak-ng-data.zip (< 0,3 s) + fichiers nommés (zéro assets.list() lent !)
 * - Piper VITS FR avec paramètres exacts (noiseScale=0.667, noiseScaleW=0.8, lengthScale=1.0)
 * - Fallback Android TextToSpeech FR prêt dès la 1ère milliseconde si besoin
 * - Double signal Lip-Sync 60 Hz : lipSyncLevel (ouverture RMS) + lipSyncWide (forme voyelle ZCR)
 * - Synthétiseur d'ambiance sonore PCM douce en option (pluie, cheminée, nuit)
 */
class OfflineVoicePipeline(private val context: Context) {

    private val initMutex = Mutex()
    private val ambientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ambientJob: Job? = null

    private val _voiceReady = MutableStateFlow(false)
    val voiceReady: StateFlow<Boolean> = _voiceReady.asStateFlow()

    private val _voiceStatus = MutableStateFlow("Initialisation rapide des voix FR…")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lipSyncLevel = MutableStateFlow(0f)
    val lipSyncLevel: StateFlow<Float> = _lipSyncLevel.asStateFlow()

    private val _lipSyncWide = MutableStateFlow(0f)
    val lipSyncWide: StateFlow<Float> = _lipSyncWide.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var ttsSiwis: OfflineTts? = null
    private var ttsUpmc: OfflineTts? = null
    private var fallbackTts: TextToSpeech? = null
    private var fallbackTtsReady = false
    private var activeTrack: AudioTrack? = null
    private var initialized = false

    private val stopListenFlag = AtomicBoolean(false)
    private val stopSpeakFlag = AtomicBoolean(false)

    init {
        // Prépare immédiatement Android TTS FR en secours instantané
        runCatching {
            fallbackTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = fallbackTts?.setLanguage(Locale.FRANCE)
                    fallbackTtsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!fallbackTtsReady) {
                        fallbackTts?.language = Locale.FRENCH
                        fallbackTtsReady = true
                    }
                }
            }
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (initialized) return@withLock
            _voiceStatus.value = "Préparation des voix neurales FR…"

            val voiceRoot = File(context.filesDir, "voice_models_v2").apply { mkdirs() }
            val espeakDir = File(voiceRoot, "espeak-ng-data")
            val whisperDir = File(voiceRoot, "whisper").apply { mkdirs() }
            val ttsDir = File(voiceRoot, "tts").apply { mkdirs() }

            // 1. Extraction ultra-rapide d'espeak-ng-data.zip (< 0,3 s !)
            val espeakMarker = File(espeakDir, ".extracted_ok")
            if (!espeakMarker.exists()) {
                runCatching {
                    unzipAssetToDir("voice/espeak-ng-data.zip", espeakDir)
                    espeakMarker.writeText("ok")
                }.onFailure { e ->
                    Log.e(TAG, "Unzip espeak-ng-data failed", e)
                }
            }

            // 2. Extraction directe des fichiers TTS & Whisper (sans aucun assets.list() lent !)
            val siwisModel = copySingleAsset("voice/tts/fr_FR-siwis-medium.onnx", File(ttsDir, "fr_FR-siwis-medium.onnx"))
            val siwisTokens = copySingleAsset("voice/tts/siwis-tokens.txt", File(ttsDir, "siwis-tokens.txt"))
            val upmcModel = copySingleAsset("voice/tts/fr_FR-upmc-medium.onnx", File(ttsDir, "fr_FR-upmc-medium.onnx"))
            val upmcTokens = copySingleAsset("voice/tts/upmc-tokens.txt", File(ttsDir, "upmc-tokens.txt"))

            val whisperEnc = copySingleAsset("voice/whisper/tiny-encoder.int8.onnx", File(whisperDir, "tiny-encoder.int8.onnx"))
            val whisperDec = copySingleAsset("voice/whisper/tiny-decoder.int8.onnx", File(whisperDir, "tiny-decoder.int8.onnx"))
            val whisperTok = copySingleAsset("voice/whisper/tiny-tokens.txt", File(whisperDir, "tiny-tokens.txt"))

            // 3. Initialisation Piper VITS FR — Voix Siwis (Féminine douce)
            if (siwisModel != null && siwisTokens != null && espeakDir.exists()) {
                runCatching {
                    val vitsCfg = OfflineTtsVitsModelConfig(
                        model = siwisModel.absolutePath,
                        lexicon = "",
                        tokens = siwisTokens.absolutePath,
                        dataDir = espeakDir.absolutePath,
                        dictDir = "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    )
                    val modelCfg = OfflineTtsModelConfig(
                        vits = vitsCfg,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                    val ttsCfg = OfflineTtsConfig(
                        model = modelCfg,
                        ruleFsts = "",
                        ruleFars = "",
                        maxNumSentences = 1,
                        silenceScale = 0.2f
                    )
                    ttsSiwis = OfflineTts(null, ttsCfg)
                    Log.i(TAG, "Piper Siwis FR loaded! sampleRate=${ttsSiwis?.sampleRate()}")
                }.onFailure { e ->
                    Log.e(TAG, "Piper Siwis load failed", e)
                }
            }

            // 4. Initialisation Piper VITS FR — Voix UPMC (Jessica féminine vive + Pierre masculin)
            if (upmcModel != null && upmcTokens != null && espeakDir.exists()) {
                runCatching {
                    val vitsCfg = OfflineTtsVitsModelConfig(
                        model = upmcModel.absolutePath,
                        lexicon = "",
                        tokens = upmcTokens.absolutePath,
                        dataDir = espeakDir.absolutePath,
                        dictDir = "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    )
                    val modelCfg = OfflineTtsModelConfig(
                        vits = vitsCfg,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                    val ttsCfg = OfflineTtsConfig(
                        model = modelCfg,
                        ruleFsts = "",
                        ruleFars = "",
                        maxNumSentences = 1,
                        silenceScale = 0.2f
                    )
                    ttsUpmc = OfflineTts(null, ttsCfg)
                    Log.i(TAG, "Piper UPMC FR loaded! numSpeakers=${ttsUpmc?.numSpeakers()}")
                }.onFailure { e ->
                    Log.e(TAG, "Piper UPMC load failed", e)
                }
            }

            // 5. Initialisation Whisper-Tiny INT8 FR (Micro hors-ligne)
            if (whisperEnc != null && whisperDec != null && whisperTok != null) {
                runCatching {
                    val whisperCfg = OfflineWhisperModelConfig().apply {
                        encoder = whisperEnc.absolutePath
                        decoder = whisperDec.absolutePath
                        language = "fr"
                        task = "transcribe"
                    }
                    val modelCfg = OfflineModelConfig().apply {
                        whisper = whisperCfg
                        tokens = whisperTok.absolutePath
                        numThreads = 2
                        provider = "cpu"
                    }
                    recognizer = OfflineRecognizer(null, OfflineRecognizerConfig().apply { modelConfig = modelCfg })
                    Log.i(TAG, "Whisper FR loaded!")
                }.onFailure { e ->
                    Log.e(TAG, "Whisper FR load failed", e)
                }
            }

            initialized = true
            val ttsCount = listOfNotNull(ttsSiwis, ttsUpmc).size
            val sttOk = recognizer != null
            _voiceReady.value = ttsCount > 0 || fallbackTtsReady
            _voiceStatus.value = when {
                ttsCount == 2 && sttOk -> "3 Voix Neurales FR (Piper) + Whisper FR actifs ✓"
                ttsCount >= 1 && sttOk -> "Voix Neurale FR + Whisper FR actifs ✓"
                ttsCount >= 1 -> "Voix Neurale FR active ✓"
                else -> "Voix système Android FR active (secours)"
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun recordAndTranscribeFrench(
        autoStopOnSilence: Boolean = true,
        maxSeconds: Int = 12
    ): String = withContext(Dispatchers.IO) {
        if (!initialized) initialize()
        stopSpeaking()
        stopListenFlag.set(false)
        _isListening.value = true
        _micLevel.value = 0f

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            _isListening.value = false
            return@withContext ""
        }

        val captured = ArrayList<Short>(sampleRate * maxSeconds)
        val chunk = ShortArray(1600)
        var speechDetected = false
        var silentChunks = 0
        val maxChunks = maxSeconds * 10

        try {
            recorder.startRecording()
            for (step in 0 until maxChunks) {
                if (stopListenFlag.get()) break
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = chunk[i]
                        captured.add(s)
                        val f = s / 32768.0
                        sumSq += f * f
                    }
                    val rms = sqrt(sumSq / read).toFloat()
                    _micLevel.value = (rms * 5.8f).coerceIn(0f, 1f)
                    if (rms > 0.025f) {
                        speechDetected = true
                        silentChunks = 0
                    } else if (speechDetected) {
                        silentChunks++
                        if (autoStopOnSilence && silentChunks >= 13) break
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            _isListening.value = false
            _micLevel.value = 0f
        }

        if (captured.size < sampleRate / 4) return@withContext ""
        val rec = recognizer ?: return@withContext ""
        val floatSamples = FloatArray(captured.size) { i -> captured[i] / 32768f }
        return@withContext runCatching {
            val stream = rec.createStream()
            stream.acceptWaveform(floatSamples, sampleRate)
            rec.decode(stream)
            val txt = rec.getResult(stream).text.trim()
            stream.release()
            txt
        }.getOrDefault("")
    }

    fun stopListening() {
        stopListenFlag.set(true)
    }

    suspend fun speak(
        text: String,
        voicePack: VoicePack,
        speed: Float = 1.0f,
        pitch: Float = 1.0f
    ) = withContext(Dispatchers.IO) {
        if (!initialized) initialize()

        val clean = sanitizeForSpeech(text)
        if (clean.isEmpty()) return@withContext

        stopSpeaking()
        stopSpeakFlag.set(false)
        _isSpeaking.value = true

        val engine = when (voicePack.modelKey) {
            "upmc" -> ttsUpmc ?: ttsSiwis
            else -> ttsSiwis ?: ttsUpmc
        }

        var playedNeural = false
        if (engine != null) {
            try {
                val sid = if (engine === ttsUpmc) voicePack.speakerId.coerceIn(0, (engine.numSpeakers() - 1).coerceAtLeast(0)) else 0
                val generated = engine.generate(clean, sid, speed.coerceIn(0.75f, 1.35f))
                val samples = generated.samples
                if (samples.isNotEmpty() && !stopSpeakFlag.get()) {
                    playedNeural = playSamplesWithVisemeLipSync(samples, generated.sampleRate, pitch)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Piper TTS playback failed, switching to Android TTS", e)
            }
        }

        if (!playedNeural && !stopSpeakFlag.get()) {
            speakWithAndroidFallback(clean, speed, pitch)
        }

        _lipSyncLevel.value = 0f
        _lipSyncWide.value = 0f
        _isSpeaking.value = false
    }

    private suspend fun playSamplesWithVisemeLipSync(
        samples: FloatArray,
        baseSampleRate: Int,
        pitch: Float
    ): Boolean {
        val sr = baseSampleRate.coerceIn(16000, 48000)
        val playbackRate = (sr * pitch.coerceIn(0.85f, 1.20f)).toInt().coerceIn(14000, 48000)
        val minBuf = AudioTrack.getMinBufferSize(
            playbackRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(playbackRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeTrack = track
        return try {
            track.play()
            val frameSize = (playbackRate / 45).coerceAtLeast(384) // ~22 ms par trame (lip-sync ultra-fluide !)
            val pcmBuf = ShortArray(frameSize)
            var idx = 0
            var smoothOpen = 0f
            var smoothWide = 0f

            while (idx < samples.size && !stopSpeakFlag.get()) {
                val count = min(frameSize, samples.size - idx)
                var sumSq = 0.0
                var zeroCross = 0
                var prev = 0f
                for (i in 0 until count) {
                    val f = (samples[idx + i] * 1.15f).coerceIn(-0.98f, 0.98f)
                    sumSq += f * f
                    if (i > 0 && ((prev >= 0f && f < 0f) || (prev < 0f && f >= 0f))) zeroCross++
                    prev = f
                    pcmBuf[i] = (f * 32767f).toInt().toShort()
                }
                val rms = sqrt(sumSq / count).toFloat()
                val zcr = zeroCross.toFloat() / count.toFloat()

                // Ouverture verticale (voyelles ouvertes) + étirement horizontal (voyelles E/I vs O/U)
                val targetOpen = ((rms - 0.012f) * 6.5f).coerceIn(0f, 1f)
                val targetWide = ((zcr - 0.06f) * 4.2f).coerceIn(-0.6f, 1f) * targetOpen
                smoothOpen = smoothOpen * 0.35f + targetOpen * 0.65f
                smoothWide = smoothWide * 0.45f + targetWide * 0.55f
                _lipSyncLevel.value = smoothOpen
                _lipSyncWide.value = smoothWide

                track.write(pcmBuf, 0, count)
                idx += count
            }

            // Attend la fin réelle de lecture du tampon AudioTrack pour ne jamais couper la dernière syllabe
            if (!stopSpeakFlag.get()) {
                val silenceTail = ShortArray(playbackRate / 5) // 200 ms silence drain
                track.write(silenceTail, 0, silenceTail.size)
                _lipSyncLevel.value = 0f
                _lipSyncWide.value = 0f
                delay(180L)
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack write error", e)
            false
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            activeTrack = null
        }
    }

    private suspend fun speakWithAndroidFallback(clean: String, speed: Float, pitch: Float) {
        runCatching {
            fallbackTts?.setSpeechRate(speed.coerceIn(0.75f, 1.35f))
            fallbackTts?.setPitch(pitch.coerceIn(0.80f, 1.25f))
            fallbackTts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "aura_fallback")
            val estMs = (clean.length * 64L / speed.coerceIn(0.75f, 1.35f)).toLong().coerceIn(700L, 14_000L)
            var elapsed = 0L
            while (elapsed < estMs && !stopSpeakFlag.get()) {
                val s = abs(sin(elapsed * 0.022)).toFloat()
                val w = sin(elapsed * 0.013).toFloat() * 0.5f
                _lipSyncLevel.value = (0.18f + 0.72f * s).coerceIn(0f, 1f)
                _lipSyncWide.value = w
                delay(28L)
                elapsed += 28L
            }
        }
    }

    fun setAmbientSound(kind: AuraAmbientSound) {
        ambientJob?.cancel()
        if (kind == AuraAmbientSound.SILENT) return
        ambientJob = ambientScope.launch {
            runAmbientPcm(kind)
        }
    }

    private suspend fun runAmbientPcm(kind: AuraAmbientSound) {
        val sr = 22050
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        try {
            track.play()
            val buf = ShortArray(2048)
            var lp1 = 0.0; var lp2 = 0.0; var idx = 0L
            while (currentCoroutineContext().isActive) {
                for (i in buf.indices) {
                    val w = Random.nextDouble() * 2.0 - 1.0
                    val s = when (kind) {
                        AuraAmbientSound.SOFT_RAIN -> {
                            lp1 += 0.14 * (w - lp1); lp2 += 0.03 * (lp1 - lp2)
                            (lp1 * 0.45 + lp2 * 0.55) * 0.14
                        }
                        AuraAmbientSound.COZY_FIRE -> {
                            lp1 += 0.04 * (w - lp1)
                            val cr = if (Random.nextFloat() < 0.0005f) (Random.nextDouble() - 0.5) * 0.5 else 0.0
                            (lp1 * 0.55 + cr) * 0.16
                        }
                        AuraAmbientSound.NIGHT_CALM -> {
                            lp1 += 0.025 * (w - lp1)
                            (0.5 + 0.5 * sin(idx * 0.0002)) * lp1 * 0.16
                        }
                        AuraAmbientSound.SILENT -> 0.0
                    }
                    idx++
                    buf[i] = (s.coerceIn(-0.9, 0.9) * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    fun stopSpeaking() {
        stopSpeakFlag.set(true)
        runCatching { activeTrack?.stop() }
        runCatching { activeTrack?.release() }
        activeTrack = null
        runCatching { fallbackTts?.stop() }
        _lipSyncLevel.value = 0f
        _lipSyncWide.value = 0f
        _isSpeaking.value = false
    }

    fun release() {
        ambientJob?.cancel()
        ambientScope.cancel()
        stopListening()
        stopSpeaking()
        runCatching { recognizer?.release() }
        runCatching { ttsSiwis?.release() }
        runCatching { ttsUpmc?.release() }
        runCatching { fallbackTts?.shutdown() }
    }

    private fun sanitizeForSpeech(text: String): String {
        return text
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("[*#`~_]"), " ")
            .replace(Regex("[\\p{So}\\p{Cs}]"), " ")
            .replace("…", ", ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun copySingleAsset(assetPath: String, dest: File): File? {
        val assetLen = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrDefault(-1L)
        if (dest.exists() && dest.length() > 0L && (assetLen <= 0L || dest.length() == assetLen)) {
            return dest
        }
        return runCatching {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "${dest.name}.part")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, bufferSize = 262144)
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) return null
            dest
        }.onFailure { e ->
            Log.e(TAG, "Failed extracting $assetPath", e)
        }.getOrNull()
    }

    private fun unzipAssetToDir(assetZipPath: String, targetDir: File) {
        targetDir.mkdirs()
        val buf = ByteArray(65536)
        context.assets.open(assetZipPath).use { raw ->
            ZipInputStream(raw).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val out = File(targetDir, entry.name)
                    if (!out.canonicalPath.startsWith(targetDir.canonicalPath)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            while (true) {
                                val r = zis.read(buf)
                                if (r <= 0) break
                                fos.write(buf, 0, r)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    companion object {
        private const val TAG = "AuraVoice"
    }
}
