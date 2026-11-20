package com.cosmic802.cosmo

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speaks chat replies out loud (used when "Speak replies" is on, and for TEST SPEAK). */
object Speaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(ctx: Context, text: String) {
        val clean = text.replace(Regex("[*#_`]"), "").take(600).trim()
        if (clean.isEmpty()) return
        val t = tts
        if (t == null) {
            pending = clean
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    pending?.let { say(it) }
                    pending = null
                } else {
                    VoiceState.ttsNote = "Text-to-speech engine not available"
                    ready = false
                    tts = null
                }
            }
            return
        }
        if (ready) say(clean) else pending = clean
    }

    private fun say(text: String) {
        val t = tts ?: return
        val loc = VoiceState.checkTtsLanguage(t)
        try {
            val voices = t.voices
                ?.filter { it.locale.language == loc.language && !it.isNetworkConnectionRequired }
                .orEmpty()
            val name: (String) -> Boolean = {
                val n = it.lowercase(Locale.US)
                n.contains("gbd") || n.contains("rjs") || (n.contains("male") && !n.contains("female"))
            }
            val v = voices.firstOrNull { name(it.name) && it.locale.country == loc.country }
                ?: voices.firstOrNull { name(it.name) }
                ?: voices.firstOrNull { it.locale.country == loc.country }
                ?: voices.firstOrNull()
            if (v != null) t.voice = v
        } catch (_: Exception) {
        }
        t.setPitch(0.85f)
        t.setSpeechRate(0.95f)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cosmo-chat")
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }
}
