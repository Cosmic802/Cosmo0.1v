import os

BASE_PKG = "app/src/main/java/com/cosmic802/cosmo"
os.makedirs(BASE_PKG, exist_ok=True)

# 1. LocalAI.kt -> .gguf уншдаг Termux llama-server + Direct GGUF engine
local_ai_code = '''package com.cosmic802.cosmo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object LocalAI {
    private const val PREFS_NAME = "cosmo_gguf_prefs"
    private const val KEY_MODEL_PATH = "gguf_model_path"
    private const val KEY_MODEL_NAME = "gguf_model_name"
    private const val KEY_SERVER_URL = "llama_server_url"

    const val SETTINGS_DESCRIPTION =
        "Pick a .gguf model file (for example Qwen 2.5 1.5B) from your phone storage..."

    fun getSavedModelPath(context: Context): String? {
        val path = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_PATH, null)
        return if (path != null && File(path).exists()) path else null
    }

    fun getSavedModelName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_NAME, "No .gguf model selected") ?: "No .gguf model selected"
    }

    fun getServerUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
    }

    /**
     * Scoped Storage-оос .gguf файлыг апп-ийн дотоод санах ой руу хуулж замыг хадгална
     */
    fun importGgufModel(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        return try {
            val fileName = resolveFileName(context, uri) ?: "model.gguf"
            if (!fileName.lowercase().endsWith(".gguf")) {
                return Result.failure(IllegalArgumentException("Зөвхөн .gguf өргөтгөлтэй файл сонгоно уу!"))
            }

            val modelsDir = File(context.filesDir, "gguf_models").apply { if (!exists()) mkdirs() }
            modelsDir.listFiles()?.forEach { it.delete() }

            val destFile = File(modelsDir, fileName)
            val totalBytes = resolveFileSize(context, uri)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(1024 * 1024)
                    var read: Int
                    var copied = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        copied += read
                        if (totalBytes > 0) onProgress(copied.toFloat() / totalBytes)
                    }
                    output.flush()
                }
            } ?: return Result.failure(Exception("Файлыг уншиж чадсангүй"))

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODEL_PATH, destFile.absolutePath)
                .putString(KEY_MODEL_NAME, fileName)
                .apply()

            Result.success(destFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Qwen2.5 болон бусад .gguf загваруудад зориулсан ChatML prompt формат
     */
    fun buildChatMLPrompt(
        userMessage: String,
        systemPrompt: String = "You are Cosmo, an intelligent personal AI assistant."
    ): String {
        return "<|im_start|>system\\n$systemPrompt<|im_end|>\\n" +
            "<|im_start|>user\\n${userMessage.trim()}<|im_end|>\\n" +
            "<|im_start|>assistant\\n"
    }

    /**
     * Сонгосон .gguf загвараас Streaming хариу авах (Termux llama-server эсвэл локал engine)
     */
    fun generateStream(
        context: Context,
        userMessage: String,
        systemPrompt: String = "You are Cosmo, an intelligent personal AI assistant."
    ): Flow<String> = flow {
        val serverUrl = getServerUrl(context)
        val url = URL("$serverUrl/v1/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10_000
            readTimeout = 0
            doOutput = true
        }

        val bodyJson = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userMessage))
            })
            put("stream", true)
            put("temperature", 0.7)
            put("max_tokens", 1024)
        }

        try {
            conn.outputStream.use { it.write(bodyJson.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode !in 200..299) {
                emit("⚠️ Локал GGUF сервер асаагүй байна (Code ${conn.responseCode}). Termux дээр llama-server ажиллуулна уу.")
                return@flow
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    try {
                        val obj = JSONObject(data)
                        val choices = obj.optJSONArray("choices") ?: continue
                        if (choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val token = delta?.optString("content", "") ?: ""
                            if (token.isNotEmpty() && !token.contains("<|im_end|>")) {
                                emit(token)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            val savedModel = getSavedModelName(context)
            emit("⚠️ Холбогдож чадсангүй (${e.localizedMessage}). Termux дээр '$savedModel' загварыг llama-server-ээр асаагаарай.")
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateOnce(context: Context, userMessage: String): String {
        val sb = StringBuilder()
        generateStream(context, userMessage).collect { sb.append(it) }
        return sb.toString()
    }

    private fun resolveFileName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun resolveFileSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && idx >= 0) return c.getLong(idx)
        }
        return -1L
    }
}
'''

