package com.cosmic802.cosmo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProfileCard() {
    val ctx = LocalContext.current
    var contactsOk by remember { mutableStateOf(Contacts.hasPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        contactsOk = it
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionLabel("PROFILE (STAYS ON THIS PHONE)")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CosmoField(
                value = Prefs.age,
                onChange = { Prefs.age = it.filter { c -> c.isDigit() }.take(3); Prefs.save() },
                label = "Age",
                modifier = Modifier.weight(1f)
            )
            CosmoField(
                value = Prefs.heightCm,
                onChange = { Prefs.heightCm = it.filter { c -> c.isDigit() }.take(3); Prefs.save() },
                label = "Height cm",
                modifier = Modifier.weight(1f)
            )
            CosmoField(
                value = Prefs.weightKg,
                onChange = { Prefs.weightKg = it.filter { c -> c.isDigit() || c == '.' }.take(5); Prefs.save() },
                label = "Weight kg",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        ChipButton(
            text = if (contactsOk) "CONTACTS: ALLOWED" else "ALLOW CONTACTS",
            color = if (contactsOk) CosmoColors.Cyan else CosmoColors.Amber,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!contactsOk) launcher.launch(android.Manifest.permission.READ_CONTACTS)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (CosmoBrain.hasStorageAccess()) {
                "Backup: ON. Settings and memory are kept in /sdcard/CosmoData and survive reinstall. The API key is not backed up."
            } else {
                "Backup: OFF. Grant ALL FILES ACCESS below so your data survives reinstall."
            },
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        Text(
            text = "Chat: \"call <name>\", \"text <name>\", \"email <name>\", \"open messenger\", \"sleep mode\".",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}
