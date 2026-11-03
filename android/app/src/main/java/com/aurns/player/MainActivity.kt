package com.aurns.player

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurns.player.data.MusicScanner
import com.aurns.player.data.Track
import com.aurns.player.downloader.AudioDownloadManager
import com.aurns.player.downloader.DownloadProgress
import com.aurns.player.downloader.InnertubeResolver
import com.aurns.player.downloader.SearchResult
import com.aurns.player.friends.FriendItem
import com.aurns.player.friends.FriendRequest
import com.aurns.player.friends.FriendsManager
import com.aurns.player.service.PlaybackService
import com.aurns.player.ui.theme.*
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val APP_VERSION = "1.4.1"
private const val VERSION_CHECK_URL = "https://raw.githubusercontent.com/selimakclk/aurns/main/version.txt"
private const val APK_RELEASE_LATEST_URL = "https://github.com/selimakclk/aurns/releases/latest/download/Aurns.apk"

class MainActivity : ComponentActivity() {

    private lateinit var downloadManager: AudioDownloadManager
    private lateinit var friendsManager: FriendsManager
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingControllerAction: (() -> Unit)? = null

    private val playerPrefs by lazy { getSharedPreferences("aurns_player_prefs", Context.MODE_PRIVATE) }

    // Parça Listesi ve Kalıcı Oynatıcı Durumları (Windows src/main.c ile %100 birebir)
    private val tracks = mutableStateListOf<Track>()
    private val hiddenTrackIds = HashSet<Long>()
    private var currentTrackIndex by mutableIntStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var isShuffle by mutableStateOf(false)
    // 0 = Kapalı, 1 = Tümünü Tekrarla, 2 = Tek Parça Tekrarla
    private var repeatMode by mutableIntStateOf(0)
    private var isMuted by mutableStateOf(false)
    private var customFolderUri by mutableStateOf<String?>(null)

    // Sadece ilerleme çubuğunu izole güncelleyen akışlar (60 FPS, sıfır liste recomposition)
    private val currentPositionFlow = MutableStateFlow(0)
    private val durationFlow = MutableStateFlow(0)

    // Canlı Jam Eksik Parça Otomatik İndirme & Canlı Milisaniye Yakalama (Seek-to-Live)
    private var jamAutoDlJob: Job? = null
    private var jamWantTitle: String = ""
    private var jamWantYtId: String = ""
    private var jamWantBaseMs: Long = 0L
    private var jamWantRecvTimeMs: Long = 0L
    private var jamWantPlaying: Boolean = true

