package com.streams.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.streams.app.data.supabase
import com.streams.app.ui.AppNav
import com.streams.app.ui.theme.StreamsTheme
import io.github.jan.supabase.auth.handleDeeplinks

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handleAuthLink(intent)
        setContent { StreamsTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthLink(intent)
    }

    /** streams://login-callback[/admin-reset]?code=... — finishes Google / e-mail sign-in. */
    private fun handleAuthLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.path?.startsWith("/admin-reset") == true) AppState.pendingRoute.value = "admin-reset"
        supabase.handleDeeplinks(intent)
    }
}
