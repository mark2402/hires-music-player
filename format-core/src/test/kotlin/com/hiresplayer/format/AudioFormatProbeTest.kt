package com.hiresplayer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatProbeTest {
    @Test
    fun flac24bit96kHz() {
        val info = AudioFormatProbe.probe(flac(sampleRate = 96_000, bitDepth = 24, channels = 2, samples = 96_000), "song.flac")
        assertNotNull(info)
        info!!
        assertEquals(AudioCodec.FLAC, info.codec)
        assertTrue(info.lossless)
        assertTrue(info.probed)
        assertEquals(24, info.bitDepth)
        assertEquals(96_000, info.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals("FLAC 24-bit / 96 kHz", formatBadge(info))
        assertEquals(QualityTier.HI_RES, qualityTier(info))
    }

    @Test
    fun flacCdIsLosslessNotHiRes() {
        val info = AudioFormatProbe.probe(flac(44_100, 16, 2, 44_100))!!
        assertEquals("FLAC 16-bit / 44.1 kHz", formatBadge(info))
        assertEquals(QualityTier.LOSSLESS, qualityTier(info))
    }

    @Test
    fun flac16bit48kHzIsHiRes() {
        val info = AudioFormatProbe.probe(flac(48_000, 16, 2, 48_000))!!
        assertEquals(QualityTier.HI_RES, qualityTier(info))
    }

    @Test
    fun flacAfterId3() {
        val body = flac(96_000, 24, 2, 96_000)
        val file = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00) + synchsafe(0) + body
        val info = AudioFormatProbe.probe(file)!!
        assertEquals(24, info.bitDepth)
        assertEquals(96_000, info.sampleRateHz)
    }

    @Test
    fun wav24bit96kHz() {
        val info = AudioFormatProbe.probe(wav(96_000, 24, 2))!!
        assertEquals(AudioCodec.WAV, info.codec)
        assertTrue(info.lossless)
        assertFalse(info.pcmIsFloat)
        assertEquals(24, info.bitDepth)
        assertEquals(96_000, info.sampleRateHz)
        assertEquals("WAV 24-bit / 96 kHz", formatBadge(info))
        assertEquals(QualityTier.HI_RES, qualityTier(info))
    }

    @Test
    fun wavFloat32() {
        val info = AudioFormatProbe.probe(wav(48_000, 32, 2, audioFormat = 3))!!
        assertTrue(info.pcmIsFloat)
        assertTrue(info.lossless)
        assertEquals(32, info.bitDepth)
        assertEquals(QualityTier.HI_RES, qualityTier(info))
    }

    @Test
    fun aiff44100() {
        val info = AudioFormatProbe.probe(aiff(44_100, 16, 2))!!
        assertEquals(AudioCodec.AIFF, info.codec)
        assertEquals(16, info.bitDepth)
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(QualityTier.LOSSLESS, qualityTier(info))
        assertEquals("AIFF 16-bit / 44.1 kHz", formatBadge(info))
    }

    @Test
    fun aifcLittleEndianPcm() {
        val info = AudioFormatProbe.probe(aiff(48_000, 24, 2, aifc = "sowt"))!!
        assertEquals(24, info.bitDepth)
        assertEquals(48_000, info.sampleRateHz)
        assertTrue(info.lossless)
        assertEquals(QualityTier.HI_RES, qualityTier(info))
    }

    @Test
    fun extended80Known44100() {
        val bytes = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        assertEquals(44100.0, decodeExtended80(bytes, 0), 0.001)
    }

    @Test
    fun mp3_320kbps() {
        val frame = mp3Frame()
        val file = frame + frame
        val info = AudioFormatProbe.probe(file, "track.mp3")!!
        assertEquals(AudioCodec.MP3, info.codec)
        assertFalse(info.lossless)
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(320_000, info.bitrateBps)
        assertEquals(2, info.channelCount)
        assertEquals("MP3 320 kbps · 44.1 kHz", formatBadge(info))
        assertEquals(QualityTier.LOSSY, qualityTier(info))
    }

    @Test
    fun alac24bit96kHz() {
        val info = AudioFormatProbe.probe(alacMp4(bitDepth = 24, sampleRate = 96_000, channels = 2))!!
        assertEquals(AudioCodec.ALAC, info.codec)
        assertTrue(info.lossless)
        assertEquals(24, info.bitDepth)
        assertEquals(96_000, info.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals("ALAC 24-bit / 96 kHz", formatBadge(info))
        assertEquals(QualityTier.HI_RES, qualityTier(info))
    }

    @Test
    fun alac24bit44100IsHiRes() {
        val info = AudioFormatProbe.probe(alacMp4(24, 44_100, 2))!!
        assertEquals(QualityTier.HI_RES, qualityTier(info))
        assertEquals("ALAC 24-bit / 44.1 kHz", formatBadge(info))
    }

    @Test
    fun aac44100() {
        val info = AudioFormatProbe.probe(aacMp4())!!
        assertEquals(AudioCodec.AAC, info.codec)
        assertFalse(info.lossless)
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals(256_000, info.bitrateBps)
        assertEquals(QualityTier.LOSSY, qualityTier(info))
        assertEquals("AAC 256 kbps · 44.1 kHz", formatBadge(info))
    }

    @Test
    fun opusAndVorbis() {
        val opus = AudioFormatProbe.probe(oggPage(opusHead()))!!
        assertEquals(AudioCodec.OPUS, opus.codec)
        assertEquals(48_000, opus.sampleRateHz)
        assertEquals(2, opus.channelCount)
        assertEquals("Opus 48 kHz", formatBadge(opus))
        assertEquals(QualityTier.LOSSY, qualityTier(opus))

        val vorbis = AudioFormatProbe.probe(oggPage(vorbisId(44_100, 2, 192_000)))!!
        assertEquals(AudioCodec.VORBIS, vorbis.codec)
        assertEquals(44_100, vorbis.sampleRateHz)
        assertEquals(192_000, vorbis.bitrateBps)
        assertEquals("Vorbis 192 kbps · 44.1 kHz", formatBadge(vorbis))
    }

    @Test
    fun garbageAndEmptyDoNotInventAFormat() {
        assertNull(AudioFormatProbe.probe(ByteArray(0)))
        assertNull(AudioFormatProbe.probe(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun filenameHintIsNotAQualityClaim() {
        val hint = AudioFormatProbe.probe(byteArrayOf(0, 1, 2, 3), "album.flac")!!
        assertEquals(AudioCodec.FLAC, hint.codec)
        assertFalse(hint.probed)
        assertNull(qualityTier(hint))
        assertEquals("FLAC", formatBadge(hint))
    }

    @Test
    fun unprobedM4aIsNotCalledLossless() {
        val hint = hintForName("song.m4a")!!
        assertFalse(hint.lossless)
        assertNull(qualityTier(hint))
    }
}

private fun flac(sampleRate: Int, bitDepth: Int, channels: Int, samples: Long, fileLength: Int = 288_000): ByteArray {
    val stream = ByteArray(34)
    var bit = 10 * 8
    fun put(value: Long, width: Int) {
        for (shift in width - 1 downTo 0) {
            if (((value ushr shift) and 1L) == 1L) {
                stream[bit / 8] = (stream[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            }
            bit++
        }
    }
    put(sampleRate.toLong(), 20)
    put((channels - 1).toLong(), 3)
    put((bitDepth - 1).toLong(), 5)
    put(samples, 36)
    val header = byteArrayOf(
        'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
        0x00, 0x00, 0x00, 34,
    )
    val body = header + stream
    return if (fileLength > body.size) body + ByteArray(fileLength - body.size) else body
}

private fun wav(sampleRate: Int, bits: Int, channels: Int, audioFormat: Int = 1): ByteArray {
    val blockAlign = channels * bits / 8
    val byteRate = sampleRate * blockAlign
    val fmt = ByteArray(16)
    writeU16le(fmt, 0, audioFormat)
    writeU16le(fmt, 2, channels)
    writeU32le(fmt, 4, sampleRate)
    writeU32le(fmt, 8, byteRate)
    writeU16le(fmt, 12, blockAlign)
    writeU16le(fmt, 14, bits)
    val data = ByteArray(8)
    data[0] = 'd'.code.toByte(); data[1] = 'a'.code.toByte(); data[2] = 't'.code.toByte(); data[3] = 'a'.code.toByte()
    return riff("WAVE", chunk("fmt ", fmt) + data)
}

private fun aiff(sampleRate: Int, bits: Int, channels: Int, aifc: String? = null): ByteArray {
    val comm = ByteArray(if (aifc == null) 18 else 22)
    writeU16be(comm, 0, channels)
    writeU32be(comm, 2, 1000)
    writeU16be(comm, 6, bits)
    val extended = extended80(sampleRate)
    extended.copyInto(comm, 8)
    if (aifc != null) {
        val raw = aifc.padEnd(4, ' ').take(4)
        raw.forEachIndexed { i, c -> comm[18 + i] = c.code.toByte() }
    }
    val formType = if (aifc == null) "AIFF" else "AIFC"
    val body = formType.toByteArray(Charsets.US_ASCII) + chunkBe("COMM", comm)
    val size = body.size
    return "FORM".toByteArray(Charsets.US_ASCII) + u32be(size) + body
}

private fun extended80(sampleRate: Int): ByteArray {
    var exponent = 16383 + 63
    var mantissa = sampleRate.toLong()
    if (mantissa == 0L) return ByteArray(10)
    while (mantissa and Long.MIN_VALUE == 0L) {
        mantissa = mantissa shl 1
        exponent--
    }
    val out = ByteArray(10)
    out[0] = ((exponent shr 8) and 0x7F).toByte()
    out[1] = (exponent and 0xFF).toByte()
    for (i in 0 until 8) {
        out[2 + i] = ((mantissa ushr (56 - 8 * i)) and 0xFF).toByte()
    }
    return out
}

private fun mp3Frame(): ByteArray {
    val header = byteArrayOf(0xFF.toByte(), 0xFA.toByte(), 0xE0.toByte(), 0x00)
    val size = 144 * 320_000 / 44_100
    return header + ByteArray(size - 4)
}

private fun alacMp4(bitDepth: Int, sampleRate: Int, channels: Int): ByteArray {
    val cookie = ByteArray(24)
    writeU32be(cookie, 0, 4096)
    cookie[5] = bitDepth.toByte()
    cookie[9] = channels.toByte()
    writeU32be(cookie, 20, sampleRate)
    val alacAtom = atom("alac", byteArrayOf(0, 0, 0, 0) + cookie)
    val description = ByteArray(28)
    writeU16be(description, 8, channels)
    writeU16be(description, 10, 16)
    writeU32be(description, 16, sampleRate shl 16)
    val sample = atom("alac", description + alacAtom)
    return moovWithSample(sample)
}

private fun aacMp4(): ByteArray {
    val asc = byteArrayOf(0x12, 0x10)
    val specific = descriptor(0x05, asc)
    val decoderConfig = ByteArray(13)
    decoderConfig[0] = 0x40
    decoderConfig[1] = 0x15
    writeU32be(decoderConfig, 9, 256_000)
    val dec = descriptor(0x04, decoderConfig + specific)
    val es = ByteArray(3) + dec
    es[2] = 0
    val esds = atom("esds", byteArrayOf(0, 0, 0, 0) + descriptor(0x03, es))
    val description = ByteArray(28)
    writeU16be(description, 8, 2)
    writeU16be(description, 10, 16)
    writeU32be(description, 16, 44_100 shl 16)
    return moovWithSample(atom("mp4a", description + esds))
}

private fun moovWithSample(sampleEntry: ByteArray): ByteArray {
    val stsd = atom("stsd", byteArrayOf(0, 0, 0, 0) + u32be(1) + sampleEntry)
    val stbl = atom("stbl", stsd)
    val minf = atom("minf", stbl)
    val mdia = atom("mdia", minf)
    val trak = atom("trak", mdia)
    val mvhd = ByteArray(20)
    writeU32be(mvhd, 12, 1000)
    writeU32be(mvhd, 16, 1000)
    val moov = atom("moov", atom("mvhd", mvhd) + trak)
    return atom("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8)) + moov
}

private fun opusHead(): ByteArray {
    val packet = ByteArray(19)
    "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(packet)
    packet[8] = 1
    packet[9] = 2
    return packet
}

private fun vorbisId(sampleRate: Int, channels: Int, nominal: Int): ByteArray {
    val packet = ByteArray(30)
    packet[0] = 1
    "vorbis".toByteArray(Charsets.US_ASCII).copyInto(packet, 1)
    packet[11] = channels.toByte()
    writeU32le(packet, 12, sampleRate)
    writeU32le(packet, 20, nominal)
    return packet
}

private fun oggPage(packet: ByteArray): ByteArray {
    val header = ByteArray(27)
    "OggS".toByteArray(Charsets.US_ASCII).copyInto(header)
    header[26] = 1
    return header + byteArrayOf(packet.size.toByte()) + packet
}

private fun descriptor(tag: Int, body: ByteArray): ByteArray =
    byteArrayOf(tag.toByte(), body.size.toByte()) + body

private fun atom(type: String, payload: ByteArray): ByteArray =
    u32be(8 + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload

private fun chunk(id: String, payload: ByteArray): ByteArray =
    id.toByteArray(Charsets.US_ASCII) + u32le(payload.size) + payload

private fun chunkBe(id: String, payload: ByteArray): ByteArray =
    id.toByteArray(Charsets.US_ASCII) + u32be(payload.size) + payload

private fun riff(kind: String, body: ByteArray): ByteArray =
    "RIFF".toByteArray(Charsets.US_ASCII) + u32le(4 + body.size) + kind.toByteArray(Charsets.US_ASCII) + body

private fun synchsafe(size: Int): ByteArray = byteArrayOf(
    ((size shr 21) and 0x7F).toByte(),
    ((size shr 14) and 0x7F).toByte(),
    ((size shr 7) and 0x7F).toByte(),
    (size and 0x7F).toByte(),
)

private fun u32be(value: Int): ByteArray = byteArrayOf(
    (value shr 24).toByte(),
    (value shr 16).toByte(),
    (value shr 8).toByte(),
    value.toByte(),
)

private fun u32le(value: Int): ByteArray = byteArrayOf(
    value.toByte(),
    (value shr 8).toByte(),
    (value shr 16).toByte(),
    (value shr 24).toByte(),
)

private fun writeU16le(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value shr 8).toByte()
}

private fun writeU16be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value shr 8).toByte()
    target[offset + 1] = value.toByte()
}

private fun writeU32le(target: ByteArray, offset: Int, value: Int) {
    u32le(value).copyInto(target, offset)
}

private fun writeU32be(target: ByteArray, offset: Int, value: Int) {
    u32be(value).copyInto(target, offset)
}
