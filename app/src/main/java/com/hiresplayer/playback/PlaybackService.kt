package com.hiresplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hiresplayer.MainActivity
import com.hiresplayer.R
import com.hiresplayer.format.AudioFormatInfo
import com.hiresplayer.format.hintForName
import com.hiresplayer.format.reconcileOutput
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var output: HiResOutput
    private var source: AudioFormatInfo? = null
    private var decoderName: String? = null
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        output = HiResOutput(this)
        val provider = DefaultMediaNotificationProvider(this)
        provider.setSmallIcon(R.drawable.ic_stat_music)
        setMediaNotificationProvider(provider)

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
                    .setEnableAudioFloatOutput(true),
            )
            .setAudioAttributes(output.exoAudioAttributes(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                decoderName = null
                inspect(player)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                this@PlaybackService.decoderName = decoderName
                publish(output.apply(source, decoderName))
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
            ) {
                val current = PlaybackInsights.state.value.plan ?: return
                val reconciled = reconcileOutput(
                    plan = current,
                    source = source,
                    actualEncoding = audioTrackConfig.encoding,
                    actualSampleRate = audioTrackConfig.sampleRate,
                )
                publish(reconciled)
            }
        })
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        output.registerDeviceCallback { publish(output.apply(source, decoderName)) }
    }

    private fun inspect(player: Player) {
        val item = player.currentMediaItem
        val uri = item?.localConfiguration?.uri
        val name = item?.mediaMetadata?.title?.toString() ?: uri?.lastPathSegment
        val token = ++generation
        if (uri == null) {
            source = null
            publish(output.apply(null, null))
            return
        }
        scope.launch {
            val probed = withContext(Dispatchers.IO) { probe(uri, name) }
            if (token != generation) return@launch
            source = probed
            publish(output.apply(probed, decoderName))
        }
    }

    private fun probe(uri: Uri, name: String?): AudioFormatInfo? {
        val fromFile = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    com.hiresplayer.format.AudioFormatProbe.probe(
                        ChannelBinarySource(stream.channel),
                        name ?: uri.lastPathSegment,
                    )
                }
            }
        }.getOrNull()
        return fromFile ?: hintForName(name ?: uri.lastPathSegment)
    }

    private fun publish(plan: com.hiresplayer.format.OutputPlan) {
        PlaybackInsights.publish(source, plan, decoderName)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
