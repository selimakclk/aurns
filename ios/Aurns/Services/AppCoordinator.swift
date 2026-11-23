import Foundation
import SwiftUI

enum AppTab: Int, CaseIterable {
    case library = 0
    case search = 1
    case friends = 2
    case settings = 3
}

/// Uygulamanın tüm servislerini (Ses, Kitaplık, İndirme, Arkadaşlar/Jam, DSYNC1/DFULL1) tek yerde bağlayan koordinatör.
@MainActor
final class AppCoordinator: ObservableObject {

    static let appVersion = "1.7.9"

    let player = AudioPlayerEngine()
    let library = LibraryManager()
    let downloads = AudioDownloadManager()
    let friends = FriendsNetworkManager()

    @Published var selectedTab: AppTab = .library
    @Published var isLightTheme: Bool {
        didSet { UserDefaults.standard.set(isLightTheme, forKey: "aurns_light_theme") }
    }
    @Published var isShuffle: Bool = false
    @Published var repeatMode: RepeatMode = .off
    @Published var showNowPlaying: Bool = false
    @Published var toastMessage: String? = nil
    @Published var streamingVideoId: String? = nil
    @Published var pendingInitialSyncPromptCode: String? = nil

    private var toastTask: Task<Void, Never>?
    private var jamPendingTarget: (title: String, ytId: String, isPlaying: Bool, posMs: Int64, recvTimeMs: Int64)? = nil
    private var isApplyingRemoteJam = false
    private var lastPlaybackTitleBroadcast = ""
    private var lastPlaybackPlayingBroadcast = false

    init() {
        self.isLightTheme = UserDefaults.standard.bool(forKey: "aurns_light_theme")
        bindCallbacks()
        Task {
            await InnertubeResolver.shared.warmUp()
            await library.scanLibrary()
        }
    }

    // MARK: - Servis Bağlantıları

    private func bindCallbacks() {
        player.onTrackEnded = { [weak self] in self?.handleTrackEnded() }
        player.onRemoteNext = { [weak self] in self?.playNext(userInitiated: true) }
        player.onRemotePrevious = { [weak self] in self?.playPrevious() }
        player.onRemoteTogglePlayPause = { [weak self] in self?.togglePlayPause() }
        player.onRemoteSeek = { [weak self] ms in self?.seekToMs(ms, broadcastJam: true) }

        downloads.onTrackDownloaded = { [weak self] url, videoId, title, targetPlId, isFromSync in
            guard let self else { return }
            Task { @MainActor in
                let track = await self.library.registerDownloadedFile(url: url, targetPlaylistId: targetPlId)
                if !isFromSync && self.friends.deviceSyncState.isPaired {
                    self.friends.notifyLocalDownloadForSync(ytId: videoId, title: title)
                }
                if let pending = self.jamPendingTarget {
                    let idMatch = !pending.ytId.isEmpty && pending.ytId == videoId
                    let titleMatch = MusicLibraryCore.foldTurkish(pending.title) == MusicLibraryCore.foldTurkish(title)
                    if (idMatch || titleMatch), let track {
                        self.jamPendingTarget = nil
                        let elapsed = max(0, Int64(Date().timeIntervalSince1970 * 1000.0) - pending.recvTimeMs)
                        let targetPos = pending.isPlaying ? (pending.posMs + elapsed) : pending.posMs
                        self.applyRemoteJamTrack(track, posMs: targetPos, isPlaying: pending.isPlaying)
                    }
                }
                if !isFromSync {
                    self.showToast("İndirildi: \(MusicLibraryCore.cleanDisplayTitle(title))")
                }
            }
        }

        friends.onRemoteJamStateRequest = { [weak self] title, ytId, isPlaying, posMs, recvTimeMs in
            self?.handleRemoteJamState(title: title, ytId: ytId, isPlaying: isPlaying, posMs: posMs, recvTimeMs: recvTimeMs)
        }

        friends.onRemoteJamPosRequest = { [weak self] playFlag, posMs, recvTimeMs in
            self?.handleRemoteJamPos(playFlag: playFlag, posMs: posMs, recvTimeMs: recvTimeMs)
        }

        friends.onRemoteJamAskRequest = { [weak self] requesterTag in
            guard let self, let cur = self.player.currentTrack else { return }
            let fullTitle = MusicLibraryCore.formatTrackFullTitle(title: cur.title, artist: cur.artist)
            self.friends.sendJamStateToPeer(
                targetTag: requesterTag,
                title: fullTitle,
                ytId: cur.ytId ?? "",
                isPlaying: self.player.isPlaying,
                posMs: self.player.positionMs
            )
        }

        friends.onJamRoomClosed = { [weak self] in
            self?.jamPendingTarget = nil
        }

        friends.onDeviceSyncTracksReceived = { [weak self] items in
            guard let self else { return }
            let inputs = self.library.matchInputs
            var queued = 0
            for (ytId, title) in items {
                let idx = MusicLibraryCore.findExactLocalTrackForYoutube(
                    tracks: inputs,
                    targetTitle: title,
                    targetYtId: ytId.isEmpty ? nil : ytId
                )
                if idx >= 0 { continue }
                self.downloads.enqueue(videoId: ytId, title: title, isFromSync: true)
                queued += 1
            }
            if queued > 0 {
                self.showToast("Cihaz eşitleme: \(queued) yeni şarkı indiriliyor")
            }
        }
    }

