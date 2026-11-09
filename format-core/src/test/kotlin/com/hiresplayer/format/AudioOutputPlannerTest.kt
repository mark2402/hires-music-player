package com.hiresplayer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPlannerTest {
    private val flac24 = AudioFormatInfo(
        codec = AudioCodec.FLAC,
        lossless = true,
        bitDepth = 24,
        sampleRateHz = 96_000,
        channelCount = 2,
        probed = true,
    )
    private val cd = AudioFormatInfo(
        codec = AudioCodec.WAV,
        lossless = true,
        bitDepth = 16,
        sampleRateHz = 44_100,
        channelCount = 2,
        probed = true,
    )
    private val mp3 = AudioFormatInfo(
        codec = AudioCodec.MP3,
        lossless = false,
        sampleRateHz = 44_100,
        bitrateBps = 320_000,
        channelCount = 2,
        probed = true,
    )

    @Test
    fun hiResFloatIsNotCalledBitPerfectWithoutAMatchingMixer() {
        val plan = planOutput(
            source = flac24,
            device = DeviceCapabilities(floatDirect = true, pcm16Direct = true),
            preservesBitDepth = true,
            ffmpegAvailable = true,
        )
        assertEquals(PcmEncoding.PCM_FLOAT, plan.encoding)
        assertFalse(plan.claimsBitPerfect)
        assertFalse(plan.depthReduced)
        assertFalse(plan.resampled)
        assertNull(plan.bitPerfectMixer)
        assertEquals("32-bit float · 96 kHz", plan.headline)
        assertTrue(plan.detail.contains("not integer bit-perfect"))
    }

    @Test
    fun bitPerfectFloatOnlyWhenUsbMixerMatchesAndTheApiAcceptsIt() {
        val mixer = MixerFormat(PcmEncoding.PCM_FLOAT, 96_000, 2)
        val plan = planOutput(
            source = flac24,
            device = DeviceCapabilities(
                floatDirect = true,
                pcm16Direct = true,
                bitPerfectMixers = listOf(mixer),
            ),
            preservesBitDepth = true,
            ffmpegAvailable = true,
        )
        assertEquals(mixer, plan.bitPerfectMixer)
        assertFalse(plan.claimsBitPerfect)
        val confirmed = plan.withBitPerfect(true)
        assertTrue(confirmed.claimsBitPerfect)
        assertEquals("Bit-perfect USB · 32-bit float", confirmed.headline)
        val rejected = plan.withBitPerfect(false)
        assertFalse(rejected.claimsBitPerfect)
        assertEquals("32-bit float · 96 kHz", rejected.headline)
    }

    @Test
    fun integerUsbMixerDoesNotMakeFloatOutputBitPerfect() {
        val plan = planOutput(
            source = flac24,
            device = DeviceCapabilities(
                floatDirect = true,
                bitPerfectMixers = listOf(MixerFormat(PcmEncoding.PCM_24BIT_PACKED, 96_000, 2)),
            ),
            preservesBitDepth = true,
            ffmpegAvailable = true,
        )
        assertNull(plan.bitPerfectMixer)
        assertFalse(plan.withBitPerfect(true).claimsBitPerfect)
    }

    @Test
    fun platformDecoderDoesNotPreserve24Bit() {
        val preserves = softwareDecoderPreservesBitDepth(AudioCodec.FLAC, "c2.android.flac.decoder", ffmpegAvailable = true)
        assertFalse(preserves)
        val plan = planOutput(
            source = flac24,
            device = DeviceCapabilities(floatDirect = true, pcm16Direct = true),
            preservesBitDepth = preserves,
            ffmpegAvailable = true,
        )
        assertTrue(plan.depthReduced)
        assertEquals(PcmEncoding.PCM_16BIT, plan.encoding)
        assertFalse(plan.claimsBitPerfect)
        assertTrue(plan.detail.contains("16-bit"))
    }

    @Test
    fun ffmpegNameCountsAsPreserving() {
        assertTrue(softwareDecoderPreservesBitDepth(AudioCodec.ALAC, "FfmpegAudioRenderer", ffmpegAvailable = false))
        assertTrue(softwareDecoderPreservesBitDepth(AudioCodec.FLAC, null, ffmpegAvailable = true))
        assertFalse(softwareDecoderPreservesBitDepth(AudioCodec.FLAC, null, ffmpegAvailable = false))
    }

    @Test
    fun unsupportedRateIsResampledAndNotBitPerfect() {
        val plan = planOutput(
            source = flac24.copy(sampleRateHz = 192_000),
            device = DeviceCapabilities(floatDirect = false, pcm16Direct = false),
            preservesBitDepth = true,
            ffmpegAvailable = true,
        )
        assertTrue(plan.resampled)
        assertTrue(plan.depthReduced)
        assertFalse(plan.claimsBitPerfect)
    }

    @Test
    fun cdQualityCanBeIntegerBitPerfectOnUsb() {
        val plan = planOutput(
            source = cd,
            device = DeviceCapabilities(
                floatDirect = true,
                pcm16Direct = true,
                bitPerfectMixers = listOf(MixerFormat(PcmEncoding.PCM_16BIT, 44_100, 2)),
            ),
            preservesBitDepth = true,
            ffmpegAvailable = true,
        )
        assertEquals(PcmEncoding.PCM_16BIT, plan.encoding)
        val confirmed = plan.withBitPerfect(true)
        assertTrue(confirmed.claimsBitPerfect)
        assertEquals("Bit-perfect USB · 16-bit PCM", confirmed.headline)
    }

    @Test
    fun lossyIsNeverBitPerfect() {
        val plan = planOutput(
            source = mp3,
            device = DeviceCapabilities(
                floatDirect = true,
                pcm16Direct = true,
                bitPerfectMixers = listOf(MixerFormat(PcmEncoding.PCM_16BIT, 44_100, 2)),
            ),
            preservesBitDepth = true,
            ffmpegAvailable = true,
        )
        assertNull(plan.bitPerfectMixer)
        assertFalse(plan.withBitPerfect(true).claimsBitPerfect)
        assertEquals(QualityTier.LOSSY, qualityTier(mp3))
    }

    @Test
    fun reconcileDropsBitPerfectWhenTheTrackWasResampled() {
        val mixer = MixerFormat(PcmEncoding.PCM_FLOAT, 96_000, 2)
        val planned = planOutput(
            source = flac24,
            device = DeviceCapabilities(floatDirect = true, bitPerfectMixers = listOf(mixer)),
            preservesBitDepth = true,
            ffmpegAvailable = true,
            bitPerfectConfirmed = true,
        )
        assertTrue(planned.claimsBitPerfect)
        val actual = reconcileOutput(
            plan = planned,
            source = flac24,
            actualEncoding = AndroidPcm.PCM_FLOAT,
            actualSampleRate = 48_000,
        )
        assertTrue(actual.resampled)
        assertFalse(actual.claimsBitPerfect)
        assertNull(actual.bitPerfectMixer)
    }

    @Test
    fun unknownDeviceDoesNotInventBitPerfect() {
        val plan = planOutput(
            source = flac24,
            device = DeviceCapabilities(),
            preservesBitDepth = true,
            ffmpegAvailable = true,
        )
        assertFalse(plan.rateVerified)
        assertFalse(plan.claimsBitPerfect)
        assertNotNull(plan.detail)
    }
}
