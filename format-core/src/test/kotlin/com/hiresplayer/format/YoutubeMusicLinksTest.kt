package com.hiresplayer.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeMusicLinksTest {
    @Test
    fun searchEncodesSpacesAndReservedCharacters() {
        assertEquals(
            "https://music.youtube.com/search?q=miles%20davis",
            YoutubeMusicLinks.searchUrl("  miles davis "),
        )
        val ampersand = YoutubeMusicLinks.searchUrl("rock & roll")
        assertTrue(ampersand.startsWith("https://music.youtube.com/search?q="))
        assertTrue(ampersand.contains("rock%20%26%20roll"))
        assertFalse(ampersand.contains(" "))
    }

    @Test
    fun playStoreTargetsTheYoutubeMusicPackage() {
        assertEquals(
            "com.google.android.apps.youtube.music",
            YoutubeMusicLinks.PACKAGE_NAME,
        )
        assertTrue(YoutubeMusicLinks.playStoreWebUrl().endsWith(YoutubeMusicLinks.PACKAGE_NAME))
        assertEquals(
            "market://details?id=com.google.android.apps.youtube.music",
            YoutubeMusicLinks.playStoreMarketUrl(),
        )
    }
}
