package com.hiresplayer.youtube

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.hiresplayer.format.YoutubeMusicLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Token Android requires before an app may call [MediaSessionManager.getActiveSessions].
 * This service does not read notification text, titles, or other apps. Playback
 * controls go through the YouTube Music media session only.
 */
class YtmNotificationListener : NotificationListenerService()

data class YtmTrack(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: android.graphics.Bitmap?,
    val durationMs: Long,
    val playing: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canNext: Boolean,
    val canPrevious: Boolean,
    val canSeek: Boolean,
)

data class YtmUiState(
    val permissionGranted: Boolean = false,
    val appInstalled: Boolean = false,
    val track: YtmTrack? = null,
    val positionMs: Long = 0L,
)

class YtmCompanion(private val context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(appContext, YtmNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(YtmUiState())
    val state: StateFlow<YtmUiState> = mutable.asStateFlow()

    private var controller: MediaController? = null
    private var listening = false
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refreshFrom(it) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            publish()
        }
    }

    fun refreshPermission() {
        val granted = isListenerEnabled()
        if (granted && !listening) {
            runCatching {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                listening = true
            }
        }
        if (!granted && listening) {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            listening = false
            detach()
        }
        if (granted) {
            val sessions = runCatching { manager.getActiveSessions(component) }.getOrNull()
            refreshFrom(sessions)
        } else {
            mutable.value = mutable.value.copy(
                permissionGranted = false,
                appInstalled = isInstalled(),
                track = null,
                positionMs = 0L,
            )
        }
    }

    fun tickPosition() {
        val playback = controller?.playbackState ?: return
        val position = positionOf(playback)
        if (position != mutable.value.positionMs) {
            mutable.value = mutable.value.copy(positionMs = position)
        }
    }

    fun toggle() {
        val controls = controller?.transportControls ?: return
        val state = controller?.playbackState ?: return
        if (state.state == PlaybackState.STATE_PLAYING) {
            if (has(state, PlaybackState.ACTION_PAUSE)) controls.pause()
        } else if (has(state, PlaybackState.ACTION_PLAY)) {
            controls.play()
        }
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seekTo(positionMs: Long) = controller?.transportControls?.seekTo(positionMs)

    fun openApp() = YoutubeMusicIntents.openApp(appContext)
    fun search(query: String) = YoutubeMusicIntents.search(appContext, query)
    fun openListenerSettings() = YoutubeMusicIntents.openListenerSettings(appContext)
    fun openAppInfo() = YoutubeMusicIntents.openAppInfo(appContext)

    fun release() {
        if (listening) {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            listening = false
        }
        detach()
    }

    private fun refreshFrom(sessions: List<MediaController>?) {
        val match = sessions?.firstOrNull { it.packageName == YoutubeMusicLinks.PACKAGE_NAME }
        if (match?.sessionToken != controller?.sessionToken) {
            detach()
            if (match != null) {
                controller = match
                match.registerCallback(callback, handler)
            }
        }
        publish()
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun publish() {
        val current = controller
        val metadata = current?.metadata
        val playback = current?.playbackState
        val track = if (current == null || metadata == null && playback == null) {
            null
        } else {
            YtmTrack(
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "YouTube Music" },
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().ifBlank { "YouTube Music" },
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.description?.iconBitmap,
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L,
                playing = playback?.state == PlaybackState.STATE_PLAYING ||
                    playback?.state == PlaybackState.STATE_BUFFERING,
                canPlay = playback != null && has(playback, PlaybackState.ACTION_PLAY),
                canPause = playback != null && has(playback, PlaybackState.ACTION_PAUSE),
                canNext = playback != null && has(playback, PlaybackState.ACTION_SKIP_TO_NEXT),
                canPrevious = playback != null && has(playback, PlaybackState.ACTION_SKIP_TO_PREVIOUS),
                canSeek = playback != null && has(playback, PlaybackState.ACTION_SEEK_TO),
            )
        }
        mutable.value = YtmUiState(
            permissionGranted = isListenerEnabled(),
            appInstalled = isInstalled(),
            track = track,
            positionMs = playback?.let(::positionOf) ?: 0L,
        )
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it) == component }
    }

    private fun isInstalled(): Boolean = try {
        if (BuildIsTiramisu) {
            appContext.packageManager.getPackageInfo(
                YoutubeMusicLinks.PACKAGE_NAME,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(YoutubeMusicLinks.PACKAGE_NAME, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun positionOf(state: PlaybackState): Long {
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
            position += elapsed
        }
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return if (duration > 0) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
    }

    private fun has(state: PlaybackState, action: Long): Boolean = state.actions and action != 0L

    private companion object {
        val BuildIsTiramisu = android.os.Build.VERSION.SDK_INT >= 33
    }
}

object YoutubeMusicIntents {
    fun openListenerSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAppInfo(context: Context) {
        val uri = Uri.fromParts("package", context.packageName, null)
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(YoutubeMusicLinks.PACKAGE_NAME)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            openPlayStore(context)
        }
    }

    fun search(context: Context, query: String) {
        if (query.isBlank()) return
        if (context.packageManager.getLaunchIntentForPackage(YoutubeMusicLinks.PACKAGE_NAME) == null) {
            openPlayStore(context)
            return
        }
        val uri = Uri.parse(YoutubeMusicLinks.searchUrl(query))
        val targeted = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(YoutubeMusicLinks.PACKAGE_NAME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(targeted)
        } catch (_: ActivityNotFoundException) {
            val generic = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(generic)
            } catch (_: ActivityNotFoundException) {
                openPlayStore(context)
            }
        }
    }

    fun openPlayStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(YoutubeMusicLinks.playStoreMarketUrl()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(YoutubeMusicLinks.playStoreWebUrl()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
