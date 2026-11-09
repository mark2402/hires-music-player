package com.hiresplayer.format

import kotlin.math.roundToInt

object AudioFormatProbe {
    fun probe(source: BinarySource, displayName: String? = null): AudioFormatInfo? {
        if (source.length <= 0) return hintForName(displayName)
        val start = id3Skip(source)
        val magic = source.readAt(start, 12)
        val probed = when {
            magic.size >= 4 && magic.ascii(0, 4) == "fLaC" -> probeFlac(source, start)
            magic.size >= 12 && isWave(magic) -> probeWav(source, start)
            magic.size >= 12 && magic.ascii(0, 4) == "FORM" -> probeAiff(source, start)
            magic.size >= 4 && magic.ascii(0, 4) == "OggS" -> probeOgg(source, start)
            isMp4(source, start) -> probeMp4(source, start)
            else -> probeMp3(source, start)
        }
        return probed ?: hintForName(displayName)
    }

    fun probe(bytes: ByteArray, displayName: String? = null): AudioFormatInfo? =
        probe(ByteArraySource(bytes), displayName)
}

private fun id3Skip(source: BinarySource): Long {
    val head = source.readAt(0, 10)
    if (head.size < 10 || head.ascii(0, 3) != "ID3") return 0L
    val size = synchsafe(head, 6)
    val start = 10L + size
    return if (start in 1 until source.length) start else 0L
}

private fun synchsafe(bytes: ByteArray, offset: Int): Int {
    if (offset + 4 > bytes.size) return 0
    return (bytes.u8(offset) and 0x7F shl 21) or
        (bytes.u8(offset + 1) and 0x7F shl 14) or
        (bytes.u8(offset + 2) and 0x7F shl 7) or
        (bytes.u8(offset + 3) and 0x7F)
}

private fun isWave(magic: ByteArray): Boolean {
    val tag = magic.ascii(0, 4)
    return (tag == "RIFF" || tag == "RF64" || tag == "BW64") && magic.ascii(8, 4) == "WAVE"
}

private fun probeFlac(source: BinarySource, start: Long): AudioFormatInfo? {
    val marker = source.readAt(start, 4)
    if (marker.size < 4 || marker.ascii(0, 4) != "fLaC") return null
    val header = source.readAt(start + 4, 4)
    if (header.size < 4) return null
    val blockType = header.u8(0) and 0x7F
    val blockSize = header.u24be(1)
    if (blockType != 0 || blockSize < 34) return null
    val info = source.readAt(start + 8, 34)
    if (info.size < 34) return null
    val sampleRate = takeBits(info, 10 * 8, 20).toInt()
    val channels = takeBits(info, 10 * 8 + 20, 3).toInt() + 1
    val bitDepth = takeBits(info, 10 * 8 + 23, 5).toInt() + 1
    val samples = takeBits(info, 10 * 8 + 28, 36)
    if (sampleRate !in 1..384_000 || channels !in 1..8 || bitDepth !in 4..32) return null
    return AudioFormatInfo(
        codec = AudioCodec.FLAC,
        lossless = true,
        bitDepth = bitDepth,
        sampleRateHz = sampleRate,
        bitrateBps = estimatedBitrate(source.length, sampleRate, samples),
        channelCount = channels,
        probed = true,
    )
}

private fun takeBits(data: ByteArray, bitOffset: Int, count: Int): Long {
    var value = 0L
    for (i in 0 until count) {
        val pos = bitOffset + i
        val byteIndex = pos / 8
        if (byteIndex >= data.size) return value
        val bit = (data.u8(byteIndex) shr (7 - (pos % 8))) and 1
        value = (value shl 1) or bit.toLong()
    }
    return value
}

