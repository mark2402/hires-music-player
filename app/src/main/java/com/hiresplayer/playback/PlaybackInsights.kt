package com.hiresplayer.playback

import com.hiresplayer.format.AudioFormatInfo
import com.hiresplayer.format.OutputPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackInsight(
    val format: AudioFormatInfo? = null,
    val plan: OutputPlan? = null,
    val decoderName: String? = null,
)

/** Process-local playback facts published by [PlaybackService] for the UI. */
object PlaybackInsights {
    private val mutable = MutableStateFlow(PlaybackInsight())
    val state: StateFlow<PlaybackInsight> = mutable.asStateFlow()

    fun publish(format: AudioFormatInfo?, plan: OutputPlan?, decoderName: String?) {
        mutable.value = PlaybackInsight(format, plan, decoderName)
    }
}