    // Paylaşılan Akıllı Liste (.m3u8 / .m3u / .aurns) Arka Plan Kuyruğu
    private var playlistImportJob: Job? = null
    private var pendingDeleteTrackId: Long = -1L
    private var pendingApkInstallFile: File? = null

    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaController?.let { p ->
                currentPositionFlow.value = (p.currentPosition.coerceAtLeast(0L) / 1000L).toInt()
                durationFlow.value = if (p.duration > 0) (p.duration / 1000L).toInt() else 0
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            refreshLocalTracksAsync()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val uriStr = treeUri.toString()
            customFolderUri = uriStr
            playerPrefs.edit().putString("custom_folder_uri", uriStr).apply()
            hiddenTrackIds.clear()
            refreshLocalTracksAsync(toastOnComplete = true)
        }
    }

    private val importPlaylistLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importSmartPlaylist(uri)
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK && pendingDeleteTrackId != -1L) {
            removeTrackEntryById(pendingDeleteTrackId)
            Toast.makeText(this, "✓ Parça cihazdan silindi", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteTrackId = -1L
    }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingApkInstallFile
        pendingApkInstallFile = null
        if (apk != null && apk.exists()) {
            installDownloadedApk(apk, retryPermission = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isShuffle = playerPrefs.getBoolean("is_shuffle", false)
        repeatMode = playerPrefs.getInt("repeat_mode", 0).coerceIn(0, 2)
        customFolderUri = playerPrefs.getString("custom_folder_uri", null)

        downloadManager = AudioDownloadManager(this)
        friendsManager = FriendsManager(this)

        downloadManager.onDownloadFinishedListener = {
            refreshLocalTracksAsync()
            Toast.makeText(this, "✓ Parça kütüphaneye eklendi!", Toast.LENGTH_SHORT).show()
        }

        downloadManager.onCancelPlaylistQueue = {
            playlistImportJob?.cancel()
            playlistImportJob = null
            cancelJamAutoDownload()
        }

        setupJamCallbacks()
        checkAndRequestPermissions()
        initMediaController()

        if (savedInstanceState == null) {
            handleIncomingPlaylistIntent(intent)
        }

        setContent {
            AurnsTheme {
                MainScreen(
                    tracks = tracks,
                    currentTrackIndex = currentTrackIndex,
                    isPlaying = isPlaying,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    isMuted = isMuted,
                    hasCustomFolder = !customFolderUri.isNullOrBlank(),
                    currentPositionFlow = currentPositionFlow,
                    durationFlow = durationFlow,
                    downloadManager = downloadManager,
                    friendsManager = friendsManager,
                    onPlayTrack = { index -> playTrack(index) },
                    onTogglePlay = { togglePlayPause() },
                    onSeek = { sec -> seekTo(sec) },
                    onNext = { playNext(fromAutoEnd = false) },
                    onPrev = { playPrev() },
                    onToggleShuffle = {
                        isShuffle = !isShuffle
                        playerPrefs.edit().putBoolean("is_shuffle", isShuffle).apply()
                    },
                    onToggleRepeat = {
                        repeatMode = (repeatMode + 1) % 3
                        playerPrefs.edit().putInt("repeat_mode", repeatMode).apply()
                    },
                    onToggleMute = { toggleMute() },
                    onStartAppUpdate = { versionStr -> startInAppSelfUpdate(versionStr) },
                    onPickSpecificFolder = { folderPickerLauncher.launch(null) },
                    onScanAllDeviceMusic = {
                        customFolderUri = null
                        playerPrefs.edit().remove("custom_folder_uri").apply()
                        hiddenTrackIds.clear()
                        refreshLocalTracksAsync(toastOnComplete = true)
                    },
                    onImportPlaylist = {
                        importPlaylistLauncher.launch(
                            arrayOf(
                                "audio/x-mpegurl",
                                "audio/mpegurl",
                                "application/vnd.apple.mpegurl",
                                "application/x-mpegurl",
                                "text/plain",
                                "*/*"
                            )
                        )
                    },
                    onExportPlaylist = { exportSmartPlaylist() },
                    onClearTracks = {
                        tracks.clear()
                        currentTrackIndex = -1
                        isPlaying = false
                        mediaController?.stop()
                    },
                    onShareSingleTrack = { track -> shareSingleTrack(track) },
                    onRemoveTrackFromList = { track ->
                        hiddenTrackIds.add(track.id)
                        removeTrackEntryById(track.id)
                    },
                    onDeleteTrackFromDevice = { track -> deleteTrackFromDevice(track) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPlaylistIntent(intent)
    }

    private fun handleIncomingPlaylistIntent(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_VIEW) {
            val dataUri = incoming.data ?: return
            lifecycleScope.launch {
                // İlk açılışta yerel parça taramasının tamamlanmasına kısa süre tanı
                delay(350)
                importSmartPlaylist(dataUri)
            }
        }
    }

    // ==================== CANLI JAM MİLİSANİYE SENKRONİZASYON MOTORU ====================

    private fun computeWantedJamLiveMs(): Long {
        val elapsed = if (jamWantPlaying && jamWantRecvTimeMs > 0L) {
            (System.currentTimeMillis() - jamWantRecvTimeMs).coerceAtLeast(0L) + 45L
        } else {
            0L
        }
        return (jamWantBaseMs + elapsed).coerceAtLeast(0L)
    }

    private fun cancelJamAutoDownload() {
        jamAutoDlJob?.cancel()
        jamAutoDlJob = null
        jamWantTitle = ""
        jamWantYtId = ""
    }

    private fun setupJamCallbacks() {
        // 1. Odadaki herhangi bir katılımcı şarkı açtığında veya durum bildirdiğinde (JAM_STATE)
        friendsManager.onRemoteJamStateRequest = { title, ytId, remotePlaying, posMs, recvTimeMs ->
            runOnUiThread {
                if (!friendsManager.jamState.value.isActive) return@runOnUiThread

                val netElapsed = (System.currentTimeMillis() - recvTimeMs).coerceIn(0L, 5000L)
                val liveMs = (posMs + if (remotePlaying) (netElapsed + 45L) else 0L).coerceAtLeast(0L)

                // A) Zaten aynı parça açık mı? Yalnızca kayma varsa milisaniye hizala!
                if (currentTrackIndex in tracks.indices &&
                    MusicScanner.isSameTrack(tracks[currentTrackIndex], title, ytId)
                ) {
                    if (jamAutoDlJob?.isActive == true) {
                        cancelJamAutoDownload()
                    }
                    friendsManager.setJamSyncStatus(
                        if (remotePlaying) "✓ Canlı milisaniye senkronize" else "⏸ Ortak duraklatıldı",
                        isDownloading = false
                    )
                    mediaController?.let { p ->
                        val drift = abs(p.currentPosition - liveMs)
                        val threshold = if (remotePlaying) 450L else 220L
                        if (drift > threshold) {
                            p.seekTo(liveMs)
                            currentPositionFlow.value = (liveMs / 1000L).toInt()
                        }
                        if (remotePlaying && !p.isPlaying) {
                            p.play()
                            isPlaying = true
                        } else if (!remotePlaying && p.isPlaying) {
                            p.pause()
                            isPlaying = false
                        }
                    }
                    return@runOnUiThread
                }

                // B) Parça yerel kütüphanede mevcut mu? Atomik olarak tam canlı milisaniyede aç!
                val foundIdx = MusicScanner.findBestTrackMatch(tracks, title, ytId)
                if (foundIdx >= 0) {
                    cancelJamAutoDownload()
                    friendsManager.setJamSyncStatus(
                        if (remotePlaying) "✓ Canlı milisaniye senkronize" else "⏸ Ortak duraklatıldı",
                        isDownloading = false
                    )
                    playTrack(
                        index = foundIdx,
                        startPosMs = liveMs,
                        startPlaying = remotePlaying,
                        isRemoteSync = true
                    )
                    return@runOnUiThread
                }

                // C) Parça bu cihazda YOK! Mevcut müziği durdur, arka planda indir, bitince 00:00 yerine CANLI SANİYEDEN başlat!
                mediaController?.pause()
                isPlaying = false

                val alreadyDownloadingSame = jamAutoDlJob?.isActive == true && (
                    (ytId.length == 11 && ytId.equals(jamWantYtId, ignoreCase = true)) ||
                    (title.isNotBlank() && MusicScanner.normalizeForMatch(title) == MusicScanner.normalizeForMatch(jamWantTitle))
                )

                jamWantTitle = title
                jamWantYtId = ytId
                jamWantBaseMs = posMs
                jamWantRecvTimeMs = recvTimeMs
                jamWantPlaying = remotePlaying

                if (alreadyDownloadingSame) {
                    return@runOnUiThread
                }

                jamAutoDlJob?.cancel()
                val displayTitle = title.ifBlank { "Ortak Jam Parçası" }
                friendsManager.setJamSyncStatus("⚡ Sende yok, indiriliyor: $displayTitle", isDownloading = true)

                jamAutoDlJob = lifecycleScope.launch {
                    try {
                        var targetVideoId = if (ytId.length == 11) ytId else ""
                        var dlTitle = displayTitle
                        var dlArtist = ""

                        if (targetVideoId.isEmpty() && title.isNotBlank()) {
                            val best = withContext(Dispatchers.IO) {
                                InnertubeResolver.search(title).firstOrNull()
                            }
                            if (best != null) {
                                targetVideoId = best.videoId
                                if (dlTitle == "Ortak Jam Parçası") dlTitle = best.title
                                dlArtist = best.artist
                            }
                        }

                        if (!isActive || targetVideoId.isEmpty()) {
                            friendsManager.setJamSyncStatus("Parça kaynağı bulunamadı", isDownloading = false)
                            return@launch
                        }

                        val ok = downloadManager.downloadTrackSync(
                            videoId = targetVideoId,
                            title = dlTitle,
                            artist = dlArtist,
                            customHeadline = "⚡ JAM ORTAK PARÇA İNDİRİLİYOR"
                        )

                        if (!isActive || !friendsManager.jamState.value.isActive) return@launch

                        if (ok) {
                            // MediaStore kaydının tamamlanması için kısa bekleme ve kütüphane yenileme
                            delay(180)
                            val updated = withContext(Dispatchers.IO) {
                                MusicScanner.scanLocalTracks(this@MainActivity, customFolderUri)
                                    .filterNot { it.id in hiddenTrackIds }
                            }
                            applyUpdatedTrackList(updated)

                            val matchIdx = MusicScanner.findBestTrackMatch(
                                tracks,
                                jamWantTitle.ifBlank { dlTitle },
                                targetVideoId
                            )
                            if (matchIdx >= 0) {
                                val catchUpMs = computeWantedJamLiveMs()
                                val shouldPlay = jamWantPlaying
                                jamWantTitle = ""
                                jamWantYtId = ""

                                friendsManager.setJamSyncStatus(
                                    "✓ Ortak parça indirildi • Canlı saniyeye eşitlendi",
                                    isDownloading = false
                                )
                                playTrack(
                                    index = matchIdx,
                                    startPosMs = catchUpMs,
                                    startPlaying = shouldPlay,
                                    isRemoteSync = true
                                )
                                // İndirme biter bitmez anlık faz kilidi için odaya güncel milisaniyeyi sor!
                                friendsManager.requestJamPartnerState()
                                delay(850)
                                if (isActive && friendsManager.jamState.value.isActive) {
                                    friendsManager.requestJamPartnerState()
                                }
                            } else {
                                friendsManager.setJamSyncStatus("İndirilen parça eşleştirilemedi", isDownloading = false)
                            }
                        } else {
                            friendsManager.setJamSyncStatus("Ortak parça indirilemedi", isDownloading = false)
                        }
                    } finally {
                        if (jamAutoDlJob == coroutineContext[Job]) {
                            jamAutoDlJob = null
                        }
                    }
                }
            }
        }

        // 2. Odadaki bir katılımcı duraklattığında, devam ettirdiğinde veya sardığında (JAM_POS)
        friendsManager.onRemoteJamPosRequest = { playFlag, posMs, recvTimeMs ->
            runOnUiThread {
                if (!friendsManager.jamState.value.isActive) return@runOnUiThread

                // Şarkı şu an arka planda indiriliyorsa hedef saniye ve çalma durumunu canlı güncelle!
                if (jamAutoDlJob?.isActive == true) {
                    jamWantBaseMs = posMs
                    jamWantRecvTimeMs = recvTimeMs
                    if (playFlag == 0) jamWantPlaying = false
                    else if (playFlag == 1) jamWantPlaying = true
                    return@runOnUiThread
                }

                // Henüz aktif bir şarkımız yoksa odadan tam şarkı durumunu iste (Kendi kendini onarma)
                if (currentTrackIndex !in tracks.indices) {
                    friendsManager.requestJamPartnerState()
                    return@runOnUiThread
                }

                val netElapsed = (System.currentTimeMillis() - recvTimeMs).coerceIn(0L, 3000L)
                mediaController?.let { p ->
                    when {
                        playFlag == 0 -> {
                            p.pause()
                            val safeMs = posMs.coerceAtLeast(0L)
                            p.seekTo(safeMs)
                            isPlaying = false
                            currentPositionFlow.value = (safeMs / 1000L).toInt()
                        }
                        playFlag == 1 -> {
                            val liveMs = (posMs + netElapsed).coerceAtLeast(0L)
                            if (abs(p.currentPosition - liveMs) > 300L) {
                                p.seekTo(liveMs)
                            }
                            p.play()
                            isPlaying = true
                            currentPositionFlow.value = (liveMs / 1000L).toInt()
                        }
                        else -> {
                            val liveMs = (posMs + if (p.isPlaying) netElapsed else 0L).coerceAtLeast(0L)
                            p.seekTo(liveMs)
                            currentPositionFlow.value = (liveMs / 1000L).toInt()
                        }
                    }
                }
            }
        }

        // 3. Odaya yeni katılan veya indirmesini bitiren bir üye anlık milisaniyemizi sorduğunda (JAM_ASK)
        friendsManager.onRemoteJamAskRequest = { requesterTag ->
            runOnUiThread {
                if (jamAutoDlJob?.isActive != true && currentTrackIndex in tracks.indices) {
                    val tr = tracks[currentTrackIndex]
                    mediaController?.let { p ->
                        friendsManager.sendJamStateToPeer(
                            targetTag = requesterTag,
                            title = tr.title,
                            ytId = tr.ytId ?: "",
                            isPlaying = p.isPlaying,
                            posMs = p.currentPosition.coerceAtLeast(0L)
                        )
                    }
                }
            }
        }

        friendsManager.onJamRoomClosed = {
            runOnUiThread {
                cancelJamAutoDownload()
            }
        }
    }

    // ==================== İZİNLER, TARAMA & MEDYA OYNATICI ====================

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            refreshLocalTracksAsync()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun applyUpdatedTrackList(newList: List<Track>) {
        val prevPlayingTrack = tracks.getOrNull(currentTrackIndex)
        tracks.clear()
        tracks.addAll(newList)
        if (prevPlayingTrack != null) {
            currentTrackIndex = tracks.indexOfFirst {
                it.id == prevPlayingTrack.id ||
                (prevPlayingTrack.path.isNotBlank() && it.path == prevPlayingTrack.path)
            }
        } else if (currentTrackIndex >= tracks.size) {
            currentTrackIndex = -1
        }
    }

    private fun refreshLocalTracksAsync(toastOnComplete: Boolean = false) {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                MusicScanner.scanLocalTracks(this@MainActivity, customFolderUri)
                    .filterNot { it.id in hiddenTrackIds }
            }
            applyUpdatedTrackList(list)
            if (toastOnComplete) {
                Toast.makeText(this@MainActivity, "✓ ${list.size} parça yüklendi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeTrackEntryById(trackId: Long) {
        val idx = tracks.indexOfFirst { it.id == trackId }
        if (idx == -1) return
        if (idx == currentTrackIndex) {
            mediaController?.stop()
            currentTrackIndex = -1
            isPlaying = false
        } else if (idx < currentTrackIndex) {
            currentTrackIndex -= 1
        }
        tracks.removeAt(idx)
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    val title = if (currentTrackIndex in tracks.indices) tracks[currentTrackIndex].title else ""
                    // Yalnızca arkadaş durum kartını günceller; Jam sıfırlama yayını yapmaz!
                    friendsManager.updateLocalPlaybackState(playing, title)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        playNext(fromAutoEnd = true)
                    }
                }
            })
            pendingControllerAction?.invoke()
            pendingControllerAction = null
            progressHandler.removeCallbacks(progressRunnable)
            progressHandler.post(progressRunnable)
        }, MoreExecutors.directExecutor())
    }

    private fun playTrack(
        index: Int,
        startPosMs: Long = 0L,
        startPlaying: Boolean = true,
        isRemoteSync: Boolean = false
    ) {
        if (index !in tracks.indices) return
        currentTrackIndex = index
        val track = tracks[index]
        val safeStartMs = startPosMs.coerceAtLeast(0L)
        isPlaying = startPlaying
        currentPositionFlow.value = (safeStartMs / 1000L).toInt()
        if (track.durationSec > 0) {
            durationFlow.value = track.durationSec
        }

        val openAction = {
            mediaController?.let { p ->
                // Atomik milisaniye açılışı: 00:00'dan tek bir ses örneği bile çalmadan doğrudan hedef milisaniyede başlar!
                p.setMediaItem(MediaItem.fromUri(track.uri), safeStartMs)
                p.prepare()
                p.playWhenReady = startPlaying
            }
            Unit
        }

        if (mediaController != null) {
            openAction()
        } else {
            pendingControllerAction = openAction
        }

        friendsManager.updateLocalPlaybackState(startPlaying, track.title)
        if (!isRemoteSync && friendsManager.jamState.value.isActive) {
            cancelJamAutoDownload()
            friendsManager.broadcastJamState(
                title = track.title,
                ytId = track.ytId ?: "",
                isPlaying = startPlaying,
                posMs = safeStartMs
            )
        }
    }

    private fun togglePlayPause() {
        if (currentTrackIndex !in tracks.indices) {
            if (tracks.isNotEmpty()) {
                playTrack(0)
            }
            return
        }
        mediaController?.let { p ->
            val curPosMs = p.currentPosition.coerceAtLeast(0L)
            val title = if (currentTrackIndex in tracks.indices) tracks[currentTrackIndex].title else ""
            if (p.isPlaying) {
                p.pause()
                isPlaying = false
                friendsManager.updateLocalPlaybackState(false, title)
                if (friendsManager.jamState.value.isActive) {
                    friendsManager.broadcastJamPos(0, curPosMs)
                }
            } else {
                p.play()
                isPlaying = true
                friendsManager.updateLocalPlaybackState(true, title)
                if (friendsManager.jamState.value.isActive) {
                    friendsManager.broadcastJamPos(1, curPosMs)
                }
            }
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        mediaController?.volume = if (isMuted) 0f else 1f
    }

    private fun startInAppSelfUpdate(versionStr: String) {
        val cleanVer = versionStr.trim().removePrefix("v").removePrefix("V")
        val taggedReleaseUrl = "https://github.com/selimakclk/aurns/releases/download/v$cleanVer/Aurns.apk"
        downloadManager.startApkUpdateDownload(
            primaryUrl = taggedReleaseUrl,
            fallbackUrl = APK_RELEASE_LATEST_URL,
            versionStr = cleanVer,
            onApkReady = { apkFile ->
                installDownloadedApk(apkFile, retryPermission = true)
            }
        )
    }

    private fun installDownloadedApk(apkFile: File, retryPermission: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                if (retryPermission) {
                    pendingApkInstallFile = apkFile
                    Toast.makeText(
                        this,
                        "Güncellemeyi kurmak için 'Bu kaynağa izin ver' seçeneğini açın.",
                        Toast.LENGTH_LONG
                    ).show()
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    unknownSourcesLauncher.launch(settingsIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Kurulum başlatılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun seekTo(sec: Int, isRemoteSync: Boolean = false) {
        val safeSec = sec.coerceAtLeast(0)
        val targetMs = safeSec * 1000L
        mediaController?.seekTo(targetMs)
        currentPositionFlow.value = safeSec
        if (!isRemoteSync && currentTrackIndex in tracks.indices && friendsManager.jamState.value.isActive) {
            friendsManager.broadcastJamPos(-1, targetMs)
        }
    }

    private fun playNext(fromAutoEnd: Boolean = false) {
        if (tracks.isEmpty()) return
        if (fromAutoEnd && repeatMode == 2 && currentTrackIndex in tracks.indices) {
            playTrack(currentTrackIndex)
            return
        }
        val next = when {
            isShuffle && tracks.size > 1 -> {
                var rnd = tracks.indices.random()
                if (rnd == currentTrackIndex) rnd = (rnd + 1) % tracks.size
                rnd
            }
            currentTrackIndex + 1 < tracks.size -> currentTrackIndex + 1
            !fromAutoEnd || repeatMode == 1 -> 0
            else -> -1
        }
        if (next in tracks.indices) {
            playTrack(next)
        } else {
            isPlaying = false
        }
    }

    private fun playPrev() {
        if (tracks.isEmpty()) return
        val prev = if (currentTrackIndex - 1 < 0) tracks.size - 1 else currentTrackIndex - 1
        playTrack(prev)
    }

    // ==================== AKILLI OYNATMA LİSTESİ İÇE / DIŞA AKTARMA (.m3u8) ====================

    private fun extractYtIdFromUrl(url: String): String? {
        val afterV = url.substringAfter("v=", "").take(11)
        if (afterV.length == 11 && afterV.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return afterV
        }
        val afterShort = url.substringAfter("youtu.be/", "").take(11)
        if (afterShort.length == 11 && afterShort.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return afterShort
        }
        return null
    }

    private fun exportSmartPlaylist() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Dışa aktarılacak parça yok!", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val m3uContent = buildString {
                append("#EXTM3U\n")
                for (t in tracks) {
                    val yt = t.ytId ?: MusicScanner.extractYouTubeId(t.path)
                    if (!yt.isNullOrBlank() && yt.length == 11) {
                        append("#EXTAURNS:YTID=").append(yt).append('\n')
                    }
                    val fullTitle = if (
                        t.title.contains(" - ") ||
                        t.artist.isBlank() ||
                        t.artist == "Yerel Parça" ||
                        t.artist == "İndirilen Parça"
                    ) {
                        t.title
                    } else {
                        "${t.artist} - ${t.title}"
                    }
                    append("#EXTINF:-1,").append(fullTitle).append('\n')
                    append(t.path.ifBlank { t.uri.toString() }).append('\n')
                }
            }

            val outFile = File(cacheDir, "Aurns_Listem.m3u8")
            outFile.writeText(m3uContent, Charsets.UTF_8)
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/x-mpegurl"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "ΛURNS Akıllı Çalma Listesi (${tracks.size} Parça)")
                clipData = ClipData.newRawUri("Aurns_Listem.m3u8", contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Akıllı Çalma Listesini Paylaş (.m3u8)"))
        } catch (e: Exception) {
            Toast.makeText(this, "Liste dışa aktarılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSmartPlaylist(uri: Uri) {
        lifecycleScope.launch {
            val parsedResult = withContext(Dispatchers.IO) {
                try {
                    val lines = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readLines() } ?: return@withContext null

                    var pendingTitle = ""
                    var pendingYtId = ""
                    var matchedLocalCount = 0
                    val missing = ArrayList<Pair<String, String>>()

                    for (rawLine in lines) {
                        val s = rawLine.removePrefix("\uFEFF").trim()
                        if (s.isEmpty()) continue

                        if (s.startsWith("#")) {
                            if (s.startsWith("#EXTAURNS:YTID=", ignoreCase = true)) {
                                val idCandidate = s.substring(15).trim()
                                if (idCandidate.length == 11) pendingYtId = idCandidate
                            } else if (s.startsWith("#EXTINF:", ignoreCase = true)) {
                                val comma = s.indexOf(',')
                                if (comma >= 0 && comma + 1 < s.length) {
                                    pendingTitle = s.substring(comma + 1).trim()
                                }
                            }
                            continue
                        }

                        val isUrl = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)
                        if (pendingYtId.isEmpty()) {
                            pendingYtId = (if (isUrl) extractYtIdFromUrl(s) else MusicScanner.extractYouTubeId(s)) ?: ""
                        }
                        if (pendingTitle.isEmpty() && !isUrl) {
                            val fileName = s.substringAfterLast('/').substringAfterLast('\\')
                            pendingTitle = MusicScanner.cleanDisplayTitle(fileName.substringBeforeLast('.'), fileName)
                        }
                        if (pendingTitle.isEmpty() && pendingYtId.isNotEmpty()) {
                            pendingTitle = "YouTube Parçası ($pendingYtId)"
                        }

                        val existingIdx = MusicScanner.findBestTrackMatch(tracks, pendingTitle, pendingYtId)
                        if (existingIdx >= 0) {
                            matchedLocalCount++
                        } else if (pendingTitle.isNotEmpty() || pendingYtId.isNotEmpty()) {
                            val isDup = missing.any { (mTitle, mYt) ->
                                (pendingYtId.isNotEmpty() && mYt.equals(pendingYtId, ignoreCase = true)) ||
                                (pendingTitle.isNotEmpty() && mTitle.equals(pendingTitle, ignoreCase = true))
                            }
                            if (!isDup) {
                                missing.add(Pair(pendingTitle, pendingYtId))
                            }
                        }

                        pendingTitle = ""
                        pendingYtId = ""
                    }
                    Pair(matchedLocalCount, missing)
                } catch (_: Exception) {
                    null
                }
            }

            if (parsedResult == null) {
                Toast.makeText(this@MainActivity, "Çalma listesi okunamadı!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val (matchedCount, missingList) = parsedResult
            if (missingList.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "✓ Listedeki $matchedCount parçanın tamamı kütüphanenizde hazır!",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            Toast.makeText(
                this@MainActivity,
                "⚡ $matchedCount parça hazır • Eksik ${missingList.size} parça otomatik indiriliyor...",
                Toast.LENGTH_LONG
            ).show()

            playlistImportJob?.cancel()
            playlistImportJob = lifecycleScope.launch {
                var downloadedOk = 0
                missingList.forEachIndexed { idx, (mTitle, mYtId) ->
                    if (!isActive) return@launch
                    val headline = "PAYLAŞILAN LİSTE YÜKLENİYOR • ${idx + 1} / ${missingList.size} Parça"
                    downloadManager.showBannerMessage(headline, mTitle, 0, isRunning = true)

                    var targetVid = if (mYtId.length == 11) mYtId else ""
                    var dlTitle = mTitle
                    var dlArtist = ""
                    if (targetVid.isEmpty()) {
                        val best = withContext(Dispatchers.IO) {
                            InnertubeResolver.search(mTitle).firstOrNull()
                        }
                        if (best != null) {
                            targetVid = best.videoId
                            dlTitle = best.title
                            dlArtist = best.artist
                        }
                    }

                    if (targetVid.isNotEmpty()) {
                        val ok = downloadManager.downloadTrackSync(
                            videoId = targetVid,
                            title = dlTitle,
                            artist = dlArtist,
                            customHeadline = headline
                        )
                        if (ok) {
                            downloadedOk++
                            delay(150)
                            val refreshed = withContext(Dispatchers.IO) {
                                MusicScanner.scanLocalTracks(this@MainActivity, customFolderUri)
                                    .filterNot { it.id in hiddenTrackIds }
                            }
                            applyUpdatedTrackList(refreshed)
                        }
                    }
                }
                downloadManager.showBannerMessage(
                    "✓ PAYLAŞILAN LİSTE TAMAMLANDI",
                    "$downloadedOk yeni parça kütüphaneye eklendi",
                    100,
                    isRunning = false,
                    isDone = true
                )
            }
        }
    }

    // ==================== TEKİL PARÇA PAYLAŞIMI & CİHAZDAN SİLME ====================

    private fun shareSingleTrack(track: Track) {
        try {
            val shareUri = when {
                track.uri.scheme == "content" -> track.uri
                track.path.isNotBlank() && File(track.path).exists() ->
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", File(track.path))
                else -> track.uri
            }
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_TITLE, track.title)
                clipData = ClipData.newRawUri(track.title, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Parçayı Paylaş"))
        } catch (e: Exception) {
            Toast.makeText(this, "Parça paylaşılamadı: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteTrackFromDevice(track: Track) {
        try {
            if (track.uri.scheme == "file" && track.path.isNotBlank()) {
                val f = File(track.path)
                if (!f.exists() || f.delete()) {
                    removeTrackEntryById(track.id)
                    Toast.makeText(this, "✓ Parça cihazdan silindi", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            if (DocumentsContract.isDocumentUri(this, track.uri)) {
                if (DocumentsContract.deleteDocument(contentResolver, track.uri)) {
                    removeTrackEntryById(track.id)
                    Toast.makeText(this, "✓ Parça cihazdan silindi", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            val deletedRows = contentResolver.delete(track.uri, null, null)
            val fallbackDeleted = if (track.path.isNotBlank()) {
                val f = File(track.path)
                f.exists() && f.delete()
            } else false

            if (deletedRows > 0 || fallbackDeleted) {
                removeTrackEntryById(track.id)
                Toast.makeText(this, "✓ Parça cihazdan silindi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Dosya silinemedi veya bulunamadı.", Toast.LENGTH_SHORT).show()
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && track.uri.scheme == "content") {
                try {
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(track.uri))
                    pendingDeleteTrackId = track.id
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    return
                } catch (_: Exception) {}
            }
            Toast.makeText(this, "Sistem bu dosyanın silinmesine izin vermedi.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Silme hatası: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        friendsManager.requestAllStatuses()
        if (friendsManager.jamState.value.isActive && jamAutoDlJob?.isActive != true) {
            friendsManager.requestJamPartnerState()
        }
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressRunnable)
        cancelJamAutoDownload()
        playlistImportJob?.cancel()
        friendsManager.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

// ==================== ANA EKRAN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tracks: List<Track>,
    currentTrackIndex: Int,
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: Int,
    isMuted: Boolean,
    hasCustomFolder: Boolean,
    currentPositionFlow: MutableStateFlow<Int>,
    durationFlow: MutableStateFlow<Int>,
    downloadManager: AudioDownloadManager,
    friendsManager: FriendsManager,
    onPlayTrack: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleMute: () -> Unit,
    onStartAppUpdate: (String) -> Unit,
    onPickSpecificFolder: () -> Unit,
    onScanAllDeviceMusic: () -> Unit,
    onImportPlaylist: () -> Unit,
    onExportPlaylist: () -> Unit,
    onClearTracks: () -> Unit,
    onShareSingleTrack: (Track) -> Unit,
    onRemoveTrackFromList: (Track) -> Unit,
    onDeleteTrackFromDevice: (Track) -> Unit
) {
    val context = LocalContext.current
    var isDrawerOpen by remember { mutableStateOf(false) }
    var isDownloadSheetOpen by remember { mutableStateOf(false) }
    var isFriendsPanelOpen by remember { mutableStateOf(false) }
    var openedFriendsFromDrawer by remember { mutableStateOf(false) }
    var isUpdateDialogOpen by remember { mutableStateOf(false) }
    var startupUpdateInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isFolderDialogOpen by remember { mutableStateOf(false) }
    var contextMenuTrack by remember { mutableStateOf<Track?>(null) }
    var confirmDeleteTrack by remember { mutableStateOf<Track?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val dlProgress by downloadManager.currentProgress.collectAsState()
    val jamState by friendsManager.jamState.collectAsState()
    val pendingRequests by friendsManager.pendingRequests.collectAsState()

    // Açılışta Sessiz Otomatik Güncelleme Denetimi (Windows WM_CREATE -> Updater_CheckAsync(hWnd, FALSE) ile Birebir)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("$VERSION_CHECK_URL?t=${System.currentTimeMillis()}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val lines = (resp.body?.string() ?: "")
                            .lines()
                            .map { it.removePrefix("\uFEFF").trim() }
                            .filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) {
                            val ver = lines[0]
                            if (compareSemVer(ver, APP_VERSION) > 0) {
                                val changelogLines = lines.drop(1).filterNot {
                                    it.startsWith("http://", ignoreCase = true) ||
                                    it.startsWith("https://", ignoreCase = true)
                                }
                                startupUpdateInfo = ver to changelogLines.joinToString("\n")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val closeFriendsPanel: () -> Unit = {
        isFriendsPanelOpen = false
        if (openedFriendsFromDrawer) {
            openedFriendsFromDrawer = false
            isDrawerOpen = true
        }
    }

    BackHandler(enabled = isFriendsPanelOpen || isDrawerOpen) {
        if (isFriendsPanelOpen) closeFriendsPanel()
        else if (isDrawerOpen) isDrawerOpen = false
    }

    // Türkçe karakter duyarsız hızlı arama (Windows src/playlist.c Playlist_MatchesQuery ile birebir)
    val filteredTracks = remember(tracks.size, tracks.hashCode(), searchQuery) {
        if (searchQuery.isBlank()) {
            tracks
        } else {
            val normQ = MusicScanner.normalizeForMatch(searchQuery)
            tracks.filter { t ->
                t.title.contains(searchQuery, ignoreCase = true) ||
                t.artist.contains(searchQuery, ignoreCase = true) ||
                (normQ.isNotEmpty() && (
                    MusicScanner.normalizeForMatch(t.title).contains(normQ) ||
                    MusicScanner.normalizeForMatch(t.artist).contains(normQ)
                ))
            }
        }
    }

    val currentPlayingTrackId = remember(tracks.size, currentTrackIndex) {
        if (currentTrackIndex in tracks.indices) tracks[currentTrackIndex].id else -1L
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (jamState.isActive) {
                    Modifier.background(Brush.verticalGradient(listOf(Color(0xFF140A18), BgObsidian)))
                } else {
                    Modifier.background(BgObsidian)
                }
            )
    ) {

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

            // 1. ÜST ÇUBUK (HEADER - Windows ΛURNS ile %100 Birebir)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(BgSurface)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { isDrawerOpen = true }) {
                    Box {
                        Icon(Icons.Default.Menu, contentDescription = "Menü", tint = TextWhite)
                        if (pendingRequests.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(AccentOrange)
                                    .align(Alignment.TopEnd)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "ΛURNS Logo",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Λ U R N S",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    letterSpacing = 2.5.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                // [Arkadaşlar / Jam (X)] Butonu
                val roomActiveOrInviting = jamState.isActive || jamState.isInviting
                Surface(
                    onClick = {
                        openedFriendsFromDrawer = false
                        isFriendsPanelOpen = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, if (roomActiveOrInviting) AccentPink else BorderDark),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Icon(
                                Icons.Default.Groups,
                                contentDescription = null,
                                tint = if (roomActiveOrInviting) AccentPink else AccentOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            if (pendingRequests.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(AccentPink)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when {
                                jamState.isActive -> "Jam (${jamState.totalParticipantCount})"
                                jamState.isInviting -> "Davet..."
                                else -> "Arkadaşlar"
                            },
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // [+ İndir] Butonu
                Surface(
                    onClick = { isDownloadSheetOpen = true },
                    shape = RoundedCornerShape(16.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(AccentOrange, AccentPink)))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "İndir",
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderDark, thickness = 0.5.dp)

            // ARAMA KUTUSU
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Şarkı veya sanatçı ara...", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Temizle", tint = TextMuted)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BgInput,
                    unfocusedContainerColor = BgInput,
                    focusedBorderColor = AccentOrange,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite
                )
            )

            // EVRENSEL CANLI İNDİRME ÇUBUĞU (Tekil, Jam Ortak Parça, Liste Kuyruğu ve ΛURNS Güncelleme)
            AnimatedVisibility(visible = dlProgress.isRunning || dlProgress.isDone || dlProgress.error != null) {
                DownloadProgressBanner(dlProgress = dlProgress, onCancel = { downloadManager.cancelActiveDownload() })
            }

            // GELEN JAM DAVETİ BİLDİRİMİ (Windows src/main.c:2933-2965 ile %100 Birebir)
            AnimatedVisibility(visible = jamState.hasIncomingInvite) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF1A0E20),
                    border = BorderStroke(1.dp, AccentPink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(AccentOrange, AccentPink))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Headphones, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${jamState.inviterName} seni Canlı Jam'e davet etti!",
                                color = TextWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Odadaki herkesle aynı saniyede canlı dinle",
                                color = TextDim,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(Brush.horizontalGradient(listOf(AccentOrange, AccentPink)))
                                .clickable { friendsManager.acceptJamInvite() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Katıl",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            onClick = { friendsManager.rejectJamInvite() },
                            shape = RoundedCornerShape(9.dp),
                            color = Color(0xFF28162C),
                            border = BorderStroke(0.5.dp, BorderDark)
                        ) {
                            Text(
                                text = "✕",
                                color = TextDim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // AKTİF VEYA KURULMAKTA OLAN ÇOK KİŞİLİ JAM ODASI BİLGİ BANDI
            AnimatedVisibility(visible = jamState.isActive || jamState.isInviting) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF1E1428),
                    border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(AccentOrange, AccentPink))),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (jamState.isActive) AccentPink else AccentOrange)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    openedFriendsFromDrawer = false
                                    isFriendsPanelOpen = true
                                }
                        ) {
                            Text(
                                text = jamState.formatHeadline(),
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subText = when {
                                jamState.isDownloadingTrack -> jamState.syncStatus
                                jamState.currentTrackTitle.isNotBlank() -> "${jamState.syncStatus} • ${jamState.currentTrackTitle}"
                                else -> jamState.syncStatus
                            }
                            Text(
                                text = subText,
                                color = AccentOrange,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            onClick = { friendsManager.leaveJamRoom() },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF33151E),
                            border = BorderStroke(0.5.dp, Color(0xFFFF3B30))
                        ) {
                            Text(
                                text = if (jamState.isInviting && !jamState.isActive) "İptal" else "Ayrıl",
                                color = Color(0xFFFF453A),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // SÜTUN BAŞLIĞI (# | PARÇA VE SANATÇI | X PARÇA — Windows src/main.c:2144-2160 ile Birebir)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted,
                    modifier = Modifier.width(26.dp)
                )
                Spacer(modifier = Modifier.width(18.dp))
                Text(
                    text = "PARÇA VE SANATÇI",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${filteredTracks.size} PARÇA",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted,
                    letterSpacing = 0.5.sp
                )
            }
            HorizontalDivider(
                color = BorderDark,
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            // ŞARKI LİSTESİ VEYA BOŞ KİTAPLIK KARTI (Windows src/main.c:2166-2185 ile Birebir)
            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 112.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BgSurface,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 26.dp)
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "Eşleşen şarkı bulunamadı" else "Müzik Kitaplığınız Hazır",
                                color = TextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) {
                                    "Farklı bir parça veya sanatçı adı yazmayı deneyin."
                                } else {
                                    "Sol üst menüden klasör seçebilir, çalma listesi içe aktarabilir veya + İndir ile şarkı ekleyebilirsiniz."
                                },
                                color = TextMuted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 132.dp, top = 4.dp)
                ) {
                    itemsIndexed(filteredTracks, key = { _, track -> track.id }) { index, track ->
                        val isCurrent = (track.id == currentPlayingTrackId)
                        TrackRowItem(
                            index = index,
                            track = track,
                            isCurrent = isCurrent,
                            isPlaying = isPlaying,
                            onClick = {
                                val realIndex = tracks.indexOfFirst { it.id == track.id }
                                if (realIndex != -1) {
                                    onPlayTrack(realIndex)
                                }
                            },
                            onOpenMenu = { contextMenuTrack = track }
                        )
                    }
                }
            }
        }

        // ALT YÜZEN OYNATICI DOCK (Her Zaman Görünür — Windows src/main.c:2367-2523 ile %100 Birebir)
        FloatingPlayerDock(
            track = tracks.getOrNull(currentTrackIndex),
            isPlaying = isPlaying,
            isShuffle = isShuffle,
            repeatMode = repeatMode,
            isMuted = isMuted,
            isJamActive = jamState.isActive,
            currentPositionFlow = currentPositionFlow,
            durationFlow = durationFlow,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onPrev = onPrev,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            onToggleMute = onToggleMute,
            onSeek = onSeek,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )

        // SOL ÇEKMECE MENÜ (DRAWER)
        if (isDrawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        isDrawerOpen = false
                    }
            )
        }

        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)
            ) + fadeIn(tween(180)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)
            ) + fadeOut(tween(180)),
            modifier = Modifier.fillMaxHeight().width(284.dp)
        ) {
            DrawerContent(
                trackCount = tracks.size,
                pendingCount = pendingRequests.size,
                hasCustomFolder = hasCustomFolder,
                onOpenFolderDialog = { isDrawerOpen = false; isFolderDialogOpen = true },
                onOpenDownload = { isDrawerOpen = false; isDownloadSheetOpen = true },
                onOpenFriends = {
                    isDrawerOpen = false
                    openedFriendsFromDrawer = true
                    isFriendsPanelOpen = true
                },
                onImportPlaylist = { isDrawerOpen = false; onImportPlaylist() },
                onExportPlaylist = { isDrawerOpen = false; onExportPlaylist() },
                onClearList = { isDrawerOpen = false; onClearTracks() },
                onCheckUpdates = { isDrawerOpen = false; isUpdateDialogOpen = true },
                onClose = { isDrawerOpen = false }
            )
        }

        // ARKADAŞLAR VE SINIRSIZ KİŞİLİK JAM PANELİ
        AnimatedVisibility(
            visible = isFriendsPanelOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)
            ) + fadeIn(tween(180)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)
            ) + fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            FriendsPanelScreen(
                friendsManager = friendsManager,
                onClose = closeFriendsPanel
            )
        }

        // YOUTUBE İNDİRME BOTTOM SHEET
        if (isDownloadSheetOpen) {
            YouTubeDownloadSheet(
                onDismiss = { isDownloadSheetOpen = false },
                onDownload = { res ->
                    downloadManager.startDownload(res.videoId, res.title, res.artist)
                    isDownloadSheetOpen = false
                }
            )
        }

        // MÜZİK KLASÖRÜ SEÇİM DİYALOĞU (Windows Klasör Seçimi Eşleniği)
        if (isFolderDialogOpen) {
            AlertDialog(
                onDismissRequest = { isFolderDialogOpen = false },
                containerColor = BgCard,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AccentOrange)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Müzik Klasörü Seç", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (hasCustomFolder) "Şu an özel seçilmiş bir klasörü dinliyorsunuz."
                                   else "Şu an cihazdaki tüm müzik arşivini tarıyorsunuz.",
                            color = TextDim,
                            fontSize = 12.sp
                        )
                        Surface(
                            onClick = {
                                isFolderDialogOpen = false
                                onPickSpecificFolder()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = BgSurface,
                            border = BorderStroke(1.dp, AccentOrange)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AccentOrange)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Belirli Klasör Seç", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Yalnızca seçtiğiniz klasördeki ses dosyalarını yükler", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                        Surface(
                            onClick = {
                                isFolderDialogOpen = false
                                onScanAllDeviceMusic()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = BgSurface,
                            border = BorderStroke(1.dp, BorderDark)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = AccentPink)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Tüm Cihaz Müziklerini Tara", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("İndirilenler ve tüm yerel müzik klasörlerini birleştirir", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { isFolderDialogOpen = false }) {
                        Text("Kapat", color = TextMuted)
                    }
                }
            )
        }

        // PARÇA SAĞ TIK / UZUN BASMA MENÜSÜ (Windows Sağ Tık Menüsü ile %100 Birebir)
        contextMenuTrack?.let { selectedTrack ->
            val (dispSong, dispArtist) = remember(selectedTrack.id, selectedTrack.title, selectedTrack.artist) {
                splitDisplaySongAndArtist(selectedTrack)
            }
            ModalBottomSheet(
                onDismissRequest = { contextMenuTrack = null },
                containerColor = BgSurface,
                dragHandle = { BottomSheetDefaults.DragHandle(color = BorderDark) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = dispSong,
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dispArtist,
                        color = AccentOrange,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(6.dp))

                    TrackContextActionRow(
                        icon = Icons.Default.PlayArrow,
                        label = "Şimdi Çal",
                        tint = AccentOrange
                    ) {
                        val realIdx = tracks.indexOfFirst { it.id == selectedTrack.id }
                        contextMenuTrack = null
                        if (realIdx != -1) onPlayTrack(realIdx)
                    }

                    TrackContextActionRow(
                        icon = Icons.Default.ContentCopy,
                        label = "Parça Adını Kopyala",
                        tint = TextWhite
                    ) {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Parça Adı", selectedTrack.title))
                        contextMenuTrack = null
                        Toast.makeText(context, "Parça adı kopyalandı!", Toast.LENGTH_SHORT).show()
                    }

                    TrackContextActionRow(
                        icon = Icons.Default.Share,
                        label = "Parçayı Paylaş",
                        tint = AccentPink
                    ) {
                        val tr = selectedTrack
                        contextMenuTrack = null
                        onShareSingleTrack(tr)
                    }

                    TrackContextActionRow(
                        icon = Icons.Default.PlaylistRemove,
                        label = "Listeden Kaldır",
                        tint = TextDim
                    ) {
                        val tr = selectedTrack
                        contextMenuTrack = null
                        onRemoveTrackFromList(tr)
                    }

                    TrackContextActionRow(
                        icon = Icons.Default.DeleteForever,
                        label = "Cihazdan Sil",
                        tint = Color(0xFFFF453A)
                    ) {
                        val tr = selectedTrack
                        contextMenuTrack = null
                        confirmDeleteTrack = tr
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        // CİHAZDAN SİLME ONAY DİYALOĞU
        confirmDeleteTrack?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmDeleteTrack = null },
                containerColor = BgCard,
                title = { Text("Cihazdan Silinsin mi?", color = TextWhite, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "'${target.title}' parçası cihazınızdan kalıcı olarak silinecek.",
                        color = TextDim,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val tr = target
                            confirmDeleteTrack = null
                            onDeleteTrackFromDevice(tr)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))
                    ) {
                        Text("Sil", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteTrack = null }) {
                        Text("Vazgeç", color = TextMuted)
                    }
                }
            )
        }

        // AÇILIŞ OTOMATİK GÜNCELLEME BİLDİRİMİ (Windows WM_APP_UPDATE_CHECK_DONE ile Birebir)
        startupUpdateInfo?.let { (newVer, changelog) ->
            AlertDialog(
                onDismissRequest = { startupUpdateInfo = null },
                containerColor = BgCard,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentOrange)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ΛURNS Güncellemesi", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column {
                        Text(
                            "Yeni bir ΛURNS sürümü mevcut!\n\nMevcut Sürüm: v$APP_VERSION\nYeni Sürüm: v$newVer",
                            color = AccentOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (changelog.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(changelog, color = TextDim, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            startupUpdateInfo = null
                            onStartAppUpdate(newVer)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                    ) {
                        Text("Şimdi Güncelle", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { startupUpdateInfo = null }) {
                        Text("Daha Sonra", color = TextMuted)
                    }
                }
            )
        }

        // CANLI GÜNCELLEMELERİ DENETLE DİYALOĞU (Windows src/updater.c ile Birebir)
        if (isUpdateDialogOpen) {
            LiveUpdateDialog(
                onDismiss = { isUpdateDialogOpen = false },
                onStartUpdate = { ver ->
                    isUpdateDialogOpen = false
                    onStartAppUpdate(ver)
                }
            )
        }
    }
}