private fun probeWav(source: BinarySource, start: Long): AudioFormatInfo? {
    var offset = start + 12
    val limit = minOf(source.length, start + 1_000_000)
    var audioFormat = -1
    var channels = 0
    var sampleRate = 0
    var byteRate = 0
    var bits = 0
    while (offset + 8 <= limit) {
        val header = source.readAt(offset, 8)
        if (header.size < 8) break
        val id = header.ascii(0, 4)
        val size = header.u32le(4)
        if (id == "fmt ") {
            val fmt = source.readAt(offset + 8, minOf(size, 64L).toInt())
            if (fmt.size >= 16) {
                audioFormat = fmt.u16le(0)
                channels = fmt.u16le(2)
                sampleRate = fmt.u32le(4).toInt()
                byteRate = fmt.u32le(8).toInt()
                bits = fmt.u16le(14)
                if (audioFormat == 0xFFFE && fmt.size >= 40) {
                    val validBits = fmt.u16le(18)
                    if (validBits in 1..64) bits = validBits
                    audioFormat = fmt.u16le(24)
                }
            }
            break
        }
        val step = 8 + size + (size and 1L)
        if (step <= 0) break
        offset += step
    }
    if (sampleRate <= 0 || channels <= 0) return null
    val isFloat = audioFormat == 3
    val lossless = audioFormat == 1 || audioFormat == 3
    val label = when (audioFormat) {
        6 -> "WAV A-law"
        7 -> "WAV µ-law"
        else -> "WAV"
    }
    return AudioFormatInfo(
        codec = AudioCodec.WAV,
        lossless = lossless,
        bitDepth = bits.takeIf { it > 0 },
        sampleRateHz = sampleRate,
        bitrateBps = byteRate.takeIf { it > 0 }?.times(8),
        channelCount = channels,
        probed = true,
        pcmIsFloat = isFloat,
        codecLabel = label,
    )
}

private fun probeAiff(source: BinarySource, start: Long): AudioFormatInfo? {
    val form = source.readAt(start, 12)
    if (form.size < 12 || form.ascii(0, 4) != "FORM") return null
    val kind = form.ascii(8, 4)
    if (kind != "AIFF" && kind != "AIFC") return null
    val aifc = kind == "AIFC"
    var offset = start + 12
    val limit = minOf(source.length, start + 1_000_000)
    while (offset + 8 <= limit) {
        val header = source.readAt(offset, 8)
        if (header.size < 8) break
        val id = header.ascii(0, 4)
        val size = header.u32be(4)
        if (id == "COMM" && size >= 18) {
            val comm = source.readAt(offset + 8, minOf(size, 64L).toInt())
            if (comm.size < 18) return null
            val channels = comm.u16be(0)
            val frames = comm.u32be(2)
            val sampleSize = comm.u16be(6)
            val rate = decodeExtended80(comm, 8).roundToInt()
            var compression = "NONE"
            if (aifc && comm.size >= 22) compression = comm.ascii(18, 4).trim()
            if (channels <= 0 || rate <= 0) return null
            val floatPcm = compression == "fl32" || compression == "fl64" || compression == "FL32"
            val lossless = compression in PCM_AIFF || !aifc
            val depth = when (compression) {
                "fl32", "FL32" -> 32
                "fl64" -> 64
                else -> sampleSize
            }
            val codec = if (compression.equals("alac", ignoreCase = true)) AudioCodec.ALAC else AudioCodec.AIFF
            return AudioFormatInfo(
                codec = codec,
                lossless = lossless || codec == AudioCodec.ALAC,
                bitDepth = depth.takeIf { it > 0 },
                sampleRateHz = rate,
                bitrateBps = if (depth > 0 && lossless) channels * depth * rate else null,
                channelCount = channels,
                probed = true,
                pcmIsFloat = floatPcm,
            ).let { info ->
                if (info.bitrateBps == null && frames > 0) {
                    info.copy(bitrateBps = estimatedBitrate(source.length, rate, frames))
                } else {
                    info
                }
            }
        }
        val step = 8 + size + (size and 1L)
        if (step <= 0) break
        offset += step
    }
    return null
}

private val PCM_AIFF = setOf("NONE", "sowt", "twos", "raw ", "in24", "in32", "fl32", "fl64", "FL32", "23bit", "24bit")

