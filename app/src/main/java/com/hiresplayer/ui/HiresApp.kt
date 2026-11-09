package com.hiresplayer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hiresplayer.library.AlbumGroup
import com.hiresplayer.library.ArtistGroup
import com.hiresplayer.library.FolderGroup
import com.hiresplayer.library.Track
import com.hiresplayer.library.albums
import com.hiresplayer.library.artists
import com.hiresplayer.library.folders

private const val LibraryRoute = "library"
private const val YoutubeRoute = "youtube"
private const val NowRoute = "now"

@Composable
fun HiresApp() {
    val playerVm: PlayerViewModel = viewModel()
    val libraryVm: LibraryViewModel = viewModel()
    val ytmVm: YtmViewModel = viewModel()
    val nav = rememberNavController()
    val player by playerVm.state.collectAsState()
    val insight by playerVm.insight.collectAsState()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (player.hasMedia && route != NowRoute) {
                    MiniPlayer(player, onOpen = { nav.navigate(NowRoute) }, onToggle = playerVm::toggle)
                }
                BottomNav(nav)
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = LibraryRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable(LibraryRoute) {
                LibraryScreen(
                    libraryVm = libraryVm,
                    onPlay = { tracks, index ->
                        playerVm.play(tracks, index)
                        nav.navigate(NowRoute)
                    },
                )
            }
            composable(YoutubeRoute) { YoutubeMusicScreen(ytmVm) }
            composable(NowRoute) {
                NowPlayingScreen(
                    player = player,
                    insight = insight,
                    onBack = { nav.popBackStack() },
                    onToggle = playerVm::toggle,
                    onNext = playerVm::next,
                    onPrevious = playerVm::previous,
                    onSeek = playerVm::seekTo,
                    onShuffle = playerVm::toggleShuffle,
                    onRepeat = playerVm::cycleRepeat,
                    onPlayIndex = playerVm::playQueueIndex,
                    onRemove = playerVm::removeQueueIndex,
                )
            }
        }
    }
}

@Composable
private fun BottomNav(nav: NavHostController) {
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBarItem(
            selected = route == LibraryRoute || route == NowRoute,
            onClick = {
                nav.navigate(LibraryRoute) {
                    popUpTo(LibraryRoute) { inclusive = true }
                    launchSingleTop = true
                }
            },
            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = route == YoutubeRoute,
            onClick = {
                nav.navigate(YoutubeRoute) { launchSingleTop = true }
            },
            icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
            label = { Text("YouTube Music") },
        )
    }
}

@Composable
private fun MiniPlayer(player: PlayerUiState, onOpen: () -> Unit, onToggle: () -> Unit) {
    val fraction = if (player.durationMs > 0) {
        (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen),
    ) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtThumb(player.artworkUri, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(player.title.ifBlank { "Now playing" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    player.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (player.isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen(libraryVm: LibraryViewModel, onPlay: (List<Track>, Int) -> Unit) {
    val state by libraryVm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var album by remember { mutableStateOf<AlbumGroup?>(null) }
    var artist by remember { mutableStateOf<ArtistGroup?>(null) }
    var folder by remember { mutableStateOf<FolderGroup?>(null) }
    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { libraryVm.refresh() }
    val folderLauncher = rememberLauncherForActivityResult(OpenMusicTree()) { uri ->
        if (uri != null) libraryVm.addFolder(uri)
    }
    LaunchedEffect(Unit) {
        if (!state.permissionGranted) permissionLauncher.launch(permissions)
    }
    val filtered = remember(state.tracks, query) {
        val q = query.trim()
        if (q.isEmpty()) state.tracks
        else state.tracks.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) ||
                it.album.contains(q, true) || it.folderPath.contains(q, true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hires", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Local lossless library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { folderLauncher.launch(null) }) {
                Icon(Icons.Filled.Folder, contentDescription = "Add a folder")
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                album = null
                artist = null
                folder = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search songs, albums, artists") },
        )
        if (!state.permissionGranted) {
            InfoCard(
                title = "Music access",
                body = "Allow audio access to list songs MediaStore already knows about. You can also add a folder without that permission.",
                action = "Allow access",
                onAction = { permissionLauncher.launch(permissions) },
            )
        }
        val drill = album ?: artist ?: folder
        if (drill != null) {
            val (title, tracks) = when (drill) {
                is AlbumGroup -> drill.title to drill.tracks
                is ArtistGroup -> drill.name to drill.tracks
                is FolderGroup -> drill.name to drill.tracks
                else -> "" to emptyList()
            }
            DetailHeader(title, onBack = {
                album = null
                artist = null
                folder = null
            })
            TrackList(tracks, onPlay)
        } else {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Songs", "Albums", "Artists", "Folders").forEachIndexed { index, label ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
                }
            }
            when {
                state.loading && filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                filtered.isEmpty() -> EmptyLibrary(state.folderCount > 0)
                tab == 1 -> GroupList(filtered.albums().map { it.title to it.artist }) { index ->
                    album = filtered.albums()[index]
                }
                tab == 2 -> GroupList(filtered.artists().map { it.name to "${it.tracks.size} songs" }) { index ->
                    artist = filtered.artists()[index]
                }
                tab == 3 -> GroupList(filtered.folders().map { it.name to it.path }) { index ->
                    folder = filtered.folders()[index]
                }
                else -> TrackList(filtered, onPlay)
            }
        }
    }
}

@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackList(tracks: List<Track>, onPlay: (List<Track>, Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(tracks, index) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtThumb(track.artworkUri, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    formatClock(track.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupList(rows: List<Pair<String, String>>, onClick: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(rows.size) { index ->
            val row = rows[index]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(index) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(row.first, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    row.second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(hasFolder: Boolean) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nothing to play yet", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasFolder) {
                "That folder did not contain FLAC, ALAC, WAV, AIFF, MP3, AAC, or Ogg files."
            } else {
                "Grant music access or add a folder of lossless files. Playback, including hi-res files, stays in this app."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ArtThumb(uri: String?, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun InfoCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.Button(onClick = onAction) { Text(action) }
    }
}

internal fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private class OpenMusicTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent {
        return super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }
}
