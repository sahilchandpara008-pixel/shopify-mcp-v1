package com.streams.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.BuildConfig
import com.streams.app.Load
import com.streams.app.data.Payment
import com.streams.app.data.PaymentSettings
import com.streams.app.data.Plan
import com.streams.app.data.Repo
import com.streams.app.data.Subscription
import com.streams.app.data.currentEmail
import com.streams.app.data.friendly
import com.streams.app.rememberLoad
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.formatDate
import com.streams.app.ui.components.formatPrice
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private data class ProfileData(
    val email: String?,
    val subscription: Subscription?,
    val plans: List<Plan>,
    val payments: List<Payment>,
    val settings: PaymentSettings?,
    val adminRole: String?,
)

@Composable
fun ProfileScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val load = rememberLoad(access) {
        coroutineScope {
            val email = currentEmail()
            val sub = async { if (email != null) Repo.mySubscription() else null }
            val plans = async { Repo.plans() }
            val pays = async { if (email != null) Repo.myPayments() else emptyList() }
            val settings = async { runCatching { Repo.paymentSettings() }.getOrNull() }
            // Only owner/manager accounts get a role back; everyone else gets null.
            val role = async { if (email != null) runCatching { Repo.myAdminRole() }.getOrNull() else null }
            ProfileData(email, sub.await(), plans.await(), pays.await(), settings.await(), role.await())
        }
    }
    var versionTaps by remember { mutableIntStateOf(0) }

    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val d = s.data
            LazyColumn(
                Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Profile",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.statusBarsPadding().padding(top = 12.dp),
                    )
                }
                item { AccountCard(d.email, d.subscription, nav) }
                if (d.adminRole != null) {
                    item {
                        Button(
                            onClick = { nav.navigate("admin") },
                            colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text("Open admin panel (${if (d.adminRole == "owner") "Owner" else "Manager"})")
                        }
                    }
                }

                item { Text("Choose a plan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                item {
                    PlansSection(d, onSignIn = { nav.navigate("auth") }, onSubmitted = {
                        scope.launch { AppState.refreshAccess() }
                    })
                }

                if (d.email != null) {
                    item { Text("Payment history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                    if (d.payments.isEmpty()) {
                        item { Text("No payments yet.", style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(d.payments, key = { it.id }) { PaymentRow(it) }
                    item {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { Repo.signOut() }
                                    AppState.refreshAccess()
                                    nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("Sign out") }
                    }
                }
                item {
                    // Tapping the version 7 times opens the admin sign-in (not linked anywhere else).
                    Text(
                        "Streams ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .clickable(interactionSource = null, indication = null) {
                                versionTaps++
                                if (versionTaps >= 7) { versionTaps = 0; nav.navigate("admin-login") }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(email: String?, sub: Subscription?, nav: NavController) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
            Text((email ?: "G").take(1).uppercase(), color = Red, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (email == null) {
                Text("Not signed in", style = MaterialTheme.typography.titleMedium)
                Text("Sign in to subscribe and unlock premium.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(email, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val expires = sub?.expiresAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                val active = sub?.status == "active" && expires?.isAfter(OffsetDateTime.now()) == true
                when {
                    active -> Text("Active · until ${formatDate(sub!!.expiresAt)}", color = Green, style = MaterialTheme.typography.bodyMedium)
                    sub != null -> Text("Expired on ${formatDate(sub.expiresAt)}", color = Amber, style = MaterialTheme.typography.bodyMedium)
                    else -> Text("No active plan", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (email == null) {
            Button(onClick = { nav.navigate("auth") }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Sign in") }
        }
    }
}

@Composable
private fun PlansSection(d: ProfileData, onSignIn: () -> Unit, onSubmitted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Plan?>(null) }
    var utr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf<Payment?>(null) }
    val pending = submitted ?: d.payments.firstOrNull { it.status == "pending" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pending != null) {
            StatusBox(
                icon = { Icon(Icons.Default.HourglassTop, null, tint = Amber) },
                title = "Payment submitted — waiting for verification",
                body = "${pending.planName ?: "Plan"} · ${formatPrice(pending.amount)} · Ref ${pending.reference}. " +
                    "We'll activate your plan as soon as it's verified.",
            )
            return@Column
        }

        d.plans.forEach { plan ->
            val isSel = selected?.id == plan.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .border(BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) Red else Color(0xFF2A2A30)), RoundedCornerShape(12.dp))
                    .clickable { selected = plan; error = null }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium)
                    Text(plan.durationLabel, style = MaterialTheme.typography.bodySmall)
                }
                Text(formatPrice(plan.price), style = MaterialTheme.typography.titleLarge)
                if (isSel) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.CheckCircle, null, tint = Red)
                }
            }
        }

        val plan = selected ?: return@Column
        if (d.email == null) {
            Button(onClick = onSignIn, colors = ButtonDefaults.buttonColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) {
                Text("Sign in to buy ${plan.name}")
            }
            return@Column
        }
        val settings = d.settings
        if (settings == null) {
            Text("Payments are not set up yet. Please try again later.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Pay ${formatPrice(plan.price)} for ${plan.name}", style = MaterialTheme.typography.titleMedium)
            Text("1. Pay with any UPI app", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = { openUpi(context, settings, plan) },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open UPI app") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Or pay manually to UPI ID", style = MaterialTheme.typography.bodySmall)
                    Text(settings.upiId, style = MaterialTheme.typography.titleSmall)
                    Text("Name: ${settings.payeeName}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { copy(context, settings.upiId) }) { Icon(Icons.Default.ContentCopy, "Copy UPI ID", tint = TextMuted) }
            }

            Text("2. Enter the UTR / reference number", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                "After paying, your UPI app shows a 12-digit UTR or transaction reference. Type it here.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = utr,
                onValueChange = { v -> utr = v.filter { it.isLetterOrDigit() }.take(35) },
                label = { Text("UTR / reference number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    if (utr.length < 6) { error = "Please enter the full reference number from your UPI app"; return@Button }
                    busy = true; error = null
                    scope.launch {
                        runCatching { Repo.submitPayment(plan.id, utr) }
                            .onSuccess { submitted = it; utr = ""; onSubmitted() }
                            .onFailure { error = it.friendly() }
                        busy = false
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Submit for verification")
            }
            Text(
                "Your plan starts after we verify the payment with our bank. No automatic renewals — ever.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusBox(icon: @Composable () -> Unit, title: String, body: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(14.dp)) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PaymentRow(p: Payment) {
    val (label, color) = when (p.status) {
        "approved" -> "Approved" to Green
        "rejected" -> "Rejected" to MaterialTheme.colorScheme.error
        else -> "Waiting" to Amber
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${p.planName ?: "Plan"} · ${formatPrice(p.amount)}", style = MaterialTheme.typography.titleSmall)
            Text("${formatDate(p.createdAt)} · Ref ${p.reference}", style = MaterialTheme.typography.bodySmall)
            p.adminNote?.takeIf { p.status == "rejected" }?.let {
                Text("Note: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** Builds upi://pay?... — just a shortcut to pre-fill the UPI app. It is NOT proof of payment. */
private fun openUpi(context: Context, s: PaymentSettings, plan: Plan) {
    val uri = Uri.Builder()
        .scheme("upi").authority("pay")
        .appendQueryParameter("pa", s.upiId)
        .appendQueryParameter("pn", s.payeeName)
        .appendQueryParameter("am", "%.2f".format(java.util.Locale.US, plan.price))
        .appendQueryParameter("cu", "INR")
        .appendQueryParameter("tn", "Streams ${plan.name}")
        .build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No UPI app found. Pay to ${s.upiId} manually.", Toast.LENGTH_LONG).show()
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("UPI ID", text))
    Toast.makeText(context, "UPI ID copied", Toast.LENGTH_SHORT).show()
}
