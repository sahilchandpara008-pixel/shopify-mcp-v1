package com.streams.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.Episode
import com.streams.app.data.Repo
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.imageUrl
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.LockedNote
import com.streams.app.ui.components.TierBadge
import com.streams.app.ui.components.formatDuration
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted

/** What a viewer may play for this title. The server enforces the same rules on the video files. */
fun canWatchFull(t: Title, hasSub: Boolean) = t.tier == Tier.FREE || hasSub

/** For locked titles: trailer if one exists, otherwise a 30-second preview of the real video. */
fun lockedMode(t: Title) = if (t.trailerPath != null) "trailer" else "preview"

@Composable
fun TitleDetailScreen(nav: NavController, titleId: String) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val hasSub by AppState.hasSubscription.collectAsStateWithLifecycle()
    val load = rememberLoad(titleId, access) {
        val t = Repo.title(titleId) ?: error("This title isn't available.")
        t to if (t.isSeries) Repo.episodes(t.id) else emptyList()
    }

    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val (t, episodes) = s.data
            val full = canWatchFull(t, hasSub)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Header(t, nav) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TierBadge(t.tier)
                            val meta = listOfNotNull(
                                if (t.isSeries) "Series · ${episodes.size} episodes" else "Movie",
                                formatDuration(t.durationSeconds).takeIf { !t.isSeries },
                            ).joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(14.dp))

                        val firstEp = episodes.firstOrNull()
                        val canPlayMain = !t.isSeries || firstEp != null
                        if (full) {
                            Button(
                                onClick = { nav.navigate(Routes.player(t.id, "full", firstEp?.id)) },
                                enabled = canPlayMain,
                                colors = ButtonDefaults.buttonColors(containerColor = Red),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                                Text(if (t.isSeries) "Play episode 1" else "Play")
                            }
                            if (t.trailerPath != null) {
                                OutlinedButton(
                                    onClick = { nav.navigate(Routes.player(t.id, "trailer")) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                ) { Text("Watch trailer") }
                            }
                        } else {
                            val mode = lockedMode(t)
                            Button(
                                onClick = { nav.navigate(Routes.player(t.id, mode, firstEp?.id)) },
                                enabled = mode == "trailer" || canPlayMain,
                                colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                                Text(if (mode == "trailer") "Watch trailer" else "Watch 30-sec preview")
                            }
                            Button(
                                onClick = { nav.navigate("profile") { launchSingleTop = true } },
                                colors = ButtonDefaults.buttonColors(containerColor = Red),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                            ) { Text("Subscribe to watch in full") }
                        }

                        if (t.description.isNotBlank()) {
                            Text(t.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                        }
                        if (t.isSeries) {
                            Text("Episodes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                        }
                    }
                }
                if (t.isSeries) {
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(ep, locked = !full) {
                            nav.navigate(Routes.player(t.id, if (full) "full" else lockedMode(t), ep.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(t: Title, nav: NavController) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Surface1)) {
        val url = imageUrl(t.coverPath)
        if (url != null) AsyncImage(url, t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.Movie, null, tint = TextMuted, modifier = Modifier.align(Alignment.Center).size(48.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Bg)))
        IconButton(
            onClick = { nav.popBackStack() },
            colors = IconButtonDefaults.iconButtonColors(containerColor = Bg.copy(alpha = 0.6f)),
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(
            t.name,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EpisodeRow(ep: Episode, locked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (locked) Icons.Default.Lock else Icons.Default.PlayArrow, null, tint = if (locked) TextMuted else Red)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.episodeNumber}. ${ep.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = formatDuration(ep.durationSeconds)
            if (locked) LockedNote("Subscribe to watch") else if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall)
        }
    }
}
