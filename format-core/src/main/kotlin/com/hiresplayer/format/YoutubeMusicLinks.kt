package com.hiresplayer.format

import java.net.URLEncoder

/**
 * Deep links for the YouTube Music companion. This app never streams or
 * extracts YouTube Music audio; these URLs only open the YouTube Music app
 * (or the Play Store listing when it is not installed).
 */
object YoutubeMusicLinks {
    const val PACKAGE_NAME = "com.google.android.apps.youtube.music"

    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8).replace("+", "%20")
        return "https://music.youtube.com/search?q=$encoded"
    }

    fun playStoreWebUrl(): String =
        "https://play.google.com/store/apps/details?id=$PACKAGE_NAME"

    fun playStoreMarketUrl(): String = "market://details?id=$PACKAGE_NAME"
}
