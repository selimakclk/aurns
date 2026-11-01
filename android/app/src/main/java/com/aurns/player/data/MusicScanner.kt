package com.aurns.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.util.Locale

object MusicScanner {

    private val YT_ID_BRACKET_REGEX = Regex("\\[([A-Za-z0-9_-]{11})]\\s*$")
    private val NOISE_SUFFIX_REGEX = Regex(
        "\\s*[\\[(](official\\s*(music\\s*)?video|official\\s*audio|lyrics?|lyric\\s*video|hd|hq|4k|audio|video|visualizer|klip|resmi\\s*klip|şarkı\\s*sözleri)[])]",
        RegexOption.IGNORE_CASE
    )
    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac", "wma")

    fun extractYouTubeId(textOrPath: String?): String? {
        if (textOrPath.isNullOrBlank()) return null
        val base = textOrPath.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val match = YT_ID_BRACKET_REGEX.find(base)
        return match?.groupValues?.getOrNull(1)
    }

    fun cleanDisplayTitle(rawTitle: String, fallbackFileName: String = ""): String {
        var s = rawTitle.trim()
        if (s.isBlank() || s.equals("<unknown>", ignoreCase = true)) {
            s = fallbackFileName.substringBeforeLast('.')
        }
        s = s.replace(YT_ID_BRACKET_REGEX, "").trim()
        s = s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        return s.ifEmpty { "Bilinmeyen Parça" }
    }

