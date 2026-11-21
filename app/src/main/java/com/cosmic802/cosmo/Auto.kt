package com.cosmic802.cosmo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** Background behaviour settings: always-on voice and doom-scroll alerts. */
object Auto {

    private const val PKG = "com.cosmic802.cosmo"
    private val main = Handler(Looper.getMainLooper())

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("cosmo_auto", Context.MODE_PRIVATE)

    fun voiceAlways(ctx: Context): Boolean = sp(ctx).getBoolean("voiceAlways", true)
    fun setVoiceAlways(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("voiceAlways", v).apply()

    fun doomOn(ctx: Context): Boolean = sp(ctx).getBoolean("doomOn", true)
    fun setDoomOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("doomOn", v).apply()

    fun doomMinutes(ctx: Context): Int = sp(ctx).getInt("doomMin", 30)
    fun setDoomMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("doomMin", v).apply()

    fun lastDoomAlert(ctx: Context): Long = sp(ctx).getLong("doomLast", 0L)
    fun setLastDoomAlert(ctx: Context, t: Long) = sp(ctx).edit().putLong("doomLast", t).apply()

    private fun micOk(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Called when the app opens. */
    fun startVoiceIfWanted(ctx: Context) {
        if (voiceAlways(ctx) && micOk(ctx) && !CosmoVoiceEngine.isRunning()) {
            CosmoVoiceEngine.start(ctx)
        }
        if (doomOn(ctx)) Tracker.schedule()
    }

    /** Called after the phone boots. Uses root to start the microphone service from the background. */
    fun onBoot(ctx: Context, pr: BroadcastReceiver.PendingResult) {
        if (doomOn(ctx)) Tracker.schedule()
        if (!voiceAlways(ctx) || !micOk(ctx)) {
            pr.finish()
            return
        }
        Thread {
            try {
                val (_, out) = RootShell.exec("am start-foreground-service --user 0 -n $PKG/.CosmoVoiceEngine")
                if (out.contains("Error", ignoreCase = true) || out.contains("not allowed", ignoreCase = true)) {
                    main.post { CosmoVoiceEngine.start(ctx) }
                }
            } catch (_: Exception) {
            } finally {
                pr.finish()
            }
        }.start()
    }

    /** Root: grants permissions and background exemptions so Cosmo keeps listening and alerting. */
    fun rootSetup(): Boolean {
        val (_, out) = RootShell.exec(
            "pm grant $PKG android.permission.RECORD_AUDIO",
            "pm grant $PKG android.permission.POST_NOTIFICATIONS",
            "pm grant $PKG android.permission.READ_CONTACTS",
            "pm grant $PKG android.permission.ACTIVITY_RECOGNITION",
            "cmd appops set $PKG GET_USAGE_STATS allow",
            "cmd appops set $PKG MANAGE_EXTERNAL_STORAGE allow",
            "cmd appops set $PKG SYSTEM_ALERT_WINDOW allow",
            "cmd appops set $PKG RECORD_AUDIO allow",
            "cmd appops set $PKG RUN_IN_BACKGROUND allow",
            "cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow",
            "dumpsys deviceidle whitelist +$PKG",
            "echo COSMO_ROOT_OK"
        )
        return out.contains("COSMO_ROOT_OK")
    }
}
