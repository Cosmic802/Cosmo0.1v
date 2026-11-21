package com.cosmic802.cosmo

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class BrainReply(
    val text: String,
    val queued: Boolean = false,
    val error: Boolean = false
)

private class ApiException(val code: Int, message: String) : Exception(message)

object CosmoBrain {

    const val DATA_DIR = "/sdcard/CosmoData"
    private const val LEGACY_KEY_FILE = "$DATA_DIR/api_key.txt"

    // Shared folder when "All files access" is granted, otherwise the app's private folder.
    private val dataDir: String
        get() = if (::appCtx.isInitialized && !hasStorageAccess()) {
            File(appCtx.filesDir, "CosmoData").absolutePath
        } else {
            DATA_DIR
        }
    private val MEMORY_FILE get() = "$dataDir/memory.json"
    private val CORRUPT_FILE get() = "$dataDir/memory.corrupt.json"
    private val QUEUE_FILE get() = "$dataDir/queue.json"

    private fun endpoint(model: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    // Tried in order when the chosen model is out of quota / unavailable.
    private val FALLBACK_MODELS = listOf("gemini-4-hydrogen", "gemini-3.8-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.5-flash")

    private const val MAX_CHAT = 60
    private const val MAX_LOGS = 200
    private const val HISTORY_TURNS = 10

    private const val PERSONA =
        "You are Cosmo, a calm, dry-witted British butler AI in the style of J.A.R.V.I.S. " +
            "Address the user as \"Sir\". Be concise, precise and practical. " +
            "Give disciplined, honest advice without lecturing. Plain text only, no markdown."

    private val lock = Any()
    private val syncing = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appCtx: Context

    @Volatile
    var onQueueResult: ((kind: String, prompt: String, reply: String) -> Unit)? = null

    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        Prefs.init(appCtx)
        Tracker.init(appCtx)
        Backup.migrateData(appCtx)
        if (Prefs.autoTrack) Tracker.schedule()
        try {
            val cm = appCtx.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        syncQueueAsync()
                    }
                }
            })
        } catch (_: Exception) {
        }
    }

    fun isOnline(): Boolean {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    fun storageSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------- file layer (direct access, root fallback) ----------

    private fun fileExists(path: String): Boolean =
        try {
            File(path).exists() || (path.startsWith(DATA_DIR) && RootShell.exec("test -f '$path'").first == 0)
        } catch (_: Exception) {
            false
        }

    private fun readFile(path: String): String? {
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) return f.readText()
        } catch (_: Exception) {
        }
        if (!path.startsWith(DATA_DIR)) return null
        val (code, out) = RootShell.exec("cat '$path'")
        return if (code == 0) out else null
    }

    private fun writeFile(path: String, text: String): Boolean {
        try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(text)
            return true
        } catch (_: Exception) {
        }
        if (!path.startsWith(DATA_DIR)) return false
        return try {
            val tmp = File(appCtx.cacheDir, "cosmo_tmp.dat")
            tmp.writeText(text)
            val (code, _) = RootShell.exec(
                "mkdir -p '$DATA_DIR'",
                "cp '${tmp.absolutePath}' '$path'",
                "chmod 666 '$path'"
            )
            code == 0
        } catch (_: Exception) {
            false
        }
    }

    // ---------- memory.json ----------

    private fun normalize(o: JSONObject): JSONObject {
        if (o.optJSONObject("habits") == null) o.put("habits", JSONObject())
        if (o.optJSONArray("chat") == null) o.put("chat", JSONArray())
        if (o.optJSONArray("logs") == null) o.put("logs", JSONArray())
        if (o.optJSONArray("facts") == null) o.put("facts", JSONArray())
        return o
    }

    private fun freshMemory(): JSONObject = normalize(JSONObject())

    private fun loadMemoryOrNull(): JSONObject? {
        val raw = readFile(MEMORY_FILE)
        if (raw == null) {
            return if (fileExists(MEMORY_FILE)) null else freshMemory()
        }
        if (raw.isBlank()) return freshMemory()
        return try {
            normalize(JSONObject(raw))
        } catch (_: Exception) {
            writeFile(CORRUPT_FILE, raw)
            freshMemory()
        }
    }

    fun loadMemory(): JSONObject = synchronized(lock) { loadMemoryOrNull() ?: freshMemory() }

    fun updateMemory(block: (JSONObject) -> Unit): Boolean = synchronized(lock) {
        val m = loadMemoryOrNull() ?: return false
        block(m)
        writeFile(MEMORY_FILE, m.toString(2))
    }

    private fun trim(arr: JSONArray, max: Int) {
        while (arr.length() > max) arr.remove(0)
    }

    fun appendChat(role: String, text: String): Boolean = updateMemory { m ->
        val chat = m.getJSONArray("chat")
        chat.put(
            JSONObject()
                .put("role", role)
                .put("text", text)
                .put("ts", System.currentTimeMillis())
        )
        trim(chat, MAX_CHAT)
    }

    fun appendLog(text: String): Boolean = updateMemory { m ->
        val logs = m.getJSONArray("logs")
        logs.put(
            JSONObject()
                .put("text", text)
                .put("ts", System.currentTimeMillis())
        )
        trim(logs, MAX_LOGS)
    }

    fun todayKey(): String = dateKey(0)

    private fun dateKey(daysAgo: Int): String {
        val t = System.currentTimeMillis() - daysAgo * 86_400_000L
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))
    }

    fun habitsFor(date: String): JSONObject =
        loadMemory().getJSONObject("habits").optJSONObject(date) ?: JSONObject()

    // ---------- api key ----------

    fun getApiKey(): String {
        val k = Prefs.apiKey
        if (k.isNotEmpty()) return k
        // one-time migration from the old /sdcard file
        val legacy = try {
            val f = File(LEGACY_KEY_FILE)
            if (f.exists() && f.canRead()) f.readText().trim() else ""
        } catch (_: Exception) {
            ""
        }
        if (legacy.isNotEmpty()) Prefs.apiKey = legacy
        return legacy
    }

    fun setApiKey(key: String): Boolean {
        Prefs.apiKey = key
        return true
    }


    // ---------- personal context (profile + remembered facts) ----------

    private val REMEMBER_RE = Regex(
        "^(?:please\\s+)?(?:remember|sana|сана|санаарай)\\s*(?:that|:)?\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    fun addFact(text: String): Boolean = updateMemory { m ->
        val a = m.getJSONArray("facts")
        a.put(JSONObject().put("text", text).put("ts", System.currentTimeMillis()))
        trim(a, 100)
    }

    fun listFacts(): List<String> {
        val a = loadMemory().optJSONArray("facts") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isNotBlank()) out.add(t)
        }
        return out
    }

    fun clearFacts(): Boolean = updateMemory { m -> m.put("facts", JSONArray()) }

    private fun languageRule(): String = when (Prefs.language) {
        "MN" -> "Always reply in Mongolian (Cyrillic script)."
        "EN" -> "Always reply in English."
        else -> "Reply in the same language the user writes in (English or Mongolian; if the Mongolian is typed in Latin letters, answer in Mongolian Cyrillic)."
    }

    private fun basePersona(): String = when (Prefs.style) {
        "FRIEND" -> "You are Cosmo, a warm, friendly personal AI assistant. Be natural, supportive and concise. Plain text only, no markdown."
        "CONCISE" -> "You are Cosmo, a personal AI assistant. Answer in at most three short sentences. Plain text only, no markdown."
        else -> PERSONA
    }

    private fun persona(): String = buildString {
        append(basePersona()).append('\n')
        append(languageRule()).append('\n')
        if (Prefs.userName.isNotBlank()) append("The user's name is ").append(Prefs.userName).append(".\n")
        if (Prefs.about.isNotBlank()) append("About the user: ").append(Prefs.about).append('\n')
        append(Profile.personaBlock())
        val facts = listFacts().takeLast(20)
        if (facts.isNotEmpty()) {
            append("Things the user asked you to remember:\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }
        append("Current date/time: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())).append('\n')
        if (Prefs.trackInAi) {
            try {
                append(Tracker.aiSummary())
            } catch (_: Exception) {
            }
        }
        try {
            val open = TaskStore.list().filter { !it.done }.take(8)
            if (open.isNotEmpty()) {
                append("User's open tasks: ").append(open.joinToString("; ") { it.title }).append('\n')
            }
            append("Today's habit data: ").append(habitsFor(todayKey()).toString()).append('\n')
        } catch (_: Exception) {
        }
    }

    private fun localSystem(): String = buildString {
        append(basePersona()).append(' ').append(languageRule())
        if (Prefs.userName.isNotBlank()) append(" The user's name is ").append(Prefs.userName).append('.')
        if (Prefs.about.isNotBlank()) append(" About the user: ").append(Prefs.about.take(300)).append('.')
        append(Profile.localBlock())
        val facts = listFacts().takeLast(5)
        if (facts.isNotEmpty()) append(" Known facts: ").append(facts.joinToString("; ")).append('.')
    }

    // ---------- gemini ----------

    private fun content(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun contentsFor(prompt: String, withHistory: Boolean): JSONArray {
        val arr = JSONArray()
        if (withHistory) {
            val chat = loadMemory().getJSONArray("chat")
            val start = maxOf(0, chat.length() - HISTORY_TURNS)
            for (i in start until chat.length()) {
                val m = chat.optJSONObject(i) ?: continue
                val role = if (m.optString("role") == "model") "model" else "user"
                if (arr.length() == 0 && role == "model") continue
                arr.put(content(role, m.optString("text")))
            }
        }
        arr.put(content("user", prompt))
        return arr
    }

    /** Tries the chosen model first, then the fallbacks when quota/availability fails. */
    private fun callGemini(contents: JSONArray): Result<String> {
        val models = (listOf(Prefs.cloudModel.ifBlank { "gemini-4-hydrogen" }) + FALLBACK_MODELS).distinct()
        var last: Result<String> = Result.failure(IllegalStateException("Gemini unavailable"))
        for (m in models) {
            val r = callGeminiModel(m, contents)
            if (r.isSuccess) return r
            last = r
            val e = r.exceptionOrNull()
            val tryNext = e is ApiException && (e.code == 429 || e.code == 404 || e.code >= 500)
            if (!tryNext) break
        }
        return last
    }

    // Gemini 4 models count thinking tokens inside maxOutputTokens, so give them room and keep thinking low.
    private fun generationConfigFor(model: String): JSONObject {
        val cfg = JSONObject()
        if (model.contains("gemini-4", ignoreCase = true)) {
            cfg.put("maxOutputTokens", 8192)
            cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        } else {
            cfg.put("maxOutputTokens", 4096)
        }
        return cfg
    }

    private fun friendlyError(code: Int, body: String): String = when {
        code == 429 -> "Gemini quota exceeded (429). Wait a minute, pick another model in Settings, or check billing for this API key."
        code == 400 || code == 401 || code == 403 -> "Gemini rejected the request ($code). Check your API key in Settings."
        code == 404 -> "Gemini model not found (404). Change the model name in Settings."
        else -> "Gemini error $code"
    }

    private fun callGeminiModel(model: String, contents: JSONArray): Result<String> {
        val key = getApiKey()
        if (key.isEmpty()) {
            return Result.failure(IllegalStateException("API key missing. Set it in Settings, Sir."))
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint(model)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", persona())))
                )
                .put("contents", contents)
                .put("generationConfig", generationConfigFor(model))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return Result.failure(ApiException(code, friendlyError(code, text)))
            }

            val parts = JSONObject(text)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            val reply = sb.toString().trim()
            return if (reply.isEmpty()) {
                Result.failure(IllegalStateException("Empty response from Gemini."))
            } else {
                Result.success(reply)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun isRetryable(e: Throwable?): Boolean =
        e is IOException || (e is ApiException && (e.code == 429 || e.code >= 500))

    private fun record(kind: String, prompt: String, reply: String) {
        if (kind == "chat") {
            appendChat("user", prompt)
            appendChat("model", reply)
        } else {
            appendLog("[$kind] $reply")
        }
    }

    // ---------- offline queue ----------

    private fun loadQueue(): JSONArray? {
        val raw = readFile(QUEUE_FILE)
        if (raw == null) {
            return if (fileExists(QUEUE_FILE)) null else JSONArray()
        }
        if (raw.isBlank()) return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun queueSize(): Int = synchronized(lock) { loadQueue()?.length() ?: 0 }

    private fun enqueue(prompt: String, kind: String): Boolean = synchronized(lock) {
        val q = loadQueue() ?: return false
        q.put(
            JSONObject()
                .put("id", System.currentTimeMillis())
                .put("kind", kind)
                .put("prompt", prompt)
        )
        writeFile(QUEUE_FILE, q.toString(2))
    }

    fun syncQueueAsync() {
        Thread { syncQueue() }.start()
    }

    fun syncQueue(): Int {
        if (!::appCtx.isInitialized) return 0
        if (!syncing.compareAndSet(false, true)) return 0
        var done = 0
        try {
            while (isOnline()) {
                val item = synchronized(lock) { loadQueue()?.optJSONObject(0) } ?: break
                val prompt = item.optString("prompt")
                val kind = item.optString("kind", "chat")
                val reply = callGemini(contentsFor(prompt, false)).getOrNull() ?: break
                record(kind, prompt, reply)
                synchronized(lock) {
                    val q = loadQueue()
                    if (q != null && q.length() > 0) {
                        q.remove(0)
                        writeFile(QUEUE_FILE, q.toString(2))
                    }
                }
                done++
                main.post { onQueueResult?.invoke(kind, prompt, reply) }
            }
        } finally {
            syncing.set(false)
        }
        return done
    }

    // ---------- public ask API ----------

    private fun askBlocking(prompt: String, kind: String): BrainReply {
        // "remember ..." works online and offline
        if (kind == "chat") {
            REMEMBER_RE.find(prompt.trim())?.let { mt ->
                val fact = mt.groupValues[1].trim()
                if (fact.isNotEmpty()) {
                    val ok = addFact(fact)
                    val mn = LocalAI.isMongolian(prompt)
                    return BrainReply(
                        if (ok) (if (mn) "Тэмдэглэж авлаа." else "Noted. I will remember that.")
                        else (if (mn) "Хадгалж чадсангүй." else "I could not save that."),
                        error = !ok
                    )
                }
            }
        }

        if (kind == "chat") {
            Assistant.tryHandle(appCtx, prompt)?.let { t ->
                record(kind, prompt, t)
                return BrainReply(t)
            }
        }

        // device actions work online and offline
        if (kind == "chat") {
            val low = prompt.lowercase(Locale.ROOT)
            if (listOf("flashlight", "torch", "fonar", "чийдэн").any { low.contains(it) }) {
                val turnOff = low.contains("off") || low.contains("унтраа") || low.contains("untra")
                val ok = DeviceActions.torch(appCtx, !turnOff)
                val mn = LocalAI.isMongolian(prompt)
                val text = when {
                    !ok -> if (mn) "Гар чийдэнг удирдаж чадсангүй." else "I could not control the flashlight."
                    turnOff -> if (mn) "Гар чийдэн унтарлаа." else "Flashlight off."
                    else -> if (mn) "Гар чийдэн асалаа." else "Flashlight on."
                }
                record(kind, prompt, text)
                return BrainReply(text, error = !ok)
            }
        }

        // 1) cloud (Gemini) when allowed, online and a key exists
        var cloudNote: String? = null
        val key = getApiKey()
        if (!Prefs.offlineOnly) {
            if (key.isEmpty()) {
                cloudNote = "No Gemini API key set"
            } else if (!isOnline()) {
                cloudNote = "No internet"
            } else {
                val result = callGemini(contentsFor(prompt, kind == "chat"))
                val reply = result.getOrNull()
                if (reply != null) {
                    record(kind, prompt, reply)
                    return BrainReply(reply)
                }
                cloudNote = result.exceptionOrNull()?.message ?: "Cloud error"
            }
        }

        // 2) on-device model, 3) built-in rule engine
        val local = LocalAI.generate(appCtx, localSystem(), prompt)
        val text = local ?: LocalAI.ruleBased(prompt, kind, cloudNote != null)
        record(kind, prompt, text)
        val note = (if (local == null && cloudNote != null) "\n\n[$cloudNote]" else "") + (if (local == null && LlamaEngine.isReady() && LlamaEngine.lastError.isNotEmpty()) "\n[Local model: ${LlamaEngine.lastError}]" else "")
        return BrainReply(text + note)
    }

    fun ask(prompt: String, kind: String = "chat", callback: (BrainReply) -> Unit) {
        Thread {
            val r = askBlocking(prompt, kind)
            main.post { callback(r) }
        }.start()
    }

    private fun askWithHabits(kind: String, instruction: String, callback: (BrainReply) -> Unit) {
        Thread {
            val mem = loadMemory()
            val habits = mem.getJSONObject("habits")
            val logs = mem.getJSONArray("logs")
            val recent = StringBuilder()
            for (i in maxOf(0, logs.length() - 5) until logs.length()) {
                recent.append("- ").append(logs.getJSONObject(i).optString("text")).append('\n')
            }
            val prompt = buildString {
                append(instruction).append("\n\n")
                append("Today (").append(todayKey()).append(") habits: ")
                append((habits.optJSONObject(todayKey()) ?: JSONObject()).toString()).append('\n')
                append("Yesterday (").append(dateKey(1)).append(") habits: ")
                append((habits.optJSONObject(dateKey(1)) ?: JSONObject()).toString()).append('\n')
                append("Recent notes:\n").append(recent)
                append("\nHabit keys: water_ml (goal 2200), steps (goal 8000), brush_am, tongue, spf, posture, skincare_pm, floss, brush_pm, hair, sleep_on_time.")
            }
            val r = askBlocking(prompt, kind)
            main.post { callback(r) }
        }.start()
    }

    fun dailyBriefing(callback: (BrainReply) -> Unit) =
        askWithHabits(
            "briefing",
            "Give my daily briefing: a one-line greeting, status of today's habits versus goals, and the top three priorities. Max 120 words.",
            callback
        )

    fun disciplineAdvice(callback: (BrainReply) -> Unit) =
        askWithHabits(
            "discipline",
            "Give firm but fair discipline advice based on my habit data. Point out what slipped, what to fix next. Max 100 words.",
            callback
        )
}