@Composable
private fun TrackContextActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = if (tint == Color(0xFFFF453A)) tint else TextWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun compareSemVer(remoteVer: String, localVer: String): Int {
    val r = remoteVer.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val l = localVer.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0..2) {
        val rv = r.getOrElse(i) { 0 }
        val lv = l.getOrElse(i) { 0 }
        if (rv != lv) return rv - lv
    }
    return 0
}

@Composable
fun LiveUpdateDialog(
    onDismiss: () -> Unit,
    onStartUpdate: (String) -> Unit
) {
    var checking by remember { mutableStateOf(true) }
    var hasUpdate by remember { mutableStateOf(false) }
    var remoteVersion by remember { mutableStateOf(APP_VERSION) }
    var notes by remember { mutableStateOf("") }
    var checkError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("$VERSION_CHECK_URL?t=${System.currentTimeMillis()}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        checkError = true
                    } else {
                        val lines = (resp.body?.string() ?: "")
                            .lines()
                            .map { it.removePrefix("\uFEFF").trim() }
                            .filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) {
                            val ver = lines[0]
                            val changelogLines = lines.drop(1).filterNot {
                                it.startsWith("http://", ignoreCase = true) ||
                                it.startsWith("https://", ignoreCase = true)
                            }
                            remoteVersion = ver
                            notes = changelogLines.joinToString("\n")
                            hasUpdate = compareSemVer(ver, APP_VERSION) > 0
                        } else {
                            checkError = true
                        }
                    }
                }
            } catch (_: Exception) {
                checkError = true
            } finally {
                checking = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentOrange)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Güncellemeleri Denetle", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            if (checking) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AccentOrange, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Sunucudan son sürüm kontrol ediliyor...", color = TextDim, fontSize = 13.sp)
                }
            } else if (checkError) {
                Text(
                    "Güncelleme sunucusuna şu an ulaşılamadı.\nMevcut sürümünüz: v$APP_VERSION",
                    color = TextDim,
                    fontSize = 13.sp
                )
            } else if (hasUpdate) {
                Column {
                    Text(
                        "Yeni sürüm bulundu: v$remoteVersion (Mevcut: v$APP_VERSION)",
                        color = AccentOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(notes, color = TextDim, fontSize = 12.sp)
                    }
                }
            } else {
                Column {
                    Text(
                        "✓ En güncel sürümü kullanıyorsunuz (v$APP_VERSION).",
                        color = Color(0xFF34C759),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(notes, color = TextDim, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!checking && hasUpdate) {
                Button(
                    onClick = { onStartUpdate(remoteVersion) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                ) {
                    Text("Şimdi Güncelle", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Tamam", color = AccentOrange, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!checking && hasUpdate) {
                TextButton(onClick = onDismiss) {
                    Text("Daha Sonra", color = TextMuted)
                }
            }
        }
    )
}

// ==================== ARKADAŞLAR & SINIRSIZ KİŞİLİK JAM PANELİ ====================

@Composable
fun FriendsPanelScreen(
    friendsManager: FriendsManager,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val myTag by friendsManager.myTag.collectAsState()
    val myName by friendsManager.myName.collectAsState()
    val friends by friendsManager.friends.collectAsState()
    val pendingRequests by friendsManager.pendingRequests.collectAsState()
    val jamState by friendsManager.jamState.collectAsState()
    val isConnected by friendsManager.isConnected.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var isCopied by remember { mutableStateOf(false) }
    var isEditingInline by remember { mutableStateOf(false) }
    var newNameInput by remember { mutableStateOf("") }
    var addTagInput by remember { mutableStateOf("") }
    var confirmRemoveFriend by remember { mutableStateOf<FriendItem?>(null) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgObsidian
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Üst Bar (Windows src/main.c:2678-2700 ile Birebir)
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onClose,
                    shape = RoundedCornerShape(8.dp),
                    color = BgInput,
                    border = BorderStroke(0.5.dp, BorderDark)
                ) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            tint = TextWhite,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "Arkadaşlar & Jam",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) Color(0xFF3CE678) else Color(0xFFFF4B4B))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Canlı" else "Bağlan..",
                    color = if (isConnected) Color(0xFF3CE678) else Color(0xFFFF4B4B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(onClick = { friendsManager.requestAllStatuses() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = TextDim, modifier = Modifier.size(17.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // SENİN ETİKETİN KARTI (Satır İçi İsim Düzenleme — Windows src/main.c:2704-2739 ile %100 Birebir)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BgInput,
                border = BorderStroke(1.dp, if (isEditingInline) AccentOrange else BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isEditingInline) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ADINI DÜZENLE (#${myTag.substringAfter("#", "0000")} SABİT)",
                                color = AccentOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "#${myTag.substringAfter("#", "0000")}",
                                color = AccentPink,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newNameInput,
                                onValueChange = { newNameInput = it.replace("#", "").take(20) },
                                singleLine = true,
                                placeholder = { Text("Yeni kullanıcı adın...", color = TextMuted, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedBorderColor = AccentOrange,
                                    unfocusedBorderColor = BorderDark,
                                    focusedContainerColor = BgObsidian,
                                    unfocusedContainerColor = BgObsidian
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                onClick = {
                                    if (newNameInput.isNotBlank()) {
                                        friendsManager.updateMyName(newNameInput.trim())
                                        isEditingInline = false
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = AccentOrange
                            ) {
                                Text(
                                    text = "✓ Kaydet",
                                    color = Color(0xFF050505),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                onClick = { isEditingInline = false },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF28282E),
                                border = BorderStroke(0.5.dp, BorderDark)
                            ) {
                                Text(
                                    text = "İptal",
                                    color = TextDim,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2A142E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (myName.firstOrNull() ?: myTag.firstOrNull() ?: 'U').uppercase(),
                                color = AccentOrange,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(AccentPink)
                                    .align(Alignment.BottomEnd)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "SENİN ETİKETİN",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Adı Düzenle",
                                    tint = AccentOrange,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clickable {
                                            newNameInput = myName
                                            isEditingInline = true
                                        }
                                )
                            }
                            Text(
                                text = myTag,
                                color = AccentOrange,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                onClick = {
                                    newNameInput = myName
                                    isEditingInline = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF222226),
                                border = BorderStroke(0.5.dp, BorderDark)
                            ) {
                                Text(
                                    text = "Düzenle",
                                    color = AccentOrange,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }

                            Surface(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Aurns Tag", myTag))
                                    isCopied = true
                                    Toast.makeText(context, "Etiket kopyalandı!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCopied) AccentOrange else Color(0xFF28282E),
                                border = BorderStroke(0.5.dp, if (isCopied) AccentOrange else BorderDark)
                            ) {
                                Text(
                                    text = if (isCopied) "✓ Kopyalandı" else "Kopyala",
                                    color = if (isCopied) Color(0xFF050505) else TextWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Gelen Canlı Jam Daveti Kartı (Arkadaşlar paneli açıkken de doğrudan görünür!)
            if (jamState.hasIncomingInvite) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF221328),
                    border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(AccentOrange, AccentPink))),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎧 CANLI JAM DAVETİ",
                                color = AccentOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                text = "${jamState.inviterName.ifBlank { jamState.inviterTag }} seni ortak dinlemeye çağırıyor!",
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.horizontalGradient(listOf(AccentOrange, AccentPink)))
                                .clickable { friendsManager.acceptJamInvite() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Katıl", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            onClick = { friendsManager.rejectJamInvite() },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF28162C),
                            border = BorderStroke(0.5.dp, BorderDark)
                        ) {
                            Text(
                                text = "✕",
                                color = TextDim,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Aktif Jam Odası Katılımcı Özeti Kartı
            if (jamState.isActive || jamState.isInviting) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1428),
                    border = BorderStroke(1.dp, AccentPink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = jamState.formatHeadline(),
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Çevrimiçi arkadaşlarına '+ Davet' ile sınırsız kişi ekleyebilirsin.",
                                color = AccentOrange,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            onClick = { friendsManager.leaveJamRoom() },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF33151E),
                            border = BorderStroke(0.5.dp, Color(0xFFFF3B30))
                        ) {
                            Text(
                                text = if (jamState.isInviting && !jamState.isActive) "İptal" else "Ayrıl",
                                color = Color(0xFFFF453A),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // SEKMELER (Windows src/main.c:2742-2765 ile Birebir)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BgInput,
                border = BorderStroke(0.5.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val tabs = listOf(
                        "Liste (${friends.size})",
                        if (pendingRequests.isNotEmpty()) "İstek (${pendingRequests.size})" else "İstekler",
                        "+ Ekle"
                    )
                    tabs.forEachIndexed { index, label ->
                        val isSelected = (selectedTab == index)
                        Surface(
                            onClick = { selectedTab = index },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF231426) else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, AccentPink) else null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) TextWhite else TextDim,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 7.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (selectedTab) {
                0 -> {
                    if (friends.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Henüz arkadaş eklemediniz", color = TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("'+ Ekle' sekmesinden arkadaşının etiketini girerek davet gönder.", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        val roomOpen = jamState.isActive || jamState.isInviting
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(friends, key = { FriendsManager.extractTagId(it.tag).ifBlank { it.tag } }) { friend ->
                                val inRoom = jamState.isFriendInRoom(friend.tag)
                                val isInvitingOnly = jamState.isInviting && !jamState.isActive && inRoom
                                FriendRowItem(
                                    friend = friend,
                                    isInJamRoom = inRoom,
                                    isInvitingWaiting = isInvitingOnly,
                                    isRoomOpen = roomOpen,
                                    onInviteJam = {
                                        if (isInvitingOnly && jamState.members.isEmpty()) {
                                            friendsManager.leaveJamRoom()
                                        } else if (!inRoom) {
                                            friendsManager.requestAllStatuses()
                                            friendsManager.inviteFriendToJam(friend.tag, friend.name)
                                            Toast.makeText(
                                                context,
                                                "🎧 ${friend.name} Jam odasına davet edildi!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onRemove = { confirmRemoveFriend = friend }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }

                1 -> {
                    if (pendingRequests.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Bekleyen arkadaşlık isteği yok", color = TextMuted, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(pendingRequests, key = { it.tag }) { req ->
                                PendingRequestItem(
                                    request = req,
                                    onAccept = {
                                        val wasLast = pendingRequests.size <= 1
                                        friendsManager.acceptFriendRequest(req.tag)
                                        if (wasLast) selectedTab = 0
                                    },
                                    onReject = { friendsManager.rejectFriendRequest(req.tag) }
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }

                2 -> {
                    val addScope = rememberCoroutineScope()
                    var isCheckingUser by remember { mutableStateOf(false) }
                    var checkStatusMessage by remember { mutableStateOf<String?>(null) }
                    var isCheckError by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Text(
                            text = "Arkadaş Ekle",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Arkadaşının etiketini yaz (ör. Ahmet#4821)",
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = addTagInput,
                            onValueChange = {
                                addTagInput = it.take(30)
                                checkStatusMessage = null
                            },
                            placeholder = { Text("KullanıcıAdı#1234", color = TextMuted) },
                            singleLine = true,
                            enabled = !isCheckingUser,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedBorderColor = AccentOrange,
                                unfocusedBorderColor = BorderDark,
                                focusedContainerColor = BgInput,
                                unfocusedContainerColor = BgInput
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (addTagInput.isBlank()) {
                                    checkStatusMessage = "Lütfen bir kullanıcı etiketi girin!"
                                    isCheckError = true
                                    return@Button
                                }
                                addScope.launch {
                                    isCheckingUser = true
                                    checkStatusMessage = null
                                    val res = friendsManager.checkUserAndSendRequest(addTagInput)
                                    isCheckingUser = false
                                    if (res.isSuccess) {
                                        checkStatusMessage = "✓ İstek gönderildi!"
                                        isCheckError = false
                                        addTagInput = ""
                                    } else {
                                        checkStatusMessage = res.exceptionOrNull()?.message
                                            ?: "Böyle bir kullanıcı bulunamadı!"
                                        isCheckError = true
                                    }
                                }
                            },
                            enabled = !isCheckingUser,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            if (isCheckingUser) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color(0xFF050505),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Kullanıcı Aranıyor...", color = Color(0xFF050505), fontWeight = FontWeight.Bold)
                            } else {
                                Text("İstek Gönder", color = Color(0xFF050505), fontWeight = FontWeight.Bold)
                            }
                        }

                        if (checkStatusMessage != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCheckError) Color(0x33FF3B30) else Color(0x3334C759),
                                border = BorderStroke(1.dp, if (isCheckError) Color(0xFFFF3B30) else Color(0xFF34C759)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isCheckError) Icons.Default.Close else Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (isCheckError) Color(0xFFFF453A) else Color(0xFF3CE678),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = checkStatusMessage ?: "",
                                        color = if (isCheckError) Color(0xFFFF453A) else Color(0xFF3CE678),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ARKADAŞI KALDIRMA ONAY DİYALOĞU (Windows src/main.c:4300-4309 ile Birebir)
        confirmRemoveFriend?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmRemoveFriend = null },
                containerColor = BgCard,
                title = { Text("Arkadaşı Kaldır", color = TextWhite, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "${target.name} arkadaş listenden çıkarılsın mı?",
                        color = TextDim,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val tag = target.tag
                            confirmRemoveFriend = null
                            friendsManager.removeFriend(tag)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))
                    ) {
                        Text("Çıkar", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemoveFriend = null }) {
                        Text("Vazgeç", color = TextMuted)
                    }
                }
            )
        }
    }
}

@Composable
fun FriendRowItem(
    friend: FriendItem,
    isInJamRoom: Boolean,
    isInvitingWaiting: Boolean,
    isRoomOpen: Boolean,
    onInviteJam: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isInJamRoom) Color(0xFF221326) else BgInput,
        border = BorderStroke(1.dp, if (isInJamRoom) AccentPink else BorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF261C2C)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (friend.name.firstOrNull() ?: 'U').uppercase(),
                    color = AccentOrange,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (friend.isOnline) Color(0xFF3CE678) else Color(0xFF5A5A64))
                        .align(Alignment.BottomEnd)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.name,
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = friend.tag,
                    color = TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                val statusLabel = when {
                    !friend.isOnline -> "Çevrimdışı"
                    friend.isPlaying && friend.currentTrack.isNotBlank() -> "🎵 ${friend.currentTrack}"
                    else -> "Çevrimiçi"
                }
                val statusColor = when {
                    !friend.isOnline -> TextMuted
                    friend.isPlaying -> AccentOrange
                    else -> Color(0xFF3CE678)
                }
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = if (friend.isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Jam Butonu Her Zaman Görünür ve Tıklanabilir (Windows src/main.c:2824-2833 ile %100 Birebir)
            Surface(
                onClick = onInviteJam,
                shape = RoundedCornerShape(7.dp),
                color = when {
                    isInJamRoom -> Color(0xFF231028)
                    friend.isOnline -> AccentOrange
                    else -> Color(0xFF222226)
                },
                border = BorderStroke(
                    1.dp,
                    when {
                        isInJamRoom -> AccentPink
                        friend.isOnline -> AccentOrange
                        else -> BorderDark
                    }
                ),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = when {
                        isInvitingWaiting -> "Bekliyor"
                        isInJamRoom -> "Odada"
                        isRoomOpen -> "+ Davet"
                        else -> "Jam"
                    },
                    color = when {
                        isInJamRoom -> AccentPink
                        friend.isOnline -> Color(0xFF050505)
                        else -> TextDim
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }

            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Arkadaşı Kaldır", tint = TextMuted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
fun PendingRequestItem(
    request: FriendRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = BgInput,
        border = BorderStroke(1.dp, BorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = request.name, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(text = request.tag, color = TextDim, fontSize = 11.sp)
                Text(text = "Arkadaşlık isteği gönderdi", color = AccentPink, fontSize = 10.sp)
            }

            Surface(
                onClick = onAccept,
                shape = RoundedCornerShape(7.dp),
                color = AccentOrange
            ) {
                Text(
                    text = "Kabul",
                    color = Color(0xFF050505),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                onClick = onReject,
                shape = RoundedCornerShape(7.dp),
                color = Color(0xFF28282E),
                border = BorderStroke(0.5.dp, BorderDark)
            ) {
                Text(
                    text = "✕",
                    color = TextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// ==================== ÇEKMECE MENÜ & OYNATICI BİLEŞENLERİ ====================

// Görüntüleme Anında "Sanatçı - Şarkı" Ayırıcı (Windows Playlist_SplitArtistSong ile %100 Birebir)
private fun splitDisplaySongAndArtist(track: Track): Pair<String, String> {
    val full = track.title.trim()
    val dash = full.indexOf(" - ")
    if (dash in 1 until (full.length - 3)) {
        val leftArtist = full.substring(0, dash).trim()
        val rightSong = full.substring(dash + 3).trim()
        if (rightSong.isNotEmpty()) {
            val dispArtist = if (
                track.artist.isNotBlank() &&
                track.artist != "Yerel Parça" &&
                track.artist != "İndirilen Parça"
            ) {
                track.artist
            } else {
                leftArtist
            }
            return rightSong to dispArtist
        }
    }
    val fallbackArtist = track.artist.ifBlank { "Yerel Parça" }
    return full to fallbackArtist
}

@Composable
fun DrawerContent(
    trackCount: Int,
    pendingCount: Int,
    hasCustomFolder: Boolean,
    onOpenFolderDialog: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenFriends: () -> Unit,
    onImportPlaylist: () -> Unit,
    onExportPlaylist: () -> Unit,
    onClearList: () -> Unit,
    onCheckUpdates: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgSurface,
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "ΛURNS Logo",
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Λ U R N S",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextWhite,
                        letterSpacing = 2.5.sp
                    )
                    Text(
                        text = if (hasCustomFolder) "$trackCount parça (Özel Klasör)" else "$trackCount parça",
                        fontSize = 11.sp,
                        color = AccentOrange
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat", tint = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            DrawerMenuItem(
                icon = Icons.Default.FolderOpen,
                title = "Müzik Klasörü Seç",
                subtitle = if (hasCustomFolder) "Özel klasör seçili • Değiştir" else "Yerel arşivi yükle",
                onClick = onOpenFolderDialog
            )

            Spacer(modifier = Modifier.height(6.dp))

            DrawerMenuItem(
                icon = Icons.Default.CloudDownload,
                title = "Müzik Ara ve İndir",
                subtitle = "YouTube'dan şarkı ekle",
                isSpecial = true,
                onClick = onOpenDownload
            )

            Spacer(modifier = Modifier.height(6.dp))

            DrawerMenuItem(
                icon = Icons.Default.Groups,
                title = "Arkadaşlar ve Jam",
                subtitle = if (pendingCount > 0) "$pendingCount yeni arkadaşlık isteği!" else "Birlikte canlı dinle",
                isSpecial = true,
                badgeCount = pendingCount,
                onClick = onOpenFriends
            )

            Spacer(modifier = Modifier.height(6.dp))

            DrawerMenuItem(
                icon = Icons.Default.FileUpload,
                title = "Oynatma Listesini İçe Aktar",
                subtitle = ".m3u8 aç ve eksikleri indir",
                onClick = onImportPlaylist
            )

            Spacer(modifier = Modifier.height(6.dp))

            DrawerMenuItem(
                icon = Icons.Default.FileDownload,
                title = "Oynatma Listesini Dışa Aktar",
                subtitle = "Akıllı liste paylaş",
                onClick = onExportPlaylist
            )

            Spacer(modifier = Modifier.height(6.dp))

            DrawerMenuItem(
                icon = Icons.Default.DeleteSweep,
                title = "Listeyi Temizle",
                subtitle = "Ekranı sıfırla",
                onClick = onClearList
            )

            Spacer(modifier = Modifier.weight(1f))

            HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))

            DrawerMenuItem(
                icon = Icons.Default.SystemUpdate,
                title = "Güncellemeleri Denetle",
                subtitle = "v$APP_VERSION sürümü",
                onClick = onCheckUpdates
            )
        }
    }
}

@Composable
fun DrawerMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isSpecial: Boolean = false,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSpecial) Color(0xFF18121E) else BgInput,
        border = if (isSpecial) BorderStroke(1.dp, Brush.horizontalGradient(listOf(AccentOrange, AccentPink)))
                 else BorderStroke(0.5.dp, BorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSpecial) AccentPink.copy(alpha = 0.15f) else Color(0xFF222226)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isSpecial) AccentOrange else TextWhite,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = if (badgeCount > 0) AccentOrange else TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (badgeCount > 0) {
                Surface(
                    shape = CircleShape,
                    color = AccentPink
                ) {
                    Text(
                        text = "$badgeCount",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRowItem(
    index: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val (songName, artistName) = remember(track.id, track.title, track.artist) {
        splitDisplaySongAndArtist(track)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(11.dp))
            .then(
                if (isCurrent) {
                    Modifier
                        .background(Color(0xFF1A121C))
                        .border(1.dp, AccentPink.copy(alpha = 0.42f), RoundedCornerShape(11.dp))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onOpenMenu
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${index + 1}",
            fontSize = 12.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) AccentOrange else TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(26.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            AuraPulseDot(isCurrent = isCurrent, isPlaying = isPlaying)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = songName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) AccentOrange else TextWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistName,
                fontSize = 11.sp,
                color = if (isCurrent) TextDim else TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isCurrent) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF20121E),
                border = BorderStroke(1.dp, AccentPink.copy(alpha = 0.65f)),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = if (isPlaying) "ÇALIYOR" else "DURAKLAT",
                    color = AccentOrange,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        if (track.durationSec > 0) {
            Text(
                text = "%d:%02d".format(track.durationSec / 60, track.durationSec % 60),
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        IconButton(onClick = onOpenMenu, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Parça Seçenekleri",
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun AuraPulseDot(isCurrent: Boolean, isPlaying: Boolean) {
    if (isCurrent && isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "auraDot")
        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(3200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "auraColorPhase"
        )
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(750, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "auraPulse"
        )

        val liveColor = when {
            phase < 1f -> lerp(AccentOrange, AccentPink, phase)
            phase < 2f -> lerp(AccentPink, AccentPurple, phase - 1f)
            else -> lerp(AccentPurple, AccentOrange, phase - 2f)
        }

        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size((11f + pulse * 4f).dp)
                    .clip(CircleShape)
                    .background(liveColor.copy(alpha = 0.28f + 0.18f * pulse))
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(liveColor)
            )
            Box(
                modifier = Modifier
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.94f))
            )
        }
    } else if (isCurrent) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(AccentPink.copy(alpha = 0.26f))
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(AccentOrange)
            )
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.85f))
            )
        }
    } else {
        // Windows src/main.c:2299 ile birebir pasif satır aura noktası RGB(60, 60, 70)
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(Color(0xFF3C3C46))
        )
    }
}

@Composable
fun FloatingPlayerDock(
    track: Track?,
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: Int,
    isMuted: Boolean,
    isJamActive: Boolean,
    currentPositionFlow: MutableStateFlow<Int>,
    durationFlow: MutableStateFlow<Int>,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleMute: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val (dispSong, dispArtist) = remember(track?.id, track?.title, track?.artist) {
        if (track != null) splitDisplaySongAndArtist(track) else ("Hazır" to "Bir parçaya tıklayın")
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BgSurface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, if (isJamActive) AccentPink else BorderDark)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgInput)
                        .border(0.5.dp, BorderDark, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (track?.albumArtUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(track.albumArtUri)
                                .crossfade(false)
                                .size(100, 100)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (track != null) AccentOrange else TextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dispSong,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dispArtist,
                        fontSize = 11.sp,
                        color = if (track != null) TextDim else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Karışık Çal + Aktif Turuncu Nokta (Windows src/main.c:2384-2390)
                Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onToggleShuffle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Karışık",
                            tint = if (isShuffle) AccentOrange else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (isShuffle) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(AccentOrange)
                        )
                    }
                }

                IconButton(onClick = onPrev, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Önceki", tint = TextWhite, modifier = Modifier.size(20.dp))
                }

                // Beyaz Daire Oynat/Duraklat Düğmesi (Windows src/main.c:2396-2407 ile %100 Birebir)
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TextWhite)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Oynat/Duraklat",
                        tint = Color(0xFF050505),
                        modifier = Modifier.size(21.dp)
                    )
                }

                IconButton(onClick = onNext, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Sonraki", tint = TextWhite, modifier = Modifier.size(20.dp))
                }

                // 3 Aşamalı Tekrar + Aktif Turuncu Nokta (Windows src/main.c:2408-2420)
                Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onToggleRepeat, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Tekrar",
                            tint = if (repeatMode > 0) AccentOrange else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (repeatMode > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(AccentOrange)
                        )
                    }
                }

                // Hoparlör / Sessize Al Düğmesi (Windows src/main.c:2464-2474 ile Birebir)
                IconButton(onClick = onToggleMute, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Ses",
                        tint = if (isMuted) AccentPink else TextDim,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            PlayerProgressSlider(
                currentPositionFlow = currentPositionFlow,
                durationFlow = durationFlow,
                onSeek = onSeek
            )
        }
    }
}

