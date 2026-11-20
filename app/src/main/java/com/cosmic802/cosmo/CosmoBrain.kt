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
    private const val MEMORY_FILE = "$DATA_DIR/memory.json"
    private const val CORRUPT_FILE = "$DATA_DIR/memory.corrupt.json"
    private const val KEY_FILE = "$DATA_DIR/api_key.txt"
    private const val QUEUE_FILE = "$DATA_DIR/queue.json"

    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"

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
            File(path).exists() || RootShell.exec("test -f '$path'").first == 0
        } catch (_: Exception) {
            false
        }

    private fun readFile(path: String): String? {
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) return f.readText()
        } catch (_: Exception) {
        }
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

    fun getApiKey(): String = synchronized(lock) {
        readFile(KEY_FILE)?.trim().orEmpty()
    }

    fun setApiKey(key: String): Boolean = synchronized(lock) {
        writeFile(KEY_FILE, key.trim())
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

    private fun callGemini(contents: JSONArray): Result<String> {
        val key = getApiKey()
        if (key.isEmpty()) {
            return Result.failure(IllegalStateException("API key missing. Set it in Settings, Sir."))
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PERSONA)))
                )
                .put("contents", contents)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("maxOutputTokens", 2048)
                )
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return Result.failure(ApiException(code, "Gemini error $code: ${text.take(160)}"))
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
        if (!isOnline()) {
            enqueue(prompt, kind)
            return BrainReply(
                "Network unavailable, Sir. Request queued; I shall process it once we are back online.",
                queued = true
            )
        }
        val result = callGemini(contentsFor(prompt, kind == "chat"))
        val reply = result.getOrNull()
        if (reply != null) {
            record(kind, prompt, reply)
            return BrainReply(reply)
        }
        val err = result.exceptionOrNull()
        if (isRetryable(err)) {
            enqueue(prompt, kind)
            return BrainReply(
                "Connection to the service failed, Sir. Request queued for retry.",
                queued = true
            )
        }
        return BrainReply(err?.message ?: "Unknown error.", error = true)
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
                append("\nHabit keys: water_ml (goal 3000), steps (goal 8000), brush_am, brush_pm, skincare_am, skincare_pm, hair.")
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
