package com.hiresplayer.format

import java.util.Locale
import kotlin.math.roundToInt

enum class AudioCodec(val label: String) {
    FLAC("FLAC"),
    ALAC("ALAC"),
    WAV("WAV"),
    AIFF("AIFF"),
    MP3("MP3"),
    AAC("AAC"),
    VORBIS("Vorbis"),
    OPUS("Opus"),
    M4A("M4A"),
    OGG("Ogg"),
}

/**
 * What we know about a file. [probed] is false when the values come only from
 * the filename, which is not enough to claim Hi-Res or Lossless.
 */
data class AudioFormatInfo(
    val codec: AudioCodec,
    val lossless: Boolean,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrateBps: Int? = null,
    val channelCount: Int? = null,
    val probed: Boolean = false,
    /** True when the stored PCM is already IEEE float rather than integer. */
    val pcmIsFloat: Boolean = false,
    val codecLabel: String = codec.label,
)

enum class QualityTier(val label: String) {
    HI_RES("Hi-Res"),
    LOSSLESS("Lossless"),
    LOSSY("Lossy"),
}

/**
 * Hi-Res means lossless audio that is better than CD (16-bit / 44.1 kHz):
 * bit depth above 16 or sample rate above 44.1 kHz. Lossless CD-quality stays
 * "Lossless". Lossy codecs are never labeled Hi-Res.
 */
fun qualityTier(info: AudioFormatInfo): QualityTier? {
    if (!info.probed) return null
    if (!info.lossless) return QualityTier.LOSSY
    val aboveCd = (info.bitDepth != null && info.bitDepth > 16) ||
        (info.sampleRateHz != null && info.sampleRateHz > 44_100)
    return if (aboveCd) QualityTier.HI_RES else QualityTier.LOSSLESS
}

fun formatSampleRate(hz: Int): String = when {
    hz % 1000 == 0 -> "${hz / 1000} kHz"
    hz % 100 == 0 -> String.format(Locale.US, "%.1f kHz", hz / 1000.0)
    else -> String.format(Locale.US, "%.2f kHz", hz / 1000.0)
}

fun formatBitrate(bps: Int): String = "${(bps + 500) / 1000} kbps"

/** Display string such as "FLAC 24-bit / 96 kHz" or "MP3 320 kbps · 44.1 kHz". */
fun formatBadge(info: AudioFormatInfo): String {
    val depth = info.bitDepth?.let { "$it-bit" }
    val rate = info.sampleRateHz?.let(::formatSampleRate)
    val bitrate = info.bitrateBps?.let(::formatBitrate)
    return when {
        depth != null && rate != null -> "${info.codecLabel} $depth / $rate"
        bitrate != null && rate != null -> "${info.codecLabel} $bitrate · $rate"
        rate != null -> "${info.codecLabel} $rate"
        bitrate != null -> "${info.codecLabel} $bitrate"
        else -> info.codecLabel
    }
}

fun hintForName(name: String?): AudioFormatInfo? {
    val ext = name
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotEmpty() && it.length <= 5 }
        ?: return null
    return when (ext) {
        "flac" -> AudioFormatInfo(AudioCodec.FLAC, lossless = true)
        "wav", "wave" -> AudioFormatInfo(AudioCodec.WAV, lossless = true)
        "aiff", "aif", "aifc" -> AudioFormatInfo(AudioCodec.AIFF, lossless = true)
        "mp3" -> AudioFormatInfo(AudioCodec.MP3, lossless = false)
        "aac" -> AudioFormatInfo(AudioCodec.AAC, lossless = false)
        "m4a", "mp4" -> AudioFormatInfo(AudioCodec.M4A, lossless = false)
        "ogg", "oga" -> AudioFormatInfo(AudioCodec.OGG, lossless = false)
        "opus" -> AudioFormatInfo(AudioCodec.OPUS, lossless = false)
        else -> null
    }
}

internal fun estimatedBitrate(byteLength: Long, sampleRateHz: Int, totalSamples: Long): Int? {
    if (byteLength <= 0 || sampleRateHz <= 0 || totalSamples <= 0) return null
    val bps = byteLength.toDouble() * 8.0 * sampleRateHz / totalSamples.toDouble()
    if (bps <= 0 || bps > Int.MAX_VALUE) return null
    return bps.roundToInt()
}
