package com.cosmic802.cosmo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TasksScreen(modifier: Modifier = Modifier) {
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun act(op: () -> Boolean) {
        scope.launch(Dispatchers.IO) {
            val ok = op()
            val l = TaskStore.list()
            withContext(Dispatchers.Main) {
                failed = !ok
                tasks = l
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            tasks = withContext(Dispatchers.IO) { TaskStore.list() }
            delay(3000)
        }
    }

    val total = tasks.size
    val done = tasks.count { it.done }
    val progress = if (total == 0) 0f else done.toFloat() / total

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionLabel("TASKS // MISSION LOG")
        Spacer(Modifier.height(10.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusRing(
                    label = "${(progress * 100).toInt()}%",
                    progress = progress,
                    ringSize = 84.dp
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "$done / $total COMPLETED",
                        color = CosmoColors.Cyan,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${total - done} OPEN",
                        color = CosmoColors.Amber,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CosmoField(
                value = input,
                onChange = { input = it },
                label = "New task",
                modifier = Modifier.weight(1f)
            )
            ChipButton("ADD", CosmoColors.Cyan, Modifier.width(72.dp)) {
                val t = input.trim()
                if (t.isNotEmpty()) {
                    input = ""
                    act { TaskStore.add(t) }
                }
            }
        }

        if (failed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "STORAGE ACCESS DENIED · CHANGES NOT SAVED",
                color = CosmoColors.Crimson,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        if (tasks.isEmpty()) {
            Text(
                text = "NO TASKS · ADD ONE OR SAY \"COSMO ADD TASK ...\"",
                color = CosmoColors.Cyan.copy(alpha = 0.6f),
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
        }

        for (t in tasks) {
            val shape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(
                        if (t.done) CosmoColors.Cyan.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.05f),
                        shape
                    )
                    .border(
                        1.dp,
                        if (t.done) CosmoColors.Cyan.copy(alpha = 0.5f) else CosmoColors.Dim,
                        shape
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(
                            2.dp,
                            if (t.done) CosmoColors.Cyan else CosmoColors.Dim,
                            RoundedCornerShape(7.dp)
                        )
                        .clickable { act { TaskStore.toggle(t.id) } },
                    contentAlignment = Alignment.Center
                ) {
                    if (t.done) {
                        Text("✓", color = CosmoColors.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = t.title,
                    color = if (t.done) CosmoColors.Cyan.copy(alpha = 0.45f) else CosmoColors.Cyan,
                    fontSize = 14.sp,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { act { TaskStore.toggle(t.id) } }
                )
                Text(
                    text = "✕",
                    color = CosmoColors.Crimson.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { act { TaskStore.remove(t.id) } }
                        .padding(start = 10.dp)
                )
            }
        }

        if (done > 0) {
            Spacer(Modifier.height(12.dp))
            ChipButton("CLEAR COMPLETED", CosmoColors.Amber, Modifier.fillMaxWidth()) {
                act { TaskStore.clearDone() }
            }
        }
    }
}
