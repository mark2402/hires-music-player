package com.hiresplayer.format

/**
 * Android AudioFormat encoding values. Kept as plain ints so this module
 * stays free of the Android SDK.
 */
object AndroidPcm {
    const val PCM_16BIT = 2
    const val PCM_FLOAT = 4
    const val PCM_24BIT_PACKED = 21
    const val PCM_32BIT = 22

    fun fromAndroid(value: Int): PcmEncoding? = when (value) {
        PCM_16BIT -> PcmEncoding.PCM_16BIT
        PCM_FLOAT -> PcmEncoding.PCM_FLOAT
        PCM_24BIT_PACKED -> PcmEncoding.PCM_24BIT_PACKED
        PCM_32BIT -> PcmEncoding.PCM_32BIT
        else -> null
    }
}

enum class PcmEncoding { PCM_16BIT, PCM_FLOAT, PCM_24BIT_PACKED, PCM_32BIT }

data class MixerFormat(
    val encoding: PcmEncoding,
    val sampleRateHz: Int,
    val channelCount: Int,
)

/**
 * What the device told us. Null direct-playback flags mean the API is too old
 * to answer (below Android 10), not that playback failed.
 */
data class DeviceCapabilities(
    val floatDirect: Boolean? = null,
    val pcm16Direct: Boolean? = null,
    val bitPerfectMixers: List<MixerFormat> = emptyList(),
)

data class OutputPlan(
    val encoding: PcmEncoding,
    val sampleRateHz: Int?,
    val resampled: Boolean,
    val rateVerified: Boolean,
    val depthReduced: Boolean,
    val bitPerfectMixer: MixerFormat?,
    val claimsBitPerfect: Boolean,
    val headline: String,
    val detail: String,
)

/**
 * True when the decoder path keeps samples above 16-bit. WAV and AIFF are
 * already PCM. FLAC and ALAC keep their depth only on the FFmpeg extension
 * (or libFLAC); the platform MediaCodec decoder often emits 16-bit.
 */
fun softwareDecoderPreservesBitDepth(
    codec: AudioCodec,
    decoderName: String?,
    ffmpegAvailable: Boolean,
): Boolean {
    if (codec == AudioCodec.WAV || codec == AudioCodec.AIFF) return true
    val name = decoderName?.lowercase()
    if (name != null) {
        if ("ffmpeg" in name || "libflac" in name) return true
        if (codec == AudioCodec.FLAC || codec == AudioCodec.ALAC) return false
        return true
    }
    return when (codec) {
        AudioCodec.FLAC, AudioCodec.ALAC -> ffmpegAvailable
        else -> true
    }
}

/**
 * Predicts the PCM this app will actually write.
 *
 * FFmpeg's audio renderer emits 32-bit float when the sink can play float
 * directly, including for 16-bit FLAC/ALAC. WAV and AIFF stay 16-bit unless
 * the file itself is high-resolution integer or float PCM, in which case
 * ExoPlayer's float output converts 24-bit integer to float32 (exact for
 * 24-bit). Platform decoders that discard bits above 16 are not described
 * as float.
 */
fun emitsFloat(
    source: AudioFormatInfo?,
    preservesBitDepth: Boolean,
    ffmpegAvailable: Boolean,
    floatDirect: Boolean?,
): Boolean {
    if (source == null || floatDirect == false) return false
    val depth = source.bitDepth ?: 16
    return when (source.codec) {
        AudioCodec.FLAC, AudioCodec.ALAC -> preservesBitDepth && ffmpegAvailable
        AudioCodec.WAV, AudioCodec.AIFF -> preservesBitDepth && (depth > 16 || source.pcmIsFloat)
        else -> false
    }
}

fun planOutput(
    source: AudioFormatInfo?,
    device: DeviceCapabilities,
    preservesBitDepth: Boolean,
    ffmpegAvailable: Boolean,
    bitPerfectConfirmed: Boolean = false,
): OutputPlan {
    val floatOut = emitsFloat(source, preservesBitDepth, ffmpegAvailable, device.floatDirect)
    val encoding = if (floatOut) PcmEncoding.PCM_FLOAT else PcmEncoding.PCM_16BIT
    val depth = source?.bitDepth
    val depthReduced = source != null && depth != null && depth > 16 && !floatOut && !source.pcmIsFloat
    val direct = if (floatOut) device.floatDirect else device.pcm16Direct
    val candidate = selectBitPerfectMixer(source, device, encoding, depthReduced)
    val resampled = candidate == null && direct == false
    val rateVerified = candidate != null || direct != null
    val claims = bitPerfectConfirmed && candidate != null
    return present(
        encoding = encoding,
        sampleRateHz = source?.sampleRateHz,
        resampled = resampled,
        rateVerified = rateVerified,
        depthReduced = depthReduced,
        mixer = candidate,
        claimsBitPerfect = claims,
    )
}

fun OutputPlan.withBitPerfect(confirmed: Boolean): OutputPlan = present(
    encoding = encoding,
    sampleRateHz = sampleRateHz,
    resampled = resampled,
    rateVerified = rateVerified,
    depthReduced = depthReduced,
    mixer = bitPerfectMixer,
    claimsBitPerfect = confirmed && bitPerfectMixer != null && !resampled && !depthReduced,
)

