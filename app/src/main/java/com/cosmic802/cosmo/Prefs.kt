package com.cosmic802.cosmo

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** App settings, stored privately inside the app (no storage permission needed). */
object Prefs {

    val ACCENTS = listOf(
        "CYAN" to Color(0xFF00E5FF),
        "VIOLET" to Color(0xFF8C7CFF),
        "GREEN" to Color(0xFF00E676),
        "PINK" to Color(0xFFFF4D8D)
    )

    private var sp: SharedPreferences? = null

    var userName by mutableStateOf("")
    var about by mutableStateOf("")
    var language by mutableStateOf("AUTO")      // AUTO / EN / MN
    var style by mutableStateOf("BUTLER")       // BUTLER / FRIEND / CONCISE
    var offlineOnly by mutableStateOf(false)
    var accent by mutableStateOf(0)
    var modelPath by mutableStateOf("")
    var cloudModel by mutableStateOf("gemini-4-hydrogen")
    var speakReplies by mutableStateOf(false)
    var autoTrack by mutableStateOf(true)
    var trackInAi by mutableStateOf(true)
    var screenLimitH by mutableStateOf(4f)
    var sleepGoalH by mutableStateOf(8f)
    var glass by mutableStateOf(1f)
    var chatSize by mutableStateOf(15)

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("cosmo_prefs", Context.MODE_PRIVATE)
        sp = p
        userName = p.getString("userName", "") ?: ""
        about = p.getString("about", "") ?: ""
        language = p.getString("language", "AUTO") ?: "AUTO"
        style = p.getString("style", "BUTLER") ?: "BUTLER"
        offlineOnly = p.getBoolean("offlineOnly", false)
        accent = p.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1)
        modelPath = p.getString("modelPath", "") ?: ""
        cloudModel = p.getString("cloudModel", "gemini-4-hydrogen") ?: "gemini-4-hydrogen"
        // old default -> new free-tier default
        if (cloudModel == "gemini-flash-latest") cloudModel = "gemini-4-hydrogen"
        speakReplies = p.getBoolean("speakReplies", false)
        autoTrack = p.getBoolean("autoTrack", true)
        trackInAi = p.getBoolean("trackInAi", true)
        screenLimitH = p.getFloat("screenLimitH", 4f)
        sleepGoalH = p.getFloat("sleepGoalH", 8f)
        glass = p.getFloat("glass", 1f)
        chatSize = p.getInt("chatSize", 15)
    }

    fun save() {
        sp?.edit()
            ?.putString("userName", userName.trim())
            ?.putString("about", about.trim())
            ?.putString("language", language)
            ?.putString("style", style)
            ?.putBoolean("offlineOnly", offlineOnly)
            ?.putInt("accent", accent)
            ?.putString("modelPath", modelPath)
            ?.putString("cloudModel", cloudModel.trim().ifEmpty { "gemini-4-hydrogen" })
            ?.putBoolean("speakReplies", speakReplies)
            ?.putBoolean("autoTrack", autoTrack)
            ?.putBoolean("trackInAi", trackInAi)
            ?.putFloat("screenLimitH", screenLimitH)
            ?.putFloat("sleepGoalH", sleepGoalH)
            ?.putFloat("glass", glass)
            ?.putInt("chatSize", chatSize)
            ?.apply()
    }

    var apiKey: String
        get() = sp?.getString("apiKey", "") ?: ""
        set(v) {
            sp?.edit()?.putString("apiKey", v.trim())?.apply()
        }

    fun accentColor(): Color = ACCENTS[accent.coerceIn(0, ACCENTS.size - 1)].second
}
