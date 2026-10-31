package com.aurns.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MusicScanner {

    fun scanLocalTracks(context: Context): List<Track> {
        val trackList = mutableListOf<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Bilinmeyen Parça"
                    val artist = cursor.getString(artistCol) ?: "Bilinmeyen Sanatçı"
                    val durationMs = cursor.getInt(durationCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val albumId = cursor.getLong(albumIdCol)

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )

                    trackList.add(
                        Track(
                            id = id,
                            title = title,
                            artist = if (artist == "<unknown>") "Yerel Parça" else artist,
                            durationSec = durationMs / 1000,
                            path = path,
                            uri = contentUri,
                            albumArtUri = albumArtUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Uygulamanın Dahili/Harici Klasöründeki İndirilen Parçaları da Tara
        try {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val aurnsDir = fallbackDir?.let { File(it, "Aurns") }
            if (aurnsDir != null && aurnsDir.exists() && aurnsDir.isDirectory) {
                aurnsDir.listFiles()?.filter {
                    it.isFile && (it.name.endsWith(".m4a", ignoreCase = true) || it.name.endsWith(".mp3", ignoreCase = true))
                }?.forEach { f ->
                    val alreadyInList = trackList.any {
                        it.title.equals(f.nameWithoutExtension, ignoreCase = true) || it.path == f.absolutePath
                    }
                    if (!alreadyInList) {
                        trackList.add(
                            Track(
                                id = f.hashCode().toLong(),
                                title = f.nameWithoutExtension,
                                artist = "İndirilen Parça",
                                durationSec = 0,
                                path = f.absolutePath,
                                uri = Uri.fromFile(f),
                                albumArtUri = null
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return trackList
    }
}
