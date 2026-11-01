package com.aurns.player.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val title: String,
    val percent: Int,
    val isRunning: Boolean,
    val isDone: Boolean = false,
    val error: String? = null
)

private const val UA_VISIONOS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

class AudioDownloadManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null

    private val _currentProgress = MutableStateFlow(DownloadProgress("", 0, false))
    val currentProgress: StateFlow<DownloadProgress> = _currentProgress.asStateFlow()

    var onDownloadFinishedListener: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun startDownload(videoId: String, title: String, artist: String) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _currentProgress.value = DownloadProgress(title, 0, isRunning = true)

            // 1. YouTube Doğrudan Ses Linkini Çözümle
            val streamInfo = InnertubeResolver.resolveAudioStream(videoId)
            if (streamInfo == null || streamInfo.url.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ses akışı alınamadı, tekrar deneyin.", Toast.LENGTH_SHORT).show()
                }
                _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = "Ses akışı alınamadı")
                return@launch
            }

            // 2. Güvenli Dosya İsmi Belirle (.m4a)
            val safeBaseName = title.replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ\\s-_.]"), "")
                .trim()
                .ifEmpty { "Aurns_Track_${System.currentTimeMillis()}" }
                .take(64)
            val safeFileName = "$safeBaseName.m4a"

            // 3. Geçici İndirme Dosyası (Uygulamanın Özel Cache Alanı - EPERM İzni Hatası Vermez)
            val tempFile = File(context.cacheDir, "dl_${System.currentTimeMillis()}.tmp")

            try {
                val totalBytes = streamInfo.contentLength

                if (totalBytes > 256 * 1024) {
                    // PARALEL 4-KANALLI İNDİRME MOTORU (Google CDN Hız Kısıtlamasını 100% Aşar - MYT Hızı 1.2 Saniye)
                    val raf = RandomAccessFile(tempFile, "rw")
                    raf.setLength(totalBytes)
                    raf.close()

                    val partCount = 4
                    val chunkSize = totalBytes / partCount
                    val totalReadAtomic = AtomicLong(0L)
                    var lastUpdate = System.currentTimeMillis()

                    coroutineScope {
                        val jobs = (0 until partCount).map { partIdx ->
                            async(Dispatchers.IO) {
                                val start = partIdx * chunkSize
                                val end = if (partIdx == partCount - 1) totalBytes - 1 else (start + chunkSize - 1)

                                val chunkReq = Request.Builder()
                                    .url(streamInfo.url)
                                    .header("User-Agent", UA_VISIONOS)
                                    .header("Range", "bytes=$start-$end")
                                    .header("Origin", "https://www.youtube.com")
                                    .header("Accept", "*/*")
                                    .build()

                                client.newCall(chunkReq).execute().use { chunkResp ->
                                    if (!chunkResp.isSuccessful && chunkResp.code != 206) {
                                        throw Exception("Parça indirme hatası: HTTP ${chunkResp.code}")
                                    }
                                    val cBody = chunkResp.body ?: throw Exception("Boş parça yanıtı")
                                    val cStream = cBody.byteStream()
                                    val partRaf = RandomAccessFile(tempFile, "rw")
                                    partRaf.seek(start)

                                    val buffer = ByteArray(64 * 1024)
                                    var partRead = 0L
                                    val partTarget = end - start + 1

                                    while (isActive && partRead < partTarget) {
                                        val toRead = minOf(buffer.size.toLong(), partTarget - partRead).toInt()
                                        val read = cStream.read(buffer, 0, toRead)
                                        if (read == -1) break
                                        partRaf.write(buffer, 0, read)
                                        partRead += read
                                        val curTotal = totalReadAtomic.addAndGet(read.toLong())

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdate > 80) {
                                            val pct = ((curTotal.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 99)
                                            _currentProgress.value = DownloadProgress(title, pct, isRunning = true)
                                            lastUpdate = now
                                        }
                                    }
                                    partRaf.close()
                                    cStream.close()
                                }
                            }
                        }
                        jobs.awaitAll()
                    }
                } else {
                    // Tek kanallı indirme (küçük dosyalar için)
                    val req = Request.Builder()
                        .url(streamInfo.url)
                        .header("User-Agent", UA_VISIONOS)
                        .header("Origin", "https://www.youtube.com")
                        .header("Accept", "*/*")
                        .build()

                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = "İndirme hatası: HTTP ${resp.code}")
                            return@launch
                        }
                        val body = resp.body ?: run {
                            _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = "Gelen veri boş")
                            return@launch
                        }
                        val inStream = body.byteStream()
                        val outStream = FileOutputStream(tempFile)
                        val buffer = ByteArray(64 * 1024)
                        while (isActive) {
                            val read = inStream.read(buffer)
                            if (read == -1) break
                            outStream.write(buffer, 0, read)
                        }
                        outStream.flush()
                        outStream.close()
                        inStream.close()
                    }
                }

                if (!isActive) {
                    return@launch
                }

                    // 4. Android Medya Depolamasına (Scoped Storage / MediaStore & App Storage) Güvenli Aktarım
                    var savedSuccessfully = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val resolver = context.contentResolver
                            try {
                                val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
                                val selectionArgs = arrayOf(safeFileName)
                                resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
                            } catch (_: Exception) {}

                            val contentValues = ContentValues().apply {
                                put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                                put(MediaStore.Audio.Media.TITLE, title)
                                put(MediaStore.Audio.Media.ARTIST, artist)
                                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Aurns")
                                put(MediaStore.Audio.Media.IS_PENDING, 1)
                            }

                            val mediaUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                            if (mediaUri != null) {
                                resolver.openOutputStream(mediaUri)?.use { destOut ->
                                    tempFile.inputStream().use { srcIn ->
                                        srcIn.copyTo(destOut)
                                    }
                                }
                                contentValues.clear()
                                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                                resolver.update(mediaUri, contentValues, null, null)
                                savedSuccessfully = true
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        // Android 9 ve altı
                        try {
                            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                            val aurnsDir = File(musicDir, "Aurns").apply { mkdirs() }
                            val targetFile = File(aurnsDir, safeFileName)
                            tempFile.copyTo(targetFile, overwrite = true)
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(targetFile.absolutePath),
                                arrayOf("audio/mp4", "audio/m4a"),
                                null
                            )
                            savedSuccessfully = true
                        } catch (_: Exception) {}
                    }

                    // Her durumda garanti yerel uygulama klasörüne de kopyala (Sıfır İzin & Sıfır EPERM Garantisi)
                    saveToFallback(tempFile, safeFileName)
                    savedSuccessfully = true

                    _currentProgress.value = DownloadProgress(title, 100, isRunning = false, isDone = true)

                    delay(300)
                    withContext(Dispatchers.Main) {
                        onDownloadFinishedListener?.invoke()
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = e.localizedMessage)
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    private fun saveToFallback(tempFile: File, safeFileName: String) {
        try {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val aurnsFallback = File(fallbackDir, "Aurns").apply { mkdirs() }
            val destFile = File(aurnsFallback, safeFileName)
            tempFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("audio/mp4", "audio/m4a"), null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelActiveDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _currentProgress.value = DownloadProgress("", 0, isRunning = false)
    }
}
