package com.hiresplayer.library

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val uri: String,
    val mimeType: String?,
    val fileName: String?,
    val folderPath: String,
    val artworkUri: String?,
)

data class AlbumGroup(
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val tracks: List<Track>,
)

data class ArtistGroup(
    val name: String,
    val tracks: List<Track>,
)

data class FolderGroup(
    val path: String,
    val name: String,
    val tracks: List<Track>,
)

private val AUDIO_EXTENSIONS = setOf(
    "flac", "wav", "wave", "aiff", "aif", "aifc",
    "m4a", "mp4", "aac", "mp3", "ogg", "oga", "opus",
)

class LibraryRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    suspend fun load(): List<Track> = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, Track>()
        if (hasAudioPermission()) {
            for (track in scanMediaStore()) merged[track.uri] = track
        }
        for (tree in savedTrees()) {
            for (track in scanTree(tree)) merged.putIfAbsent(track.uri, track)
        }
        merged.values.sortedBy { it.title.lowercase() }
    }

    fun savedFolderCount(): Int = savedTrees().size

    fun addTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val next = savedTrees().map { it.toString() }.toMutableSet()
        next.add(uri.toString())
        prefs.edit().putStringSet(KEY_TREES, next).apply()
    }

    fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun savedTrees(): List<Uri> =
        prefs.getStringSet(KEY_TREES, emptySet()).orEmpty().map(Uri::parse)

    private fun scanMediaStore(): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            projection += MediaStore.Audio.Media.RELATIVE_PATH
            projection += MediaStore.Audio.Media.ALBUM_ARTIST
        } else {
            projection += MediaStore.Audio.Media.DATA
        }
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val pathCol = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            }
            val albumArtistCol = if (Build.VERSION.SDK_INT >= 29) {
                cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)
                val mime = cursor.getString(mimeCol)
                if (!isSupported(name, mime)) continue
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val albumId = cursor.getLong(albumIdCol)
                val rawPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                val folder = folderFrom(rawPath, Build.VERSION.SDK_INT >= 29)
                val artist = cursor.getString(artistCol).orEmpty().ifBlank { "Unknown artist" }
                val albumArtist = if (albumArtistCol >= 0) cursor.getString(albumArtistCol).orEmpty() else ""
                tracks += Track(
                    id = "ms:$id",
                    title = cursor.getString(titleCol).orEmpty().ifBlank { titleFromName(name) },
                    artist = artist,
                    album = cursor.getString(albumCol).orEmpty().ifBlank { "Unknown album" },
                    albumArtist = albumArtist.ifBlank { artist },
                    durationMs = cursor.getLong(durationCol).coerceAtLeast(0),
                    uri = uri.toString(),
                    mimeType = mime,
                    fileName = name,
                    folderPath = folder,
                    artworkUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                        albumId,
                    ).toString(),
                )
            }
        }
        return tracks
    }

    private fun scanTree(tree: Uri): List<Track> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        val tracks = mutableListOf<Track>()
        fun walk(directory: DocumentFile, path: String) {
            val children = directory.listFiles()
            for (child in children) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    walk(child, if (path.isEmpty()) name else "$path/$name")
                } else if (isSupported(name, child.type)) {
                    val tags = readTags(child.uri)
                    val folder = path.ifBlank { root.name ?: "Folder" }
                    tracks += Track(
                        id = "saf:${child.uri}",
                        title = tags.title ?: titleFromName(name),
                        artist = tags.artist ?: "Unknown artist",
                        album = tags.album ?: folder.substringAfterLast('/'),
                        albumArtist = tags.albumArtist ?: tags.artist ?: "Unknown artist",
                        durationMs = tags.durationMs,
                        uri = child.uri.toString(),
                        mimeType = child.type,
                        fileName = name,
                        folderPath = folder,
                        artworkUri = null,
                    )
                }
            }
        }
        walk(root, "")
        return tracks
    }

    private fun readTags(uri: Uri): Tags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            Tags(null, null, null, null, 0L)
        } finally {
            retriever.release()
        }
    }

    private data class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val durationMs: Long,
    )

    companion object {
        private const val KEY_TREES = "saf_trees"

        fun isSupported(name: String?, mime: String?): Boolean {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext in AUDIO_EXTENSIONS) return true
            val type = mime?.lowercase().orEmpty()
            return type.startsWith("audio/") && type !in setOf("audio/midi", "audio/x-midi")
        }

        fun titleFromName(name: String?): String =
            name?.substringBeforeLast('.')?.replace('_', ' ')?.ifBlank { "Untitled" } ?: "Untitled"

        fun folderFrom(raw: String, relative: Boolean): String {
            val normalized = raw.trim().trim('/')
            if (normalized.isEmpty()) return "Music"
            return if (relative) {
                normalized.trimEnd('/')
            } else {
                normalized.substringBeforeLast('/', normalized).substringAfterLast('/')
                    .ifBlank { "Music" }
            }.ifBlank { "Music" }
        }
    }
}

fun List<Track>.albums(): List<AlbumGroup> = groupBy { it.album.lowercase() to it.albumArtist.lowercase() }
    .values
    .map { tracks ->
        val first = tracks.first()
        AlbumGroup(
            title = first.album,
            artist = first.albumArtist.ifBlank { first.artist },
            artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
            tracks = tracks.sortedBy { it.title.lowercase() },
        )
    }
    .sortedBy { it.title.lowercase() }

fun List<Track>.artists(): List<ArtistGroup> = groupBy { it.artist.lowercase() }
    .values
    .map { tracks -> ArtistGroup(tracks.first().artist, tracks.sortedBy { it.title.lowercase() }) }
    .sortedBy { it.name.lowercase() }

fun List<Track>.folders(): List<FolderGroup> = groupBy { it.folderPath }
    .map { (path, tracks) ->
        FolderGroup(path, path.substringAfterLast('/').ifBlank { path }, tracks.sortedBy { it.title.lowercase() })
    }
    .sortedBy { it.path.lowercase() }
