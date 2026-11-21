package com.kinetix.coach.vision

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceCoach(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val MIN_REP_GAP_MS = 650L
        private const val MIN_POSTURE_GAP_MS = 8500L
        private const val SAME_POSTURE_CUE_GAP_MS = 18000L
        private const val POST_REP_QUIET_MS = 1600L
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var lastSpokenMs = 0L
    private var lastRepSpokenMs = 0L
    private var lastPostureSpokenMs = 0L
    private val lastCueTimeByText = HashMap<String, Long>()

    @Volatile
    var enabled: Boolean = true

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.FRENCH)
            ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(1.06f)
        }
    }

    fun speak(cue: String?, isRepCounter: Boolean = false, force: Boolean = false) {
        if (!enabled || !ready || cue.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (!force) {
            if (isRepCounter) {
                if (now - lastRepSpokenMs < MIN_REP_GAP_MS) return
            } else {
                if (now - lastRepSpokenMs < POST_REP_QUIET_MS) return
                if (now - lastPostureSpokenMs < MIN_POSTURE_GAP_MS) return
                val prevSame = lastCueTimeByText[cue]
                if (prevSame != null && (now - prevSame) < SAME_POSTURE_CUE_GAP_MS) return
            }
        }

        lastSpokenMs = now
        if (isRepCounter) {
            lastRepSpokenMs = now
        } else if (!force) {
            lastPostureSpokenMs = now
            lastCueTimeByText[cue] = now
        }
        tts?.speak(cue, TextToSpeech.QUEUE_FLUSH, null, "kinetix_cue_$now")
    }

    fun resetCooldowns() {
        lastPostureSpokenMs = 0L
        lastCueTimeByText.clear()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
