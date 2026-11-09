package com.hiresplayer.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.hiresplayer.library.Track
import com.hiresplayer.library.toMediaItem
import com.hiresplayer.playback.PlaybackInsight
import com.hiresplayer.playback.PlaybackInsights
import com.hiresplayer.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueEntry(val index: Int, val title: String, val artist: String, val playing: Boolean)

data class PlayerUiState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentIndex: Int = 0,
    val queue: List<QueueEntry> = emptyList(),
    val error: String? = null,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutable.asStateFlow()
    val insight: StateFlow<PlaybackInsight> = PlaybackInsights.state

    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null
    private val future = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    init {
        future.addListener(
            {
                val player = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = player
                player.addListener(listener)
                pending?.invoke()
                pending = null
                publish()
            },
            ContextCompat.getMainExecutor(app),
        )
        viewModelScope.launch {
            while (isActive) {
                publish()
                delay(if (controller?.isPlaying == true) 200 else 600)
            }
        }
    }

    fun play(tracks: List<Track>, index: Int) {
        fun start(player: MediaController) {
            if (tracks.isEmpty()) return
            val start = index.coerceIn(0, tracks.lastIndex)
            player.setMediaItems(tracks.map { it.toMediaItem() }, start, 0L)
            player.prepare()
            player.play()
        }
        val player = controller
        if (player == null) {
            pending = { controller?.let(::start) }
        } else {
            start(player)
        }
    }

    fun toggle() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun next() = controller?.seekToNext()
    fun previous() {
        val player = controller ?: return
        if (player.currentPosition > 3_000) player.seekTo(0) else player.seekToPrevious()
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playQueueIndex(index: Int) {
        controller?.seekTo(index, 0L)
    }

    fun removeQueueIndex(index: Int) {
        controller?.removeMediaItem(index)
    }

    private fun publish() {
        val player = controller ?: return
        val item = player.currentMediaItem
        val duration = player.duration
        mutable.value = PlayerUiState(
            connected = true,
            hasMedia = player.mediaItemCount > 0,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            album = item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
            artworkUri = item?.mediaMetadata?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0) duration else 0L,
            currentIndex = player.currentMediaItemIndex,
            queue = List(player.mediaItemCount) { index ->
                val queued = player.getMediaItemAt(index)
                QueueEntry(
                    index = index,
                    title = queued.mediaMetadata.title?.toString().orEmpty().ifBlank { "Track ${index + 1}" },
                    artist = queued.mediaMetadata.artist?.toString().orEmpty(),
                    playing = index == player.currentMediaItemIndex,
                )
            },
            error = player.playerError?.message,
        )
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        MediaController.releaseFuture(future)
        super.onCleared()
    }
}
