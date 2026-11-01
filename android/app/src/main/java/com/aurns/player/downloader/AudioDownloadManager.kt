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
    val error: String? = null,
    val headline: String = ""
)

private const val UA_VISIONOS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

class AudioDownloadManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    var onCancelPlaylistQueue: (() -> Unit)? = null

    private val _currentProgress = MutableStateFlow(DownloadProgress("", 0, false))
    val currentProgress: StateFlow<DownloadProgress> = _currentProgress.asStateFlow()

    var onDownloadFinishedListener: (() -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun showBannerMessage(headline: String, title: String, percent: Int, isRunning: Boolean, isDone: Boolean = false) {
        _currentProgress.value = DownloadProgress(
            title = title,
            percent = percent.coerceIn(0, 100),
            isRunning = isRunning,
            isDone = isDone,
            headline = headline
        )
    }

    fun startDownload(videoId: String, title: String, artist: String, customHeadline: String = "") {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val ok = downloadTrackSync(videoId, title, artist, customHeadline, showErrorToast = true)
            if (ok) {
                delay(220)
                withContext(Dispatchers.Main) {
                    onDownloadFinishedListener?.invoke()
                }
            }
        }
    }

    // Hem tekil indirme, hem Jam eksik sarki yakalama, hem de Paylasilan Oynatma Listesi kuyrugu tarafindan ortak kullanilir!
    suspend fun downloadTrackSync(
        videoId: String,
        title: String,
        artist: String,
        customHeadline: String = "",
        showErrorToast: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val activeHeadline = customHeadline.ifEmpty { "PARÇA İNDİRİLİYOR (MYT HIZI)" }
        _currentProgress.value = DownloadProgress(title, 0, isRunning = true, headline = activeHeadline)

        val streamInfo = InnertubeResolver.resolveAudioStream(videoId)
        if (streamInfo == null || streamInfo.url.isEmpty()) {
            if (showErrorToast) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ses akışı alınamadı, tekrar deneyin.", Toast.LENGTH_SHORT).show()
                }
            }
            _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = "Ses akışı alınamadı", headline = activeHeadline)
            return@withContext false
        }

        // Windows ile %100 ayni [videoId] dosya isimlendirme formati: Jam ve Akilli Liste eslestirmesini garantiler!
        val safeBaseName = title
            .replace(Regex("\\[([A-Za-z0-9_-]{11})]\\s*$"), "")
            .replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ\\s-_.]"), "")
            .trim()
            .ifEmpty { "Aurns_Track_${System.currentTimeMillis()}" }
            .take(64)

        val idSuffix = if (videoId.length == 11) " [$videoId]" else ""
        val safeFileName = "$safeBaseName$idSuffix.m4a"
        val tempFile = File(context.cacheDir, "dl_${System.nanoTime()}.tmp")

        try {
            val totalBytes = streamInfo.contentLength

            if (totalBytes > 256 * 1024) {
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
                                cBody.byteStream().use { cStream ->
                                    RandomAccessFile(tempFile, "rw").use { partRaf ->
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
                                            if (now - lastUpdate > 75) {
                                                val pct = ((curTotal.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 99)
                                                _currentProgress.value = DownloadProgress(title, pct, isRunning = true, headline = activeHeadline)
                                                lastUpdate = now
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } else {
                val req = Request.Builder()
                    .url(streamInfo.url)
                    .header("User-Agent", UA_VISIONOS)
                    .header("Origin", "https://www.youtube.com")
                    .header("Accept", "*/*")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = "İndirme hatası: HTTP ${resp.code}", headline = activeHeadline)
                        return@withContext false
                    }
                    val body = resp.body ?: run {
                        _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = "Gelen veri boş", headline = activeHeadline)
                        return@withContext false
                    }
                    body.byteStream().use { inStream ->
                        FileOutputStream(tempFile).use { outStream ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive) {
                                val read = inStream.read(buffer)
                                if (read == -1) break
                                outStream.write(buffer, 0, read)
                            }
                            outStream.flush()
                        }
                    }
                }
            }

            if (!isActive) return@withContext false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val resolver = context.contentResolver
                    try {
                        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
                        resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, arrayOf(safeFileName))
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
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
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
                } catch (_: Exception) {}
            }

            saveToFallback(tempFile, safeFileName)
            _currentProgress.value = DownloadProgress(title, 100, isRunning = false, isDone = true, headline = "✓ İNDİRME TAMAMLANDI")
            return@withContext true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (isActive) {
                _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = e.localizedMessage, headline = activeHeadline)
            }
            return@withContext false
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
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
        onCancelPlaylistQueue?.invoke()
        downloadJob?.cancel()
        downloadJob = null
        _currentProgress.value = DownloadProgress("", 0, isRunning = false)
    }
}
