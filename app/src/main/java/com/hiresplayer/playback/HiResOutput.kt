package com.hiresplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import com.hiresplayer.format.AndroidPcm
import com.hiresplayer.format.AudioFormatInfo
import com.hiresplayer.format.DeviceCapabilities
import com.hiresplayer.format.MixerFormat
import com.hiresplayer.format.OutputPlan
import com.hiresplayer.format.planOutput
import com.hiresplayer.format.softwareDecoderPreservesBitDepth
import com.hiresplayer.format.withBitPerfect

/**
 * Asks Android 14+ for a USB mixer that matches the PCM this app will write,
 * and otherwise reports float or 16-bit output without calling it bit-perfect.
 */
class HiResOutput(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val frameworkAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var activeDevice: AudioDeviceInfo? = null

    fun exoAudioAttributes(): androidx.media3.common.AudioAttributes =
        androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

    fun registerDeviceCallback(onChange: () -> Unit) {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChange()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChange()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun ffmpegAvailable(): Boolean = try {
        val library = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
        library.getMethod("isAvailable").invoke(null) as Boolean
    } catch (_: Throwable) {
        false
    }

    fun apply(source: AudioFormatInfo?, decoderName: String?): OutputPlan {
        val ffmpeg = ffmpegAvailable()
        val preserves = source?.let {
            softwareDecoderPreservesBitDepth(it.codec, decoderName, ffmpeg)
        } ?: false
        val mixers = usbBitPerfectMixers()
        val rate = source?.sampleRateHz ?: 0
        val channels = source?.channelCount ?: 2
        val caps = DeviceCapabilities(
            floatDirect = direct(AndroidPcm.PCM_FLOAT, rate, channels),
            pcm16Direct = direct(AndroidPcm.PCM_16BIT, rate, channels),
            bitPerfectMixers = mixers.map { it.format },
        )
        val draft = planOutput(
            source = source,
            device = caps,
            preservesBitDepth = preserves,
            ffmpegAvailable = ffmpeg,
        )
        clearActive()
        val match = draft.bitPerfectMixer?.let { wanted ->
            mixers.firstOrNull { it.format == wanted }
        }
        val confirmed = if (match != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val applied = audioManager.setPreferredMixerAttributes(
                frameworkAttributes,
                match.device,
                match.attributes,
            )
            if (applied) activeDevice = match.device
            applied
        } else {
            false
        }
        return draft.withBitPerfect(confirmed)
    }

    private fun clearActive() {
        val device = activeDevice ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            audioManager.clearPreferredMixerAttributes(frameworkAttributes, device)
        }
        activeDevice = null
    }

    private fun direct(encoding: Int, sampleRate: Int, channels: Int): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || sampleRate <= 0) return null
        if (channels !in 1..2) return null
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(mask)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.getDirectPlaybackSupport(format, frameworkAttributes) !=
                AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
        } else {
            @Suppress("DEPRECATION")
            AudioTrack.isDirectPlaybackSupported(format, frameworkAttributes)
        }
    }

    private fun usbBitPerfectMixers(): List<UsbMixer> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return emptyList()
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return devices.flatMap { device ->
            audioManager.getSupportedMixerAttributes(device).mapNotNull { mixer ->
                if (mixer.mixerBehavior != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) return@mapNotNull null
                val encoding = AndroidPcm.fromAndroid(mixer.format.encoding) ?: return@mapNotNull null
                val channels = mixer.format.channelCount
                if (channels <= 0 || mixer.format.sampleRate <= 0) return@mapNotNull null
                UsbMixer(
                    device = device,
                    attributes = mixer,
                    format = MixerFormat(encoding, mixer.format.sampleRate, channels),
                )
            }
        }
    }

    private data class UsbMixer(
        val device: AudioDeviceInfo,
        val attributes: AudioMixerAttributes,
        val format: MixerFormat,
    )
}
