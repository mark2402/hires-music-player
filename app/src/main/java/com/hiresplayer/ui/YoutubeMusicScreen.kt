package com.hiresplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hiresplayer.youtube.YtmTrack

@Composable
fun YoutubeMusicScreen(viewModel: YtmViewModel) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onVisible()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("YouTube Music", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Companion controls only. YouTube Music has no public streaming API, so this app does not play, download, or extract its audio.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
        ) {
            Text("Lossy · plays in YouTube Music", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Whatever is shown here is a lossy stream inside the YouTube Music app. Local FLAC, ALAC, WAV, and AIFF files play in Hires, on the Library tab.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!state.appInstalled) {
            InfoCard(
                title = "YouTube Music is not installed",
                body = "Open and search will take you to the Play Store listing for com.google.android.apps.youtube.music.",
                action = "Open Play Store",
                onAction = viewModel::openApp,
            )
        }
        if (!state.permissionGranted) {
            InfoCard(
                title = "Notification access",
                body = "Android only exposes another app's playback session to a notification listener. Hires uses that access to find the YouTube Music session and to send play, pause, skip, and seek. It does not store or show notifications from other apps. The system permission itself can see notifications; grant it only if you want these controls.",
                action = "Grant notification access",
                onAction = viewModel::openSettings,
            )
        } else {
            SessionCard(state.track, state.positionMs, viewModel)
        }
        OutlinedButton(onClick = viewModel::openApp, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.appInstalled) "Open in YouTube Music" else "Open YouTube Music in the Play Store")
        }
        Text("Search YouTube Music", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Artist, album, or song") },
        )
        Button(
            onClick = { viewModel.search(query) },
            enabled = query.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.appInstalled) "Search in YouTube Music" else "Search requires the app — open Play Store")
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SessionCard(track: YtmTrack?, positionMs: Long, viewModel: YtmViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        if (track == null) {
            Text("Nothing is playing in YouTube Music.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Start something in YouTube Music and it will show up here. The audio still plays there.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = track.artwork
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (track.album.isNotBlank()) {
                    Text(track.album, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
        if (track.durationMs > 0) {
            var dragging by remember(track.title) { mutableStateOf(false) }
            var drag by remember(track.title) { mutableFloatStateOf(positionMs.toFloat()) }
            val shown = if (dragging) drag else positionMs.toFloat()
            Slider(
                value = shown.coerceIn(0f, track.durationMs.toFloat()),
                onValueChange = {
                    dragging = true
                    drag = it
                },
                onValueChangeFinished = {
                    if (track.canSeek) viewModel.seekTo(drag.toLong())
                    dragging = false
                },
                enabled = track.canSeek,
                valueRange = 0f..track.durationMs.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(shown.toLong()), style = MaterialTheme.typography.labelMedium)
                Text(formatClock(track.durationMs), style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = viewModel::previous, enabled = track.canPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous in YouTube Music")
            }
            IconButton(onClick = viewModel::toggle, enabled = track.canPlay || track.canPause) {
                Icon(
                    if (track.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (track.playing) "Pause YouTube Music" else "Play YouTube Music",
                )
            }
            IconButton(onClick = viewModel::next, enabled = track.canNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next in YouTube Music")
            }
        }
    }
}
