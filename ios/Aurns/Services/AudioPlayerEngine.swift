import Foundation
import AVFoundation
import MediaPlayer
import UIKit

/// AVFoundation + Kilit Ekranı / Denetim Merkezi (MPNowPlayingInfoCenter) +
/// Sıfır disk yazımlı direkt YouTube akış çalıcı (`▶ Çal`).
@MainActor
final class AudioPlayerEngine: ObservableObject {

    @Published var currentTrack: Track?
    @Published var isPlaying: Bool = false
    @Published var isBuffering: Bool = false
    @Published var positionMs: Int64 = 0
    @Published var durationMs: Int64 = 0
    @Published var currentArtwork: UIImage?

    var onTrackEnded: (() -> Void)?
    var onRemoteNext: (() -> Void)?
    var onRemotePrevious: (() -> Void)?
    var onRemoteTogglePlayPause: (() -> Void)?
    var onRemoteSeek: ((Int64) -> Void)?

    private let player = AVPlayer()
    private var timeObserverToken: Any?
    private var endObserver: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var rateObservation: NSKeyValueObservation?
    private var interruptionObserver: NSObjectProtocol?
    private var routeObserver: NSObjectProtocol?
    private var artworkTask: Task<Void, Never>?
    private let artworkCache = NSCache<NSString, UIImage>()

    init() {
        artworkCache.countLimit = 48
        configureAudioSession()
        configureRemoteCommands()
        observePlayerEvents()
    }

    func tearDown() {
        if let token = timeObserverToken {
            player.removeTimeObserver(token)
            timeObserverToken = nil
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
            self.interruptionObserver = nil
        }
        if let routeObserver {
            NotificationCenter.default.removeObserver(routeObserver)
            self.routeObserver = nil
        }
        stop()
    }

