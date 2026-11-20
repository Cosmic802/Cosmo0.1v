package com.cosmic802.cosmo

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps settings and AI memory in /sdcard/CosmoData so they survive uninstall and reinstall. */
object Backup {

    private const val SHARED = "/sdcard/CosmoData"
    private const val FLAG = "restoredFromBackup"

    private fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    fun savePrefs(sp: SharedPreferences) {
        if (!hasAccess()) return
        try {
            val o = JSONObject()
            for ((k, v) in sp.all) {
                if (k == "apiKey" || k == "modelPath" || k == FLAG) continue
                val tag = when (v) {
                    is Boolean -> "b"
                    is Int -> "i"
                    is Long -> "l"
                    is Float -> "f"
                    is String -> "s"
                    else -> continue
                }
                o.put(k, JSONArray().put(tag).put(v))
            }
            val dir = File(SHARED)
            dir.mkdirs()
            File(dir, "prefs.json").writeText(o.toString(2))
        } catch (_: Exception) {
        }
    }

    /** Restores settings once after a reinstall. The API key is never written to the backup. */
    fun restoreIfNeeded(sp: SharedPreferences) {
        if (sp.getBoolean(FLAG, false) || !hasAccess()) return
        val f = File(SHARED, "prefs.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val e = sp.edit()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val a = o.optJSONArray(k) ?: continue
                when (a.optString(0)) {
                    "b" -> e.putBoolean(k, a.getBoolean(1))
                    "i" -> e.putInt(k, a.getInt(1))
                    "l" -> e.putLong(k, a.getLong(1))
                    "f" -> e.putFloat(k, a.getDouble(1).toFloat())
                    "s" -> e.putString(k, a.getString(1))
                }
            }
            e.putBoolean(FLAG, true)
            e.apply()
        } catch (_: Exception) {
        }
    }

    /** Moves memory.json and queue.json from the private folder to the shared folder once access is granted. */
    fun migrateData(ctx: Context) {
        if (!hasAccess()) return
        try {
            val priv = File(ctx.filesDir, "CosmoData")
            val dest = File(SHARED)
            dest.mkdirs()
            for (name in listOf("memory.json", "queue.json")) {
                val src = File(priv, name)
                val dst = File(dest, name)
                if (src.exists() && !dst.exists()) src.copyTo(dst)
            }
        } catch (_: Exception) {
        }
    }
}
