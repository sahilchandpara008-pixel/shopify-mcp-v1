package com.streams.app.ui.admin

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.streams.app.rememberLoad
import com.streams.app.data.Repo
import com.streams.app.payments.AutoVerify
import com.streams.app.ui.components.formatDate
import com.streams.app.ui.components.formatPrice
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.TextMuted

/**
 * Admin → Settings: turn this phone into the payment checker. It reads the bank's "credited"
 * SMS and UPI app notifications for the business UPI ID and activates the matching order.
 */
@Composable
fun AutoVerifyCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AutoVerify.isEnabled(context)) }
    var sms by remember { mutableStateOf(AutoVerify.hasSmsPermission(context)) }
    var notif by remember { mutableStateOf(AutoVerify.hasNotificationAccess(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    // Re-check permissions when coming back from the system settings screens.
    LifecycleResumeEffect(Unit) {
        sms = AutoVerify.hasSmsPermission(context)
        notif = AutoVerify.hasNotificationAccess(context)
        refresh++
        onPauseOrDispose { }
    }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { sms = it }
    val credits = rememberLoad(refresh) { runCatching { Repo.recentBankCredits() }.getOrDefault(emptyList()) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-verify payments on this phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use the phone that gets the bank SMS / UPI app alerts for your UPI ID. Each customer order has a " +
                        "unique amount (e.g. ₹149.37); when that credit arrives here, the plan starts automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    AutoVerify.setEnabled(context, it)
                    if (it && !sms) smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Red),
            )
        }
        if (enabled) {
            StatusRow(sms, "Read bank SMS", "Allow") { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }
            StatusRow(notif, "Read UPI app notifications (GPay, PhonePe, Paytm, Bandhan)", "Open") {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (!sms || !notif) {
                Text(
                    "Android may say the setting is restricted because the app wasn't installed from Play Store. Then open " +
                        "App info → ⋮ (top right) → \"Allow restricted settings\", and try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open App info") }
            }
            Text(
                "Keep this phone on the internet and signed in as admin. One permission is enough; both is best.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Recent credits seen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            val list = (credits.state as? com.streams.app.Load.Ok)?.data.orEmpty()
            if (list.isEmpty()) {
                Text("None yet. Make a ₹1 test payment to your UPI ID to check it works.", style = MaterialTheme.typography.bodySmall)
            }
            list.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${formatPrice(c.amount)} · ${formatDate(c.receivedAt)}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            (c.source ?: "") + (c.ref?.let { " · UTR $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    Text(
                        if (c.paymentId != null) "Matched" else "No order",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (c.paymentId != null) Green else TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean, label: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            null,
            tint = if (ok) Green else Amber,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!ok) OutlinedButton(onClick = onAction) { Text(action, color = Color.White) }
    }
}
