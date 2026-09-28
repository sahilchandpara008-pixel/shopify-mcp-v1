package com.streams.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.ui.components.PrimaryButton
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Poppins
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.TextFaint
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onClose: () -> Unit, reason: String = "Sign in to watch and to subscribe.") {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun sendLink() {
        error = null
        if (!email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) { error = "Please enter a valid email address"; return }
        busy = true
        scope.launch {
            runCatching { Repo.sendMagicLink(email) }
                .onSuccess { sentTo = email.trim() }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Red.copy(alpha = 0.22f), Bg, Bg))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(64.dp))
            Text("STREAMS", color = Red, fontFamily = Poppins, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Movies, series & originals",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(reason, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(40.dp))

            if (sentTo != null) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(Surface1), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MarkEmailRead, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Check your inbox", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "We sent a sign-in link to\n$sentTo\n\nOpen it on this phone — no password needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { sentTo = null }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Use a different email", color = Color.White)
                }
            } else {
                Button(
                    onClick = {
                        error = null
                        scope.launch { runCatching { Repo.signInWithGoogle() }.onFailure { error = it.friendly() } }
                    },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("G", fontWeight = FontWeight.Black, color = Color(0xFF4285F4), fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("Continue with Google", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                }

                Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = TextFaint.copy(alpha = 0.4f))
                    Text("  or  ", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.weight(1f), color = TextFaint.copy(alpha = 0.4f))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    placeholder = { Text("you@example.com") },
                    label = { Text("Email address") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendLink() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, cursorColor = Color.White, focusedLabelColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Email me a sign-in link", { sendLink() }, loading = busy)
                Text(
                    "No password needed — we'll email you a one-tap link.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(36.dp))
            Text(
                "Free titles need a free account.\nPremium titles also need a plan.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopEnd)) {
            Icon(Icons.Default.Close, "Not now", tint = Color.White)
        }
    }
}
