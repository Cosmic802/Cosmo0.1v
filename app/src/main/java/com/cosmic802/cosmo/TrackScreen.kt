package com.cosmic802.cosmo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun BarFill(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Color.White.copy(alpha = 0.08f), shape)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(8.dp)
                .background(color, shape)
        )
    }
}

private fun sleepColor(h: Double?): Color = when {
    h == null -> Color.White.copy(alpha = 0.06f)
    h < 6.0 -> Color(0xFFE5383B)
    h < 7.0 -> Color(0xFF2F8F78)
    h < 8.0 -> Color(0xFF22C17A)
    h < 9.0 -> Color(0xFF2BE3A0)
    else -> Color(0xFFFFB300)
}

@Composable
fun TrackScreen() {
    val ctx = LocalContext.current
    var dash by remember { mutableStateOf<Dash?>(null) }
    var actOk by remember { mutableStateOf(Tracker.hasActivityPermission()) }
    var usageOk by remember { mutableStateOf(Tracker.hasUsageAccess()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        actOk = ok
        if (ok) {
            Prefs.autoTrack = true
            Prefs.save()
            Tracker.schedule()
            Thread { Tracker.snapshot() }.start()
        }
    }

    // live step updates while this screen is open
    DisposableEffect(actOk) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = if (actOk) sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) else null
        var lastWrite = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                if (now - lastWrite < 15_000L) return
                lastWrite = now
                val v = e.values[0].toLong()
                Thread {
                    Tracker.recordSteps(v)
                    Tracker.syncHabitSteps()
                }.start()
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(l) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            actOk = Tracker.hasActivityPermission()
            usageOk = Tracker.hasUsageAccess()
            dash = withContext(Dispatchers.IO) { Tracker.loadDashboard() }
            delay(10_000L)
        }
    }

    val accent = CosmoColors.Cyan

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionLabel("AUTO TRACKING")
        Text(
            text = if (Prefs.autoTrack && (actOk || usageOk)) "ACTIVE · RUNS IN BACKGROUND" else "SETUP NEEDED",
            color = accent.copy(alpha = 0.65f),
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(10.dp))

        if (!actOk || !usageOk) {
            GlassCard(Modifier.fillMaxWidth(), accent = CosmoColors.Amber) {
                SectionLabel("ALLOW ACCESS")
                Text(
                    text = "Steps need the activity permission. Screen time and sleep need usage access. Data stays on your phone.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                if (!actOk) {
                    ChipButton("ALLOW STEP TRACKING", CosmoColors.Cyan, Modifier.fillMaxWidth()) {
                        permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (!usageOk) {
                    ChipButton("ALLOW SCREEN TIME ACCESS", CosmoColors.Amber, Modifier.fillMaxWidth()) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val d = dash
        if (d == null) {
            Text("LOADING...", color = accent.copy(alpha = 0.6f), fontSize = 12.sp, letterSpacing = 1.sp)
            return@Column
        }

        // ---------- steps ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("STEPS TODAY")
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                Text(d.steps.toString(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "  / ${HabitStore.STEPS_GOAL}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            BarFill(d.steps.toFloat() / HabitStore.STEPS_GOAL, accent)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "STATUS · ${d.status}",
                color = accent.copy(alpha = 0.8f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------- screen time ----------
        val limitMin = (Prefs.screenLimitH * 60f).toInt().coerceAtLeast(30)
        val over = d.screenMin > limitMin
        val screenColor = if (over) CosmoColors.Crimson else accent
        GlassCard(Modifier.fillMaxWidth(), accent = screenColor) {
            SectionLabel("SCREEN TIME TODAY", if (over) CosmoColors.Crimson else CosmoColors.Amber)
            if (!usageOk) {
                Text(
                    "Allow screen time access above to see this.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Text(Tracker.fmtMin(d.screenMin), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "  limit ${Prefs.screenLimitH.toInt()}h",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                BarFill(d.screenMin.toFloat() / limitMin, screenColor)
                Spacer(Modifier.height(12.dp))
                val topMin = (d.apps.firstOrNull()?.minutes ?: 1).coerceAtLeast(1)
                d.apps.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.label,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(Tracker.fmtMin(a.minutes), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(3.dp))
                    BarFill(a.minutes.toFloat() / topMin, accent.copy(alpha = 0.7f))
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- week ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("SCREEN TIME · 7 DAYS")
            Spacer(Modifier.height(10.dp))
            val maxM = (d.weekMin.maxOrNull() ?: 60).coerceAtLeast(60)
            Row(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                d.weekMin.forEachIndexed { i, m ->
                    val frac = m.toFloat() / maxM
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(maxOf(3f, 80f * frac).dp)
                                .background(
                                    if (i == 6) accent else accent.copy(alpha = 0.4f),
                                    RoundedCornerShape(6.dp)
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Tracker.dayLetter(i - 6),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- sleep ----------
        GlassCard(Modifier.fillMaxWidth(), accent = CosmoColors.Violet) {
            SectionLabel("SLEEP · ESTIMATED FROM SCREEN-OFF TIME")
            val score = Tracker.sleepScore(d.sleep)
            if (!usageOk) {
                Text(
                    "Allow screen time access above to estimate sleep.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else if (score == null) {
                Text(
                    "No nights recorded yet. Cosmo logs one each morning.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                val rank = Tracker.rankFor(score)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                    Text(score.toString(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "  / 100   RANK $rank",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("F", "D", "C", "B", "A", "S").forEach { r ->
                        val sel = r == rank
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    if (sel) CosmoColors.Violet.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.07f),
                                    shape
                                )
                                .border(1.dp, if (sel) Color.White else Color.Transparent, shape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                r,
                                color = if (sel) Color.White else Color.White.copy(alpha = 0.45f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                val vals = d.sleep.values.toList()
                val avg = vals.average()
                val best = vals.maxOrNull() ?: 0.0
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        vals.size.toString() to "NIGHTS",
                        String.format(Locale.US, "%.1fh", avg) to "AVERAGE",
                        String.format(Locale.US, "%.1fh", best) to "BEST"
                    ).forEach { (v, label) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(v, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // heatmap: 12 weeks, one dot per night
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (w in 0 until 12) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (day in 0 until 7) {
                                val offset = (w * 7 + day) - 83
                                Box(
                                    Modifier
                                        .size(15.dp)
                                        .background(sleepColor(d.sleep[Tracker.dayKey(offset)]), CircleShape)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        "<6" to sleepColor(5.0),
                        "6-7" to sleepColor(6.5),
                        "7-8" to sleepColor(7.5),
                        "8-9" to sleepColor(8.5),
                        "9+" to sleepColor(9.5)
                    ).forEach { (t, c) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(c, CircleShape))
                            Spacer(Modifier.width(4.dp))
                            Text(t, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
