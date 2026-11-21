package com.cosmic802.cosmo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AutoCard() {
    val ctx = LocalContext.current
    var voice by remember { mutableStateOf(Auto.voiceAlways(ctx)) }
    var doom by remember { mutableStateOf(Auto.doomOn(ctx)) }
    var mins by remember { mutableStateOf(Auto.doomMinutes(ctx)) }
    var usageOk by remember { mutableStateOf(Tracker.hasUsageAccess()) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            usageOk = Tracker.hasUsageAccess()
            delay(1500)
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionLabel("BACKGROUND")
        Spacer(Modifier.height(8.dp))

        SettingSwitch(
            title = "Always-on voice",
            subtitle = "Listens in the background after the app opens or the phone boots. Say \"Cosmo\" or \"Hey Cosmo\".",
            checked = voice,
            onChange = {
                voice = it
                Auto.setVoiceAlways(ctx, it)
                if (it) Auto.startVoiceIfWanted(ctx) else CosmoVoiceEngine.stop(ctx)
            }
        )
        SettingSwitch(
            title = "Scroll alert",
            subtitle = "Notify me when Instagram, TikTok and similar apps take about $mins minutes of the last stretch.",
            checked = doom,
            onChange = {
                doom = it
                Auto.setDoomOn(ctx, it)
                if (it) Tracker.schedule()
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (v in listOf(20, 30, 45)) {
                ChipButton(
                    text = "$v MIN",
                    color = if (mins == v) CosmoColors.Cyan else CosmoColors.Cyan.copy(alpha = 0.4f),
                    modifier = Modifier.weight(1f)
                ) {
                    mins = v
                    Auto.setDoomMinutes(ctx, v)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (!usageOk) {
            ChipButton("GRANT USAGE ACCESS (NEEDED)", CosmoColors.Amber, Modifier.fillMaxWidth()) {
                ctx.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        ChipButton("ROOT SETUP: GRANT ALL", CosmoColors.Cyan, Modifier.fillMaxWidth()) {
            msg = "RUNNING ROOT SETUP..."
            Thread {
                msg = if (Auto.rootSetup()) "ROOT SETUP DONE" else "ROOT SETUP FAILED (is root allowed?)"
            }.start()
        }
        Spacer(Modifier.height(8.dp))
        ChipButton("START VOICE NOW", CosmoColors.Amber, Modifier.fillMaxWidth()) {
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                CosmoVoiceEngine.start(ctx)
                msg = "VOICE STARTING..."
            } else {
                msg = "MIC PERMISSION MISSING (tap Voice on HOME, or use ROOT SETUP)"
            }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text = msg, color = CosmoColors.Cyan, fontSize = 11.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Boot start needs ROOT SETUP once. Scroll checks run about every 15 minutes, so an alert can come a few minutes late.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}
