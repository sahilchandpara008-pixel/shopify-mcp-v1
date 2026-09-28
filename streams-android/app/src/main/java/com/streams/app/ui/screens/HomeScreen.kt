package com.streams.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.streams.app.AppState
import com.streams.app.Load
import com.streams.app.data.Campaign
import com.streams.app.data.Channel
import com.streams.app.data.Repo
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.imageUrl
import com.streams.app.rememberLoad
import com.streams.app.ui.Routes
import com.streams.app.ui.components.ChipRow
import com.streams.app.ui.components.EmptyState
import com.streams.app.ui.components.ErrorState
import com.streams.app.ui.components.Loading
import com.streams.app.ui.components.PosterCard
import com.streams.app.ui.components.TierBadge
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.Surface2
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class HomeData(val titles: List<Title>, val channels: List<Channel>, val campaign: Campaign?)

fun List<Title>.filterTier(filter: String) = when (filter) {
    "Free" -> filter { it.tier == Tier.FREE }
    "Premium" -> filter { it.tier != Tier.FREE }
    else -> this
}

@Composable
fun HomeScreen(nav: NavController) {
    val access by AppState.accessVersion.collectAsStateWithLifecycle()
    val load = rememberLoad(access) {
        coroutineScope {
            val t = async { Repo.titles() }
            val c = async { Repo.channels() }
            val camp = async { runCatching { Repo.activeCampaign() }.getOrNull() }
            HomeData(t.await(), c.await(), camp.await())
        }
    }
    var filter by remember { mutableStateOf("All") }

    when (val s = load.state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorState(s.message, load.reload)
        is Load.Ok -> {
            val all = s.data.titles.filterTier(filter)
            val hero = all.firstOrNull { it.featured } ?: all.maxByOrNull { it.viewCount }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "STREAMS",
                        color = Red,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                s.data.campaign?.let { c -> item { CampaignBanner(c) } }
                hero?.let { h -> item { Hero(h, nav) } }
                item {
                    ChipRow(listOf("All", "Free", "Premium"), filter, { filter = it }, Modifier.padding(vertical = 12.dp))
                }
                if (all.isEmpty()) {
                    item { EmptyState("Nothing here yet. New titles are on the way.") }
                }
                val trending = all.sortedByDescending { it.viewCount }.take(12)
                if (trending.isNotEmpty()) item { PosterRow("Trending on Streams", trending, nav) }
                s.data.channels.forEach { ch ->
                    val list = all.filter { it.channelId == ch.id }
                    if (list.isNotEmpty()) item(key = ch.id) {
                        PosterRow(ch.name, list, nav, onMore = { nav.navigate(Routes.channel(ch.id)) })
                    }
                }
                val loose = all.filter { t -> s.data.channels.none { it.id == t.channelId } }
                if (loose.isNotEmpty()) item { PosterRow("More to watch", loose, nav) }
            }
        }
    }
}

@Composable
private fun CampaignBanner(c: Campaign) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(12.dp),
    ) {
        Text(c.name, style = MaterialTheme.typography.titleSmall, color = Red)
        if (c.message.isNotBlank()) Text(c.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hero(t: Title, nav: NavController) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1),
    ) {
        imageUrl(t.coverPath)?.let {
            AsyncImage(it, t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Bg.copy(alpha = 0.95f))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            if (t.tier != Tier.FREE) TierBadge(t.tier)
            Text(
                t.name,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (t.description.isNotBlank()) {
                Text(t.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { nav.navigate(Routes.title(t.id)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play")
                }
                OutlinedButton(onClick = { nav.navigate(Routes.title(t.id)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Info, null); Spacer(Modifier.width(4.dp)); Text("Details")
                }
            }
        }
    }
}

@Composable
fun PosterRow(heading: String, titles: List<Title>, nav: NavController, onMore: (() -> Unit)? = null) {
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onMore != null) {
                androidx.compose.material3.IconButton(onClick = onMore) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "See all")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(titles, key = { it.id }) { t ->
                PosterCard(t, Modifier.width(112.dp)) { nav.navigate(Routes.title(t.id)) }
            }
        }
    }
}
