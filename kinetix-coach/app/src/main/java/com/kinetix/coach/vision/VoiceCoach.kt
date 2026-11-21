package com.kinetix.coach.vision

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceCoach(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var lastSpokenMs = 0L
    private var lastText = ""

    @Volatile
    var enabled: Boolean = true

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.FRENCH)
            ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(1.08f)
        }
    }

    fun speak(cue: String?, isRepCounter: Boolean = false) {
        if (!enabled || !ready || cue.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val minGapMs = if (isRepCounter) 700L else 3600L
        if (now - lastSpokenMs < minGapMs) return
        if (!isRepCounter && cue == lastText && now - lastSpokenMs < 6500L) return

        lastSpokenMs = now
        lastText = cue
        tts?.speak(cue, TextToSpeech.QUEUE_FLUSH, null, "kinetix_cue_$now")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
