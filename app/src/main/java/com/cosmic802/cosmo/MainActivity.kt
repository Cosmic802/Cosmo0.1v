package com.cosmic802.cosmo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

enum class CosmoMode(val label: String, val color: Color) {
    STUDY("STUDY", CosmoColors.Cyan),
    GAME("GAME", CosmoColors.Amber),
    SLEEP("SLEEP", CosmoColors.Crimson),
    NORMAL("NORMAL", CosmoColors.Cyan)
}

object ModeState {
    var mode by mutableStateOf(CosmoMode.NORMAL)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CosmoBrain.init(this)
        CosmoBrain.syncQueueAsync()
        CosmoVoiceEngine.onModeChange = { ModeState.mode = it }
        setContent {
            CosmoTheme {
                CosmoApp()
            }
        }
    }
}

@Composable
fun CosmoApp() {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("HUD", "TASKS", "LIFE", "STUDY", "BRAIN", "SYS")

    CosmoBackground(accent = ModeState.mode.color) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                when (tab) {
                    0 -> HudScreen(onNavigate = { tab = it })
                    1 -> TasksScreen(Modifier.fillMaxSize())
                    2 -> HabitScreen(Modifier.fillMaxSize())
                    3 -> StudyPlayerScreen(Modifier.fillMaxSize())
                    4 -> BrainScreen()
                    else -> SettingsScreen()
                }
            }

            val navShape = RoundedCornerShape(24.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(CosmoColors.Panel.copy(alpha = 0.9f), navShape)
                    .border(1.dp, CosmoColors.Dim, navShape)
                    .padding(4.dp)
            ) {
                tabs.forEachIndexed { i, name ->
                    val sel = i == tab
                    val itemShape = RoundedCornerShape(20.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .background(
                                if (sel) CosmoColors.Cyan.copy(alpha = 0.15f) else Color.Transparent,
                                itemShape
                            )
                            .clickable { tab = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name,
                            color = if (sel) CosmoColors.Cyan else CosmoColors.Cyan.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModeOrb(
    target: CosmoMode,
    selected: Boolean,
    symbol: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(
                    if (selected) target.color.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                    CircleShape
                )
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) target.color else CosmoColors.Dim,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = symbol, color = target.color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = target.label,
            color = if (selected) target.color else target.color.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun HudScreen(onNavigate: (Int) -> Unit) {
    val ctx = LocalContext.current
    val mode = ModeState.mode
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(JSONObject()) }
    var windLeft by remember { mutableStateOf(0L) }
    var voiceOn by remember { mutableStateOf(CosmoVoiceEngine.isRunning()) }
    var overlayOn by remember { mutableStateOf(RedOverlayService.isRunning()) }
    var openTasks by remember { mutableStateOf(0) }
    var queued by remember { mutableStateOf(0) }

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greet = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) {
            CosmoVoiceEngine.start(ctx)
            voiceOn = true
        } else {
            status = "MICROPHONE PERMISSION DENIED"
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            day = withContext(Dispatchers.IO) { HabitStore.load() }
            openTasks = withContext(Dispatchers.IO) { TaskStore.list().count { !it.done } }
            queued = withContext(Dispatchers.IO) { CosmoBrain.queueSize() }
            delay(8000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            windLeft = RootShell.windDownRemainingMs()
            voiceOn = CosmoVoiceEngine.isRunning()
            overlayOn = RedOverlayService.isRunning()
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        if (!StudyPlayer.scanned) StudyPlayer.refresh()
    }

    fun setMode(m: CosmoMode) {
        if (busy) return
        busy = true
        status = "APPLYING ${m.label}..."
        RootShell.apply(ctx, m) { ok ->
            busy = false
            if (ok) {
                ModeState.mode = m
                status = "MODE ${m.label} ACTIVE"
            } else {
                status = "ROOT ACCESS DENIED"
            }
        }
    }

    fun toggleVoice() {
        if (voiceOn) {
            CosmoVoiceEngine.stop(ctx)
            voiceOn = false
        } else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CosmoVoiceEngine.start(ctx)
            voiceOn = true
        } else {
            permLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            )
        }
    }

    fun toggleOverlay() {
        if (overlayOn) {
            RootShell.stopOverlay(ctx)
            overlayOn = false
        } else if (Settings.canDrawOverlays(ctx)) {
            RootShell.startOverlay(ctx)
            overlayOn = true
        } else {
            status = "GRANT OVERLAY IN SYS TAB"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "$greet, Sir.",
            color = CosmoColors.Cyan.copy(alpha = 0.7f),
            fontSize = 14.sp,
            letterSpacing = 1.sp
        )
        Text(
            text = "Your Day,",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Your Way.",
            color = mode.color,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(Modifier.weight(1f), accent = mode.color) {
                Text("SYSTEM", color = CosmoColors.Amber, fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = mode.label,
                    color = mode.color,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (voiceOn) "● VOICE ON" else "○ VOICE OFF",
                    color = CosmoColors.Cyan.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
                Text(
                    text = "QUEUE: $queued",
                    color = CosmoColors.Cyan.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            CoreVisualizer(modifier = Modifier.size(130.dp), accent = mode.color)
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = status,
                color = if (status.contains("DENIED") || status.contains("GRANT")) CosmoColors.Crimson else CosmoColors.Cyan.copy(alpha = 0.7f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }
        if (windLeft > 0L) {
            val s = windLeft / 1000
            Text(
                text = "WIND-DOWN LOCK IN %d:%02d".format(s / 60, s % 60),
                color = CosmoColors.Crimson,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        GlassCard(Modifier.fillMaxWidth(), accent = mode.color) {
            Text("Mode Control", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ModeOrb(CosmoMode.STUDY, mode == CosmoMode.STUDY, "✎") { setMode(CosmoMode.STUDY) }
                ModeOrb(CosmoMode.GAME, mode == CosmoMode.GAME, "◈") { setMode(CosmoMode.GAME) }
                ModeOrb(CosmoMode.SLEEP, mode == CosmoMode.SLEEP, "☾") { setMode(CosmoMode.SLEEP) }
                ModeOrb(CosmoMode.NORMAL, mode == CosmoMode.NORMAL, "☀") { setMode(CosmoMode.NORMAL) }
            }
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("Today", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusRing(
                    label = "WATER",
                    progress = HabitStore.water(day).toFloat() / HabitStore.WATER_GOAL
                )
                StatusRing(
                    label = "STEPS",
                    progress = HabitStore.steps(day).toFloat() / HabitStore.STEPS_GOAL,
                    color = CosmoColors.Amber
                )
                StatusRing(label = "HABITS", progress = HabitStore.progress(day))
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(
                Modifier.weight(1f).height(84.dp),
                accent = if (voiceOn) CosmoColors.Amber else CosmoColors.Cyan,
                onClick = { toggleVoice() }
            ) {
                Text("Voice", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (voiceOn) "LISTENING · TAP TO STOP" else "OFF · TAP TO START",
                    color = if (voiceOn) CosmoColors.Amber else CosmoColors.Cyan.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
            GlassCard(
                Modifier.weight(1f).height(84.dp),
                accent = if (overlayOn) CosmoColors.Crimson else CosmoColors.Cyan,
                onClick = { toggleOverlay() }
            ) {
                Text("Red Filter", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (overlayOn) "RUNNING · TAP TO STOP" else "OFF · TAP TO START",
                    color = if (overlayOn) CosmoColors.Crimson else CosmoColors.Cyan.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(
                Modifier.weight(1f).height(84.dp),
                onClick = { onNavigate(1) }
            ) {
                Text("Tasks", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$openTasks OPEN",
                    color = CosmoColors.Amber,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
            GlassCard(
                Modifier.weight(1f).height(84.dp),
                onClick = { onNavigate(4) }
            ) {
                Text("Briefing", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ASK COSMO",
                    color = CosmoColors.Cyan.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        val current = StudyPlayer.tracks.getOrNull(StudyPlayer.index)
        GlassCard(Modifier.fillMaxWidth(), accent = CosmoColors.Amber) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(CosmoColors.Amber.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = CosmoColors.Amber, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (current != null) StudyPlayer.title(current) else "NO TRACK",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "STUDY PLAYER",
                        color = CosmoColors.Amber,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp
                    )
                }
                ChipButton("‹‹", CosmoColors.Cyan, Modifier.width(44.dp)) { StudyPlayer.prev() }
                Spacer(Modifier.width(6.dp))
                ChipButton(
                    if (StudyPlayer.playing) "II" else "▶",
                    CosmoColors.Amber,
                    Modifier.width(48.dp)
                ) { StudyPlayer.toggle() }
                Spacer(Modifier.width(6.dp))
                ChipButton("››", CosmoColors.Cyan, Modifier.width(44.dp)) { StudyPlayer.next() }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun CosmoField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visual: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = CosmoColors.Cyan.copy(alpha = 0.7f), fontSize = 12.sp) },
        singleLine = singleLine,
        visualTransformation = visual,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = CosmoColors.Cyan,
            unfocusedTextColor = CosmoColors.Cyan,
            focusedBorderColor = CosmoColors.Cyan,
            unfocusedBorderColor = CosmoColors.Dim,
            cursorColor = CosmoColors.Cyan
        )
    )
}

@Composable
fun SectionLabel(text: String, color: Color = CosmoColors.Amber) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun BrainScreen() {
    var input by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        CosmoBrain.onQueueResult = { _, _, r -> reply = r }
        onDispose { CosmoBrain.onQueueResult = null }
    }
    LaunchedEffect(Unit) {
        while (true) {
            queued = withContext(Dispatchers.IO) { CosmoBrain.queueSize() }
            delay(3000)
        }
    }

    fun run(block: ((BrainReply) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        reply = "PROCESSING..."
        block {
            busy = false
            reply = it.text
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionLabel("COSMO BRAIN // GEMINI FLASH")
        Text(
            text = "QUEUED REQUESTS: $queued",
            color = CosmoColors.Cyan.copy(alpha = 0.6f),
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipButton("BRIEFING", CosmoColors.Cyan, Modifier.weight(1f)) {
                run { cb -> CosmoBrain.dailyBriefing(cb) }
            }
            ChipButton("DISCIPLINE", CosmoColors.Amber, Modifier.weight(1f)) {
                run { cb -> CosmoBrain.disciplineAdvice(cb) }
            }
        }

        Spacer(Modifier.height(14.dp))

        CosmoField(
            value = input,
            onChange = { input = it },
            label = "Ask Cosmo",
            modifier = Modifier.fillMaxWidth(),
            singleLine = false
        )
        Spacer(Modifier.height(10.dp))
        ChipButton("SEND", CosmoColors.Cyan, Modifier.fillMaxWidth()) {
            val p = input.trim()
            if (p.isNotEmpty() && !busy) {
                input = ""
                run { cb -> CosmoBrain.ask(p, "chat", cb) }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (reply.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    text = reply,
                    color = CosmoColors.Cyan,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var intensity by remember { mutableStateOf(RedOverlayService.getIntensity(ctx)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var storageOk by remember { mutableStateOf(CosmoBrain.hasStorageAccess()) }
    var overlayOn by remember { mutableStateOf(RedOverlayService.isRunning()) }

    LaunchedEffect(Unit) {
        key = withContext(Dispatchers.IO) { CosmoBrain.getApiKey() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            overlayOk = Settings.canDrawOverlays(ctx)
            storageOk = CosmoBrain.hasStorageAccess()
            overlayOn = RedOverlayService.isRunning()
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionLabel("GEMINI API KEY")
        Spacer(Modifier.height(8.dp))
        CosmoField(
            value = key,
            onChange = { key = it },
            label = "API key (/sdcard/CosmoData/api_key.txt)",
            modifier = Modifier.fillMaxWidth(),
            visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation()
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipButton(if (showKey) "HIDE" else "SHOW", CosmoColors.Cyan, Modifier.weight(1f)) {
                showKey = !showKey
            }
            ChipButton("SAVE KEY", CosmoColors.Amber, Modifier.weight(1f)) {
                val k = key
                Thread {
                    val ok = CosmoBrain.setApiKey(k)
                    msg = if (ok) "KEY SAVED" else "SAVE FAILED · CHECK STORAGE ACCESS"
                    if (ok) CosmoBrain.syncQueueAsync()
                }.start()
            }
        }

        Spacer(Modifier.height(24.dp))

        SectionLabel("RED FILTER INTENSITY")
        Slider(
            value = intensity,
            onValueChange = {
                intensity = it
                RedOverlayService.setIntensity(ctx, it)
            },
            colors = SliderDefaults.colors(
                thumbColor = CosmoColors.Crimson,
                activeTrackColor = CosmoColors.Crimson,
                inactiveTrackColor = CosmoColors.Dim
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipButton("PREVIEW ON", CosmoColors.Crimson, Modifier.weight(1f)) {
                if (Settings.canDrawOverlays(ctx)) {
                    RootShell.startOverlay(ctx)
                } else {
                    msg = "GRANT OVERLAY PERMISSION FIRST"
                }
            }
            ChipButton("PREVIEW OFF", CosmoColors.Cyan, Modifier.weight(1f)) {
                RootShell.stopOverlay(ctx)
            }
        }
        Text(
            text = if (overlayOn) "OVERLAY: RUNNING" else "OVERLAY: STOPPED",
            color = CosmoColors.Cyan.copy(alpha = 0.6f),
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(Modifier.height(24.dp))

        SectionLabel("PERMISSIONS")
        Spacer(Modifier.height(8.dp))
        ChipButton(
            text = if (overlayOk) "OVERLAY: GRANTED" else "OVERLAY: GRANT",
            color = if (overlayOk) CosmoColors.Cyan else CosmoColors.Amber,
            modifier = Modifier.fillMaxWidth()
        ) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Spacer(Modifier.height(8.dp))
        ChipButton(
            text = if (storageOk) "ALL FILES ACCESS: GRANTED" else "ALL FILES ACCESS: GRANT",
            color = if (storageOk) CosmoColors.Cyan else CosmoColors.Amber,
            modifier = Modifier.fillMaxWidth()
        ) {
            ctx.startActivity(CosmoBrain.storageSettingsIntent(ctx))
        }
        Spacer(Modifier.height(8.dp))
        ChipButton("TEST ROOT ACCESS", CosmoColors.Cyan, Modifier.fillMaxWidth()) {
            msg = "CHECKING ROOT..."
            Thread {
                msg = if (RootShell.isRootAvailable()) "ROOT: GRANTED" else "ROOT: DENIED"
            }.start()
        }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = msg,
                color = if (msg.contains("DENIED") || msg.contains("FAILED")) CosmoColors.Crimson else CosmoColors.Cyan,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
        }
    }
}
