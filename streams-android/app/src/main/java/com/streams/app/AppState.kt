package com.streams.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.data.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide state: guest mode, subscription status, and a pending deep-link destination. */
object AppState {
    private const val PREFS = "streams"
    private lateinit var appContext: Context

    val guest = MutableStateFlow(false)
    val hasSubscription = MutableStateFlow(false)

    /** Bumped whenever access may have changed (login/logout/approval) so screens reload. */
    val accessVersion = MutableStateFlow(0)

    /** Set by MainActivity when the admin "set password" e-mail link is opened. */
    val pendingRoute = MutableStateFlow<String?>(null)

    val session: StateFlow<SessionStatus> get() = supabase.auth.sessionStatus

    fun init(context: Context) {
        appContext = context.applicationContext
        guest.value = prefs().getBoolean("guest", false)
    }

    fun setGuest(value: Boolean) {
        guest.value = value
        prefs().edit().putBoolean("guest", value).apply()
    }

    suspend fun refreshAccess() {
        hasSubscription.value =
            supabase.auth.currentUserOrNull() != null && runCatching { Repo.hasActiveSubscription() }.getOrDefault(false)
        accessVersion.value++
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// ---------------------------------------------------------------- small loading helper

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

class Loaded<T>(val state: Load<T>, val reload: () -> Unit)

/** Runs [block] when [keys] change; exposes Loading/Ok/Err plus a reload() for "Try again". */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Loaded<T> {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, attempt) {
        if (state !is Load.Ok) state = Load.Loading
        state = runCatching { block() }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    return Loaded(state) { attempt++ }
}
