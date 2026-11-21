package com.cosmic802.cosmo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Warns when feed apps (Instagram, TikTok ...) take up most of the last stretch of time. */
object Doom {

    private const val CHANNEL = "cosmo_doom"
    private const val COOLDOWN_MS = 60L * 60_000L

    val APPS = mapOf(
        "com.instagram.android" to "Instagram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.facebook.katana" to "Facebook",
        "com.snapchat.android" to "Snapchat",
        "com.twitter.android" to "X",
        "com.reddit.frontpage" to "Reddit"
    )

    /** Foreground milliseconds per feed app inside [from, to]. */
    private fun usage(ctx: Context, from: Long, to: Long): Map<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(from - 3L * 3_600_000L, to)
        val e = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val total = HashMap<String, Long>()

        fun close(pkg: String, end: Long) {
            val start = open.remove(pkg) ?: return
            val a = maxOf(start, from)
            val b = minOf(end, to)
            if (b > a) total[pkg] = (total[pkg] ?: 0L) + (b - a)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg in APPS && pkg !in open) open[pkg] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED -> if (pkg in APPS) close(pkg, e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    for (p in open.keys.toList()) close(p, e.timeStamp)
                }
            }
        }
        for (p in open.keys.toList()) close(p, to)
        return total
    }

    fun check(ctx: Context) {
        try {
            if (!Auto.doomOn(ctx) || !Tracker.hasUsageAccess()) return
            val limit = Auto.doomMinutes(ctx)
            val now = System.currentTimeMillis()
            if (now - Auto.lastDoomAlert(ctx) < COOLDOWN_MS) return

            val windowMin = limit + 15
            val per = usage(ctx, now - windowMin * 60_000L, now)
            val totalMin = per.values.sum() / 60_000L
            if (totalMin < limit) return

            val topPkg = per.maxByOrNull { it.value }?.key
            val topName = APPS[topPkg] ?: "social apps"
            notify(ctx, "About $totalMin min on $topName in the last $windowMin min. Put the phone down for a bit, Sir.")
            Auto.setLastDoomAlert(ctx, now)
        } catch (_: Exception) {
        }
    }

    private fun notify(ctx: Context, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Cosmo scroll alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            ctx,
            11,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Cosmo · scroll alert")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(9021, n)
        } catch (_: SecurityException) {
        }
    }
}