@Composable
fun PlayerProgressSlider(
    currentPositionFlow: MutableStateFlow<Int>,
    durationFlow: MutableStateFlow<Int>,
    onSeek: (Int) -> Unit
) {
    val currentPos by currentPositionFlow.collectAsState()
    val duration by durationFlow.collectAsState()
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var barWidthPx by remember { mutableFloatStateOf(1f) }

    val activeFraction = if (isDragging) {
        dragFraction
    } else if (duration > 0) {
        (currentPos.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayedSec = if (isDragging && duration > 0) {
        (dragFraction * duration).toInt()
    } else {
        currentPos
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "%d:%02d".format(displayedSec / 60, displayedSec % 60),
            fontSize = 10.sp,
            color = if (isDragging) AccentOrange else TextMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 3 Renkli Sunset-Neon İlerleme Çubuğu (Turuncu -> Pembe -> Mor — Windows src/main.c:2444-2459 ile %100 Birebir)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .onSizeChanged { sz -> barWidthPx = sz.width.coerceAtLeast(1).toFloat() }
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        if (duration > 0) {
                            val frac = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            onSeek((frac * duration).toInt())
                        }
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (duration > 0) {
                                isDragging = true
                                dragFraction = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            if (duration > 0) {
                                change.consume()
                                dragFraction = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = {
                            if (isDragging && duration > 0) {
                                onSeek((dragFraction * duration).toInt())
                            }
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isDragging) 6.dp else 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF202024))
            ) {
                if (activeFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(activeFraction)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(AccentOrange, AccentPink, AccentPurple)))
                    )
                }
            }

            if (duration > 0) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val thumbSize = if (isDragging) 12.dp else 9.dp
                    val maxOffset = (maxWidth - thumbSize).coerceAtLeast(0.dp)
                    Box(
                        modifier = Modifier
                            .offset(x = maxOffset * activeFraction)
                            .size(thumbSize)
                            .clip(CircleShape)
                            .background(if (isDragging) AccentOrange else TextWhite)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "%d:%02d".format(duration / 60, duration % 60),
            fontSize = 10.sp,
            color = TextMuted,
            modifier = Modifier.width(34.dp)
        )
    }
}

