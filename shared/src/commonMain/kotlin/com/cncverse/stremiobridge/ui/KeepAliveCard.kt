package com.cncverse.stremiobridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cncverse.stremiobridge.state.KeepAlivePlatform
import kotlinx.coroutines.delay

/** What keeps the bridge running in the background, and the phone settings that can stop it (Android only). */
@Composable
fun KeepAliveCard() {
    if (!KeepAlivePlatform.supported) return
    var exempt by remember { mutableStateOf(KeepAlivePlatform.batteryExempt()) }
    var note by remember { mutableStateOf<String?>(null) }
    // the answer changes when the user comes back from the system screen
    LaunchedEffect(Unit) { while (true) { delay(2_000); exempt = KeepAlivePlatform.batteryExempt() } }

    AmoledCard(Modifier.fillMaxWidth()) {
        SectionTitle(
            "Keep running",
            "The bridge runs as a foreground service with the CPU and Wi-Fi kept awake. It starts again by itself after a phone " +
                "restart or an app update, is checked every 15 minutes and brought back if Android closed it, and its web server " +
                "is tested every minute and restarted if it stops answering. Pressing Stop turns all of this off.",
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (exempt) "✓ Battery optimisation: off for this app (the bridge can restart itself)"
            else "✗ Battery optimisation is on: Android may close the bridge and won't let it start again in the background",
            color = if (exempt) Green400 else Amber400, fontSize = 12.sp,
        )
        if (!exempt) {
            Spacer(Modifier.height(6.dp))
            ActionButton("Allow running in the background", primary = true) { KeepAlivePlatform.openBatterySettings() }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Xiaomi, Oppo, Realme, Vivo, OnePlus, Huawei and Samsung phones have their own battery saver as well: allow " +
                "\"Auto-start\" / \"Background activity\" / \"Unrestricted\" battery for this app there.",
            color = TextMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Auto-start settings") {
                note = if (KeepAlivePlatform.openAutostartSettings()) null
                else { KeepAlivePlatform.openAppSettings(); "This phone has no separate auto-start screen: opened the app's settings (Battery -> Unrestricted)." }
            }
            ActionButton("App settings") { KeepAlivePlatform.openAppSettings() }
        }
        note?.let { Spacer(Modifier.height(6.dp)); Text(it, color = TextMuted, fontSize = 11.sp) }
    }
}
