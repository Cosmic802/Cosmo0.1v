package com.cosmic802.cosmo

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import org.json.JSONArray
import java.io.File
import java.util.Calendar

enum class CosmoMode(val label: String, private val tint: () -> Color) {
    STUDY("STUDY", { CosmoColors.Cyan }),
    GAME("GAME", { CosmoColors.Amber }),
    SLEEP("SLEEP", { CosmoColors.Crimson }),
    NORMAL("NORMAL", { CosmoColors.Cyan });

    val color: Color get() = tint()
}

fun aiStatus(): String {
    val local = LocalAI.isModelReady()
    return when {
        Prefs.offlineOnly -> if (local) "OFFLINE · LOCAL MODEL" else "OFFLINE · BASIC"
        CosmoBrain.getApiKey().isEmpty() -> if (local) "NO KEY · LOCAL MODEL" else "NO KEY · BASIC"
        else -> "CLOUD · GEMINI"
    }
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
    val tabs = listOf(
        "◉" to "HOME", "✓" to "TASKS", "♡" to "LIFE",
        "♪" to "STUDY", "✦" to "CHAT", "▤" to "STATS", "⚙" to "SET"
    )

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
                    5 -> TrackScreen()
                    else -> SettingsScreen()
                }
            }

            val navShape = RoundedCornerShape(28.dp)
            val accent = CosmoColors.Cyan
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f))
                        ),
                        navShape
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.3f), accent.copy(alpha = 0.15f))
                        ),
                        navShape
                    )
                    .padding(5.dp)
            ) {
                tabs.forEachIndexed { i, (icon, name) ->
                    val sel = i == tab
                    val itemShape = RoundedCornerShape(22.dp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .background(
                                if (sel) accent.copy(alpha = 0.18f) else Color.Transparent,
                                itemShape
                            )
                            .border(
                                1.dp,
                                if (sel) accent.copy(alpha = 0.5f) else Color.Transparent,
                                itemShape
                            )
                            .clickable { tab = i },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = icon,
                            color = if (sel) accent else Color.White.copy(alpha = 0.5f),
                            fontSize = 17.sp
                        )
                        Text(
                            text = name,
                            color = if (sel) accent else Color.White.copy(alpha = 0.45f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
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
            text = "$greet, ${Prefs.userName.ifBlank { "Sir" }}.",
            color = CosmoColors.Cyan.copy(alpha = 0.7f),
            fontSize = 14.sp,
            letterSpacing = 1.sp
        )
        Text(
            text = "Your Day,",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Your Way.",
            style = TextStyle(
                brush = Brush.horizontalGradient(listOf(mode.color, Color(0xFFB39DFF))),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
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
                    text = aiStatus(),
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
                    text = if (voiceOn) {
                        VoiceState.status
                    } else {
                        VoiceState.error ?: "OFF · TAP TO START"
                    },
                    color = if (voiceOn) CosmoColors.Amber else if (VoiceState.error != null) CosmoColors.Crimson else CosmoColors.Cyan.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
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

class ChatMsg(val fromUser: Boolean, val text: String)

@Composable
fun ChatBubble(m: ChatMsg) {
    val accent = CosmoColors.Cyan
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (m.fromUser) 18.dp else 4.dp,
        bottomEnd = if (m.fromUser) 4.dp else 18.dp
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (m.fromUser) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f),
                    shape
                )
                .border(1.dp, accent.copy(alpha = if (m.fromUser) 0.5f else 0.2f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = m.text,
                color = Color.White,
                fontSize = Prefs.chatSize.sp,
                lineHeight = (Prefs.chatSize * 1.4f).sp
            )
        }
    }
}

@Composable
fun BrainScreen() {
    val ctx = LocalContext.current
    val msgs = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var micMsg by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val hist = withContext(Dispatchers.IO) { CosmoBrain.loadMemory().getJSONArray("chat") }
        msgs.clear()
        for (i in 0 until hist.length()) {
            val o = hist.optJSONObject(i) ?: continue
            msgs.add(ChatMsg(o.optString("role") != "model", o.optString("text")))
        }
    }
    DisposableEffect(Unit) {
        CosmoBrain.onQueueResult = { kind, _, r -> if (kind == "chat") msgs.add(ChatMsg(false, r)) }
        onDispose { CosmoBrain.onQueueResult = null }
    }
    LaunchedEffect(msgs.size, busy) {
        val n = msgs.size + (if (busy) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    fun send(shown: String, block: ((BrainReply) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        msgs.add(ChatMsg(true, shown))
        block { r ->
            busy = false
            msgs.add(ChatMsg(false, r.text))
            if (Prefs.speakReplies && !r.error) Speaker.speak(ctx, r.text)
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val said = res.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (res.resultCode == android.app.Activity.RESULT_OK && said.isNotEmpty()) {
            micMsg = ""
            VoiceState.lastHeard = said
            if (!busy) {
                send(said) { cb -> CosmoBrain.ask(said, "chat", cb) }
            } else {
                input = said
            }
        } else if (res.resultCode != android.app.Activity.RESULT_OK) {
            micMsg = "Nothing heard. Try again."
        }
    }

    fun startMic() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoiceState.recognitionLocale())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Cosmo")
        }
        try {
            micMsg = ""
            speechLauncher.launch(i)
        } catch (e: Exception) {
            micMsg = "No speech app found. Install the Google app, then try again."
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("COSMO CHAT")
                Text(
                    text = aiStatus(),
                    color = CosmoColors.Cyan.copy(alpha = 0.65f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
            ChipButton("CLEAR", CosmoColors.Crimson, Modifier.width(80.dp)) {
                msgs.clear()
                Thread { CosmoBrain.updateMemory { it.put("chat", JSONArray()) } }.start()
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipButton("BRIEFING", CosmoColors.Cyan, Modifier.weight(1f)) {
                send("Briefing") { cb -> CosmoBrain.dailyBriefing(cb) }
            }
            ChipButton("DISCIPLINE", CosmoColors.Amber, Modifier.weight(1f)) {
                send("Discipline") { cb -> CosmoBrain.disciplineAdvice(cb) }
            }
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (msgs.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            text = "Ask me anything. Say \"remember ...\" and I will keep it as a note about you.",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
            items(msgs.size) { i -> ChatBubble(msgs[i]) }
            if (busy) item { ChatBubble(ChatMsg(false, "…")) }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CosmoField(
                value = input,
                onChange = { input = it },
                label = "Message",
                modifier = Modifier.weight(1f),
                singleLine = false
            )
            Spacer(Modifier.width(8.dp))
            ChipButton("MIC", CosmoColors.Amber, Modifier.width(56.dp)) { startMic() }
            Spacer(Modifier.width(6.dp))
            ChipButton("SEND", CosmoColors.Cyan, Modifier.width(72.dp)) {
                val p = input.trim()
                if (p.isNotEmpty() && !busy) {
                    input = ""
                    send(p) { cb -> CosmoBrain.ask(p, "chat", cb) }
                }
            }
        }
        if (micMsg.isNotEmpty()) {
            Text(
                text = micMsg,
                color = CosmoColors.Crimson,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
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
    var notes by remember { mutableStateOf(0) }
    var intensity by remember { mutableStateOf(RedOverlayService.getIntensity(ctx)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var storageOk by remember { mutableStateOf(CosmoBrain.hasStorageAccess()) }
    var overlayOn by remember { mutableStateOf(RedOverlayService.isRunning()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            msg = "COPYING MODEL... (can take a minute)"
            Thread {
                try {
                    val out = LocalAI.modelFile(ctx)
                    out.parentFile?.mkdirs()
                    ctx.contentResolver.openInputStream(uri)?.use { inp ->
                        out.outputStream().use { o -> inp.copyTo(o, 1 shl 16) }
                    }
                    LocalAI.release()
                    Prefs.modelPath = out.absolutePath
                    Prefs.save()
                    msg = "MODEL READY · ${out.length() / 1_000_000} MB"
                } catch (e: Exception) {
                    msg = "MODEL COPY FAILED: ${e.message}"
                }
            }.start()
        }
    }

    LaunchedEffect(Unit) {
        key = withContext(Dispatchers.IO) { CosmoBrain.getApiKey() }
        notes = withContext(Dispatchers.IO) { CosmoBrain.listFacts().size }
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
        // ---------- profile ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("PROFILE · COSMO KNOWS YOU")
            Spacer(Modifier.height(8.dp))
            CosmoField(
                value = Prefs.userName,
                onChange = { Prefs.userName = it; Prefs.save() },
                label = "Your name",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            CosmoField(
                value = Prefs.about,
                onChange = { Prefs.about = it; Prefs.save() },
                label = "About you (job, goals, interests)",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SAVED NOTES: $notes",
                    color = CosmoColors.Cyan.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )
                ChipButton("CLEAR NOTES", CosmoColors.Crimson, Modifier.width(120.dp)) {
                    Thread {
                        CosmoBrain.clearFacts()
                        notes = 0
                    }.start()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        ProfileCard()
        Spacer(Modifier.height(12.dp))

        // ---------- voice ----------
        var diag by remember { mutableStateOf(listOf<String>()) }
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("VOICE")
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = "Wake word",
                subtitle = "ON: say the wake word first. OFF: every phrase is a command and goes to the AI",
                checked = Prefs.wakeEnabled,
                onChange = { Prefs.wakeEnabled = it; Prefs.save() }
            )
            CosmoField(
                value = Prefs.wakeWord,
                onChange = { Prefs.wakeWord = it.take(20); Prefs.save() },
                label = "Wake word (default: cosmo)",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            SettingSwitch(
                title = "Speak replies",
                subtitle = "Read chat answers out loud",
                checked = Prefs.speakReplies,
                onChange = { Prefs.speakReplies = it; Prefs.save() }
            )
            Text(
                text = "Voice language follows LANGUAGE below (English or Mongolian, if your phone supports it).",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChipButton("CHECK VOICE", CosmoColors.Cyan, Modifier.weight(1f)) {
                    diag = VoiceState.diagnose(ctx)
                }
                ChipButton("TEST SPEAK", CosmoColors.Amber, Modifier.weight(1f)) {
                    Speaker.speak(ctx, "Voice systems online, Sir.")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "STATUS: ${VoiceState.status}",
                color = CosmoColors.Cyan.copy(alpha = 0.8f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            VoiceState.error?.let {
                Text(text = "ERROR: $it", color = CosmoColors.Crimson, fontSize = 11.sp)
            }
            if (VoiceState.lastHeard.isNotEmpty()) {
                Text(
                    text = "LAST HEARD: ${VoiceState.lastHeard}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
            for (line in diag) {
                Text(text = line, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, lineHeight = 15.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- ai behaviour ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("AI PERSONALITY")
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf("BUTLER" to "BUTLER", "FRIEND" to "FRIEND", "CONCISE" to "SHORT"),
                selected = Prefs.style,
                onSelect = { Prefs.style = it; Prefs.save() }
            )
            Spacer(Modifier.height(14.dp))
            SectionLabel("LANGUAGE")
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf("AUTO" to "AUTO", "EN" to "ENGLISH", "MN" to "МОНГОЛ"),
                selected = Prefs.language,
                onSelect = { Prefs.language = it; Prefs.save() }
            )
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = "Offline only",
                subtitle = "Never send chats to the internet",
                checked = Prefs.offlineOnly,
                onChange = { Prefs.offlineOnly = it; Prefs.save() }
            )
            Text(
                text = aiStatus(),
                color = CosmoColors.Cyan.copy(alpha = 0.6f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------- cloud ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("GOOGLE GEMINI (ONLINE)")
            Spacer(Modifier.height(8.dp))
            CosmoField(
                value = key,
                onChange = { key = it },
                label = "Gemini API key",
                modifier = Modifier.fillMaxWidth(),
                visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf(
                    "gemini-4-hydrogen" to "HYDROGEN",
                    "gemini-4-carbon" to "CARBON",
                    "gemini-3.1-pro-preview" to "3.1 PRO"
                ),
                selected = Prefs.cloudModel,
                onSelect = { Prefs.cloudModel = it; Prefs.save() }
            )
            Spacer(Modifier.height(8.dp))
            CosmoField(
                value = Prefs.cloudModel,
                onChange = { Prefs.cloudModel = it; Prefs.save() },
                label = "Model ID (default gemini-4-hydrogen)",
                modifier = Modifier.fillMaxWidth()
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
                    CosmoBrain.setApiKey(key)
                    msg = "KEY SAVED"
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- local model ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("OFFLINE AI MODEL")
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (LocalAI.isModelReady()) "STATUS: MODEL INSTALLED" else "STATUS: NO MODEL (BASIC OFFLINE MODE)",
                color = if (LocalAI.isModelReady()) CosmoColors.Cyan else CosmoColors.Amber,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Text(
                text = "Pick a Gemma .task model file (for example Gemma 3 1B int4) from your phone storage. It runs fully on-device.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChipButton("PICK MODEL", CosmoColors.Cyan, Modifier.weight(1f)) {
                    picker.launch(arrayOf("*/*"))
                }
                ChipButton("REMOVE", CosmoColors.Crimson, Modifier.weight(1f)) {
                    Thread {
                        LocalAI.release()
                        if (Prefs.modelPath.isNotEmpty()) File(Prefs.modelPath).delete()
                        Prefs.modelPath = ""
                        Prefs.save()
                        msg = "MODEL REMOVED"
                    }.start()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- theme ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("ACCENT COLOUR")
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Prefs.ACCENTS.forEachIndexed { i, (_, c) ->
                    val sel = Prefs.accent == i
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(c.copy(alpha = 0.22f), CircleShape)
                            .border(if (sel) 2.dp else 1.dp, if (sel) c else CosmoColors.Dim, CircleShape)
                            .clickable { Prefs.accent = i; Prefs.save() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(16.dp).background(c, CircleShape))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- tracking ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("AUTO TRACKING")
            Spacer(Modifier.height(6.dp))
            SettingSwitch(
                title = "Track in background",
                subtitle = "Steps, screen time and sleep, every ~15 minutes",
                checked = Prefs.autoTrack,
                onChange = {
                    Prefs.autoTrack = it
                    Prefs.save()
                    if (it) Tracker.schedule() else Tracker.cancel()
                }
            )
            SettingSwitch(
                title = "Let Cosmo AI see my data",
                subtitle = "Adds steps, screen time and sleep to the chat context",
                checked = Prefs.trackInAi,
                onChange = { Prefs.trackInAi = it; Prefs.save() }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "DAILY SCREEN TIME LIMIT · ${Prefs.screenLimitH.toInt()} H",
                color = CosmoColors.Cyan.copy(alpha = 0.75f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Slider(
                value = Prefs.screenLimitH,
                onValueChange = { Prefs.screenLimitH = it.toInt().toFloat().coerceIn(1f, 12f); Prefs.save() },
                valueRange = 1f..12f,
                colors = SliderDefaults.colors(
                    thumbColor = CosmoColors.Cyan,
                    activeTrackColor = CosmoColors.Cyan,
                    inactiveTrackColor = CosmoColors.Dim
                )
            )
            Text(
                text = "SLEEP GOAL · ${String.format(java.util.Locale.US, "%.1f", Prefs.sleepGoalH)} H",
                color = CosmoColors.Cyan.copy(alpha = 0.75f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Slider(
                value = Prefs.sleepGoalH,
                onValueChange = { Prefs.sleepGoalH = (Math.round(it * 2f) / 2f).coerceIn(5f, 10f); Prefs.save() },
                valueRange = 5f..10f,
                colors = SliderDefaults.colors(
                    thumbColor = CosmoColors.Cyan,
                    activeTrackColor = CosmoColors.Cyan,
                    inactiveTrackColor = CosmoColors.Dim
                )
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------- display ----------
        GlassCard(Modifier.fillMaxWidth()) {
            SectionLabel("DISPLAY")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "GLASS STRENGTH",
                color = CosmoColors.Cyan.copy(alpha = 0.75f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Slider(
                value = Prefs.glass,
                onValueChange = { Prefs.glass = it; Prefs.save() },
                valueRange = 0.4f..2f,
                colors = SliderDefaults.colors(
                    thumbColor = CosmoColors.Cyan,
                    activeTrackColor = CosmoColors.Cyan,
                    inactiveTrackColor = CosmoColors.Dim
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "CHAT TEXT SIZE",
                color = CosmoColors.Cyan.copy(alpha = 0.75f),
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf("13" to "SMALL", "15" to "MEDIUM", "18" to "LARGE"),
                selected = Prefs.chatSize.toString(),
                onSelect = { Prefs.chatSize = it.toInt(); Prefs.save() }
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------- red filter ----------
        GlassCard(Modifier.fillMaxWidth(), accent = CosmoColors.Crimson) {
            SectionLabel("RED FILTER INTENSITY", CosmoColors.Crimson)
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
        }

        Spacer(Modifier.height(12.dp))

        // ---------- permissions ----------
        GlassCard(Modifier.fillMaxWidth()) {
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
                text = if (storageOk) "ALL FILES (OPTIONAL): GRANTED" else "ALL FILES (OPTIONAL): GRANT",
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
        Spacer(Modifier.height(8.dp))
    }
}
