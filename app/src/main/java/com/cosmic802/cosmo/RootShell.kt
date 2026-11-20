package com.cosmic802.cosmo

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

object RootShell {

    private const val PREFS = "cosmo_root"
    private const val K_BRIGHT = "saved_brightness"
    private const val K_BRIGHT_MODE = "saved_brightness_mode"
    private const val DONE_MARK = "COSMO_DONE"
    private const val OVERLAY_CLASS = "com.cosmic802.cosmo.RedOverlayService"

    const val WIND_DOWN_MS = 40L * 60L * 1000L
    private const val SLEEP_BRIGHTNESS = 15
    private const val FALLBACK_BRIGHTNESS = 128

    val DISTRACTING = listOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.youtube",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.snapchat.android",
        "com.twitter.android",
        "com.reddit.frontpage"
    )

    private val main = Handler(Looper.getMainLooper())
    private var windDownTask: Runnable? = null

    @Volatile
    private var windDownEnd: Long = 0L

    private fun run(script: String): Pair<Int, String> {
        return try {
            val p = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            code to out
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        }
    }

    fun exec(vararg commands: String): Pair<Int, String> =
        run(commands.joinToString("; "))

    fun isRootAvailable(): Boolean {
        val (code, out) = run("id -u")
        return code == 0 && out.trim() == "0"
    }

    fun windDownRemainingMs(): Long {
        val left = windDownEnd - SystemClock.elapsedRealtime()
        return if (windDownEnd == 0L || left < 0L) 0L else left
    }

    fun apply(context: Context, mode: CosmoMode, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        Thread {
            cancelWindDown()
            val cmds = ArrayList<String>()
            cmds += resetCommands(app)
            when (mode) {
                CosmoMode.STUDY -> {
                    cmds += dndOn()
                    cmds += forceStopCommands()
                }
                CosmoMode.GAME -> {
                    cmds += "settings put system peak_refresh_rate 120.0"
                    cmds += "settings put system min_refresh_rate 120.0"
                    cmds += "am kill-all"
                    cmds += dndOn()
                }
                CosmoMode.SLEEP -> {
                    cmds += saveBrightnessCommands(app)
                    cmds += "settings put system screen_brightness_mode 0"
                    cmds += "settings put system screen_brightness $SLEEP_BRIGHTNESS"
                    cmds += dndOn()
                }
                CosmoMode.NORMAL -> Unit
            }
            cmds += "echo $DONE_MARK"
            val (_, out) = run(cmds.joinToString("; "))
            val ok = out.contains(DONE_MARK)

            if (ok) {
                when (mode) {
                    CosmoMode.SLEEP -> {
                        startOverlay(app)
                        scheduleWindDown()
                    }
                    else -> stopOverlay(app)
                }
            }
            main.post { onDone(ok) }
        }.start()
    }

    private fun dndOn(): String = "cmd notification set_dnd priority"

    private fun forceStopCommands(): List<String> =
        DISTRACTING.map { "am force-stop $it" }

    private fun lockCommands(): List<String> =
        DISTRACTING.flatMap {
            listOf("am force-stop $it", "pm suspend --user 0 $it")
        }

    private fun unlockCommands(): List<String> =
        DISTRACTING.map { "pm unsuspend --user 0 $it" }

    private fun resetCommands(context: Context): List<String> {
        val cmds = ArrayList<String>()
        cmds += "cmd notification set_dnd off"
        cmds += "settings delete system peak_refresh_rate"
        cmds += "settings delete system min_refresh_rate"
        cmds += unlockCommands()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(K_BRIGHT)) {
            val b = prefs.getInt(K_BRIGHT, FALLBACK_BRIGHTNESS)
            val m = prefs.getInt(K_BRIGHT_MODE, 1)
            cmds += "settings put system screen_brightness $b"
            cmds += "settings put system screen_brightness_mode $m"
            prefs.edit().remove(K_BRIGHT).remove(K_BRIGHT_MODE).apply()
        }
        return cmds
    }

    private fun saveBrightnessCommands(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val b = run("settings get system screen_brightness").second.trim().toIntOrNull()
            ?: FALLBACK_BRIGHTNESS
        val m = run("settings get system screen_brightness_mode").second.trim().toIntOrNull()
            ?: 1
        prefs.edit().putInt(K_BRIGHT, b).putInt(K_BRIGHT_MODE, m).apply()
        return emptyList()
    }

    private fun scheduleWindDown() {
        val task = Runnable {
            windDownEnd = 0L
            Thread {
                run((lockCommands() + "echo $DONE_MARK").joinToString("; "))
            }.start()
        }
        windDownTask = task
        windDownEnd = SystemClock.elapsedRealtime() + WIND_DOWN_MS
        main.postDelayed(task, WIND_DOWN_MS)
    }

    private fun cancelWindDown() {
        windDownTask?.let { main.removeCallbacks(it) }
        windDownTask = null
        windDownEnd = 0L
    }

    private fun overlayIntent(context: Context): Intent =
        Intent().setClassName(context, OVERLAY_CLASS)

    fun startOverlay(context: Context) {
        try {
            context.applicationContext.startForegroundService(overlayIntent(context))
        } catch (_: Exception) {
        }
    }

    fun stopOverlay(context: Context) {
        try {
            context.applicationContext.stopService(overlayIntent(context))
        } catch (_: Exception) {
        }
    }
}