    // MARK: - Bildirim Çubuğu

    func showToast(_ message: String) {
        toastTask?.cancel()
        toastMessage = message
        toastTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if !Task.isCancelled { self?.toastMessage = nil }
        }
    }

    // MARK: - Oynatma & Direkt Akış (`▶ Çal`)

    func playTrack(_ track: Track, broadcastJam: Bool = true) {
        player.play(track: track)
        notifyPlaybackPresence(track: track, isPlaying: true)
        if broadcastJam && !isApplyingRemoteJam && (friends.jamState.isActive || friends.jamState.isInviting) {
            let fullTitle = MusicLibraryCore.formatTrackFullTitle(title: track.title, artist: track.artist)
            friends.broadcastJamState(title: fullTitle, ytId: track.ytId ?? "", isPlaying: true, posMs: 0)
        }
    }

    func playDisplayedAt(index: Int) {
        let list = library.displayedTracks
        guard index >= 0 && index < list.count else { return }
        playTrack(list[index])
    }

    func playAllOrShuffle(shuffle: Bool) {
        let list = library.displayedTracks
        guard !list.isEmpty else {
            showToast("Kitaplıkta çalınacak şarkı yok")
            return
        }
        isShuffle = shuffle
        let target = shuffle ? list.randomElement()! : list[0]
        playTrack(target)
    }

    func playOnlineSearchResult(_ result: SearchResult) {
        let exactIdx = MusicLibraryCore.findExactLocalTrackForYoutube(
            tracks: library.matchInputs,
            targetTitle: result.title,
            targetYtId: result.videoId
        )
        if exactIdx >= 0 && exactIdx < library.allTracks.count {
            playTrack(library.allTracks[exactIdx])
            return
        }

        guard streamingVideoId != result.videoId else { return }
        streamingVideoId = result.videoId
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if self.streamingVideoId == result.videoId { self.streamingVideoId = nil } }
            var stream = await InnertubeResolver.shared.resolveAudioStream(videoId: result.videoId)
            if stream == nil {
                stream = await InnertubeResolver.shared.resolveAudioStream(videoId: result.videoId, bypassCache: true)
            }
            guard let info = stream, let url = URL(string: info.url) else {
                self.showToast("Canlı akış açılamadı, indirmeyi deneyin")
                return
            }
            let streamTrack = Track(
                id: "stream:\(result.videoId)",
                title: MusicLibraryCore.cleanDisplayTitle(result.title),
                artist: result.artist,
                fileURL: nil,
                streamURL: url,
                streamUserAgent: info.userAgent,
                durationSec: 0,
                ytId: result.videoId,
                thumbnailURL: result.thumbnailURL
            )
            self.playTrack(streamTrack)
        }
    }

    func togglePlayPause() {
        if player.currentTrack == nil {
            playAllOrShuffle(shuffle: isShuffle)
            return
        }
        player.togglePlayPause()
        let nowPlaying = player.isPlaying
        if let cur = player.currentTrack {
            notifyPlaybackPresence(track: cur, isPlaying: nowPlaying)
        }
        if !isApplyingRemoteJam && (friends.jamState.isActive || friends.jamState.isInviting) {
            friends.broadcastJamPos(playFlag: nowPlaying ? 1 : 0, posMs: player.positionMs)
        }
    }

    func seekToMs(_ ms: Int64, broadcastJam: Bool = true) {
        player.seek(toMs: ms)
        if broadcastJam && !isApplyingRemoteJam && (friends.jamState.isActive || friends.jamState.isInviting) {
            friends.broadcastJamPos(playFlag: -1, posMs: ms)
        }
    }

    func playNext(userInitiated: Bool) {
        let list = library.displayedTracks
        guard !list.isEmpty else { return }

        if !userInitiated && repeatMode == .one, let cur = player.currentTrack {
            playTrack(cur)
            return
        }

        if isShuffle {
            let candidates = list.filter { $0.id != player.currentTrack?.id }
            if let next = candidates.randomElement() ?? list.first {
                playTrack(next)
            }
            return
        }

        if let cur = player.currentTrack, let idx = list.firstIndex(where: { $0.id == cur.id }) {
            let nextIdx = idx + 1
            if nextIdx < list.count {
                playTrack(list[nextIdx])
            } else if repeatMode == .all || userInitiated {
                playTrack(list[0])
            } else {
                player.pause()
            }
        } else {
            playTrack(list[0])
        }
    }

    func playPrevious() {
        if player.positionMs > 3000 {
            seekToMs(0, broadcastJam: true)
            return
        }
        let list = library.displayedTracks
        guard !list.isEmpty else { return }
        if let cur = player.currentTrack, let idx = list.firstIndex(where: { $0.id == cur.id }) {
            let prevIdx = idx > 0 ? idx - 1 : list.count - 1
            playTrack(list[prevIdx])
        } else {
            playTrack(list[0])
        }
    }

    private func handleTrackEnded() {
        if friends.jamState.isActive && !friends.jamState.isHost {
            return
        }
        playNext(userInitiated: false)
    }

    private func notifyPlaybackPresence(track: Track, isPlaying: Bool) {
        let title = MusicLibraryCore.formatTrackFullTitle(title: track.title, artist: track.artist)
        if title == lastPlaybackTitleBroadcast && isPlaying == lastPlaybackPlayingBroadcast { return }
        lastPlaybackTitleBroadcast = title
        lastPlaybackPlayingBroadcast = isPlaying
        friends.updateLocalPlaybackState(isPlaying: isPlaying, trackTitle: title)
    }

    // MARK: - Canlı Jam Milisaniye Senkronizasyonu

    private func handleRemoteJamState(title: String, ytId: String, isPlaying: Bool, posMs: Int64, recvTimeMs: Int64) {
        let idx = MusicLibraryCore.findBestTrackMatch(
            tracks: library.matchInputs,
            targetTitle: title,
            targetYtId: ytId.isEmpty ? nil : ytId
        )
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000.0)
        let targetPos = isPlaying ? (posMs + max(0, nowMs - recvTimeMs)) : posMs

        if idx >= 0 && idx < library.allTracks.count {
            jamPendingTarget = nil
            applyRemoteJamTrack(library.allTracks[idx], posMs: targetPos, isPlaying: isPlaying)
            return
        }

        // Yerelde yok: anında çevrimiçi akışla başlat + arka planda indir!
        jamPendingTarget = (title, ytId, isPlaying, posMs, recvTimeMs)
        friends.setJamSyncStatus("Eksik parça indiriliyor: \(title)", isDownloading: true, missingTitle: title)
        downloads.enqueue(videoId: ytId, title: title, isFromSync: false)

        if MusicLibraryCore.isValidYtId(ytId) {
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let info = await InnertubeResolver.shared.resolveAudioStream(videoId: ytId),
                   let url = URL(string: info.url),
                   self.jamPendingTarget?.ytId == ytId {
                    let streamTrack = Track(
                        id: "stream:\(ytId)",
                        title: MusicLibraryCore.cleanDisplayTitle(title),
                        artist: "Canlı Jam",
                        fileURL: nil,
                        streamURL: url,
                        streamUserAgent: info.userAgent,
                        durationSec: 0,
                        ytId: ytId,
                        thumbnailURL: "https://i.ytimg.com/vi/\(ytId)/mqdefault.jpg"
                    )
                    let elapsed = max(0, Int64(Date().timeIntervalSince1970 * 1000.0) - recvTimeMs)
                    self.applyRemoteJamTrack(streamTrack, posMs: isPlaying ? (posMs + elapsed) : posMs, isPlaying: isPlaying)
                }
            }
        }
    }

    private func applyRemoteJamTrack(_ track: Track, posMs: Int64, isPlaying: Bool) {
        isApplyingRemoteJam = true
        defer { isApplyingRemoteJam = false }

        if player.currentTrack?.id == track.id {
            if abs(player.positionMs - posMs) > 650 {
                player.seek(toMs: posMs)
            }
            if isPlaying && !player.isPlaying { player.resume() }
            else if !isPlaying && player.isPlaying { player.pause() }
        } else {
            player.play(track: track, startMs: posMs, startPaused: !isPlaying)
        }
        friends.setJamSyncStatus(isPlaying ? "✓ Canlı milisaniye senkronize" : "⏸ Ortak duraklatıldı", isDownloading: false)
    }

    private func handleRemoteJamPos(playFlag: Int, posMs: Int64, recvTimeMs: Int64) {
        isApplyingRemoteJam = true
        defer { isApplyingRemoteJam = false }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000.0)
        let shouldPlay = playFlag < 0 ? player.isPlaying : (playFlag == 1)
        let targetPos = shouldPlay ? (posMs + max(0, nowMs - recvTimeMs)) : posMs

        if abs(player.positionMs - targetPos) > 550 {
            player.seek(toMs: targetPos)
        }
        if playFlag == 0 {
            player.pause()
            friends.setJamSyncStatus("⏸ Ortak duraklatıldı")
        } else if playFlag == 1 {
            player.resume()
            friends.setJamSyncStatus("✓ Canlı milisaniye senkronize")
        }
    }

    // MARK: - Cihaz Senkronizasyonu İlk Eşleştirme & Dış Dosya Açma

    func pairDeviceSyncWithPrompt(rawCode: String) -> Bool {
        guard friends.pairDeviceSync(rawCode) else { return false }
        pendingInitialSyncPromptCode = friends.deviceSyncState.syncCode
        return true
    }

    func generateDeviceSyncWithPrompt() -> String {
        let code = friends.generateAndPairDeviceSync()
        pendingInitialSyncPromptCode = code
        return code
    }

    func confirmInitialLibrarySync() {
        let count = friends.enableInitialLibrarySync(tracks: library.allTracks)
        pendingInitialSyncPromptCode = nil
        showToast("Mevcut liste (\(count) şarkı) diğer cihazla eşitleniyor")
    }

    func handleIncomingURL(_ url: URL) {
        let ext = url.pathExtension.lowercased()
        if ext == "m3u8" || ext == "m3u" || ext == "aurns" {
            importM3u8Playlist(from: url)
        } else {
            Task { @MainActor in
                let added = await library.importAudioURLs([url])
                if added > 0 { showToast("\(added) müzik dosyası kitaplığa eklendi") }
            }
        }
    }

    func importM3u8Playlist(from url: URL) {
        guard let res = library.importM3u8File(url: url) else {
            showToast("Çalma listesi okunamadı")
            return
        }
        for item in res.missing {
            downloads.enqueue(
                videoId: item.ytId,
                title: item.title,
                targetPlaylistId: res.playlist.id,
                isFromSync: false
            )
        }
        selectedTab = .library
        if res.missing.isEmpty {
            showToast("'\(res.playlist.name)' oluşturuldu (\(res.matchedCount) şarkı)")
        } else {
            showToast("'\(res.playlist.name)' oluşturuldu • \(res.matchedCount) mevcut, \(res.missing.count) indiriliyor")
        }
    }
}