    // MARK: - Ses Oturumu & Arka Plan Oynatma

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        } catch {
            // Simülatör veya ilk başlatma kısıtı — oynatırken tekrar denenir
        }
    }

    private func configureRemoteCommands() {
        let cmd = MPRemoteCommandCenter.shared()

        cmd.playCommand.isEnabled = true
        cmd.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if let cb = self?.onRemoteTogglePlayPause { cb() } else { self?.resume() }
            }
            return .success
        }

        cmd.pauseCommand.isEnabled = true
        cmd.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if let cb = self?.onRemoteTogglePlayPause { cb() } else { self?.pause() }
            }
            return .success
        }

        cmd.togglePlayPauseCommand.isEnabled = true
        cmd.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if let cb = self?.onRemoteTogglePlayPause { cb() } else { self?.togglePlayPause() }
            }
            return .success
        }

        cmd.nextTrackCommand.isEnabled = true
        cmd.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.onRemoteNext?() }
            return .success
        }

        cmd.previousTrackCommand.isEnabled = true
        cmd.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.onRemotePrevious?() }
            return .success
        }

        cmd.changePlaybackPositionCommand.isEnabled = true
        cmd.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let ms = Int64(max(0, posEvent.positionTime * 1000.0))
            Task { @MainActor in
                if let cb = self?.onRemoteSeek { cb(ms) } else { self?.seek(toMs: ms) }
            }
            return .success
        }
    }

    private func observePlayerEvents() {
        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                let sec = CMTimeGetSeconds(time)
                if sec.isFinite && sec >= 0 {
                    self.positionMs = Int64(sec * 1000.0)
                }
                if let dur = self.player.currentItem?.duration {
                    let dSec = CMTimeGetSeconds(dur)
                    if dSec.isFinite && dSec > 0 {
                        self.durationMs = Int64(dSec * 1000.0)
                    }
                }
                self.updateNowPlayingElapsed()
            }
        }

        rateObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            Task { @MainActor in
                guard let self else { return }
                switch p.timeControlStatus {
                case .playing:
                    self.isPlaying = true
                    self.isBuffering = false
                case .waitingToPlayAtSpecifiedRate:
                    self.isBuffering = true
                case .paused:
                    self.isPlaying = false
                    self.isBuffering = false
                @unknown default:
                    break
                }
                self.updateNowPlayingInfo()
            }
        }

        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notif in
            guard let info = notif.userInfo,
                  let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeVal) else { return }
            Task { @MainActor in
                if type == .began {
                    self?.player.pause()
                } else if type == .ended,
                          let optVal = info[AVAudioSessionInterruptionOptionKey] as? UInt,
                          AVAudioSession.InterruptionOptions(rawValue: optVal).contains(.shouldResume) {
                    self?.player.play()
                }
            }
        }

        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] notif in
            guard let info = notif.userInfo,
                  let reasonVal = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonVal),
                  reason == .oldDeviceUnavailable else { return }
            Task { @MainActor in
                self?.player.pause()
            }
        }
    }

    // MARK: - Oynatma Kontrolü

    func play(track: Track, startMs: Int64 = 0, startPaused: Bool = false) {
        try? AVAudioSession.sharedInstance().setActive(true)

        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }

        let item: AVPlayerItem
        if let localURL = track.fileURL {
            item = AVPlayerItem(url: localURL)
            player.automaticallyWaitsToMinimizeStalling = true
        } else if let streamURL = track.streamURL {
            var options: [String: Any] = [:]
            if let ua = track.streamUserAgent, !ua.isEmpty {
                options["AVURLAssetHTTPHeaderFieldsKey"] = ["User-Agent": ua]
            }
            let asset = AVURLAsset(url: streamURL, options: options)
            item = AVPlayerItem(asset: asset)
            item.preferredForwardBufferDuration = 2.0
            player.automaticallyWaitsToMinimizeStalling = false
        } else {
            return
        }

        currentTrack = track
        positionMs = max(0, startMs)
        durationMs = Int64(max(0, track.durationSec)) * 1000
        isBuffering = track.isOnlineStream

        player.replaceCurrentItem(with: item)

        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.onTrackEnded?()
            }
        }

        statusObservation?.invalidate()
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] observed, _ in
            Task { @MainActor in
                guard let self else { return }
                if observed.status == .readyToPlay {
                    let dSec = CMTimeGetSeconds(observed.duration)
                    if dSec.isFinite && dSec > 0 {
                        self.durationMs = Int64(dSec * 1000.0)
                    }
                    self.updateNowPlayingInfo()
                } else if observed.status == .failed {
                    self.isBuffering = false
                    self.isPlaying = false
                }
            }
        }

        if startMs > 0 {
            let target = CMTime(value: startMs, timescale: 1000)
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                Task { @MainActor in
                    if !startPaused {
                        self?.player.play()
                    }
                }
            }
        } else if startPaused {
            player.pause()
        } else {
            player.play()
        }

        loadArtwork(for: track)
        updateNowPlayingInfo()
    }

    func togglePlayPause() {
        if isPlaying {
            pause()
        } else {
            resume()
        }
    }

    func pause() {
        player.pause()
        isPlaying = false
        updateNowPlayingInfo()
    }

    func resume() {
        guard currentTrack != nil else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        player.play()
        isPlaying = true
        updateNowPlayingInfo()
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        currentTrack = nil
        currentArtwork = nil
        isPlaying = false
        isBuffering = false
        positionMs = 0
        durationMs = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    func seek(toMs ms: Int64) {
        let clamped = max(0, durationMs > 0 ? min(ms, durationMs) : ms)
        positionMs = clamped
        let target = CMTime(value: clamped, timescale: 1000)
        player.seek(to: target, toleranceBefore: CMTime(value: 50, timescale: 1000), toleranceAfter: CMTime(value: 50, timescale: 1000))
        updateNowPlayingElapsed()
    }

    // MARK: - Kapak Resmi (Gömülü ID3/MP4 + Çevrimiçi Önizleme)

    func cachedArtwork(for key: String) -> UIImage? {
        artworkCache.object(forKey: key as NSString)
    }

    private func loadArtwork(for track: Track) {
        artworkTask?.cancel()
        let key = track.storageKey as NSString
        if let cached = artworkCache.object(forKey: key) {
            currentArtwork = cached
            updateNowPlayingInfo()
            return
        }
        currentArtwork = nil

        artworkTask = Task { [weak self] in
            var found: UIImage? = nil
            if let fileURL = track.fileURL {
                found = await Self.extractEmbeddedArtwork(from: fileURL)
            }
            if found == nil, let thumb = track.thumbnailURL, let url = URL(string: thumb) {
                if let (data, _) = try? await URLSession.shared.data(from: url) {
                    found = UIImage(data: data)
                }
            } else if found == nil, let ytId = track.ytId, MusicLibraryCore.isValidYtId(ytId),
                      let url = URL(string: "https://i.ytimg.com/vi/\(ytId)/mqdefault.jpg") {
                if let (data, _) = try? await URLSession.shared.data(from: url) {
                    found = UIImage(data: data)
                }
            }
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, self.currentTrack?.id == track.id else { return }
                if let img = found {
                    self.artworkCache.setObject(img, forKey: key)
                    self.currentArtwork = img
                    self.updateNowPlayingInfo()
                }
            }
        }
    }

    private static func extractEmbeddedArtwork(from url: URL) async -> UIImage? {
        let asset = AVURLAsset(url: url)
        guard let metadata = try? await asset.load(.commonMetadata) else { return nil }
        for item in metadata where item.commonKey == .commonKeyArtwork {
            if let data = try? await item.load(.dataValue), let img = UIImage(data: data) {
                return img
            }
        }
        return nil
    }

    // MARK: - Kilit Ekranı (MPNowPlayingInfoCenter)

    private func updateNowPlayingInfo() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        let split = MusicLibraryCore.splitDisplaySongAndArtist(title: track.title, artist: track.artist)
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: split.song,
            MPMediaItemPropertyArtist: split.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs) / 1000.0,
            MPMediaItemPropertyPlaybackDuration: Double(max(durationMs, Int64(track.durationSec) * 1000)) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        if let art = currentArtwork {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: art.size) { _ in art }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func updateNowPlayingElapsed() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs) / 1000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
