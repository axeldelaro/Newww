package com.newww.auracompanion.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.*
import com.newww.auracompanion.model.VoicePack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pipeline Vocal 100 % Hors-Ligne intégré dans l'APK :
 * - STT (Écoute FR) : Sherpa-ONNX Whisper-Tiny INT8 (lang="fr") + détection fin de parole
 * - TTS (Voix FR neurale) : Sherpa-ONNX Piper VITS (Siwis + UPMC Jessica/Pierre) avec réglage vitesse/hauteur
 * - Lip-Sync temps réel 60 Hz piloté par l'enveloppe RMS de l'audio joué
 */
class OfflineVoicePipeline(private val context: Context) {

    private val _voiceReady = MutableStateFlow(false)
    val voiceReady: StateFlow<Boolean> = _voiceReady.asStateFlow()

    private val _voiceStatus = MutableStateFlow("Préparation de la voix neurale FR…")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lipSyncLevel = MutableStateFlow(0f)
    val lipSyncLevel: StateFlow<Float> = _lipSyncLevel.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var ttsSiwis: OfflineTts? = null
    private var ttsUpmc: OfflineTts? = null
    private var fallbackTts: TextToSpeech? = null
    private var activeTrack: AudioTrack? = null

    private val stopListenFlag = AtomicBoolean(false)
    private val stopSpeakFlag = AtomicBoolean(false)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (_voiceReady.value) return@withContext
        _voiceStatus.value = "Extraction des voix neurales FR & Whisper…"

        val voiceRoot = File(context.filesDir, "voice_models").apply { mkdirs() }
        val espeakDir = File(voiceRoot, "espeak-ng-data")
        val whisperDir = File(voiceRoot, "whisper")
        val ttsDir = File(voiceRoot, "tts")

        runCatching {
            copyAssetTreeIfNeeded("voice/espeak-ng-data", espeakDir)
            copyAssetTreeIfNeeded("voice/whisper", whisperDir)
            copyAssetTreeIfNeeded("voice/tts", ttsDir)
        }

        // 1. Initialisation Whisper-Tiny INT8 FR (STT local)
        runCatching {
            val enc = File(whisperDir, "tiny-encoder.int8.onnx")
            val dec = File(whisperDir, "tiny-decoder.int8.onnx")
            val tok = File(whisperDir, "tiny-tokens.txt")
            if (enc.exists() && dec.exists() && tok.exists()) {
                val whisperCfg = OfflineWhisperModelConfig().apply {
                    encoder = enc.absolutePath
                    decoder = dec.absolutePath
                    language = "fr"
                    task = "transcribe"
                }
                val modelCfg = OfflineModelConfig().apply {
                    whisper = whisperCfg
                    tokens = tok.absolutePath
                    numThreads = 2
                    provider = "cpu"
                }
                val recCfg = OfflineRecognizerConfig().apply {
                    modelConfig = modelCfg
                }
                recognizer = OfflineRecognizer(null, recCfg)
            }
        }

        // 2. Initialisation Piper VITS FR — Voix Siwis (féminine douce)
        runCatching {
            val model = File(ttsDir, "fr_FR-siwis-medium.onnx")
            val tokens = File(ttsDir, "siwis-tokens.txt")
            if (model.exists() && tokens.exists() && espeakDir.exists()) {
                val vits = OfflineTtsVitsModelConfig().apply {
                    this.model = model.absolutePath
                    this.tokens = tokens.absolutePath
                    this.dataDir = espeakDir.absolutePath
                }
                val mCfg = OfflineTtsModelConfig().apply {
                    this.vits = vits
                    this.numThreads = 2
                    this.provider = "cpu"
                }
                ttsSiwis = OfflineTts(null, OfflineTtsConfig().apply { this.model = mCfg })
            }
        }

        // 3. Initialisation Piper VITS FR — Voix UPMC (Jessica féminine vive + Pierre masculin)
        runCatching {
            val model = File(ttsDir, "fr_FR-upmc-medium.onnx")
            val tokens = File(ttsDir, "upmc-tokens.txt")
            if (model.exists() && tokens.exists() && espeakDir.exists()) {
                val vits = OfflineTtsVitsModelConfig().apply {
                    this.model = model.absolutePath
                    this.tokens = tokens.absolutePath
                    this.dataDir = espeakDir.absolutePath
                }
                val mCfg = OfflineTtsModelConfig().apply {
                    this.vits = vits
                    this.numThreads = 2
                    this.provider = "cpu"
                }
                ttsUpmc = OfflineTts(null, OfflineTtsConfig().apply { this.model = mCfg })
            }
        }