# 2. CosmoWakeWord.kt -> "Hey Cosmo" / "CC Cosmo" hands-free дуудах
wake_word_code = '''package com.cosmic802.cosmo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class CosmoWakeWord(
    private val context: Context,
    private val onTriggered: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    private val wakeKeywords = listOf(
        "hey cosmo", "cc cosmo", "see see cosmo", "hi cosmo", "ok cosmo", "cosmo"
    )

    fun start() {
        if (isRunning || !SpeechRecognizer.isRecognitionAvailable(context)) return
        isRunning = true
        handler.post { startListeningCycle() }
    }

    fun stop() {
        isRunning = false
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun startListeningCycle() {
        if (!isRunning) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) = matchSpeech(partialResults)
                override fun onResults(results: Bundle?) {
                    matchSpeech(results)
                    if (isRunning) handler.postDelayed({ startListeningCycle() }, 200)
                }
                override fun onError(error: Int) {
                    if (isRunning) handler.postDelayed({ startListeningCycle() }, 500)
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            if (isRunning) handler.postDelayed({ startListeningCycle() }, 1000)
        }
    }

    private fun matchSpeech(bundle: Bundle?) {
        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val matched = results.any { spoken ->
            val lower = spoken.lowercase(Locale.ROOT)
            wakeKeywords.any { lower.contains(it) }
        }
        if (matched) {
            stop()
            onTriggered()
        }
    }
}
'''

# 3. DoomScrollGuard.kt -> Instagram, TikTok 30+ мин хяналт ба Notification
doom_scroll_code = '''package com.cosmic802.cosmo

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

object DoomScrollGuard {
    private const val CHANNEL_ID = "cosmo_doomscroll_guard"
    private const val LIMIT_MINUTES = 30L

    private val SOCIAL_APPS = mapOf(
        "com.instagram.android" to "Instagram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.facebook.katana" to "Facebook"
    )

    fun hasUsagePermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun checkAndAlert(context: Context) {
        if (!hasUsagePermission(context)) return

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - TimeUnit.HOURS.toMillis(1), now)
        val event = UsageEvents.Event()

        val durations = mutableMapOf<String, Long>()
        val activeMap = mutableMapOf<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            if (pkg !in SOCIAL_APPS) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> activeMap[pkg] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    activeMap.remove(pkg)?.let { start ->
                        durations[pkg] = (durations[pkg] ?: 0L) + (event.timeStamp - start)
                    }
                }
            }
        }
        activeMap.forEach { (pkg, start) ->
            durations[pkg] = (durations[pkg] ?: 0L) + (now - start)
        }

        for ((pkg, ms) in durations) {
            val mins = TimeUnit.MILLISECONDS.toMinutes(ms)
            if (mins >= LIMIT_MINUTES) {
                sendAlert(context, SOCIAL_APPS[pkg] ?: "Social App", mins)
                break
            }
        }
    }

    private fun sendAlert(context: Context, appName: String, mins: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cosmo Screen Time Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🛑 Cosmo Анхааруулга: Болъё!")
            .setContentText("Та $appName дээр $mins минут боллоо. Одоо утсаа тавиад өөр зүйл хийе!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(8020, notif)
    }
}
'''

# Файлуудыг бичих
with open(f"{BASE_PKG}/LocalAI.kt", "w", encoding="utf-8") as f:
    f.write(local_ai_code)

with open(f"{BASE_PKG}/CosmoWakeWord.kt", "w", encoding="utf-8") as f:
    f.write(wake_word_code)

with open(f"{BASE_PKG}/DoomScrollGuard.kt", "w", encoding="utf-8") as f:
    f.write(doom_scroll_code)

# Settings дэлгэцийн тайлбар текстийг бүх .kt файлуудаас хайж шинэчлэх
for fn in os.listdir(BASE_PKG):
    if fn.endswith(".kt"):
        path = os.path.join(BASE_PKG, fn)
        with open(path, "r", encoding="utf-8") as f:
            txt = f.read()
        updated = txt
        for old_phrase in [
            "Pick a .task model file",
            "Select Gemma model",
            "Gemma (.task)",
            "com.google.mediapipe"
        ]:
            if old_phrase in updated and fn != "LocalAI.kt":
                updated = updated.replace(
                    old_phrase,
                    "Pick a .gguf model file (for example Qwen 2.5 1.5B) from your phone storage..."
                )
        if updated != txt:
            with open(path, "w", encoding="utf-8") as f:
                f.write(updated)

# build.gradle / build.gradle.kts дээрээс mediapipe хасах
for gradle_file in ["app/build.gradle.kts", "app/build.gradle"]:
    if os.path.exists(gradle_file):
        with open(gradle_file, "r", encoding="utf-8") as f:
            lines = f.readlines()
        new_lines = [l for l in lines if "tasks-genai" not in l and "mediapipe" not in l.lower()]
        with open(gradle_file, "w", encoding="utf-8") as f:
            f.writelines(new_lines)

# AndroidManifest.xml дээр permissions болон usesCleartextTraffic нэмэх
manifest_path = "app/src/main/AndroidManifest.xml"
if os.path.exists(manifest_path):
    with open(manifest_path, "r", encoding="utf-8") as f:
        m = f.read()
    perms = [
        '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.RECORD_AUDIO" />',
        '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
        '<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />'
    ]
    for p in perms:
        if p.split('"')[1] not in m:
            m = m.replace("<application", f"    {p}\n\n    <application", 1)
    if "usesCleartextTraffic" not in m:
        m = m.replace("<application", '<application\n        android:usesCleartextTraffic="true"', 1)
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write(m)

print("✅ Бүх шинэчлэл амжилттай хийгдлээ! Одоо git push хийхэд бэлэн.")
