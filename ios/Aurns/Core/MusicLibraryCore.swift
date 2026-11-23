import Foundation

/// Windows (src/playlist.c) ve Android (MusicScanner.kt) ile %100 birebir
/// başlık temizleme, Türkçe katlama, katı remix ayrımı ve M3U8 çekirdeği.
enum MusicLibraryCore {

    private static let noiseWords: Set<String> = [
        "official", "video", "audio", "lyric", "lyrics", "music", "hd", "4k", "hq",
        "klip", "resmi", "sarki", "sozleri", "sozu", "feat", "ft", "featuring",
        "prod", "by", "vevo", "topic", "mp3", "m4a", "wav", "flac", "opus",
        "track", "full", "version", "edit", "clip", "visualizer", "remastered"
    ]

    private static let softJamEditionWords: Set<String> = [
        "live", "canli", "acoustic", "akustik", "unplugged", "performans", "performance"
    ]

    static func foldTurkish(_ raw: String) -> String {
        var out = ""
        out.reserveCapacity(raw.count)
        var lastWasSpace = true
        for scalar in raw.unicodeScalars {
            let mapped: Character?
            switch scalar.value {
            case 0x0041...0x005A: // A-Z
                if scalar.value == 0x0049 { mapped = "i" }
                else { mapped = Character(UnicodeScalar(scalar.value + 32)!) }
            case 0x0061...0x007A, 0x0030...0x0039: // a-z, 0-9
                mapped = Character(scalar)
            case 0x0130, 0x0131: mapped = "i" // İ, ı
            case 0x015E, 0x015F: mapped = "s" // Ş, ş
            case 0x011E, 0x011F: mapped = "g" // Ğ, ğ
            case 0x00DC, 0x00FC: mapped = "u" // Ü, ü
            case 0x00D6, 0x00F6: mapped = "o" // Ö, ö
            case 0x00C7, 0x00E7: mapped = "c" // Ç, ç
            default: mapped = nil
            }
            if let ch = mapped {
                out.append(ch)
                lastWasSpace = false
            } else if !lastWasSpace {
                out.append(" ")
                lastWasSpace = true
            }
        }
        return out.trimmingCharacters(in: .whitespaces)
    }

    static func extractYouTubeId(_ textOrPath: String?) -> String? {
        guard let raw = textOrPath, !raw.isEmpty else { return nil }
        let chars = Array(raw)
        let n = chars.count
        var i = 0
        while i < n {
            if chars[i] == "[" {
                var j = i + 1
                while j < n && chars[j] != "]" { j += 1 }
                if j < n {
                    var inner = String(chars[(i + 1)..<j])
                        .trimmingCharacters(in: CharacterSet(charactersIn: " _"))
                    if inner.count == 11 && isValidYtId(inner) {
                        return inner
                    }
                    // _id_ kalıbı
                    if inner.count == 13 && inner.hasPrefix("_") && inner.hasSuffix("_") {
                        inner = String(inner.dropFirst().dropLast())
                        if isValidYtId(inner) { return inner }
                    }
                }
                i = j
            }
            i += 1
        }
        return nil
    }

