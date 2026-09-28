package com.streams.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onClose: () -> Unit, reason: String = "Sign in to watch and to subscribe.") {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("STREAMS", color = Red, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))

        Button(
            onClick = {
                error = null
                scope.launch { runCatching { Repo.signInWithGoogle() }.onFailure { error = it.friendly() } }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            Text("G", fontWeight = FontWeight.Black, color = Color(0xFF4285F4), fontSize = 18.sp)
            Spacer(Modifier.size(10.dp))
            Text("Continue with Google")
        }

        Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("  or use email  ", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.weight(1f))
        }

        if (sentTo != null) {
            Text("Check your inbox", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "We sent a sign-in link to $sentTo. Open it on this phone to sign in — no password needed.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { sentTo = null }) { Text("Use a different email") }
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email address") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Email, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    error = null
                    if (!email.contains("@")) { error = "Please enter a valid email"; return@OutlinedButton }
                    busy = true
                    scope.launch {
                        runCatching { Repo.sendMagicLink(email) }
                            .onSuccess { sentTo = email.trim() }
                            .onFailure { error = it.friendly() }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Email me a sign-in link")
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onClose) { Text("Not now", color = TextMuted) }
        Text(
            "Free titles need a free account. Premium titles also need a plan.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
