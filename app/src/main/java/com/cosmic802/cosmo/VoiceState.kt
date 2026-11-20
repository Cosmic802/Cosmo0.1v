package com.cosmic802.cosmo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Live voice status shown in the UI, plus wake-word and language helpers. */
object VoiceState {

    var status by mutableStateOf("OFF")
    var error by mutableStateOf<String?>(null)
    var lastHeard by mutableStateOf("")
    var ttsNote by mutableStateOf("")

    private val DEFAULT_WAKE = listOf(
        "cosmo", "cosmos", "kosmo", "kosmos", "cosmic", "cozmo", "kozmo", "cos mo", "kos mo"
    )

    fun recognitionLocale(): String = when (Prefs.language) {
        "MN" -> "mn-MN"
        else -> "en-GB"
    }

    fun ttsLocale(): Locale = when (Prefs.language) {
        "MN" -> Locale("mn", "MN")
        else -> Locale.UK
    }

    private fun wakeVariants(): List<String> {
        val w = Prefs.wakeWord.trim().lowercase(Locale.ROOT)
        return if (w.isEmpty() || w == "cosmo") DEFAULT_WAKE else listOf(w)
    }

    /** Returns (wake word found, text after the wake word). */
    fun splitWake(t: String): Pair<Boolean, String> {
        for (v in wakeVariants().sortedByDescending { it.length }) {
            val i = t.indexOf(v)
            if (i >= 0) {
                val rest = t.substring(i + v.length)
                    .trim()
                    .trim(',', '.', '?', '!')
                    .trim()
                return true to rest
            }
        }
        return false to t
    }

    fun diagnose(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        out += if (mic) "Mic permission: OK" else "Mic permission: MISSING (tap VOICE on HOME to grant)"

        val avail = SpeechRecognizer.isRecognitionAvailable(ctx)
        out += if (avail) {
            "Speech service: OK"
        } else {
            "Speech service: MISSING. Install Google app or Speech Services by Google, then retry."
        }

        val services = try {
            ctx.packageManager
                .queryIntentServices(android.content.Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo.packageName }
        } catch (_: Exception) {
            emptyList()
        }
        out += "Recognition apps: " + (if (services.isEmpty()) "none" else services.joinToString(", "))

        out += "Recognition language: ${recognitionLocale()}"
        out += "Wake word: " + (if (Prefs.wakeEnabled) "\"${Prefs.wakeWord.ifBlank { "cosmo" }}\"" else "OFF (every phrase is a command)")
        out += if (ttsNote.isNotEmpty()) "Speech output: $ttsNote" else "Speech output: not checked yet (use TEST SPEAK)"
        out += "Offline hint: Phone Settings > Google > Speech > Offline speech recognition > download English."
        return out
    }

    /** Called by TTS code to check language support and remember a short note. */
    fun checkTtsLanguage(tts: TextToSpeech): Locale {
        val want = ttsLocale()
        var r = tts.setLanguage(want)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsNote = "${want.toLanguageTag()} voice missing, using English"
            r = tts.setLanguage(Locale.UK)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            return Locale.UK
        }
        ttsNote = "OK (${want.toLanguageTag()})"
        return want
    }
}
