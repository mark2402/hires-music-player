package com.hiresplayer.library

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

fun Track.toMediaItem(): MediaItem {
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    val mime = when {
        mimeType?.startsWith("audio/") == true && mimeType != "audio/mpegurl" -> mimeType
        ext == "flac" -> "audio/flac"
        ext == "wav" || ext == "wave" -> "audio/wav"
        ext == "aiff" || ext == "aif" || ext == "aifc" -> "audio/aiff"
        ext == "mp3" -> "audio/mpeg"
        ext == "opus" -> "audio/opus"
        ext == "ogg" || ext == "oga" -> "audio/ogg"
        ext == "m4a" || ext == "mp4" || ext == "aac" -> "audio/mp4"
        else -> null
    }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .apply { if (mime != null) setMimeType(mime) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .build(),
        )
        .build()
}