@Composable
fun DownloadProgressBanner(dlProgress: DownloadProgress, onCancel: () -> Unit) {
    LaunchedEffect(dlProgress.isDone) {
        if (dlProgress.isDone) {
            delay(2500)
            onCancel()
        }
    }
    LaunchedEffect(dlProgress.error) {
        if (dlProgress.error != null) {
            delay(3500)
            onCancel()
        }
    }

    val bannerHeadline = when {
        dlProgress.error != null -> "HATA: ${dlProgress.error}"
        dlProgress.headline.isNotBlank() -> dlProgress.headline
        dlProgress.isDone -> "✓ İNDİRME TAMAMLANDI"
        else -> "YOUTUBE'DAN İNDİRİLİYOR"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141018))
            .border(1.dp, if (dlProgress.error != null) Color(0xFFFF3B30) else AccentPink.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bannerHeadline,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (dlProgress.isDone) AccentOrange else if (dlProgress.error != null) Color(0xFFFF3B30) else AccentPink
                )
                Text(
                    text = dlProgress.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (dlProgress.error != null) "!" else "%${dlProgress.percent}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (dlProgress.error != null) Color(0xFFFF3B30) else AccentOrange
            )
            if (dlProgress.isRunning) {
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onCancel, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "İptal", tint = TextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF202024))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((dlProgress.percent / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(AccentOrange, AccentPink, AccentPurple)))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeDownloadSheet(
    onDismiss: () -> Unit,
    onDownload: (SearchResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = BorderDark) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .fillMaxHeight(0.85f)
        ) {
            Text(
                text = "ΛURNS • Müzik Ara ve İndir",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Şarkı veya sanatçı ara...", color = TextMuted, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = AccentOrange,
                        unfocusedBorderColor = BorderDark,
                        focusedContainerColor = BgInput,
                        unfocusedContainerColor = BgInput
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (query.isNotBlank() && !isLoading) {
                            isLoading = true
                            hasSearched = true
                            scope.launch {
                                results = InnertubeResolver.search(query)
                                isLoading = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(50.dp)
                ) {
                    Text(
                        text = if (isLoading) "Aranıyor" else "Ara",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF050505)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentOrange, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("YouTube taranıyor...", color = TextMuted, fontSize = 12.sp)
                    }
                }
            } else if (hasSearched && results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Sonuç bulunamadı.", color = TextDim, fontSize = 13.sp)
                }
            } else if (!hasSearched) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Şarkı veya sanatçı adı yazıp Ara'ya dokunun.", color = TextMuted, fontSize = 12.sp)
                }
            } else {
                Text(
                    text = "${results.size} sonuç bulundu • Dokunarak hemen indirebilirsiniz",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(results, key = { _, item -> item.videoId }) { _, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDownload(item) }
                                .padding(vertical = 7.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(74.dp, 42.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BgInput)
                                    .border(0.5.dp, BorderDark, RoundedCornerShape(6.dp))
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(item.thumbnailUrl)
                                        .crossfade(false)
                                        .size(160, 90)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (item.durationText.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(2.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(BgSurface.copy(alpha = 0.9f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = item.durationText,
                                            color = TextWhite,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.artist,
                                    color = TextDim,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Surface(
                                onClick = { onDownload(item) },
                                shape = RoundedCornerShape(8.dp),
                                color = AccentOrange
                            ) {
                                Text(
                                    text = "İndir",
                                    color = Color(0xFF050505),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
