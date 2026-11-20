package com.cosmic802.cosmo

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

object HabitStore {

    const val WATER_GOAL = 3000
    const val STEPS_GOAL = 8000
    private const val KEEP_DAYS = 90

    val TOGGLES = listOf(
        "brush_am" to "BRUSH TEETH · AM",
        "brush_pm" to "BRUSH TEETH · PM",
        "skincare_am" to "SKINCARE · AM",
        "skincare_pm" to "SKINCARE · PM",
        "hair" to "HAIR CARE"
    )

    fun load(): JSONObject = CosmoBrain.habitsFor(CosmoBrain.todayKey())

    private fun prune(habits: JSONObject) {
        if (habits.length() <= KEEP_DAYS) return
        val keys = ArrayList<String>()
        val it = habits.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        for (k in keys.take(keys.size - KEEP_DAYS)) habits.remove(k)
    }

    private fun mutate(block: (JSONObject) -> Unit): Boolean {
        val date = CosmoBrain.todayKey()
        return CosmoBrain.updateMemory { m ->
            val habits = m.getJSONObject("habits")
            val day = habits.optJSONObject(date) ?: JSONObject().also { habits.put(date, it) }
            block(day)
            prune(habits)
        }
    }

    fun add(key: String, delta: Int): Boolean = mutate { d ->
        d.put(key, (d.optInt(key, 0) + delta).coerceAtLeast(0))
    }

    fun toggle(key: String): Boolean = mutate { d ->
        d.put(key, !d.optBoolean(key, false))
    }

    fun water(d: JSONObject): Int = d.optInt("water_ml", 0)

    fun steps(d: JSONObject): Int = d.optInt("steps", 0)

    fun progress(d: JSONObject): Float {
        var sum = minOf(water(d).toFloat() / WATER_GOAL, 1f) +
            minOf(steps(d).toFloat() / STEPS_GOAL, 1f)
        for ((k, _) in TOGGLES) if (d.optBoolean(k, false)) sum += 1f
        return sum / (2 + TOGGLES.size)
    }
}

@Composable
fun ChipButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(42.dp)
            .background(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.08f))),
                shape
            )
            .border(1.dp, color.copy(alpha = 0.75f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun HabitScreen(modifier: Modifier = Modifier) {
    var day by remember { mutableStateOf(JSONObject()) }
    var failed by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun change(op: () -> Boolean) {
        Thread {
            val ok = op()
            val d = HabitStore.load()
            main.post {
                failed = !ok
                day = d
            }
        }.start()
    }

    LaunchedEffect(Unit) {
        Thread {
            val d = HabitStore.load()
            main.post { day = d }
        }.start()
    }

    val water = HabitStore.water(day)
    val steps = HabitStore.steps(day)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "LIFE-OS // ${CosmoBrain.todayKey()}",
            color = CosmoColors.Amber,
            fontSize = 12.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(12.dp))
        StatusRing(
            label = "${(HabitStore.progress(day) * 100).toInt()}%",
            progress = HabitStore.progress(day),
            ringSize = 110.dp
        )

        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "STORAGE ACCESS DENIED · CHANGES NOT SAVED",
                color = CosmoColors.Crimson,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("WATER", color = CosmoColors.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("$water / ${HabitStore.WATER_GOAL} ml", color = CosmoColors.Cyan, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { minOf(water.toFloat() / HabitStore.WATER_GOAL, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = CosmoColors.Cyan,
            trackColor = CosmoColors.Dim
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipButton("-250", CosmoColors.Crimson, Modifier.weight(1f)) { change { HabitStore.add("water_ml", -250) } }
            ChipButton("+250", CosmoColors.Cyan, Modifier.weight(1f)) { change { HabitStore.add("water_ml", 250) } }
            ChipButton("+500", CosmoColors.Cyan, Modifier.weight(1f)) { change { HabitStore.add("water_ml", 500) } }
        }

        Spacer(Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("STEPS", color = CosmoColors.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("$steps / ${HabitStore.STEPS_GOAL}", color = CosmoColors.Amber, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { minOf(steps.toFloat() / HabitStore.STEPS_GOAL, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = CosmoColors.Amber,
            trackColor = CosmoColors.Dim
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipButton("-500", CosmoColors.Crimson, Modifier.weight(1f)) { change { HabitStore.add("steps", -500) } }
            ChipButton("+500", CosmoColors.Amber, Modifier.weight(1f)) { change { HabitStore.add("steps", 500) } }
            ChipButton("+1000", CosmoColors.Amber, Modifier.weight(1f)) { change { HabitStore.add("steps", 1000) } }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = "ROUTINES",
            color = CosmoColors.Cyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        for ((key, label) in HabitStore.TOGGLES) {
            val done = day.optBoolean(key, false)
            val shape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(if (done) CosmoColors.Cyan.copy(alpha = 0.12f) else CosmoColors.Panel, shape)
                    .border(1.dp, if (done) CosmoColors.Cyan else CosmoColors.Dim, shape)
                    .clickable { change { HabitStore.toggle(key) } }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .border(2.dp, if (done) CosmoColors.Cyan else CosmoColors.Dim, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Text("✓", color = CosmoColors.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    text = label,
                    color = if (done) CosmoColors.Cyan else CosmoColors.Cyan.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