        // Secours Android TTS français si besoin
        runCatching {
            fallbackTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    fallbackTts?.language = Locale.FRENCH
                }
            }
        }

        val sttOk = recognizer != null
        val ttsOk = ttsSiwis != null || ttsUpmc != null
        _voiceReady.value = sttOk || ttsOk
        _voiceStatus.value = when {
            sttOk && ttsOk -> "Voix Neurale FR + Whisper Local actifs (100 % hors-ligne)"
            ttsOk -> "Voix Neurale FR active (100 % hors-ligne)"
            sttOk -> "Whisper FR actif (TTS système)"
            else -> "Audio système hors-ligne actif"
        }
    }

    /**
     * Enregistre au micro à 16 kHz jusqu'à [stopListening] ou silence prolongé ([autoStopOnSilence])
     * puis transcrit localement en français avec Whisper-Tiny INT8.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordAndTranscribeFrench(
        autoStopOnSilence: Boolean = true,
        maxSeconds: Int = 12
    ): String = withContext(Dispatchers.IO) {
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
        val chunk = ShortArray(1600) // 100 ms
        var speechDetected = false
        var silentChunksAfterSpeech = 0
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
                    _micLevel.value = (rms * 5.5f).coerceIn(0f, 1f)

                    if (rms > 0.028f) {
                        speechDetected = true
                        silentChunksAfterSpeech = 0
                    } else if (speechDetected) {
                        silentChunksAfterSpeech++
                        if (autoStopOnSilence && silentChunksAfterSpeech >= 14) {
                            break
                        }
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
            val text = rec.getResult(stream).text.trim()
            stream.release()
            text
        }.getOrDefault("")
    }

    fun stopListening() {
        stopListenFlag.set(true)
    }

    /**
     * Synthétise et joue la réponse avec la voix neurale choisie tout en émettant
     * le niveau temps réel [lipSyncLevel] (0..1) pour animer les lèvres à 60 FPS !
     */
    suspend fun speak(
        text: String,
        voicePack: VoicePack,
        speed: Float = 1.0f,
        pitch: Float = 1.0f
    ) = withContext(Dispatchers.IO) {
        val clean = text
            .replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return@withContext

        stopSpeaking()
        stopSpeakFlag.set(false)
        _isSpeaking.value = true

        val engine = when (voicePack.modelKey) {
            "upmc" -> ttsUpmc ?: ttsSiwis
            else -> ttsSiwis ?: ttsUpmc
        }

        if (engine == null) {
            // Secours Android TTS avec animation lèvres synthétique douce
            runCatching {
                fallbackTts?.setSpeechRate(speed.coerceIn(0.7f, 1.4f))
                fallbackTts?.setPitch(pitch.coerceIn(0.75f, 1.35f))
                fallbackTts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "aura_utt")
                val estMs = (clean.length * 62L / speed.coerceIn(0.7f, 1.4f)).toLong().coerceIn(800L, 12000L)
                var elapsed = 0L
                while (elapsed < estMs && !stopSpeakFlag.get()) {
                    _lipSyncLevel.value = (0.25f + 0.65f * kotlin.math.abs(kotlin.math.sin(elapsed * 0.018))).toFloat()
                    delay(32L)
                    elapsed += 32L
                }
            }
            _lipSyncLevel.value = 0f
            _isSpeaking.value = false
            return@withContext
        }

        try {
            val sid = if (engine === ttsUpmc) voicePack.speakerId else 0
            val generated = engine.generate(clean, sid, speed.coerceIn(0.7f, 1.4f))
            val samples = generated.samples
            if (samples.isEmpty() || stopSpeakFlag.get()) {
                _lipSyncLevel.value = 0f
                _isSpeaking.value = false
                return@withContext
            }

            // Ajustement subtil du pitch via fréquence d'échantillonnage de lecture
            val baseRate = generated.sampleRate.coerceIn(16000, 48000)
            val playbackRate = (baseRate * pitch.coerceIn(0.85f, 1.20f)).toInt().coerceIn(14000, 48000)
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
            track.play()

            val frameSize = (playbackRate / 30).coerceAtLeast(512) // ~33 ms par trame lip-sync
            val pcmBuf = ShortArray(frameSize)
            var idx = 0
            var smoothed = 0f

            while (idx < samples.size && !stopSpeakFlag.get()) {
                val count = min(frameSize, samples.size - idx)
                var sumSq = 0.0
                for (i in 0 until count) {
                    val f = samples[idx + i].coerceIn(-1f, 1f)
                    sumSq += f * f
                    pcmBuf[i] = (f * 32767f).toInt().toShort()
                }
                val rms = sqrt(sumSq / count).toFloat()
                val targetLip = ((rms - 0.015f) * 5.2f).coerceIn(0f, 1f)
                smoothed = smoothed * 0.42f + targetLip * 0.58f
                _lipSyncLevel.value = smoothed

                track.write(pcmBuf, 0, count)
                idx += count
            }

            runCatching { track.stop() }
            runCatching { track.release() }
            activeTrack = null
        } catch (_: Throwable) {
        } finally {
            _lipSyncLevel.value = 0f
            _isSpeaking.value = false
        }
    }

    fun stopSpeaking() {
        stopSpeakFlag.set(true)
        runCatching { activeTrack?.stop() }
        runCatching { activeTrack?.release() }
        activeTrack = null
        runCatching { fallbackTts?.stop() }
        _lipSyncLevel.value = 0f
        _isSpeaking.value = false
    }

    fun release() {
        stopListening()
        stopSpeaking()
        runCatching { recognizer?.release() }
        runCatching { ttsSiwis?.release() }
        runCatching { ttsUpmc?.release() }
        runCatching { fallbackTts?.shutdown() }
    }

    private fun copyAssetTreeIfNeeded(assetPath: String, destDir: File) {
        val entries = context.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // Fichier feuille
            if (destDir.exists() && destDir.length() > 0L) return
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destDir).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
        } else {
            destDir.mkdirs()
            for (child in entries) {
                copyAssetTreeIfNeeded("$assetPath/$child", File(destDir, child))
            }
        }
    }
}
