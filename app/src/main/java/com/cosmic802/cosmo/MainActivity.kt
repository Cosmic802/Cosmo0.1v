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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    val tabs = listOf("HUD", "LIFE", "STUDY", "BRAIN", "SYS")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CosmoColors.Black)
            .systemBarsPadding()
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            when (tab) {
                0 -> HudScreen()
                1 -> HabitScreen(Modifier.fillMaxSize())
                2 -> StudyPlayerScreen(Modifier.fillMaxSize())
                3 -> BrainScreen()
                else -> SettingsScreen()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CosmoColors.Panel)
        ) {
            tabs.forEachIndexed { i, name ->
                val sel = i == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(width = 28.dp, height = 3.dp)
                                .background(if (sel) CosmoColors.Cyan else Color.Transparent)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = name,
                            color = if (sel) CosmoColors.Cyan else CosmoColors.Cyan.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HudScreen() {
    val ctx = LocalContext.current
    val mode = ModeState.mode
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(JSONObject()) }
    var windLeft by remember { mutableStateOf(0L) }
    var voiceOn by remember { mutableStateOf(CosmoVoiceEngine.isRunning()) }

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
            delay(10_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            windLeft = RootShell.windDownRemainingMs()
            voiceOn = CosmoVoiceEngine.isRunning()
            delay(1000)
        }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "C O S M O",
            color = CosmoColors.Cyan,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
        Text(
            text = "J.A.R.V.I.S. CORE // ONLINE",
            color = CosmoColors.Amber,
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(20.dp))

        CoreVisualizer(modifier = Modifier.size(230.dp), accent = mode.color)

        Spacer(Modifier.height(14.dp))

        Text(
            text = "MODE: ${mode.label}",
            color = mode.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp
        )
        if (status.isNotEmpty()) {
            Text(
                text = status,
                color = if (status.contains("DENIED")) CosmoColors.Crimson else CosmoColors.Cyan.copy(alpha = 0.7f),
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

        Spacer(Modifier.height(16.dp))

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

        Spacer(Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeButton(CosmoMode.STUDY, mode == CosmoMode.STUDY, Modifier.weight(1f)) { setMode(CosmoMode.STUDY) }
            ModeButton(CosmoMode.GAME, mode == CosmoMode.GAME, Modifier.weight(1f)) { setMode(CosmoMode.GAME) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeButton(CosmoMode.SLEEP, mode == CosmoMode.SLEEP, Modifier.weight(1f)) { setMode(CosmoMode.SLEEP) }
            ModeButton(CosmoMode.NORMAL, mode == CosmoMode.NORMAL, Modifier.weight(1f)) { setMode(CosmoMode.NORMAL) }
        }

        Spacer(Modifier.height(16.dp))

        ChipButton(
            text = if (voiceOn) "VOICE: ON  (TAP TO STOP)" else "VOICE: OFF  (TAP TO START)",
            color = if (voiceOn) CosmoColors.Amber else CosmoColors.Cyan,
            modifier = Modifier.fillMaxWidth()
        ) {
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
            val shape = RoundedCornerShape(12.dp)
            Text(
                text = reply,
                color = CosmoColors.Cyan,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CosmoColors.Panel, shape)
                    .border(1.dp, CosmoColors.Dim, shape)
                    .padding(14.dp)
            )
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

@Composable
fun ModeButton(
    target: CosmoMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(56.dp)
            .background(
                if (selected) target.color.copy(alpha = 0.18f) else CosmoColors.Panel,
                shape
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) target.color else CosmoColors.Dim,
                shape = shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = target.label,
            color = if (selected) target.color else target.color.copy(alpha = 0.6f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}
