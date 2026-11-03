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
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val title: String,
    val percent: Int,
    val isRunning: Boolean,
    val isDone: Boolean = false,
    val error: String? = null,
    val headline: String = ""
)

class AudioDownloadManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    var onCancelPlaylistQueue: (() -> Unit)? = null

    private val _currentProgress = MutableStateFlow(DownloadProgress("", 0, false))
    val currentProgress: StateFlow<DownloadProgress> = _currentProgress.asStateFlow()

    var onDownloadFinishedListener: (() -> Unit)? = null

    private val client get() = InnertubeResolver.sharedHttpClient

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
                delay(80)
                withContext(Dispatchers.Main) {
                    onDownloadFinishedListener?.invoke()
                }
            }
        }
    }

    suspend fun downloadTrackSync(
        videoId: String,
        title: String,
        artist: String,
        customHeadline: String = "",
        showErrorToast: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val activeHeadline = customHeadline.ifEmpty { "PARÇA İNDİRİLİYOR (YÜKSEK HIZ)" }
        _currentProgress.value = DownloadProgress(title, 2, isRunning = true, headline = activeHeadline)

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

        val safeBaseName = title
            .replace(Regex("\\[([A-Za-z0-9_-]{11})]\\s*$"), "")
            .replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ\\s-_.]"), "")
            .trim()
            .ifEmpty { "Aurns_Track_${System.currentTimeMillis()}" }
            .take(64)

        val idSuffix = if (videoId.length == 11) " [$videoId]" else ""
        val safeFileName = "$safeBaseName$idSuffix.${streamInfo.fileExt}"
        val tempFile = File(context.cacheDir, "dl_${System.nanoTime()}.tmp")

        try {
            val totalBytes = streamInfo.contentLength
            val ua = streamInfo.userAgent.ifBlank { InnertubeResolver.UA_VISIONOS }
            var downloadSuccess = false

            if (totalBytes > 256 * 1024) {
                try {
                    RandomAccessFile(tempFile, "rw").use { raf ->
                        raf.setLength(totalBytes)
                    }

                    val partCount = 4
                    val chunkSize = totalBytes / partCount
                    val totalReadAtomic = AtomicLong(0L)
                    val lastUiUpdateMs = AtomicLong(0L)

                    coroutineScope {
                        val jobs = (0 until partCount).map { partIdx ->
                            async(Dispatchers.IO) {
                                val start = partIdx * chunkSize
                                val end = if (partIdx == partCount - 1) totalBytes - 1 else (start + chunkSize - 1)

                                val chunkReq = Request.Builder()
                                    .url(streamInfo.url)
                                    .header("User-Agent", ua)
                                    .header("Range", "bytes=$start-$end")
                                    .header("Accept", "*/*")
                                    .build()

                                client.newCall(chunkReq).execute().use { chunkResp ->
                                    if (!chunkResp.isSuccessful && chunkResp.code != 206) {
                                        throw Exception("Parça indirme hatası: HTTP ${chunkResp.code}")
                                    }
                                    val cBody = chunkResp.body ?: throw Exception("Boş parça yanıtı")
                                    writeStreamSlice(cBody.byteStream(), tempFile, start, end - start + 1, totalBytes, title, activeHeadline, totalReadAtomic, lastUiUpdateMs)
                                }
                            }
                        }
                        jobs.awaitAll()
                    }
                    downloadSuccess = true
                } catch (_: Exception) {
                    downloadSuccess = false
                }
            }

            // Çok parçalı Range isteği herhangi bir sebepten başarısız olursa temiz tekil akışla tamamla
            if (!downloadSuccess) {
                val req = Request.Builder()
                    .url(streamInfo.url)
                    .header("User-Agent", ua)
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
                    val streamTotal = if (totalBytes > 0) totalBytes else body.contentLength()
                    var streamRead = 0L
                    var lastUiMs = 0L
                    body.byteStream().use { inStream ->
                        FileOutputStream(tempFile).use { outStream ->
                            val buffer = ByteArray(128 * 1024)
                            while (isActive) {
                                val read = inStream.read(buffer)
                                if (read == -1) break
                                outStream.write(buffer, 0, read)
                                streamRead += read
                                val now = System.currentTimeMillis()
                                if (now - lastUiMs > 75 && streamTotal > 0) {
                                    lastUiMs = now
                                    val pct = ((streamRead.toDouble() / streamTotal) * 100).toInt().coerceIn(2, 99)
                                    _currentProgress.value = DownloadProgress(title, pct, isRunning = true, headline = activeHeadline)
                                }
                            }
                            outStream.flush()
                        }
                    }
                }
            }

            if (!isActive) return@withContext false

            var savedToMediaStore = false
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
                        put(MediaStore.Audio.Media.MIME_TYPE, streamInfo.mimeType)
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Aurns")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }

                    val mediaUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (mediaUri != null) {
                        resolver.openOutputStream(mediaUri)?.use { destOut ->
                            tempFile.inputStream().use { srcIn ->
                                srcIn.copyTo(destOut, 256 * 1024)
                            }
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        resolver.update(mediaUri, contentValues, null, null)
                        savedToMediaStore = true
                    }
                } catch (_: Exception) {}
            } else {
                try {
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val aurnsDir = File(musicDir, "Aurns").apply { mkdirs() }
                    val targetFile = File(aurnsDir, safeFileName)
                    tempFile.copyTo(targetFile, overwrite = true, bufferSize = 256 * 1024)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf(streamInfo.mimeType),
                        null
                    )
                    savedToMediaStore = true
                } catch (_: Exception) {}
            }

            // Yalnızca birincil kayıt başarısızsa yedek klasöre yaz
            if (!savedToMediaStore) {
                saveToFallback(tempFile, safeFileName, streamInfo.mimeType)
            }

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

    private fun CoroutineScope.writeStreamSlice(
        cStream: java.io.InputStream,
        tempFile: File,
        start: Long,
        partTarget: Long,
        totalBytes: Long,
        title: String,
        activeHeadline: String,
        totalReadAtomic: AtomicLong,
        lastUiUpdateMs: AtomicLong
    ) {
        cStream.use { stream ->
            RandomAccessFile(tempFile, "rw").use { partRaf ->
                partRaf.seek(start)
                val buffer = ByteArray(128 * 1024)
                var partRead = 0L

                while (isActive && partRead < partTarget) {
                    val toRead = minOf(buffer.size.toLong(), partTarget - partRead).toInt()
                    val read = stream.read(buffer, 0, toRead)
                    if (read == -1) break
                    partRaf.write(buffer, 0, read)
                    partRead += read
                    val curTotal = totalReadAtomic.addAndGet(read.toLong())

                    val now = System.currentTimeMillis()
                    val prev = lastUiUpdateMs.get()
                    if (now - prev > 65 && lastUiUpdateMs.compareAndSet(prev, now)) {
                        val pct = ((curTotal.toDouble() / totalBytes) * 100).toInt().coerceIn(2, 99)
                        _currentProgress.value = DownloadProgress(title, pct, isRunning = true, headline = activeHeadline)
                    }
                }
            }
        }
    }

    private fun saveToFallback(tempFile: File, safeFileName: String, mimeType: String) {
        try {
            val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val aurnsFallback = File(fallbackDir, "Aurns").apply { mkdirs() }
            val destFile = File(aurnsFallback, safeFileName)
            tempFile.copyTo(destFile, overwrite = true, bufferSize = 256 * 1024)
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
        } catch (_: Exception) {}
    }

    fun cancelActiveDownload() {
        onCancelPlaylistQueue?.invoke()
        downloadJob?.cancel()
        downloadJob = null
        _currentProgress.value = DownloadProgress("", 0, isRunning = false)
    }

    // Uygulama İçi Canlı APK Güncelleyici (Windows src/updater.c WM_APP_UPDATE_PROGRESS ile %100 Birebir)
    fun startApkUpdateDownload(
        primaryUrl: String,
        fallbackUrl: String,
        versionStr: String,
        onApkReady: (File) -> Unit
    ) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val headline = "ΛURNS GÜNCELLENİYOR"
            val title = "Yeni sürüm indiriliyor (v$versionStr)..."
            _currentProgress.value = DownloadProgress(title, 2, isRunning = true, headline = headline)

            val apkFile = File(context.cacheDir, "Aurns-update.apk")
            if (apkFile.exists()) apkFile.delete()

            suspend fun tryDownloadUrl(targetUrl: String): Boolean {
                return try {
                    val req = Request.Builder()
                        .url("$targetUrl?t=${System.currentTimeMillis()}")
                        .header("Accept", "*/*")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return false
                        val body = resp.body ?: return false
                        val totalLen = body.contentLength()
                        var downloaded = 0L
                        var lastPct = 2
                        body.byteStream().use { input ->
                            FileOutputStream(apkFile).use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (isActive) {
                                    val r = input.read(buf)
                                    if (r == -1) break
                                    output.write(buf, 0, r)
                                    downloaded += r
                                    val pct = if (totalLen > 0) {
                                        ((downloaded * 99L) / totalLen).toInt().coerceIn(2, 99)
                                    } else {
                                        ((downloaded * 95L) / 14_500_000L).toInt().coerceIn(2, 95)
                                    }
                                    if (pct > lastPct) {
                                        lastPct = pct
                                        _currentProgress.value = DownloadProgress(title, pct, isRunning = true, headline = headline)
                                    }
                                }
                                output.flush()
                            }
                        }
                    }
                    apkFile.exists() && apkFile.length() > 500_000L
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    if (apkFile.exists()) apkFile.delete()
                    false
                }
            }

            val ok = tryDownloadUrl(primaryUrl) || tryDownloadUrl(fallbackUrl)
            if (!isActive) return@launch

            if (ok) {
                _currentProgress.value = DownloadProgress("Kurulum ekranı açılıyor...", 100, isRunning = false, isDone = true, headline = "✓ GÜNCELLEME İNDİRİLDİ")
                withContext(Dispatchers.Main) {
                    onApkReady(apkFile)
                }
            } else {
                _currentProgress.value = DownloadProgress("Güncelleme indirilemedi", 0, isRunning = false, error = "Bağlantı hatası", headline = headline)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Güncelleme dosyası indirilemedi. İnternet bağlantınızı kontrol edin.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
