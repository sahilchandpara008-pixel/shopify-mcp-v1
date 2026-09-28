package com.streams.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streams.app.data.Tier
import com.streams.app.data.Title
import com.streams.app.data.imageUrl
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface2
import com.streams.app.ui.theme.TextMuted

@Composable
fun TierBadge(tier: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val (bg, fg) = when (tier) {
        Tier.FREE -> Color(0xFF14532D) to Color(0xFF86EFAC)
        Tier.PREMIUM -> Red to Color.White
        else -> Color(0xFFFBBF24) to Color.Black
    }
    Text(
        Tier.label(tier).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Poster that stretches to whatever width its parent gives it (fluid). */
@Composable
fun PosterCard(title: Title, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2),
        ) {
            val url = imageUrl(title.coverPath)
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Movie, null, tint = TextMuted, modifier = Modifier.align(Alignment.Center).size(32.dp))
            }
            if (title.tier != Tier.FREE) {
                TierBadge(title.tier, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }
        Text(
            title.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun ChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    sidePadding: Dp = 16.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = sidePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(o) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Red,
                    selectedLabelColor = Color.White,
                ),
            )
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Red) }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
fun LockedNote(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

val ScreenPadding = PaddingValues(horizontal = 16.dp)

fun formatDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun formatDate(iso: String): String = runCatching {
    val instant = java.time.OffsetDateTime.parse(iso)
    instant.atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))
}.getOrDefault(iso.take(10))

fun formatPrice(amount: Double?): String =
    if (amount == null) "" else "₹" + (if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(amount))
