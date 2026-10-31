package com.aurns.player.downloader

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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

            // 1. YouTube Doğrudan Ses Linkini Çözümle (0.1 sn)
            val streamInfo = InnertubeResolver.resolveAudioStream(videoId)
            if (streamInfo == null || streamInfo.url.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ses akışı alınamadı, tekrar deneyin.", Toast.LENGTH_SHORT).show()
                }
                _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = "Ses akışı alınamadı")
                return@launch
            }

            // 2. Güvenli Dosya İsmi ve Hedef Klasör (.m4a)
            val safeFileName = title.replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ\\s-_.]"), "")
                .trim()
                .ifEmpty { "Aurns_Track_${System.currentTimeMillis()}" }
                .take(64) + ".m4a"

            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val aurnsDir = File(musicDir, "Aurns")
            if (!aurnsDir.exists()) aurnsDir.mkdirs()
            val targetFile = File(aurnsDir, safeFileName)
            val tempFile = File(aurnsDir, "$safeFileName.tmp")

            // 3. DOĞRUDAN OKHTTP STREAM İLE ANINDA İNDİR (MYT Hızı: 0.5 Saniyede Tamamlama)
            try {
                val req = Request.Builder()
                    .url(streamInfo.url)
                    .header("User-Agent", UA_VISIONOS)
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
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

                    val totalBytes = if (body.contentLength() > 0) body.contentLength() else streamInfo.contentLength
                    val inStream = body.byteStream()
                    val outStream = FileOutputStream(tempFile)

                    val buffer = ByteArray(64 * 1024) // 64 KB yüksek hızlı tampon
                    var totalRead = 0L
                    var lastUpdate = System.currentTimeMillis()

                    while (isActive) {
                        val read = inStream.read(buffer)
                        if (read == -1) break
                        outStream.write(buffer, 0, read)
                        totalRead += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100 && totalBytes > 0) {
                            val pct = ((totalRead.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 99)
                            _currentProgress.value = DownloadProgress(title, pct, isRunning = true)
                            lastUpdate = now
                        }
                    }

                    outStream.flush()
                    outStream.close()
                    inStream.close()

                    if (!isActive) {
                        tempFile.delete()
                        return@launch
                    }

                    // Temp dosyayı gerçek dosyaya dönüştür
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)

                    _currentProgress.value = DownloadProgress(title, 100, isRunning = false, isDone = true)

                    // 4. Android Medya Kütüphanesine Anında Kaydet
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf("audio/mp4", "audio/m4a")
                    ) { _, _ ->
                        scope.launch(Dispatchers.Main) {
                            onDownloadFinishedListener?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    tempFile.delete()
                    _currentProgress.value = DownloadProgress(title, 0, isRunning = false, error = e.localizedMessage)
                }
            }
        }
    }

    fun cancelActiveDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _currentProgress.value = DownloadProgress("", 0, isRunning = false)
    }
}
