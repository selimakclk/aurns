import Foundation
import AVFoundation

/// Yerel kitaplık tarama (`Documents/` = iPhone Dosyalar uygulamasında doğrudan görünür),
/// sınırsız özel çalma listeleri, sürükle-bırak sıralama, çoklu seçim ve isimli `#PLAYLIST:` M3U8 yöneticisi.
@MainActor
final class LibraryManager: ObservableObject {

    @Published var allTracks: [Track] = []
    @Published var userPlaylists: [UserPlaylist] = []
    @Published var selectedPlaylistId: String? = nil // nil = Tüm Şarkılar
    @Published var isScanning: Bool = false

    private var customOrder: [String] = []
    private var hiddenKeys: Set<String> = []

    private static let supportedExtensions: Set<String> = [
        "m4a", "mp3", "wav", "flac", "aac", "caf", "aiff", "mp4", "ogg", "opus"
    ]

    var documentsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
    }

    private var metadataDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("Aurns", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    private var playlistsURL: URL { metadataDirectory.appendingPathComponent("user_playlists.json") }
    private var orderURL: URL { metadataDirectory.appendingPathComponent("track_order.json") }
    private var hiddenURL: URL { metadataDirectory.appendingPathComponent("hidden_keys.json") }

    init() {
        loadMetadata()
    }

    // MARK: - Aktif Görünüm

    var activePlaylist: UserPlaylist? {
        guard let id = selectedPlaylistId else { return nil }
        return userPlaylists.first { $0.id == id }
    }

    var displayedTracks: [Track] {
        guard let pl = activePlaylist else { return allTracks }
        let map = Dictionary(uniqueKeysWithValues: allTracks.map { ($0.storageKey, $0) })
        let inputs = matchInputs
        var out: [Track] = []
        out.reserveCapacity(pl.trackKeys.count)
        for key in pl.trackKeys {
            if MusicLibraryCore.isOnlinePlaylistKey(key),
               let parsed = MusicLibraryCore.parseOnlinePlaylistKey(key) {
                let localIdx: Int
                if let vid = parsed.ytId {
                    localIdx = MusicLibraryCore.findExactLocalTrackForYoutube(tracks: inputs, targetTitle: parsed.title, targetYtId: vid)
                } else {
                    localIdx = MusicLibraryCore.findBestTrackMatch(tracks: inputs, targetTitle: parsed.title, targetYtId: nil)
                }
                if localIdx >= 0 && localIdx < allTracks.count {
                    out.append(allTracks[localIdx])
                } else {
                    let split = MusicLibraryCore.splitDisplaySongAndArtist(title: parsed.title, artist: "Çevrimiçi")
                    let artLabel = split.artist.contains("Çevrimiçi") ? split.artist : "\(split.artist) • Çevrimiçi"
                    out.append(Track(
                        id: key,
                        title: parsed.title,
                        artist: artLabel,
                        fileURL: nil,
                        streamURL: nil,
                        streamUserAgent: nil,
                        durationSec: 0,
                        ytId: parsed.ytId,
                        thumbnailURL: parsed.ytId.map { "https://i.ytimg.com/vi/\($0)/mqdefault.jpg" }
                    ))
                }
            } else if let local = map[key] {
                out.append(local)
            }
        }
        return out
    }

    var matchInputs: [TrackMatchInput] {
        allTracks.map {
            TrackMatchInput(
                title: $0.title,
                artist: $0.artist,
                ytId: $0.ytId,
                path: $0.storageKey
            )
        }
    }

    // MARK: - Kitaplık Tarama

    func scanLibrary() async {
        isScanning = true
        defer { isScanning = false }

        let docs = documentsDirectory
        let hidden = hiddenKeys
        let order = customOrder
        let exts = Self.supportedExtensions

        let scanned = await Task.detached(priority: .userInitiated) { () -> [Track] in
            let fm = FileManager.default
            guard let enumerator = fm.enumerator(
                at: docs,
                includingPropertiesForKeys: [.isRegularFileKey, .creationDateKey, .contentModificationDateKey],
                options: [.skipsHiddenFiles]
            ) else { return [] }

            var fileURLs: [(url: URL, date: Date)] = []
            while let obj = enumerator.nextObject() as? URL {
                let ext = obj.pathExtension.lowercased()
                guard exts.contains(ext) else { continue }
                let vals = try? obj.resourceValues(forKeys: [.isRegularFileKey, .creationDateKey, .contentModificationDateKey])
                guard vals?.isRegularFile == true else { continue }
                let key = obj.lastPathComponent
                if hidden.contains(key) { continue }
                let date = vals?.contentModificationDate ?? vals?.creationDate ?? Date.distantPast
                fileURLs.append((obj, date))
            }

            // En yeni dosyalar varsayılan olarak üstte
            fileURLs.sort { $0.date > $1.date }

            var results: [Track] = []
            results.reserveCapacity(fileURLs.count)
            for item in fileURLs {
                let url = item.url
                let fileName = url.lastPathComponent
                let ytId = MusicLibraryCore.extractYouTubeId(fileName)
                let cleaned = MusicLibraryCore.cleanDisplayTitle(fileName, fallbackFileName: fileName)
                let split = MusicLibraryCore.splitDisplaySongAndArtist(title: cleaned, artist: "Yerel Parça")

                let asset = AVURLAsset(url: url)
                var durSec = 0
                if let dur = try? await asset.load(.duration) {
                    let s = CMTimeGetSeconds(dur)
                    if s.isFinite && s > 0 { durSec = Int(s.rounded()) }
                }

                results.append(Track(
                    id: fileName,
                    title: cleaned.isEmpty ? fileName : cleaned,
                    artist: split.artist,
                    fileURL: url,
                    streamURL: nil,
                    streamUserAgent: nil,
                    durationSec: durSec,
                    ytId: ytId,
                    thumbnailURL: nil
                ))
            }

            // Kullanıcının özel sıralamasını uygula; yeni inenler EN ÜSTTE kalır!
            if !order.isEmpty {
                let rank = Dictionary(uniqueKeysWithValues: order.enumerated().map { ($1, $0) })
                let newOnes = results.filter { rank[$0.storageKey] == nil }
                let existing = results.filter { rank[$0.storageKey] != nil }
                    .sorted { (rank[$0.storageKey] ?? Int.max) < (rank[$1.storageKey] ?? Int.max) }
                return newOnes + existing
            }
            return results
        }.value

        allTracks = scanned
        customOrder = scanned.map { $0.storageKey }
        saveMetadata()
    }

    /// Yeni indirilen şarkıyı kesinlikle EN ÜSTE (indeks 0) yerleştirir ve gerekirse hedef listeye ekler.
    func registerDownloadedFile(url: URL, targetPlaylistId: String? = nil) async -> Track? {
        let key = url.lastPathComponent
        hiddenKeys.remove(key)

        let ytId = MusicLibraryCore.extractYouTubeId(key)
        let cleaned = MusicLibraryCore.cleanDisplayTitle(key, fallbackFileName: key)
        let split = MusicLibraryCore.splitDisplaySongAndArtist(title: cleaned, artist: "İndirilen Parça")
        let asset = AVURLAsset(url: url)
        var durSec = 0
        if let dur = try? await asset.load(.duration) {
            let s = CMTimeGetSeconds(dur)
            if s.isFinite && s > 0 { durSec = Int(s.rounded()) }
        }

        let track = Track(
            id: key,
            title: cleaned.isEmpty ? key : cleaned,
            artist: split.artist,
            fileURL: url,
            streamURL: nil,
            streamUserAgent: nil,
            durationSec: durSec,
            ytId: ytId,
            thumbnailURL: nil
        )

        allTracks.removeAll { $0.storageKey == key }
        allTracks.insert(track, at: 0)
        customOrder = allTracks.map { $0.storageKey }

        if let pid = targetPlaylistId {
            userPlaylists = MusicLibraryCore.addTracksToPlaylist(lists: userPlaylists, playlistId: pid, keys: [key])
        }
        saveMetadata()
        return track
    }

    // MARK: - iPhone Dosyalar Uygulamasından Müzik İçe Aktarma

    func importAudioURLs(_ urls: [URL]) async -> Int {
        var added = 0
        let fm = FileManager.default
        let docs = documentsDirectory

        for rawURL in urls {
            let didStart = rawURL.startAccessingSecurityScopedResource()
            defer { if didStart { rawURL.stopAccessingSecurityScopedResource() } }

            var isDir: ObjCBool = false
            if fm.fileExists(atPath: rawURL.path, isDirectory: &isDir), isDir.boolValue {
                if let sub = fm.enumerator(at: rawURL, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) {
                    while let child = sub.nextObject() as? URL {
                        if Self.supportedExtensions.contains(child.pathExtension.lowercased()) {
                            if copySingleFileToDocs(child, docs: docs) { added += 1 }
                        }
                    }
                }
            } else if Self.supportedExtensions.contains(rawURL.pathExtension.lowercased()) {
                if copySingleFileToDocs(rawURL, docs: docs) { added += 1 }
            }
        }
        if added > 0 {
            await scanLibrary()
        }
        return added
    }

    private func copySingleFileToDocs(_ source: URL, docs: URL) -> Bool {
        let fm = FileManager.default
        let fileName = source.lastPathComponent
        let dest = docs.appendingPathComponent(fileName)
        hiddenKeys.remove(fileName)
        if fm.fileExists(atPath: dest.path) {
            return true
        }
        do {
            try fm.copyItem(at: source, to: dest)
            return true
        } catch {
            return false
        }
    }

    // MARK: - Sürükle-Bırak Sıralama & Kaldırma / Silme

    func moveTracks(from source: IndexSet, to destination: Int) {
        if let pid = selectedPlaylistId, let pIdx = userPlaylists.firstIndex(where: { $0.id == pid }) {
            var keys = userPlaylists[pIdx].trackKeys
            keys.move(fromOffsets: source, toOffset: destination)
            userPlaylists[pIdx].trackKeys = keys
            saveMetadata()
        } else {
            allTracks.move(fromOffsets: source, toOffset: destination)
            customOrder = allTracks.map { $0.storageKey }
            saveMetadata()
        }
    }

    func removeTracksFromCurrentView(keys: Set<String>) {
        guard !keys.isEmpty else { return }
        if let pid = selectedPlaylistId, let pIdx = userPlaylists.firstIndex(where: { $0.id == pid }) {
            userPlaylists[pIdx].trackKeys.removeAll { keys.contains($0) }
        } else {
            hiddenKeys.formUnion(keys)
            allTracks.removeAll { keys.contains($0.storageKey) }
            customOrder.removeAll { keys.contains($0) }
            for i in userPlaylists.indices {
                userPlaylists[i].trackKeys.removeAll { keys.contains($0) }
            }
        }
        saveMetadata()
    }

    func deleteTrackFromDevice(_ track: Track) {
        if let url = track.fileURL {
            try? FileManager.default.removeItem(at: url)
        }
        let key = track.storageKey
        hiddenKeys.remove(key)
        allTracks.removeAll { $0.storageKey == key }
        customOrder.removeAll { $0 == key }
        for i in userPlaylists.indices {
            userPlaylists[i].trackKeys.removeAll { $0 == key }
        }
        saveMetadata()
    }

    func clearLibraryHideAll() {
        for t in allTracks { hiddenKeys.insert(t.storageKey) }
        allTracks.removeAll()
        customOrder.removeAll()
        saveMetadata()
    }

    func restoreHiddenTracks() async {
        hiddenKeys.removeAll()
        saveMetadata()
        await scanLibrary()
    }

    // MARK: - Sınırsız Özel Çalma Listeleri

    @discardableResult
    func createPlaylist(name: String) -> UserPlaylist? {
        let (updated, created) = MusicLibraryCore.createPlaylist(lists: userPlaylists, rawName: name)
        guard let created else { return nil }
        userPlaylists = updated
        saveMetadata()
        return created
    }

    func renamePlaylist(id: String, newName: String) {
        let clean = String(newName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !clean.isEmpty, let idx = userPlaylists.firstIndex(where: { $0.id == id }) else { return }
        userPlaylists[idx].name = clean
        saveMetadata()
    }

    func deletePlaylist(id: String) {
        userPlaylists.removeAll { $0.id == id }
        if selectedPlaylistId == id { selectedPlaylistId = nil }
        saveMetadata()
    }

    func addTracksToPlaylist(playlistId: String, keys: [String]) {
        userPlaylists = MusicLibraryCore.addTracksToPlaylist(lists: userPlaylists, playlistId: playlistId, keys: keys)
        saveMetadata()
    }

    func removeTrackFromPlaylist(playlistId: String, key: String) {
        guard let idx = userPlaylists.firstIndex(where: { $0.id == playlistId }) else { return }
        userPlaylists[idx].trackKeys.removeAll { $0 == key }
        saveMetadata()
    }

    // MARK: - İsimli Akıllı M3U8 Dışa / İçe Aktarma (#PLAYLIST:)

    func exportPlaylistToTempFile(playlist: UserPlaylist?) -> URL? {
        let exportTracks: [Track]
        let exportName: String
        if let pl = playlist {
            let map = Dictionary(uniqueKeysWithValues: allTracks.map { ($0.storageKey, $0) })
            exportTracks = pl.trackKeys.compactMap { map[$0] }
            exportName = pl.name
        } else {
            exportTracks = allTracks
            exportName = "Tüm Şarkılar"
        }
        guard !exportTracks.isEmpty else { return nil }

        let entries = exportTracks.map { t in
            M3uTrackEntry(
                title: MusicLibraryCore.formatTrackFullTitle(title: t.title, artist: t.artist),
                pathOrUri: t.storageKey,
                ytId: t.ytId
            )
        }
        let content = MusicLibraryCore.buildM3u8Content(entries: entries, playlistName: exportName)
        let safeName = exportName
            .components(separatedBy: CharacterSet(charactersIn: "/\\:*?\"<>|\r\n\t"))
            .joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let fileName = "\(safeName.isEmpty ? "Aurns_Liste" : safeName).m3u8"
        let outURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        do {
            try content.write(to: outURL, atomically: true, encoding: .utf8)
            return outURL
        } catch {
            return nil
        }
    }

    func importM3u8File(url: URL) -> (playlist: UserPlaylist, matchedCount: Int, missing: [ParsedM3uEntry])? {
        let didStart = url.startAccessingSecurityScopedResource()
        defer { if didStart { url.stopAccessingSecurityScopedResource() } }

        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let lines = text.components(separatedBy: .newlines)
        let parsed = MusicLibraryCore.parseM3u8Lines(lines, existingTracks: matchInputs)

        let fallbackName = url.deletingPathExtension().lastPathComponent
            .replacingOccurrences(of: "_", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let targetName = parsed.playlistName.isEmpty
            ? (fallbackName.isEmpty ? "İçe Aktarılan Liste" : fallbackName)
            : parsed.playlistName

        let newPlaylist: UserPlaylist
        if let created = createPlaylist(name: targetName) {
            newPlaylist = created
        } else {
            return nil
        }

        let matchedKeys = parsed.matchedIndices.compactMap { idx -> String? in
            guard idx >= 0 && idx < allTracks.count else { return nil }
            return allTracks[idx].storageKey
        }
        if !matchedKeys.isEmpty {
            addTracksToPlaylist(playlistId: newPlaylist.id, keys: matchedKeys)
        }
        selectedPlaylistId = newPlaylist.id
        let refreshed = userPlaylists.first { $0.id == newPlaylist.id } ?? newPlaylist
        return (refreshed, parsed.matchedCount, parsed.missingTracks)
    }

    // MARK: - Kalıcılık (Application Support/Aurns)

    private func loadMetadata() {
        if let data = try? Data(contentsOf: playlistsURL),
           let decoded = try? JSONDecoder().decode([UserPlaylist].self, from: data) {
            userPlaylists = decoded
        }
        if let data = try? Data(contentsOf: orderURL),
           let decoded = try? JSONDecoder().decode([String].self, from: data) {
            customOrder = decoded
        }
        if let data = try? Data(contentsOf: hiddenURL),
           let decoded = try? JSONDecoder().decode([String].self, from: data) {
            hiddenKeys = Set(decoded)
        }
    }

    private func saveMetadata() {
        if let data = try? JSONEncoder().encode(userPlaylists) {
            try? data.write(to: playlistsURL, options: .atomic)
        }
        if let data = try? JSONEncoder().encode(customOrder) {
            try? data.write(to: orderURL, options: .atomic)
        }
        if let data = try? JSONEncoder().encode(Array(hiddenKeys)) {
            try? data.write(to: hiddenURL, options: .atomic)
        }
    }
}