/**
 * Corrects the prediction once ExoPlayer reports the AudioTrack it opened.
 * A sample-rate change means Android resampled, so bit-perfect is dropped.
 */
fun reconcileOutput(
    plan: OutputPlan,
    source: AudioFormatInfo?,
    actualEncoding: Int,
    actualSampleRate: Int,
): OutputPlan {
    val encoding = AndroidPcm.fromAndroid(actualEncoding) ?: plan.encoding
    val rateMismatch = source?.sampleRateHz != null &&
        actualSampleRate > 0 &&
        actualSampleRate != source.sampleRateHz
    val mixerStillMatches = plan.bitPerfectMixer != null &&
        !rateMismatch &&
        plan.bitPerfectMixer.encoding == encoding &&
        plan.bitPerfectMixer.sampleRateHz == actualSampleRate
    val depth = source?.bitDepth
    val depthReduced = when {
        source == null -> plan.depthReduced
        depth != null && depth > 16 && encoding == PcmEncoding.PCM_16BIT && !source.pcmIsFloat -> true
        else -> false
    }
    return present(
        encoding = encoding,
        sampleRateHz = actualSampleRate.takeIf { it > 0 } ?: plan.sampleRateHz,
        resampled = rateMismatch || (plan.resampled && !mixerStillMatches),
        rateVerified = true,
        depthReduced = depthReduced,
        mixer = plan.bitPerfectMixer.takeIf { mixerStillMatches },
        claimsBitPerfect = plan.claimsBitPerfect && mixerStillMatches && !depthReduced,
    )
}

private fun selectBitPerfectMixer(
    source: AudioFormatInfo?,
    device: DeviceCapabilities,
    encoding: PcmEncoding,
    depthReduced: Boolean,
): MixerFormat? {
    if (source == null || !source.lossless || !source.probed || depthReduced) return null
    val rate = source.sampleRateHz ?: return null
    val channels = source.channelCount ?: return null
    val depth = source.bitDepth
    val exact = when (encoding) {
        PcmEncoding.PCM_FLOAT -> source.pcmIsFloat || (depth != null && depth <= 24)
        PcmEncoding.PCM_16BIT -> depth == null || depth <= 16
        PcmEncoding.PCM_24BIT_PACKED -> depth == 24 || depth == 20
        PcmEncoding.PCM_32BIT -> depth == 32 && !source.pcmIsFloat
    }
    if (!exact) return null
    return device.bitPerfectMixers.firstOrNull {
        it.encoding == encoding && it.sampleRateHz == rate && it.channelCount == channels
    }
}

private fun present(
    encoding: PcmEncoding,
    sampleRateHz: Int?,
    resampled: Boolean,
    rateVerified: Boolean,
    depthReduced: Boolean,
    mixer: MixerFormat?,
    claimsBitPerfect: Boolean,
): OutputPlan {
    val rateText = sampleRateHz?.let { " · ${formatSampleRate(it)}" }.orEmpty()
    val headline = when {
        claimsBitPerfect && encoding == PcmEncoding.PCM_FLOAT -> "Bit-perfect USB · 32-bit float"
        claimsBitPerfect && encoding == PcmEncoding.PCM_16BIT -> "Bit-perfect USB · 16-bit PCM"
        claimsBitPerfect && encoding == PcmEncoding.PCM_24BIT_PACKED -> "Bit-perfect USB · 24-bit PCM"
        encoding == PcmEncoding.PCM_FLOAT && resampled -> "32-bit float, resampled"
        encoding == PcmEncoding.PCM_FLOAT -> "32-bit float$rateText"
        depthReduced && resampled -> "16-bit PCM, resampled"
        depthReduced -> "16-bit PCM"
        resampled -> "16-bit PCM, resampled"
        else -> "16-bit PCM$rateText"
    }
    val detail = when {
        claimsBitPerfect ->
            "Android accepted a bit-perfect USB mixer that matches this stream. " +
                "This app does not resample it or reduce its bit depth. " +
                "The phone's audio HAL still has to honor that request."
        depthReduced ->
            "The decoder feeding this track is delivering 16-bit PCM, so bit depth above 16-bit is not preserved."
        encoding == PcmEncoding.PCM_FLOAT && resampled ->
            "High-resolution samples are converted to 32-bit float, and this device does not advertise the file's sample rate, so Android may resample."
        encoding == PcmEncoding.PCM_FLOAT ->
            "High-resolution samples are sent as 32-bit float. 24-bit integers fit exactly in float32. " +
                "This is not integer bit-perfect unless a matching USB mixer is active."
        resampled ->
            "This device does not advertise direct playback at the file sample rate, so Android may resample."
        !rateVerified ->
            "Sample-rate support could not be queried on this Android version. Playback uses the file rate when the device allows it."
        else ->
            "Playing 16-bit PCM through the device mixer at the file sample rate when the hardware supports it."
    }
    return OutputPlan(
        encoding = encoding,
        sampleRateHz = sampleRateHz,
        resampled = resampled,
        rateVerified = rateVerified,
        depthReduced = depthReduced,
        bitPerfectMixer = mixer,
        claimsBitPerfect = claimsBitPerfect,
        headline = headline,
        detail = detail,
    )
}
