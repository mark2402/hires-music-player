package com.hiresplayer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probes files produced by FFmpeg, not just hand-built headers.
 */
class RealMediaProbeTest {
    @Test
    fun ffmpegFixtures() {
        val flacHiRes = load("audio/24-96.flac")
        assertEquals(AudioCodec.FLAC, flacHiRes.codec)
        assertEquals(24, flacHiRes.bitDepth)
        assertEquals(96_000, flacHiRes.sampleRateHz)
        assertEquals(2, flacHiRes.channelCount)
        assertEquals(QualityTier.HI_RES, qualityTier(flacHiRes))
        assertEquals("FLAC 24-bit / 96 kHz", formatBadge(flacHiRes))

        val flacCd = load("audio/16-44.flac")
        assertEquals(16, flacCd.bitDepth)
        assertEquals(44_100, flacCd.sampleRateHz)
        assertEquals(QualityTier.LOSSLESS, qualityTier(flacCd))

        val wav = load("audio/24-96.wav")
        assertEquals(AudioCodec.WAV, wav.codec)
        assertEquals(24, wav.bitDepth)
        assertEquals(96_000, wav.sampleRateHz)
        assertTrue(wav.lossless)
        assertEquals(QualityTier.HI_RES, qualityTier(wav))

        val aiff = load("audio/16-44.aiff")
        assertEquals(AudioCodec.AIFF, aiff.codec)
        assertEquals(16, aiff.bitDepth)
        assertEquals(44_100, aiff.sampleRateHz)
        assertEquals(QualityTier.LOSSLESS, qualityTier(aiff))

        val mp3 = load("audio/320.mp3")
        assertEquals(AudioCodec.MP3, mp3.codec)
        assertEquals(44_100, mp3.sampleRateHz)
        assertEquals(320_000, mp3.bitrateBps)
        assertFalse(mp3.lossless)
        assertEquals(QualityTier.LOSSY, qualityTier(mp3))

        val opus = load("audio/opus.ogg")
        assertEquals(AudioCodec.OPUS, opus.codec)
        assertEquals(48_000, opus.sampleRateHz)
        assertEquals(QualityTier.LOSSY, qualityTier(opus))

        val vorbis = load("audio/vorbis.ogg")
        assertEquals(AudioCodec.VORBIS, vorbis.codec)
        assertEquals(44_100, vorbis.sampleRateHz)
        assertEquals(QualityTier.LOSSY, qualityTier(vorbis))

        val alac = load("audio/alac.m4a")
        assertEquals(AudioCodec.ALAC, alac.codec)
        assertTrue(alac.lossless)
        assertEquals(96_000, alac.sampleRateHz)
        assertEquals(2, alac.channelCount)
        assertEquals(QualityTier.HI_RES, qualityTier(alac))

        val aac = load("audio/aac.m4a")
        assertEquals(AudioCodec.AAC, aac.codec)
        assertEquals(44_100, aac.sampleRateHz)
        assertFalse(aac.lossless)
        assertEquals(QualityTier.LOSSY, qualityTier(aac))
    }

    private fun load(path: String): AudioFormatInfo {
        val bytes = javaClass.classLoader.getResourceAsStream(path)!!.readBytes()
        val info = AudioFormatProbe.probe(bytes, path.substringAfterLast('/'))
        return checkNotNull(info) { path }
    }
}
