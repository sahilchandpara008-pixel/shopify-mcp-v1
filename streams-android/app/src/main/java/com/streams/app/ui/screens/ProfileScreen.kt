package com.streams.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
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
import com.streams.app.ui.components.Card
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.PrimaryButton
import com.streams.app.ui.components.formatDate
import com.streams.app.ui.components.formatPrice
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Gold
import com.streams.app.ui.theme.Green
import com.streams.app.ui.theme.Outline
import com.streams.app.ui.theme.Poppins
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.RedGradient
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextFaint
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        "Profile",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.statusBarsPadding().padding(top = 14.dp),
                    )
                }
                item { MembershipCard(d.email, d.subscription, nav) }
                if (d.adminRole != null) {
                    item {
                        MenuRow(
                            icon = Icons.Default.AdminPanelSettings,
                            title = "Admin panel",
                            subtitle = "Upload content, approve payments · ${if (d.adminRole == "owner") "Owner" else "Manager"}",
                            onClick = { nav.navigate("admin") },
                        )
                    }
                }

                item {
                    Column {
                        Text("Choose your plan", style = MaterialTheme.typography.titleLarge)
                        Text("One-time UPI payment · no auto-renewal", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    PlansSection(d, onSignIn = { nav.navigate("auth") }, onSubmitted = {
                        scope.launch { AppState.refreshAccess() }
                    })
                }

                if (d.email != null) {
                    item { Text("Payment history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp)) }
                    if (d.payments.isEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Receipt, null, tint = TextFaint, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Your payments will appear here.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    items(d.payments, key = { it.id }) { PaymentRow(it) }
                    item {
                        MenuRow(
                            icon = Icons.AutoMirrored.Filled.Logout,
                            title = "Sign out",
                            subtitle = d.email,
                            onClick = {
                                scope.launch {
                                    runCatching { Repo.signOut() }
                                    AppState.refreshAccess()
                                    nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
                                }
                            },
                        )
                    }
                }
                item {
                    // Tapping the version 7 times opens the admin sign-in (not linked anywhere else).
                    Text(
                        "Streams ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
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
private fun MembershipCard(email: String?, sub: Subscription?, nav: NavController) {
    val expires = sub?.expiresAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
    val active = sub?.status == "active" && expires?.isAfter(OffsetDateTime.now()) == true
    val daysLeft = if (active && expires != null) java.time.Duration.between(OffsetDateTime.now(), expires).toDays() else 0
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                if (active) Brush.linearGradient(listOf(Color(0xFF3B0A0D), Color(0xFF1A1014), Surface1))
                else Brush.linearGradient(listOf(Surface2, Surface1)),
            )
            .border(1.dp, if (active) Red.copy(alpha = 0.45f) else Outline, MaterialTheme.shapes.large)
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (email != null) RedGradient else Brush.linearGradient(listOf(Surface2, Surface2))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text((email ?: "?").take(1).uppercase(), color = Color.White, fontFamily = Poppins, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(email ?: "Welcome to Streams", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val (label, color) = when {
                        email == null -> "Sign in to watch and subscribe" to TextMuted
                        active -> "Premium member" to Gold
                        sub != null -> "Plan expired" to Amber
                        else -> "Free account" to TextMuted
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Icon(Icons.Default.WorkspacePremium, null, tint = Gold, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
            }
            when {
                email == null -> {
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Sign in", { nav.navigate("auth") })
                }
                active -> {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$daysLeft", fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(if (daysLeft == 1L) "day left" else "days left", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    Text("Valid until ${formatDate(sub!!.expiresAt)}", style = MaterialTheme.typography.bodySmall)
                }
                sub != null -> {
                    Spacer(Modifier.height(10.dp))
                    Text("Expired on ${formatDate(sub.expiresAt)}. Choose a plan below to continue watching premium.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextMuted)
    }
}

@Composable
private fun PlansSection(d: ProfileData, onSignIn: () -> Unit, onSubmitted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pre-select the "most popular" plan so the next step is obvious.
    val popularId = d.plans.firstOrNull { it.durationDays in 28..31 }?.id ?: d.plans.getOrNull(d.plans.size / 2)?.id
    var selected by remember(d.plans) { mutableStateOf(d.plans.firstOrNull { it.id == popularId }) }
    var utr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf<Payment?>(null) }
    val pending = submitted ?: d.payments.firstOrNull { it.status == "pending" }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pending != null) {
            Card {
                Row {
                    Icon(Icons.Default.HourglassTop, null, tint = Amber)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Payment submitted — waiting for verification", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${pending.planName ?: "Plan"} · ${formatPrice(pending.amount)} · Ref ${pending.reference}\n" +
                                "Your plan starts as soon as we verify it (usually within a few hours).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            return@Column
        }

        d.plans.forEach { plan ->
            PlanCard(plan, selected?.id == plan.id, plan.id == popularId) { selected = plan; error = null }
        }

        val plan = selected ?: return@Column
        if (d.email == null) {
            Spacer(Modifier.height(4.dp))
            PrimaryButton("Sign in to buy ${plan.name}", onSignIn)
            return@Column
        }
        val settings = d.settings
        if (settings == null) {
            Text("Payments are not set up yet. Please try again later.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Card(Modifier.padding(top = 6.dp), padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pay for ${plan.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(formatPrice(plan.price), style = MaterialTheme.typography.headlineSmall)
                }

                Step(1, "Pay with any UPI app")
                PrimaryButton("Pay ${formatPrice(plan.price)} via UPI", { openUpi(context, settings, plan) })
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Surface2).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text("Or pay manually to", style = MaterialTheme.typography.bodySmall)
                        Text(settings.upiId, style = MaterialTheme.typography.titleSmall)
                        Text(settings.payeeName, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { copy(context, settings.upiId) }) { Icon(Icons.Default.ContentCopy, "Copy UPI ID", tint = Color.White) }
                }

                Step(2, "Enter the UTR / reference number")
                Text(
                    "After paying, your UPI app shows a 12-digit UTR or transaction ID (e.g. in payment details).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = utr,
                    onValueChange = { v -> utr = v.filter { it.isLetterOrDigit() }.take(35).uppercase() },
                    label = { Text("UTR / reference number") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, cursorColor = Color.White, focusedLabelColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Step(3, "Submit — we'll verify and activate your plan")
                PrimaryButton(
                    "Submit for verification",
                    onClick = {
                        if (utr.length < 6) { error = "Please enter the full reference number from your UPI app"; return@PrimaryButton }
                        busy = true; error = null
                        scope.launch {
                            runCatching { Repo.submitPayment(plan.id, utr) }
                                .onSuccess { submitted = it; utr = ""; onSubmitted() }
                                .onFailure { error = it.friendly() }
                            busy = false
                        }
                    },
                    loading = busy,
                    enabled = utr.length >= 6,
                )
            }
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, selected: Boolean, popular: Boolean, onClick: () -> Unit) {
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = if (popular) 10.dp else 0.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(if (selected) Red.copy(alpha = 0.10f) else Surface1)
                .border(if (selected) 2.dp else 1.dp, if (selected) Red else Outline, MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null,
                tint = if (selected) Red else TextFaint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium)
                Text(plan.durationLabel, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatPrice(plan.price), style = MaterialTheme.typography.titleLarge)
                if (plan.durationDays > 1) {
                    Text("₹%.0f / day".format(plan.price / plan.durationDays), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (popular) {
            Text(
                "MOST POPULAR",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .offset(x = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RedGradient)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.Black, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
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
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Surface1).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${p.planName ?: "Plan"} · ${formatPrice(p.amount)}", style = MaterialTheme.typography.titleSmall)
            Text("${formatDate(p.createdAt)} · Ref ${p.reference}", style = MaterialTheme.typography.bodySmall)
            p.adminNote?.takeIf { p.status == "rejected" }?.let {
                Text("Reason: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
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