    static func extractYtIdFromUrl(_ url: String) -> String? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let patterns = [
            "(?:^|[?&/])v=([A-Za-z0-9_-]{11})(?:[&#?/]|$)",
            "youtu\\.be/([A-Za-z0-9_-]{11})(?:[&#?/]|$)",
            "youtube\\.com/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})(?:[&#?/]|$)"
        ]
        for pat in patterns {
            if let regex = try? NSRegularExpression(pattern: pat, options: [.caseInsensitive]),
               let match = regex.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)),
               match.numberOfRanges >= 2,
               let r = Range(match.range(at: 1), in: trimmed) {
                let candidate = String(trimmed[r])
                if isValidYtId(candidate) { return candidate }
            }
        }
        return nil
    }

    static func isValidYtId(_ s: String) -> Bool {
        guard s.count == 11 else { return false }
        for ch in s {
            let ok = (ch >= "A" && ch <= "Z") || (ch >= "a" && ch <= "z") ||
                     (ch >= "0" && ch <= "9") || ch == "_" || ch == "-"
            if !ok { return false }
        }
        return true
    }

    static func cleanDisplayTitle(_ rawTitle: String, fallbackFileName: String = "") -> String {
        var s = rawTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { s = fallbackFileName }
        // Uzantı ayıkla
        let exts = [".m4a", ".mp3", ".opus", ".webm", ".wav", ".flac", ".aac", ".ogg"]
        for ext in exts {
            if s.lowercased().hasSuffix(ext) {
                s = String(s.dropLast(ext.count))
                break
            }
        }
        // [ytId] etiketi ve parantez içi gürültü etiketlerini temizle
        if let rxBracket = try? NSRegularExpression(pattern: "\\s*\\[[A-Za-z0-9_-]{11}\\]\\s*$") {
            s = rxBracket.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
        }
        if let rxNoise = try? NSRegularExpression(
            pattern: "\\s*[\\(\\[](?:official\\s*(?:music\\s*)?(?:video|audio|lyric\\s*video|klip)|resmi\\s*klip|lyric\\s*video|lyrics|audio|hd|4k|hq|visualizer)[\\)\\]]",
            options: [.caseInsensitive]
        ) {
            s = rxNoise.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
        }
        if let rxSpaces = try? NSRegularExpression(pattern: "\\s{2,}") {
            s = rxSpaces.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: " ")
        }
        return s.trimmingCharacters(in: CharacterSet(charactersIn: " -_"))
    }

    static func splitDisplaySongAndArtist(title: String, artist: String) -> (song: String, artist: String) {
        let full = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if let dashRange = full.range(of: " - ") {
            let leftArtist = String(full[..<dashRange.lowerBound]).trimmingCharacters(in: .whitespaces)
            let rightSong = String(full[dashRange.upperBound...]).trimmingCharacters(in: .whitespaces)
            if !leftArtist.isEmpty && !rightSong.isEmpty {
                let dispArtist: String
                if !artist.isEmpty && artist != "Yerel Parça" && artist != "İndirilen Parça" {
                    dispArtist = artist
                } else {
                    dispArtist = leftArtist
                }
                return (rightSong, dispArtist)
            }
        }
        return (full, artist.isEmpty ? "Yerel Parça" : artist)
    }

    static func formatTrackFullTitle(title: String, artist: String) -> String {
        if title.contains(" - ") || artist.isEmpty || artist == "Yerel Parça" || artist == "İndirilen Parça" {
            return title
        }
        return "\(artist) - \(title)"
    }

    static func matchesSearchTokens(title: String, artist: String, query: String) -> Bool {
        let qFold = foldTurkish(query)
        guard !qFold.isEmpty else { return true }
        let hay = foldTurkish("\(artist) \(title)")
        if hay.contains(qFold) { return true }
        let tokens = qFold.split(separator: " ")
        return !tokens.isEmpty && tokens.allSatisfy { hay.contains($0) }
    }

    private static func splitMeaningfulTokens(_ folded: String, stripSoftEdition: Bool) -> [String] {
        folded.split(separator: " ").compactMap { sub in
            let w = String(sub)
            if w.count <= 1 { return nil }
            if noiseWords.contains(w) { return nil }
            if stripSoftEdition && softJamEditionWords.contains(w) { return nil }
            return w
        }
    }

    private static func tokensExactMatch(_ a: [String], _ b: [String]) -> Bool {
        guard !a.isEmpty && a.count == b.count else { return false }
        return Set(a) == Set(b)
    }

    /// Katı YouTube ↔ Yerel eşleşme (PC Playlist_FindExactForYoutube & Android findExactLocalTrackForYoutube ile %100 birebir!):
    /// Farklı 11 haneli ytId veya remix/mashup/milli sürümler ASLA orijinal şarkıyla aynı sayılmaz!
    static func findExactLocalTrackForYoutube(
        tracks: [TrackMatchInput],
        targetTitle: String,
        targetYtId: String?
    ) -> Int {
        guard !tracks.isEmpty else { return -1 }
        let cleanTargetId = targetYtId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let hasTargetId = isValidYtId(cleanTargetId)

        // 1. Katman: 11 haneli YouTube ID birebir eşleşmesi
        if hasTargetId {
            for (i, t) in tracks.enumerated() {
                let itemId = t.ytId ?? extractYouTubeId(t.path) ?? extractYouTubeId(t.title) ?? ""
                if !itemId.isEmpty && itemId == cleanTargetId {
                    return i
                }
            }
        }

        let cleanedTarget = cleanDisplayTitle(targetTitle)
        let targetFold = foldTurkish(cleanedTarget)
        guard !targetFold.isEmpty else { return -1 }
        let targetTokens = splitMeaningfulTokens(targetFold, stripSoftEdition: false)
        let (tSongRaw, tArtRaw) = splitDisplaySongAndArtist(title: cleanedTarget, artist: "")
        let tSongTok = splitMeaningfulTokens(foldTurkish(tSongRaw), stripSoftEdition: false)
        let tArtTok = splitMeaningfulTokens(foldTurkish(tArtRaw), stripSoftEdition: false)

        // 2. Katman: YALNIZCA [ytId] taşımayan yerel MP3/M4A dosyaları için katı başlık eşleşmesi
        for (i, t) in tracks.enumerated() {
            if hasTargetId {
                let itemId = t.ytId ?? extractYouTubeId(t.path) ?? extractYouTubeId(t.title) ?? ""
                if isValidYtId(itemId) { continue } // Farklı YouTube videosu -> ASLA eşleşme!
            }
            let candFull = cleanDisplayTitle(formatTrackFullTitle(title: t.title, artist: t.artist))
            let candFold = foldTurkish(candFull)
            if !candFold.isEmpty && candFold == targetFold { return i }

            let candTokens = splitMeaningfulTokens(candFold, stripSoftEdition: false)
            if tokensExactMatch(targetTokens, candTokens) { return i }

            let (cSongRaw, cArtRaw) = splitDisplaySongAndArtist(title: candFull, artist: t.artist)
            let cSongTok = splitMeaningfulTokens(foldTurkish(cSongRaw), stripSoftEdition: false)
            let cArtTok = splitMeaningfulTokens(foldTurkish(cArtRaw), stripSoftEdition: false)
            if !tArtTok.isEmpty && !cArtTok.isEmpty &&
               tokensExactMatch(tSongTok, cSongTok) && tokensExactMatch(tArtTok, cArtTok) {
                return i
            }
        }
        return -1
    }

    /// Canlı Jam & M3U8 içe aktarma için esnek eşleşme (akustik/canlı toleranslı)
    static func findBestTrackMatch(
        tracks: [TrackMatchInput],
        targetTitle: String,
        targetYtId: String?
    ) -> Int {
        let exact = findExactLocalTrackForYoutube(tracks: tracks, targetTitle: targetTitle, targetYtId: targetYtId)
        if exact >= 0 { return exact }

        let cleanedTarget = cleanDisplayTitle(targetTitle)
        let (tSongRaw, tArtRaw) = splitDisplaySongAndArtist(title: cleanedTarget, artist: "")
        let tSongTok = splitMeaningfulTokens(foldTurkish(tSongRaw), stripSoftEdition: true)
        let tArtTok = splitMeaningfulTokens(foldTurkish(tArtRaw), stripSoftEdition: true)
        guard !tSongTok.isEmpty else { return -1 }

        for (i, t) in tracks.enumerated() {
            let candFull = cleanDisplayTitle(formatTrackFullTitle(title: t.title, artist: t.artist))
            let (cSongRaw, cArtRaw) = splitDisplaySongAndArtist(title: candFull, artist: t.artist)
            let cSongTok = splitMeaningfulTokens(foldTurkish(cSongRaw), stripSoftEdition: true)
            let cArtTok = splitMeaningfulTokens(foldTurkish(cArtRaw), stripSoftEdition: true)
            if tokensExactMatch(tSongTok, cSongTok) {
                if tArtTok.isEmpty || cArtTok.isEmpty || !Set(tArtTok).isDisjoint(with: Set(cArtTok)) {
                    return i
                }
            }
        }
        return -1
    }

    // MARK: - İsimli Akıllı M3U8 Dışa / İçe Aktarma (#PLAYLIST: + #EXTAURNS:YTID=)

    static func buildM3u8Content(entries: [M3uTrackEntry], playlistName: String = "") -> String {
        var out = "#EXTM3U\n"
        let cleanPl = playlistName
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespaces)
        if !cleanPl.isEmpty {
            out += "#PLAYLIST:\(cleanPl)\n"
        }
        for entry in entries {
            let yt = entry.ytId.flatMap { isValidYtId($0) ? $0 : nil } ?? extractYouTubeId(entry.pathOrUri)
            if let yt { out += "#EXTAURNS:YTID=\(yt)\n" }
            out += "#EXTINF:-1,\(entry.title)\n"
            out += "\(entry.pathOrUri)\n"
        }
        return out
    }

    static func parseM3u8Lines(_ lines: [String], existingTracks: [TrackMatchInput]) -> M3uImportResult {
        var parsedPlaylistName = ""
        var pendingTitle = ""
        var pendingYtId = ""
        var matchedCount = 0
        var matchedIndices: [Int] = []
        var matchedSet: Set<Int> = []
        var missing: [ParsedM3uEntry] = []

        for rawLine in lines {
            var s = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if s.hasPrefix("\u{FEFF}") { s.removeFirst() }
            if s.isEmpty { continue }

            if s.hasPrefix("#") {
                let upper = s.uppercased()
                if parsedPlaylistName.isEmpty && upper.hasPrefix("#PLAYLIST:") {
                    parsedPlaylistName = String(s.dropFirst(10)).trimmingCharacters(in: .whitespaces)
                } else if parsedPlaylistName.isEmpty && upper.hasPrefix("#EXTAURNS:NAME=") {
                    parsedPlaylistName = String(s.dropFirst(15)).trimmingCharacters(in: .whitespaces)
                } else if upper.hasPrefix("#EXTAURNS:YTID=") {
                    let cand = String(s.dropFirst(15)).trimmingCharacters(in: .whitespaces)
                    if isValidYtId(cand) { pendingYtId = cand }
                } else if upper.hasPrefix("#EXTINF:"), let commaIdx = s.firstIndex(of: ",") {
                    let after = s.index(after: commaIdx)
                    pendingTitle = String(s[after...]).trimmingCharacters(in: .whitespaces)
                }
                continue
            }

            let lower = s.lowercased()
            let isUrl = lower.hasPrefix("http://") || lower.hasPrefix("https://")
            if pendingYtId.isEmpty {
                pendingYtId = (isUrl ? extractYtIdFromUrl(s) : extractYouTubeId(s)) ?? ""
            }
            if pendingTitle.isEmpty && !isUrl {
                let fn = s.components(separatedBy: CharacterSet(charactersIn: "/\\")).last ?? s
                pendingTitle = cleanDisplayTitle(fn, fallbackFileName: fn)
            }
            if pendingTitle.isEmpty && !pendingYtId.isEmpty {
                pendingTitle = "YouTube Parçası (\(pendingYtId))"
            }

            let idx = findBestTrackMatch(tracks: existingTracks, targetTitle: pendingTitle, targetYtId: pendingYtId)
            if idx >= 0 {
                matchedCount += 1
                if matchedSet.insert(idx).inserted {
                    matchedIndices.append(idx)
                }
            } else if !pendingTitle.isEmpty || !pendingYtId.isEmpty {
                let dup = missing.contains { m in
                    (!pendingYtId.isEmpty && m.ytId.caseInsensitiveCompare(pendingYtId) == .orderedSame) ||
                    (!pendingTitle.isEmpty && m.title.caseInsensitiveCompare(pendingTitle) == .orderedSame)
                }
                if !dup {
                    missing.append(ParsedM3uEntry(title: pendingTitle, ytId: pendingYtId))
                }
            }
            pendingTitle = ""
            pendingYtId = ""
        }

        return M3uImportResult(
            playlistName: parsedPlaylistName,
            matchedCount: matchedCount,
            matchedIndices: matchedIndices,
            missingTracks: missing
        )
    }

    // MARK: - 8 Kanallı Paralel İndirme Byte Aralığı Hesabı (Windows dl_speed.c & Android birebir)

    static func computeByteRanges(totalBytes: Int64, maxChannels: Int = 8) -> [(start: Int64, end: Int64)] {
        guard totalBytes > 0 && maxChannels > 0 else { return [] }
        let minSlice: Int64 = 65_536
        let channels = max(1, min(maxChannels, Int(totalBytes / minSlice)))
        let baseSize = totalBytes / Int64(channels)
        var out: [(Int64, Int64)] = []
        out.reserveCapacity(channels)
        var offset: Int64 = 0
        for i in 0..<channels {
            let end = (i == channels - 1) ? (totalBytes - 1) : (offset + baseSize - 1)
            out.append((offset, end))
            offset = end + 1
        }
        return out
    }

    // MARK: - Sürüm Karşılaştırma & Manifest Ayrıştırma

    static func compareSemVer(_ remoteVer: String, _ localVer: String) -> Int {
        func parts(_ raw: String) -> [Int] {
            var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if s.hasPrefix("v") || s.hasPrefix("V") { s.removeFirst() }
            if let dash = s.firstIndex(of: "-") { s = String(s[..<dash]) }
            return s.split(separator: ".").map { Int($0.prefix(while: { $0.isNumber })) ?? 0 }
        }
        let r = parts(remoteVer), l = parts(localVer)
        let n = max(3, r.count, l.count)
        for i in 0..<n {
            let rv = i < r.count ? r[i] : 0
            let lv = i < l.count ? l[i] : 0
            if rv != lv { return rv - lv }
        }
        return 0
    }

    static func parseVersionManifest(_ body: String, currentVersion: String) -> RemoteUpdateMetadata? {
        let lines = body
            .components(separatedBy: .newlines)
            .map { $0.replacingOccurrences(of: "\u{FEFF}", with: "").trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard let first = lines.first, !first.contains("<"), !first.lowercased().hasPrefix("html") else {
            return nil
        }
        var ver = first
        if ver.hasPrefix("v") || ver.hasPrefix("V") { ver.removeFirst() }
        guard ver.first?.isNumber == true else { return nil }

        let rest = lines.dropFirst()
        let urlLine = rest.first { $0.lowercased().hasPrefix("http://") || $0.lowercased().hasPrefix("https://") } ?? ""
        let notes = rest.filter { !$0.lowercased().hasPrefix("http://") && !$0.lowercased().hasPrefix("https://") }
        return RemoteUpdateMetadata(
            version: ver,
            changelog: notes.joined(separator: "\n"),
            hasUpdate: compareSemVer(ver, currentVersion) > 0,
            downloadURL: urlLine
        )
    }

    // MARK: - Sınırsız Çalma Listesi Deposu (JSON)

    static func createPlaylist(lists: [UserPlaylist], rawName: String) -> ([UserPlaylist], UserPlaylist?) {
        let clean = String(rawName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !clean.isEmpty else { return (lists, nil) }
        var idx = lists.count + 1
        var id = ""
        repeat {
            id = "pl_\(Int(Date().timeIntervalSince1970 * 1000))_\(idx)"
            idx += 1
        } while lists.contains(where: { $0.id == id })
        let created = UserPlaylist(id: id, name: clean, trackKeys: [])
        return (lists + [created], created)
    }

    static func addTracksToPlaylist(lists: [UserPlaylist], playlistId: String, keys: [String]) -> [UserPlaylist] {
        let cleanKeys = keys.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !cleanKeys.isEmpty else { return lists }
        return lists.map { pl in
            guard pl.id == playlistId else { return pl }
            var seen = Set(pl.trackKeys)
            var merged = pl.trackKeys
            for k in cleanKeys where seen.insert(k).inserted {
                merged.append(k)
            }
            return UserPlaylist(id: pl.id, name: pl.name, trackKeys: merged)
        }
    }
}
