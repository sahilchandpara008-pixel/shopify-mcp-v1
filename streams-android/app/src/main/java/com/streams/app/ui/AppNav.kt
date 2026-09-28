package com.streams.app.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streams.app.AppState
import com.streams.app.ui.admin.AdminLoginScreen
import com.streams.app.ui.admin.AdminResetPasswordScreen
import com.streams.app.ui.admin.AdminScreen
import com.streams.app.ui.admin.ContentEditorScreen
import com.streams.app.ui.components.Loading
import com.streams.app.ui.screens.AuthScreen
import com.streams.app.ui.screens.ChannelDetailScreen
import com.streams.app.ui.screens.ChannelsScreen
import com.streams.app.ui.screens.HomeScreen
import com.streams.app.ui.screens.PlayerScreen
import com.streams.app.ui.screens.ProfileScreen
import com.streams.app.ui.screens.ShowsScreen
import com.streams.app.ui.screens.TitleDetailScreen
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.TextMuted
import io.github.jan.supabase.auth.status.SessionStatus

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("channels", "Channels", Icons.Default.LiveTv),
    Tab("shows", "Shows", Icons.Default.GridView),
    Tab("profile", "Profile", Icons.Default.Person),
)

object Routes {
    fun title(id: String) = "title/$id"
    fun channel(id: String) = "channel/$id"
    /** mode = full | trailer | preview */
    fun player(titleId: String, mode: String, episodeId: String? = null) =
        "player/$titleId?mode=$mode" + (episodeId?.let { "&ep=$it" } ?: "")
    fun edit(id: String?) = if (id == null) "admin-edit" else "admin-edit?id=$id"
}

@Composable
fun AppNav() {
    val session by AppState.session.collectAsStateWithLifecycle()
    val guest by AppState.guest.collectAsStateWithLifecycle()
    val pendingRoute by AppState.pendingRoute.collectAsStateWithLifecycle()

    if (session is SessionStatus.Initializing) {
        Loading()
        return
    }
    val signedIn = session is SessionStatus.Authenticated

    // Re-check subscription whenever the signed-in user changes.
    val userId = (session as? SessionStatus.Authenticated)?.session?.user?.id
    LaunchedEffect(userId) { AppState.refreshAccess() }

    val nav = rememberNavController()
    val start = remember { if (signedIn || guest) "home" else "auth" }

    // Leave the sign-in screen as soon as a session appears.
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    LaunchedEffect(signedIn, route) {
        if (signedIn && route == "auth") nav.navigate("home") { popUpTo("auth") { inclusive = true } }
    }
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { nav.navigate(it); AppState.pendingRoute.value = null }
    }

    val showBar = tabs.any { it.route == route }
    Scaffold(
        containerColor = Bg,
        bottomBar = { if (showBar) BottomBar(nav, route) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("auth") {
                AuthScreen(onGuest = {
                    AppState.setGuest(true)
                    nav.navigate("home") { popUpTo("auth") { inclusive = true } }
                })
            }
            composable("home") { HomeScreen(nav) }
            composable("channels") { ChannelsScreen(nav) }
            composable("shows") { ShowsScreen(nav) }
            composable("profile") { ProfileScreen(nav) }
            composable("channel/{id}") { ChannelDetailScreen(nav, it.arguments?.getString("id")!!) }
            composable("title/{id}") { TitleDetailScreen(nav, it.arguments?.getString("id")!!) }
            composable(
                "player/{id}?mode={mode}&ep={ep}",
                arguments = listOf(
                    navArgument("mode") { type = NavType.StringType; defaultValue = "full" },
                    navArgument("ep") { type = NavType.StringType; nullable = true },
                ),
            ) {
                PlayerScreen(
                    nav,
                    titleId = it.arguments?.getString("id")!!,
                    requestedMode = it.arguments?.getString("mode") ?: "full",
                    episodeId = it.arguments?.getString("ep"),
                )
            }

            // Admin — reachable only by the hidden gesture on Profile or the e-mail link.
            composable("admin-login") { AdminLoginScreen(nav) }
            composable("admin-reset") { AdminResetPasswordScreen(nav) }
            composable("admin") { AdminScreen(nav) }
            composable(
                "admin-edit?id={id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
            ) { ContentEditorScreen(nav, it.arguments?.getString("id")) }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    NavigationBar(containerColor = Color(0xFF111114)) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Red,
                    selectedTextColor = Red,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                ),
            )
        }
    }
}
