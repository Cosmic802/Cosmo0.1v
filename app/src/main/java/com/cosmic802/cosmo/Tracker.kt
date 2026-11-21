package com.cosmic802.cosmo

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppUse(val label: String, val minutes: Int)

class Dash(
    val steps: Int,
    val status: String,
    val screenMin: Int,
    val apps: List<AppUse>,
    val weekMin: List<Int>,
    val sleep: Map<String, Double>
)

/**
 * Automatic tracking: steps (step-counter sensor), screen time (usage stats),
 * movement status and an estimated sleep log (longest screen-off stretch at night).
 * A background alarm wakes a tiny receiver every ~15 minutes, so nothing runs constantly.
 */
object Tracker {

    private const val SP = "cosmo_track"
    private const val ACTION = "com.cosmic802.cosmo.TRACK_TICK"
    private lateinit var app: Context
    private val lock = Any()

    @Volatile
    private var weekCache: List<Int> = emptyList()
    private var weekTs = 0L

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    private fun p() = app.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ---------------- dates ----------------

    fun dayKey(offset: Int = 0): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offset)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    fun dayLetter(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offset)
        return SimpleDateFormat("EEE", Locale.US).format(c.time).substring(0, 1)
    }

    private fun dayStart(offset: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offset)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun fmtMin(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    // ---------------- permissions ----------------

    fun hasActivityPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(): Boolean = try {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    // ---------------- scheduling ----------------

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        app,
        7,
        Intent(app, TrackReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule() {
        try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pending()
            )
        } catch (_: Exception) {
        }
    }

    fun cancel() {
        try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending())
        } catch (_: Exception) {
        }
    }

    /** One tracking tick: read steps, sync habits, update sleep log. Call off the main thread. */
    fun snapshot() {
        readStepCounterOnce()?.let {
            recordSteps(it)
            syncHabitSteps()
        }
        if (hasUsageAccess()) updateSleep()
    }

    // ---------------- steps ----------------

    private fun readStepCounterOnce(): Long? {
        if (!hasActivityPermission()) return null
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1)
        var value = -1L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                value = e.values[0].toLong()
                latch.countDown()
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))
        latch.await(6, TimeUnit.SECONDS)
        sm.unregisterListener(l)
        return if (value >= 0) value else null
    }

    fun recordSteps(counter: Long) {
        synchronized(lock) {
            val sp = p()
            val now = System.currentTimeMillis()
            val today = dayKey()
            val lastDay = sp.getString("day", null)
            val last = sp.getLong("last", -1L)
            val lastTs = sp.getLong("lastTs", 0L)
            var base = sp.getLong("base", counter)
            var carry = sp.getLong("carry", 0L)
            val hist = JSONObject(sp.getString("stepsHist", "{}") ?: "{}")
            var dSteps = 0L
            if (lastDay == null || last < 0) {
                base = counter
                carry = 0L
            } else {
                if (counter >= last) dSteps = counter - last
                if (lastDay != today) {
                    hist.put(lastDay, carry + (last - base).coerceAtLeast(0L))
                    base = if (counter >= last) last else 0L
                    carry = 0L
                } else if (counter < last) {
                    // phone rebooted today: the sensor restarted from zero
                    carry += (last - base).coerceAtLeast(0L)
                    base = 0L
                }
            }
            trimKeys(hist, 120)
            sp.edit()
                .putString("day", today)
                .putLong("last", counter)
                .putLong("lastTs", now)
                .putLong("base", base)
                .putLong("carry", carry)
                .putLong("dSteps", dSteps)
                .putLong("dMs", if (lastTs > 0L) now - lastTs else 0L)
                .putString("stepsHist", hist.toString())
                .apply()
        }
    }

    private fun trimKeys(o: JSONObject, keep: Int) {
        if (o.length() <= keep) return
        val keys = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        for (k in keys.take(keys.size - keep)) o.remove(k)
    }

    fun stepsToday(): Int = synchronized(lock) {
        val sp = p()
        if (sp.getString("day", null) != dayKey()) {
            0
        } else {
            (sp.getLong("carry", 0L) + (sp.getLong("last", 0L) - sp.getLong("base", 0L)).coerceAtLeast(0L)).toInt()
        }
    }

    /** Pushes newly tracked steps into the habit tracker so the HOME ring stays in sync. */
    fun syncHabitSteps() {
        val tracked = stepsToday()
        val sp = p()
        val today = dayKey()
        val synced = if (sp.getString("syncDay", null) == today) sp.getInt("synced", 0) else 0
        val delta = tracked - synced
        if (delta > 0 && HabitStore.add("steps", delta)) {
            sp.edit().putString("syncDay", today).putInt("synced", tracked).apply()
        }
    }

    fun walkingStatus(): String {
        val sp = p()
        val ts = sp.getLong("lastTs", 0L)
        if (ts == 0L) return "NO DATA YET"
        if (System.currentTimeMillis() - ts > 60 * 60_000L) return "NO RECENT DATA"
        val dMs = sp.getLong("dMs", 0L)
        if (dMs <= 0L) return "STILL"
        val perMin = sp.getLong("dSteps", 0L) * 60_000f / dMs
        return when {
            perMin >= 60f -> "WALKING"
            perMin >= 10f -> "LIGHT MOVEMENT"
            else -> "STILL"
        }
    }

    // ---------------- screen time ----------------

    private fun usm() = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private fun ignoredPackages(): Set<String> {
        val set = HashSet<String>()
        set.add("com.android.systemui")
        try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            app.packageManager.queryIntentActivities(i, 0).forEach { set.add(it.activityInfo.packageName) }
        } catch (_: Exception) {
        }
        return set
    }

    private fun label(pkg: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg.substringAfterLast('.')
    }

    fun screenTime(start: Long, end: Long): Pair<Int, List<AppUse>> {
        if (!hasUsageAccess()) return Pair(0, emptyList())
        return try {
            val ign = ignoredPackages()
            val stats = usm().queryAndAggregateUsageStats(start, end)
            var total = 0L
            val rows = ArrayList<Pair<String, Long>>()
            for ((pkg, st) in stats) {
                val t = st.totalTimeInForeground
                if (t <= 0L || ign.contains(pkg)) continue
                total += t
                rows.add(Pair(pkg, t))
            }
            rows.sortByDescending { it.second }
            Pair(
                (total / 60_000L).toInt(),
                rows.take(6).map { AppUse(label(it.first), (it.second / 60_000L).toInt()) }
            )
        } catch (_: Exception) {
            Pair(0, emptyList())
        }
    }

    fun screenToday(): Pair<Int, List<AppUse>> = screenTime(dayStart(0), System.currentTimeMillis())

    // ---------------- sleep ----------------

    /** Hours of the longest screen-off stretch between 20:00 and 12:00 for the night ending on day [offset]. */
    private fun sleepFor(offset: Int): Double? {
        val dStart = dayStart(offset)
        val now = System.currentTimeMillis()
        if (now < dStart + 6 * 3_600_000L) return null
        val winStart = dStart - 4 * 3_600_000L
        var winEnd = dStart + 12 * 3_600_000L
        if (winEnd > now) winEnd = now
        if (winEnd <= winStart) return null

        val events = usm().queryEvents(winStart, winEnd)
        val e = UsageEvents.Event()
        var screenOn = false
        var seen = false
        var offStart = winStart
        var longest = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) {
                if (!screenOn) {
                    val gap = e.timeStamp - offStart
                    if (gap > longest) longest = gap
                }
                screenOn = true
                seen = true
            } else if (e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (screenOn || !seen) offStart = e.timeStamp
                screenOn = false
                seen = true
            }
        }
        if (!seen) return null
        if (!screenOn) {
            val gap = winEnd - offStart
            if (gap > longest) longest = gap
        }
        val hours = longest / 3_600_000.0
        if (hours < 2.0) return null
        return Math.round(hours.coerceAtMost(14.0) * 10.0) / 10.0
    }

    private fun updateSleep() {
        try {
            val o = JSONObject(p().getString("sleep", "{}") ?: "{}")
            for (off in 0 downTo -6) {
                val h = sleepFor(off) ?: continue
                o.put(dayKey(off), h)
            }
            trimKeys(o, 120)
            p().edit().putString("sleep", o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun sleepMap(): Map<String, Double> {
        val out = HashMap<String, Double>()
        try {
            val o = JSONObject(p().getString("sleep", "{}") ?: "{}")
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optDouble(k, 0.0)
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun sleepScore(map: Map<String, Double>): Int? {
        val recent = map.entries.sortedByDescending { it.key }.take(14)
        if (recent.isEmpty()) return null
        val avg = recent.map { it.value }.average()
        return (avg / Prefs.sleepGoalH.toDouble() * 100.0).coerceAtMost(100.0).toInt()
    }

    fun rankFor(score: Int): String = when {
        score >= 90 -> "S"
        score >= 80 -> "A"
        score >= 70 -> "B"
        score >= 60 -> "C"
        score >= 50 -> "D"
        else -> "F"
    }

    // ---------------- dashboard + AI summary ----------------

    fun loadDashboard(): Dash {
        val (min, apps) = screenToday()
        val now = System.currentTimeMillis()
        if (now - weekTs > 300_000L || weekCache.size != 7) {
            weekCache = (-6..0).map { off -> screenTime(dayStart(off), dayStart(off + 1)).first }
            weekTs = now
        }
        val week = weekCache.toMutableList()
        week[6] = min
        return Dash(stepsToday(), walkingStatus(), min, apps, week, sleepMap())
    }

    fun aiSummary(): String {
        val steps = stepsToday()
        val usage = hasUsageAccess()
        val sleep = sleepMap().entries.maxByOrNull { it.key }
        if (steps == 0 && !usage && sleep == null) return ""
        val sb = StringBuilder("Phone tracking data: steps today ")
        sb.append(steps).append(" (").append(walkingStatus().lowercase(Locale.US)).append(")")
        if (usage) {
            val (min, apps) = screenToday()
            sb.append("; screen time today ").append(fmtMin(min))
            if (apps.isNotEmpty()) {
                sb.append(" (top apps: ")
                    .append(apps.take(3).joinToString(", ") { it.label + " " + fmtMin(it.minutes) })
                    .append(")")
            }
            sb.append("; daily screen-time limit ").append(Prefs.screenLimitH.toInt()).append("h")
        }
        if (sleep != null) {
            sb.append("; last tracked sleep ")
                .append(String.format(Locale.US, "%.1f", sleep.value))
                .append("h (").append(sleep.key).append(")")
        }
        return sb.append(".\n").toString()
    }
}

class TrackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CosmoBrain.init(context)
        val pr = goAsync()
        Thread {
            try {
                if (Prefs.autoTrack) Tracker.snapshot()
                Doom.check(context)
            } catch (_: Exception) {
            } finally {
                pr.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CosmoBrain.init(context)
        if (Prefs.autoTrack) Tracker.schedule()
        Auto.onBoot(context, goAsync())
    }
}

object DeviceActions {
    fun torch(ctx: Context, on: Boolean): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (_: Exception) {
            false
        }
    }
}