/** 80-bit IEEE 754 extended precision, used by AIFF COMM chunks. */
internal fun decodeExtended80(data: ByteArray, offset: Int): Double {
    if (offset + 10 > data.size) return 0.0
    val exponent = ((data.u8(offset) and 0x7F) shl 8) or data.u8(offset + 1)
    val sign = data.u8(offset) and 0x80
    var mantissa = 0L
    for (i in 0 until 8) {
        mantissa = (mantissa shl 8) or data.u8(offset + 2 + i).toLong()
    }
    if (exponent == 0 && mantissa == 0L) return 0.0
    if (exponent == 0x7FFF) {
        return if (sign != 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    }
    // The 64-bit mantissa is unsigned. Bit 63 makes Long.toDouble() negative.
    val value = mantissa.toULong().toDouble() * Math.pow(2.0, (exponent - 16383 - 63).toDouble())
    return if (sign != 0) -value else value
}

private fun probeOgg(source: BinarySource, start: Long): AudioFormatInfo? {
    val page = source.readAt(start, 27)
    if (page.size < 27 || page.ascii(0, 4) != "OggS") return null
    val segments = page.u8(26)
    val table = source.readAt(start + 27, segments)
    if (table.size < segments) return null
    var packetLength = 0
    for (segment in table) {
        val value = segment.toInt() and 0xFF
        packetLength += value
        if (value < 255) break
    }
    val body = source.readAt(start + 27 + segments, packetLength)
    if (body.size < packetLength) return null
    if (packetLength >= 19 && body.ascii(0, 8) == "OpusHead") {
        val channels = body.u8(9)
        return AudioFormatInfo(
            codec = AudioCodec.OPUS,
            lossless = false,
            sampleRateHz = 48_000,
            channelCount = channels.takeIf { it in 1..8 },
            probed = true,
        )
    }
    if (packetLength >= 30 && body.u8(0) == 0x01 && body.ascii(1, 6) == "vorbis") {
        val channels = body.u8(11)
        val rate = body.u32le(12).toInt()
        val nominal = body.u32le(20).toInt()
        if (rate <= 0) return null
        return AudioFormatInfo(
            codec = AudioCodec.VORBIS,
            lossless = false,
            sampleRateHz = rate,
            bitrateBps = nominal.takeIf { it > 0 },
            channelCount = channels.takeIf { it in 1..8 },
            probed = true,
        )
    }
    return null
}

private data class Mp3Frame(
    val bitrateKbps: Int,
    val sampleRate: Int,
    val channels: Int,
    val frameSize: Int,
)

private fun probeMp3(source: BinarySource, start: Long): AudioFormatInfo? {
    val windowSize = minOf(16_384L, source.length - start).toInt()
    if (windowSize < 4) return null
    val window = source.readAt(start, windowSize)
    var index = 0
    while (index + 4 <= window.size) {
        val frame = parseMp3Frame(window, index) ?: run {
            index++
            continue
        }
        val next = index + frame.frameSize
        val confirmed = next + 4 <= window.size && parseMp3Frame(window, next) != null
        val fitsFile = start + index + frame.frameSize <= source.length
        if (confirmed || (fitsFile && index < 64)) {
            return AudioFormatInfo(
                codec = AudioCodec.MP3,
                lossless = false,
                sampleRateHz = frame.sampleRate,
                bitrateBps = frame.bitrateKbps * 1000,
                channelCount = frame.channels,
                probed = true,
            )
        }
        index++
    }
    return null
}

private fun parseMp3Frame(data: ByteArray, offset: Int): Mp3Frame? {
    if (offset + 4 > data.size) return null
    val b0 = data.u8(offset)
    val b1 = data.u8(offset + 1)
    val b2 = data.u8(offset + 2)
    val b3 = data.u8(offset + 3)
    if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
    val versionBits = (b1 shr 3) and 0x03
    val layerBits = (b1 shr 1) and 0x03
    if (versionBits == 0x01 || layerBits == 0x00) return null
    val bitrateIndex = (b2 shr 4) and 0x0F
    val sampleIndex = (b2 shr 2) and 0x03
    if (bitrateIndex == 0 || bitrateIndex == 0x0F || sampleIndex == 0x03) return null
    val mpeg1 = versionBits == 0x03
    val mpeg25 = versionBits == 0x00
    val layer = when (layerBits) {
        0x03 -> 1
        0x02 -> 2
        else -> 3
    }
    val bitrate = mp3BitrateKbps(mpeg1, layer, bitrateIndex) ?: return null
    val sampleRate = mp3SampleRate(mpeg1, mpeg25, sampleIndex) ?: return null
    val padding = (b2 shr 1) and 0x01
    val frameSize = when {
        layer == 1 -> ((12 * bitrate * 1000 / sampleRate) + padding) * 4
        layer == 3 && !mpeg1 -> (72 * bitrate * 1000 / sampleRate) + padding
        else -> (144 * bitrate * 1000 / sampleRate) + padding
    }
    if (frameSize < 4) return null
    val channels = if (((b3 shr 6) and 0x03) == 0x03) 1 else 2
    return Mp3Frame(bitrate, sampleRate, channels, frameSize)
}

private fun mp3BitrateKbps(mpeg1: Boolean, layer: Int, index: Int): Int? {
    val table = when {
        mpeg1 && layer == 1 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
        mpeg1 && layer == 2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
        mpeg1 && layer == 3 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        !mpeg1 && layer == 1 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
        else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    }
    val value = table.getOrNull(index) ?: return null
    return value.takeIf { it > 0 }
}

private fun mp3SampleRate(mpeg1: Boolean, mpeg25: Boolean, index: Int): Int? {
    val base = when (index) {
        0 -> 44_100
        1 -> 48_000
        2 -> 32_000
        else -> return null
    }
    return when {
        mpeg1 -> base
        mpeg25 -> base / 4
        else -> base / 2
    }
}

private fun isMp4(source: BinarySource, start: Long): Boolean {
    val header = source.readAt(start, 12)
    if (header.size < 8) return false
    val type = header.ascii(4, 4)
    return type == "ftyp" || type == "moov" || type == "mdat" || type == "free" || type == "wide" || type == "styp"
}

private fun probeMp4(source: BinarySource, start: Long): AudioFormatInfo? {
    var seconds: Double? = null
    var found: AudioFormatInfo? = null
    fun walk(from: Long, to: Long, depth: Int) {
        if (depth > 16 || found != null) return
        var offset = from
        var guard = 0
        while (offset + 8 <= to && found == null && guard++ < 10_000) {
            val header = source.readAt(offset, 16)
            if (header.size < 8) return
            val size32 = header.u32be(0)
            val type = header.ascii(4, 4)
            val (headerSize, size) = when (size32) {
                0L -> 8 to (to - offset)
                1L -> {
                    if (header.size < 16) return
                    16 to header.u64be(8)
                }
                else -> 8 to size32
            }
            if (size < headerSize || offset + size > to + 8) return
            val payload = offset + headerSize
            val next = offset + size
            when (type) {
                "moov", "trak", "mdia", "minf", "stbl" -> walk(payload, next, depth + 1)
                "mvhd" -> seconds = parseMvhd(source, payload, next - payload)
                "stsd" -> found = parseStsd(source, payload, next - payload)
            }
            if (next <= offset) return
            offset = next
        }
    }
    walk(start, source.length, 0)
    val info = found ?: return null
    val duration = seconds
    if (info.bitrateBps == null && duration != null && duration > 0.0) {
        val bps = (source.length.toDouble() * 8.0 / duration).roundToInt()
        if (bps > 0) return info.copy(bitrateBps = bps)
    }
    return info
}

private fun parseMvhd(source: BinarySource, offset: Long, size: Long): Double? {
    val data = source.readAt(offset, minOf(size, 48L).toInt())
    if (data.size < 20) return null
    val version = data.u8(0)
    return if (version == 1 && data.size >= 32) {
        val timescale = data.u32be(20)
        val duration = data.u64be(24)
        if (timescale == 0L) null else duration.toDouble() / timescale.toDouble()
    } else {
        val timescale = data.u32be(12)
        val duration = data.u32be(16)
        if (timescale == 0L) null else duration.toDouble() / timescale.toDouble()
    }
}

private fun parseStsd(source: BinarySource, offset: Long, size: Long): AudioFormatInfo? {
    val head = source.readAt(offset, 8)
    if (head.size < 8) return null
    val count = head.u32be(4).toInt()
    var entry = offset + 8
    val end = offset + size
    repeat(minOf(count, 8)) {
        if (entry + 8 > end) return null
        val header = source.readAt(entry, 8)
        if (header.size < 8) return null
        val entrySize = header.u32be(0)
        if (entrySize < 8 || entry + entrySize > end + 8) return null
        val type = header.ascii(4, 4)
        val parsed = when (type) {
            "alac" -> parseAlacEntry(source, entry, entrySize)
            "mp4a" -> parseMp4aEntry(source, entry, entrySize)
            "fLaC" -> parseFlacInMp4(source, entry, entrySize)
            "Opus" -> AudioFormatInfo(AudioCodec.OPUS, lossless = false, sampleRateHz = 48_000, probed = true)
            else -> null
        }
        if (parsed != null) return parsed
        entry += entrySize
    }
    return null
}

private fun parseAlacEntry(source: BinarySource, entry: Long, entrySize: Long): AudioFormatInfo? {
    val bytes = source.readAt(entry, minOf(entrySize, 4096L).toInt())
    if (bytes.size < 36) return null
    var index = 8
    while (index + 8 < bytes.size) {
        if (bytes.ascii(index, 4) == "alac") {
            val atomStart = index - 4
            if (atomStart >= 0) {
                val atomSize = bytes.u32be(atomStart)
                if (atomSize in 24..512 && atomStart + atomSize <= bytes.size) {
                    val payload = bytes.copyOfRange(atomStart + 8, (atomStart + atomSize).toInt())
                    parseAlacCookie(payload)?.let { return it }
                }
            }
        }
        index++
    }
    val channels = bytes.u16be(24)
    val depth = bytes.u16be(26)
    val rate = (bytes.u32be(32) ushr 16).toInt()
    if (rate !in 8000..384_000) return null
    return AudioFormatInfo(
        codec = AudioCodec.ALAC,
        lossless = true,
        bitDepth = depth.takeIf { it in 8..32 },
        sampleRateHz = rate,
        channelCount = channels.takeIf { it in 1..8 },
        probed = true,
    )
}

private fun parseAlacCookie(payload: ByteArray): AudioFormatInfo? {
    for (offset in intArrayOf(4, 0)) {
        if (payload.size < offset + 24) continue
        val bitDepth = payload.u8(offset + 5)
        val channels = payload.u8(offset + 9)
        val rate = payload.u32be(offset + 20).toInt()
        if (bitDepth in setOf(16, 20, 24, 32) && channels in 1..8 && rate in 8000..384_000) {
            return AudioFormatInfo(
                codec = AudioCodec.ALAC,
                lossless = true,
                bitDepth = bitDepth,
                sampleRateHz = rate,
                channelCount = channels,
                probed = true,
            )
        }
    }
    return null
}

private fun parseMp4aEntry(source: BinarySource, entry: Long, entrySize: Long): AudioFormatInfo? {
    val bytes = source.readAt(entry, minOf(entrySize, 2048L).toInt())
    if (bytes.size < 36) return null
    val headerChannels = bytes.u16be(24).takeIf { it in 1..8 }
    val headerRate = (bytes.u32be(32) ushr 16).toInt().takeIf { it in 8000..384_000 }
    val esdsAt = bytes.indexOfAscii("esds")
    var rate = headerRate
    var channels = headerChannels
    var bitrate: Int? = null
    if (esdsAt >= 4) {
        val atomStart = esdsAt - 4
        val atomSize = bytes.u32be(atomStart).toInt()
        if (atomSize in 16..bytes.size - atomStart) {
            val payload = bytes.copyOfRange(atomStart + 8, atomStart + atomSize)
            val specific = parseEsds(payload)
            if (specific != null) {
                rate = specific.sampleRateHz
                channels = specific.channels ?: channels
                bitrate = specific.bitrateBps
            }
        }
    }
    return AudioFormatInfo(
        codec = AudioCodec.AAC,
        lossless = false,
        sampleRateHz = rate,
        bitrateBps = bitrate,
        channelCount = channels,
        probed = true,
    )
}

private data class AacSpecific(val sampleRateHz: Int, val channels: Int?, val bitrateBps: Int?)

private fun parseEsds(payload: ByteArray): AacSpecific? {
    val start = if (payload.size > 4 && payload.u8(4) == 0x03) 4 else 0
    var rate: Int? = null
    var channels: Int? = null
    var bitrate: Int? = null
    fun walk(from: Int, to: Int) {
        var index = from
        while (index + 2 <= to && rate == null) {
            val tag = payload.u8(index)
            val (length, next) = readDescriptorLength(payload, index + 1) ?: return
            val end = minOf(next + length, to)
            if (end < next) return
            when (tag) {
                0x03 -> {
                    val flags = if (next + 2 < end) payload.u8(next + 2) else 0
                    var child = next + 3
                    if (flags and 0x80 != 0 && child < end) {
                        child += payload.u8(child) + 1
                    }
                    if (flags and 0x40 != 0) child += 2
                    if (child < end) walk(child, end)
                }
                0x04 -> {
                    if (next + 13 <= end) {
                        val avg = payload.u32be(next + 9).toInt()
                        if (avg > 0) bitrate = avg
                        walk(next + 13, end)
                    }
                }
                0x05 -> {
                    val asc = parseAudioSpecificConfig(payload.copyOfRange(next, end))
                    if (asc != null) {
                        rate = asc.first
                        channels = asc.second
                    }
                }
            }
            index = end
        }
    }
    walk(start, payload.size)
    val sampleRate = rate ?: return null
    return AacSpecific(sampleRate, channels, bitrate)
}

private fun readDescriptorLength(data: ByteArray, offset: Int): Pair<Int, Int>? {
    var value = 0
    var index = offset
    var count = 0
    while (count < 4 && index < data.size) {
        val byte = data.u8(index)
        index++
        count++
        value = (value shl 7) or (byte and 0x7F)
        if (byte and 0x80 == 0) return value to index
    }
    return null
}

private val AAC_RATES = intArrayOf(
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
)

private fun parseAudioSpecificConfig(data: ByteArray): Pair<Int, Int?>? {
    if (data.size < 2) return null
    val bits = BitReader(data)
    var objectType = bits.take(5)
    if (objectType < 0) return null
    if (objectType == 31) {
        val ext = bits.take(6)
        if (ext < 0) return null
        objectType = 32 + ext
    }
    val freqIndex = bits.take(4)
    if (freqIndex < 0) return null
    val rate = if (freqIndex == 0x0F) {
        val explicit = bits.take(24)
        if (explicit <= 0) return null
        explicit
    } else {
        AAC_RATES.getOrNull(freqIndex) ?: return null
    }
    val channelConfig = bits.take(4)
    val channels = channelConfig.takeIf { it in 1..8 }
    return rate to channels
}

private class BitReader(private val data: ByteArray) {
    private var position = 0

    fun take(count: Int): Int {
        var value = 0
        repeat(count) {
            val byteIndex = position / 8
            if (byteIndex >= data.size) return -1
            val bit = (data.u8(byteIndex) shr (7 - (position % 8))) and 1
            value = (value shl 1) or bit
            position++
        }
        return value
    }
}

private fun parseFlacInMp4(source: BinarySource, entry: Long, entrySize: Long): AudioFormatInfo? {
    val bytes = source.readAt(entry, minOf(entrySize, 4096L).toInt())
    val marker = bytes.indexOfAscii("fLaC")
    if (marker >= 0) {
        val sliced = ByteArraySource(bytes.copyOfRange(marker, bytes.size), bytes.size.toLong())
        probeFlac(sliced, 0)?.let { return it.copy(bitrateBps = null) }
    }
    return AudioFormatInfo(AudioCodec.FLAC, lossless = true, probed = true)
}