    fun splitArtistAndTitle(cleanTitle: String, rawArtist: String?): Pair<String, String> {
        val validArtist = rawArtist?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) && !it.equals("Bilinmeyen Sanatçı", ignoreCase = true)
        }
        val dashIdx = cleanTitle.indexOf(" - ")
        if (dashIdx in 1 until (cleanTitle.length - 3)) {
            val left = cleanTitle.substring(0, dashIdx).trim()
            val right = cleanTitle.substring(dashIdx + 3).trim()
            if (validArtist == null) {
                return Pair(left, cleanTitle)
            }
            if (right.isNotEmpty()) {
                return Pair(validArtist, cleanTitle)
            }
        }
        return Pair(validArtist ?: "Yerel Parça", cleanTitle)
    }

    // Türkçe karakter katlamalı normalize edici (Windows src/playlist.c NormalizeForMatch ile %100 birebir!)
    fun normalizeForMatch(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var cleaned = input.replace(YT_ID_BRACKET_REGEX, "")
        cleaned = cleaned.replace(NOISE_SUFFIX_REGEX, " ")
        val sb = StringBuilder(cleaned.length)
        var lastWasSpace = true
        for (ch in cleaned) {
            val mapped: Char? = when (ch) {
                'ı', 'İ', 'I' -> 'i'
                'ş', 'Ş' -> 's'
                'ğ', 'Ğ' -> 'g'
                'ü', 'Ü' -> 'u'
                'ö', 'Ö' -> 'o'
                'ç', 'Ç' -> 'c'
                in 'A'..'Z' -> ch.lowercaseChar()
                in 'a'..'z', in '0'..'9' -> ch
                else -> ' '
            }
            if (mapped == ' ') {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else if (mapped != null) {
                sb.append(mapped)
                lastWasSpace = false
            }
        }
        return sb.toString().trim()
    }

    fun isSameTrack(track: Track, remoteTitle: String, remoteYtId: String?): Boolean {
        if (!remoteYtId.isNullOrBlank() && remoteYtId.length == 11 && !track.ytId.isNullOrBlank()) {
            if (track.ytId.equals(remoteYtId, ignoreCase = true)) return true
        }
        val normTrack = normalizeForMatch(track.title)
        val normRemote = normalizeForMatch(remoteTitle)
        if (normTrack.isEmpty() || normRemote.isEmpty()) return false
        if (normTrack == normRemote) return true
        if (normTrack.length >= 5 && normRemote.length >= 5) {
            if (normTrack.contains(normRemote) || normRemote.contains(normTrack)) return true
        }
        val fullTrack = normalizeForMatch("${track.artist} ${track.title}")
        if (fullTrack == normRemote || (normRemote.length >= 6 && fullTrack.contains(normRemote))) return true
        return false
    }

    fun findBestTrackMatch(tracks: List<Track>, remoteTitle: String, remoteYtId: String?): Int {
        // 1. Önce 11 haneli YouTube Video ID ile kesin eşleşme ara
        if (!remoteYtId.isNullOrBlank() && remoteYtId.length == 11) {
            val ytIdx = tracks.indexOfFirst { it.ytId != null && it.ytId.equals(remoteYtId, ignoreCase = true) }
            if (ytIdx != -1) return ytIdx
        }

        val normRemote = normalizeForMatch(remoteTitle)
        if (normRemote.isEmpty()) return -1

        // 2. Tam normalize başlık eşleşmesi
        val exactIdx = tracks.indexOfFirst { normalizeForMatch(it.title) == normRemote }
        if (exactIdx != -1) return exactIdx

        // 3. Sanatçı + Başlık birleşik eşleşme veya kapsama skoru
        var bestIdx = -1
        var bestScore = 0
        val remoteTokens = normRemote.split(' ').filter { it.length >= 2 }

        tracks.forEachIndexed { idx, t ->
            val normT = normalizeForMatch(t.title)
            val normFull = normalizeForMatch("${t.artist} ${t.title}")
            var score = 0
            if (normT.isNotEmpty() && (normT.contains(normRemote) || normRemote.contains(normT))) {
                score = 80
            } else if (normFull.contains(normRemote) || normRemote.contains(normFull)) {
                score = 70
            } else if (remoteTokens.isNotEmpty()) {
                val matched = remoteTokens.count { tok -> normFull.contains(tok) }
                if (matched * 100 >= remoteTokens.size * 75 && matched >= 2) {
                    score = (matched * 60) / remoteTokens.size
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        return if (bestScore >= 45) bestIdx else -1
    }

    fun scanLocalTracks(context: Context, customTreeUriString: String? = null): List<Track> {
        if (!customTreeUriString.isNullOrBlank()) {
            val treeTracks = scanFolderTreeUri(context, Uri.parse(customTreeUriString))
            if (treeTracks.isNotEmpty()) return treeTracks
        }

        val trackList = mutableListOf<Track>()
        val seenKeys = HashSet<String>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val dispCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol) ?: ""
                    val dispName = cursor.getString(dispCol) ?: ""
                    val rawArtist = cursor.getString(artistCol)
                    val durationMs = cursor.getInt(durationCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val albumId = cursor.getLong(albumIdCol)

                    val ytId = extractYouTubeId(dispName) ?: extractYouTubeId(path) ?: extractYouTubeId(rawTitle)
                    val cleanedTitle = cleanDisplayTitle(rawTitle, dispName.ifEmpty { path.substringAfterLast('/') })
                    val (artist, finalTitle) = splitArtistAndTitle(cleanedTitle, rawArtist)

                    val dedupKey = ytId?.lowercase(Locale.ROOT) ?: normalizeForMatch(finalTitle)
                    if (dedupKey.isNotEmpty() && !seenKeys.add(dedupKey)) continue

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )

                    trackList.add(
                        Track(
                            id = id,
                            title = finalTitle,
                            artist = artist,
                            durationSec = (durationMs / 1000).coerceAtLeast(0),
                            path = path,
                            uri = contentUri,
                            albumArtUri = albumArtUri,
                            ytId = ytId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Uygulamanın Dahili/Harici Yedek Klasöründeki İndirilen Parçaları da Tara
        try {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val aurnsDir = fallbackDir?.let { File(it, "Aurns") }
            if (aurnsDir != null && aurnsDir.exists() && aurnsDir.isDirectory) {
                aurnsDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTS }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { f ->
                        val ytId = extractYouTubeId(f.name)
                        val cleanedTitle = cleanDisplayTitle(f.nameWithoutExtension, f.name)
                        val dedupKey = ytId?.lowercase(Locale.ROOT) ?: normalizeForMatch(cleanedTitle)
                        if (dedupKey.isNotEmpty() && seenKeys.add(dedupKey)) {
                            val (artist, finalTitle) = splitArtistAndTitle(cleanedTitle, null)
                            var durSec = 0
                            try {
                                val mmr = MediaMetadataRetriever()
                                mmr.setDataSource(f.absolutePath)
                                durSec = ((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                                mmr.release()
                            } catch (_: Exception) {}

                            trackList.add(
                                Track(
                                    id = (f.absolutePath.hashCode().toLong() and 0x7FFFFFFFL) + 1_000_000_000L,
                                    title = finalTitle,
                                    artist = if (artist == "Yerel Parça") "İndirilen Parça" else artist,
                                    durationSec = durSec,
                                    path = f.absolutePath,
                                    uri = Uri.fromFile(f),
                                    albumArtUri = null,
                                    ytId = ytId
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

    private fun scanFolderTreeUri(context: Context, treeUri: Uri): List<Track> {
        val result = mutableListOf<Track>()
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (!mime.startsWith("audio/") && ext !in AUDIO_EXTS) continue

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val ytId = extractYouTubeId(name)
                    val cleaned = cleanDisplayTitle(name.substringBeforeLast('.'), name)
                    val (artist, finalTitle) = splitArtistAndTitle(cleaned, null)

                    var durSec = 0
                    try {
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(context, docUri)
                        durSec = ((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                        mmr.release()
                    } catch (_: Exception) {}

                    result.add(
                        Track(
                            id = (docUri.toString().hashCode().toLong() and 0x7FFFFFFFL) + 2_000_000_000L,
                            title = finalTitle,
                            artist = artist,
                            durationSec = durSec,
                            path = docUri.toString(),
                            uri = docUri,
                            albumArtUri = null,
                            ytId = ytId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
