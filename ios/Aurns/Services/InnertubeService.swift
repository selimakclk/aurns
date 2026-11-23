import Foundation

/// YouTube Innertube arama, iOS donanım uyumlu M4A/AAC akış çözümleme ve
/// 8 kanallı paralel HTTP Range indirme motoru (sıfır 3. parti bağımlılık).
actor InnertubeResolver {
    static let shared = InnertubeResolver()

    static let uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    static let uaIos = "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"
    static let uaVisionOs = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    static let uaAndroidVr = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private struct CachedStream {
        let info: StreamInfo
        let expiresAt: Date
    }

    private struct CachedSearch {
        let results: [SearchResult]
        let expiresAt: Date
    }

    private let session: URLSession
    private var streamCache: [String: CachedStream] = [:]
    private var searchCache: [String: CachedSearch] = [:]
    private var inFlightResolves: [String: Task<StreamInfo?, Never>] = [:]
    private var cachedVisitorId: String?
    private var visitorIdDate: Date?

    private init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 12
        cfg.timeoutIntervalForResource = 25
        cfg.httpMaximumConnectionsPerHost = 12
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.session = URLSession(configuration: cfg)
    }

    func warmUp() {
        Task { _ = await fetchVisitorId() }
    }

    func prefetchAudioStream(videoId: String) {
        let clean = videoId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard MusicLibraryCore.isValidYtId(clean) else { return }
        if let c = streamCache[clean], c.expiresAt > Date() { return }
        Task { _ = await resolveAudioStream(videoId: clean) }
    }

    func invalidateCachedStream(videoId: String) {
        streamCache.removeValue(forKey: videoId)
    }

    // MARK: - YouTube Arama

    func search(query: String) async -> [SearchResult] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let effective = MusicLibraryCore.extractYtIdFromUrl(trimmed) ?? trimmed
        let cacheKey = effective.lowercased()
        let now = Date()
        if let cached = searchCache[cacheKey], cached.expiresAt > now {
            return cached.results
        }

        guard let url = URL(string: "https://www.youtube.com/youtubei/v1/search?prettyPrint=false") else { return [] }
        let body: [String: Any] = [
            "query": effective,
            "params": "EgIQAQ==",
            "context": [
                "client": [
                    "clientName": "WEB",
                    "clientVersion": "2.20250101.00.00",
                    "hl": "tr",
                    "gl": "TR"
                ]
            ]
        ]

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(Self.uaDesktop, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return []
            }
            var renderers: [[String: Any]] = []
            collectVideoRenderers(json, into: &renderers)

            var out: [SearchResult] = []
            var seen = Set<String>()
            for vr in renderers {
                guard let videoId = vr["videoId"] as? String,
                      MusicLibraryCore.isValidYtId(videoId),
                      seen.insert(videoId).inserted else { continue }

                guard let title = extractText(vr["title"]), !title.isEmpty else { continue }
                let duration = extractText(vr["lengthText"]) ?? ""
                let upDur = duration.uppercased()
                if duration.isEmpty || upDur.contains("CANLI") || upDur.contains("LIVE") { continue }

                let artist = extractText(vr["ownerText"])
                    ?? extractText(vr["shortBylineText"])
                    ?? extractText(vr["longBylineText"])
                    ?? "YouTube"

                let thumb = "https://i.ytimg.com/vi/\(videoId)/mqdefault.jpg"
                out.append(SearchResult(
                    videoId: videoId,
                    title: title,
                    artist: artist,
                    durationText: duration,
                    thumbnailURL: thumb
                ))
                if out.count >= 30 { break }
            }

            if !out.isEmpty {
                if searchCache.count > 24 {
                    searchCache = searchCache.filter { $0.value.expiresAt > now }
                    if searchCache.count > 24 { searchCache.removeAll() }
                }
                searchCache[cacheKey] = CachedSearch(results: out, expiresAt: now.addingTimeInterval(60))
            }
            return out
        } catch {
            return []
        }
    }

    // MARK: - Ses Akışı Çözümleme (iOS AVPlayer için öncelikle M4A/AAC)

    func resolveAudioStream(videoId: String, bypassCache: Bool = false) async -> StreamInfo? {
        let clean = videoId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard MusicLibraryCore.isValidYtId(clean) else { return nil }
        let now = Date()

        if !bypassCache, let cached = streamCache[clean], cached.expiresAt > now {
            return cached.info
        }
        if bypassCache {
            streamCache.removeValue(forKey: clean)
        } else if let existing = inFlightResolves[clean] {
            return await existing.value
        }

        let task = Task<StreamInfo?, Never> {
            // iOS AVPlayer M4A (audio/mp4 AAC) akışlarını donanım hızlandırmalı oynatır;
            // IOS, ANDROID_VR ve VISIONOS istemcilerinden en kaliteli M4A akışını seç.
            let candidates: [() async -> StreamInfo?] = [
                { await self.tryResolveWithIos(videoId: clean) },
                { await self.tryResolveWithAndroidVr(videoId: clean) },
                { await self.tryResolveWithVisionOs(videoId: clean) }
            ]
            var fallback: StreamInfo? = nil
            for resolver in candidates {
                if let info = await resolver() {
                    if info.fileExt == "m4a" {
                        return info
                    }
                    if fallback == nil { fallback = info }
                }
            }
            return fallback
        }

        inFlightResolves[clean] = task
        let resolved = await task.value
        inFlightResolves.removeValue(forKey: clean)

        if let resolved {
            if streamCache.count > 64 {
                streamCache = streamCache.filter { $0.value.expiresAt > now }
                if streamCache.count > 64 { streamCache.removeAll() }
            }
            streamCache[clean] = CachedStream(info: resolved, expiresAt: Date().addingTimeInterval(180))
        }
        return resolved
    }

    private func fetchVisitorId() async -> String? {
        if let v = cachedVisitorId, let d = visitorIdDate, Date().timeIntervalSince(d) < 6 * 3600 {
            return v
        }
        guard let url = URL(string: "https://www.youtube.com/youtubei/v1/visitor_id") else { return cachedVisitorId }
        let body: [String: Any] = [
            "context": [
                "client": [
                    "clientName": "WEB",
                    "clientVersion": "2.20250101.00.00",
                    "hl": "tr",
                    "gl": "TR"
                ]
            ]
        ]
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(Self.uaDesktop, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        if let (data, _) = try? await session.data(for: req),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let rc = json["responseContext"] as? [String: Any],
           let vis = rc["visitorData"] as? String, !vis.isEmpty {
            cachedVisitorId = vis
            visitorIdDate = Date()
            return vis
        }
        return cachedVisitorId
    }

    private func tryResolveWithIos(videoId: String) async -> StreamInfo? {
        let body: [String: Any] = [
            "videoId": videoId,
            "contentCheckOk": true,
            "racyCheckOk": true,
            "context": [
                "client": [
                    "clientName": "IOS",
                    "clientVersion": "20.03.02",
                    "deviceMake": "Apple",
                    "deviceModel": "iPhone16,2",
                    "userAgent": Self.uaIos,
                    "osName": "iPhone",
                    "osVersion": "18.2.1.22C161",
                    "hl": "en",
                    "gl": "US"
                ]
            ]
        ]
        return await postPlayerRequest(body: body, ua: Self.uaIos, clientId: "5", clientVer: "20.03.02", visitorId: nil)
    }

    private func tryResolveWithAndroidVr(videoId: String) async -> StreamInfo? {
        let body: [String: Any] = [
            "videoId": videoId,
            "contentCheckOk": true,
            "racyCheckOk": true,
            "context": [
                "client": [
                    "clientName": "ANDROID_VR",
                    "clientVersion": "1.56.21",
                    "deviceMake": "Oculus",
                    "deviceModel": "Quest 3",
                    "androidSdkVersion": 32,
                    "userAgent": Self.uaAndroidVr,
                    "osName": "Android",
                    "osVersion": "12L",
                    "hl": "en",
                    "gl": "US"
                ]
            ]
        ]
        return await postPlayerRequest(body: body, ua: Self.uaAndroidVr, clientId: "28", clientVer: "1.56.21", visitorId: nil)
    }

    private func tryResolveWithVisionOs(videoId: String) async -> StreamInfo? {
        let vis = await fetchVisitorId()
        var client: [String: Any] = [
            "clientName": "VISIONOS",
            "clientVersion": "1.02",
            "deviceMake": "Apple",
            "deviceModel": "RealityDevice17,1",
            "userAgent": Self.uaVisionOs,
            "osName": "visionOS",
            "osVersion": "26.5.23O471",
            "hl": "en",
            "gl": "US"
        ]
        if let vis, !vis.isEmpty { client["visitorData"] = vis }
        let body: [String: Any] = [
            "videoId": videoId,
            "contentCheckOk": true,
            "racyCheckOk": true,
            "playbackContext": [
                "contentPlaybackContext": [
                    "html5Preference": "HTML5_PREF_WANTS",
                    "signatureTimestamp": 20753
                ]
            ],
            "context": ["client": client]
        ]
        return await postPlayerRequest(body: body, ua: Self.uaVisionOs, clientId: "101", clientVer: "1.02", visitorId: vis)
    }

    private func postPlayerRequest(
        body: [String: Any],
        ua: String,
        clientId: String,
        clientVer: String,
        visitorId: String?
    ) async -> StreamInfo? {
        guard let url = URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false") else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue(clientId, forHTTPHeaderField: "X-YouTube-Client-Name")
        req.setValue(clientVer, forHTTPHeaderField: "X-YouTube-Client-Version")
        if let visitorId, !visitorId.isEmpty {
            req.setValue(visitorId, forHTTPHeaderField: "X-Goog-Visitor-Id")
        }
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)

        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return nil
            }
            return extractBestAudio(json: json, ua: ua)
        } catch {
            return nil
        }
    }

    private func extractBestAudio(json: [String: Any], ua: String) -> StreamInfo? {
        if let statusObj = json["playabilityStatus"] as? [String: Any],
           let status = statusObj["status"] as? String {
            let up = status.uppercased()
            if up == "ERROR" || up == "LOGIN_REQUIRED" || up == "UNPLAYABLE" {
                return nil
            }
        }
        guard let streaming = json["streamingData"] as? [String: Any] else { return nil }
        let arrays: [[[String: Any]]] = [
            streaming["adaptiveFormats"] as? [[String: Any]],
            streaming["formats"] as? [[String: Any]]
        ].compactMap { $0 }

        var bestMp4: StreamInfo?
        var bestMp4Bitrate = -1
        var bestHlsOrMuxed: StreamInfo?

        for arr in arrays {
            for fmt in arr {
                guard let mime = fmt["mimeType"] as? String,
                      let urlStr = fmt["url"] as? String,
                      urlStr.hasPrefix("http") else { continue }

                let bitrate = (fmt["bitrate"] as? Int) ?? (fmt["averageBitrate"] as? Int) ?? 0
                let contentLen: Int64
                if let s = fmt["contentLength"] as? String, let v = Int64(s) {
                    contentLen = v
                } else if let n = fmt["contentLength"] as? NSNumber {
                    contentLen = n.int64Value
                } else {
                    contentLen = 0
                }

                if mime.hasPrefix("audio/mp4") {
                    if bitrate > bestMp4Bitrate {
                        bestMp4Bitrate = bitrate
                        bestMp4 = StreamInfo(
                            url: urlStr,
                            mimeType: "audio/mp4",
                            fileExt: "m4a",
                            bitrate: bitrate,
                            contentLength: contentLen,
                            userAgent: ua
                        )
                    }
                } else if mime.hasPrefix("video/mp4") && bestHlsOrMuxed == nil {
                    bestHlsOrMuxed = StreamInfo(
                        url: urlStr,
                        mimeType: "video/mp4",
                        fileExt: "m4a",
                        bitrate: bitrate,
                        contentLength: contentLen,
                        userAgent: ua
                    )
                }
            }
            if bestMp4 != nil { break }
        }
        return bestMp4 ?? bestHlsOrMuxed
    }

    // MARK: - Sürüm Kontrolü (Cloudflare Pages / Workers)

    func fetchRemoteUpdateMetadata(currentVersion: String) async -> RemoteUpdateMetadata? {
        let urls = [
            "https://aurns.pages.dev/version.txt",
            "https://aurns.akcselim525.workers.dev/version.txt"
        ]
        for raw in urls {
            guard let url = URL(string: raw) else { continue }
            var req = URLRequest(url: url)
            req.timeoutInterval = 8
            req.cachePolicy = .reloadIgnoringLocalCacheData
            if let (data, resp) = try? await session.data(for: req),
               let http = resp as? HTTPURLResponse,
               (200...299).contains(http.statusCode),
               let body = String(data: data, encoding: .utf8),
               let meta = MusicLibraryCore.parseVersionManifest(body, currentVersion: currentVersion) {
                return meta
            }
        }
        return nil
    }

    // MARK: - JSON Yardımcıları

    private func extractText(_ node: Any?) -> String? {
        guard let dict = node as? [String: Any] else { return nil }
        if let simple = dict["simpleText"] as? String, !simple.isEmpty {
            return simple
        }
        if let runs = dict["runs"] as? [[String: Any]] {
            let joined = runs.compactMap { $0["text"] as? String }.joined()
            return joined.isEmpty ? nil : joined
        }
        return nil
    }

    private func collectVideoRenderers(_ node: Any, into list: inout [[String: Any]]) {
        if let dict = node as? [String: Any] {
            if let vr = dict["videoRenderer"] as? [String: Any] {
                list.append(vr)
            }
            for (_, v) in dict {
                collectVideoRenderers(v, into: &list)
            }
        } else if let arr = node as? [Any] {
            for item in arr {
                collectVideoRenderers(item, into: &list)
            }
        }
    }
}

// MARK: - 8 Kanallı Paralel Turbo İndirme Yöneticisi

@MainActor
final class AudioDownloadManager: ObservableObject {

    struct DownloadJob: Equatable {
        let videoId: String
        let title: String
        let artist: String
        let targetPlaylistId: String?
        let isFromSync: Bool
    }

    @Published var progress = DownloadProgress()

    var onTrackDownloaded: ((_ fileURL: URL, _ videoId: String, _ title: String, _ targetPlaylistId: String?, _ isFromSync: Bool) -> Void)?

    private var queue: [DownloadJob] = []
    private var workerTask: Task<Void, Never>?
    private let downloadSession: URLSession

    init() {
        let cfg = URLSessionConfiguration.default
        cfg.httpMaximumConnectionsPerHost = 12
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 120
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.downloadSession = URLSession(configuration: cfg)
    }

    func enqueue(
        videoId: String,
        title: String,
        artist: String = "",
        targetPlaylistId: String? = nil,
        isFromSync: Bool = false
    ) {
        let cleanId = videoId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanId.isEmpty || !cleanTitle.isEmpty else { return }

        if !cleanId.isEmpty {
            if progress.activeVideoId == cleanId || queue.contains(where: { $0.videoId == cleanId }) {
                return
            }
        } else if queue.contains(where: { $0.title.caseInsensitiveCompare(cleanTitle) == .orderedSame }) {
            return
        }

        queue.append(DownloadJob(
            videoId: cleanId,
            title: cleanTitle.isEmpty ? cleanId : cleanTitle,
            artist: artist,
            targetPlaylistId: targetPlaylistId,
            isFromSync: isFromSync
        ))
        refreshQueueCounters()

        if workerTask == nil {
            workerTask = Task { [weak self] in
                await self?.processQueue()
            }
        }
    }

    func cancelAll() {
        queue.removeAll()
        workerTask?.cancel()
        workerTask = nil
        progress = DownloadProgress()
    }

    private func refreshQueueCounters() {
        let ids = Set(queue.map { $0.videoId }.filter { !$0.isEmpty })
        progress.queuedVideoIds = ids
        progress.queueCount = queue.count
    }

    private func processQueue() async {
        defer {
            workerTask = nil
        }

        while !queue.isEmpty && !Task.isCancelled {
            let job = queue.removeFirst()
            refreshQueueCounters()

            progress = DownloadProgress(
                title: job.title,
                percent: 0,
                isRunning: true,
                isDone: false,
                error: nil,
                headline: job.isFromSync ? "Eşitlenen parça indiriliyor..." : "Akış hazırlanıyor...",
                activeVideoId: job.videoId,
                queuedVideoIds: progress.queuedVideoIds,
                queueCount: queue.count
            )

            var resolvedVideoId = job.videoId
            var resolvedTitle = job.title

            if !MusicLibraryCore.isValidYtId(resolvedVideoId) {
                progress.headline = "YouTube'da aranıyor..."
                let hits = await InnertubeResolver.shared.search(query: job.title)
                if let first = hits.first {
                    resolvedVideoId = first.videoId
                    if resolvedTitle.isEmpty { resolvedTitle = first.title }
                    progress.activeVideoId = resolvedVideoId
                } else {
                    progress = DownloadProgress(
                        title: job.title,
                        percent: 0,
                        isRunning: false,
                        isDone: false,
                        error: "YouTube'da eşleşen parça bulunamadı.",
                        headline: "İndirme başarısız",
                        activeVideoId: "",
                        queuedVideoIds: progress.queuedVideoIds,
                        queueCount: queue.count
                    )
                    try? await Task.sleep(nanoseconds: 1_200_000_000)
                    continue
                }
            }

            // Sıradaki işin stream linkini önceden çöz (sıfır bekleme!)
            if let nextJob = queue.first, MusicLibraryCore.isValidYtId(nextJob.videoId) {
                await InnertubeResolver.shared.prefetchAudioStream(videoId: nextJob.videoId)
            }

            var stream = await InnertubeResolver.shared.resolveAudioStream(videoId: resolvedVideoId)
            if stream == nil {
                stream = await InnertubeResolver.shared.resolveAudioStream(videoId: resolvedVideoId, bypassCache: true)
            }

            guard let streamInfo = stream, let remoteURL = URL(string: streamInfo.url) else {
                progress = DownloadProgress(
                    title: resolvedTitle,
                    percent: 0,
                    isRunning: false,
                    isDone: false,
                    error: "Ses akışı çözülemedi.",
                    headline: "İndirme başarısız",
                    activeVideoId: "",
                    queuedVideoIds: progress.queuedVideoIds,
                    queueCount: queue.count
                )
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                continue
            }

            let safeBase = sanitizeFilename(MusicLibraryCore.cleanDisplayTitle(resolvedTitle))
            let finalName = "\(safeBase.isEmpty ? "Aurns_Track" : safeBase) [\(resolvedVideoId)].\(streamInfo.fileExt)"
            let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            let destURL = docsDir.appendingPathComponent(finalName)
            let tmpURL = FileManager.default.temporaryDirectory.appendingPathComponent("aurns_dl_\(UUID().uuidString).part")

            progress.headline = "8-Kanallı Turbo İndirme..."

            do {
                try await downloadTurbo(
                    remoteURL: remoteURL,
                    streamInfo: streamInfo,
                    tempURL: tmpURL
                )
                if FileManager.default.fileExists(atPath: destURL.path) {
                    try? FileManager.default.removeItem(at: destURL)
                }
                try FileManager.default.moveItem(at: tmpURL, to: destURL)

                progress = DownloadProgress(
                    title: resolvedTitle,
                    percent: 100,
                    isRunning: false,
                    isDone: true,
                    error: nil,
                    headline: "İndirildi ✓",
                    activeVideoId: "",
                    queuedVideoIds: progress.queuedVideoIds,
                    queueCount: queue.count
                )
                onTrackDownloaded?(destURL, resolvedVideoId, resolvedTitle, job.targetPlaylistId, job.isFromSync)
            } catch {
                try? FileManager.default.removeItem(at: tmpURL)
                if Task.isCancelled { break }
                progress = DownloadProgress(
                    title: resolvedTitle,
                    percent: 0,
                    isRunning: false,
                    isDone: false,
                    error: "İndirme hatası: \(error.localizedDescription)",
                    headline: "İndirme başarısız",
                    activeVideoId: "",
                    queuedVideoIds: progress.queuedVideoIds,
                    queueCount: queue.count
                )
                try? await Task.sleep(nanoseconds: 1_400_000_000)
            }
        }

        if queue.isEmpty && progress.isDone {
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            if queue.isEmpty && !progress.isRunning {
                progress = DownloadProgress()
            }
        }
    }

    private func downloadTurbo(remoteURL: URL, streamInfo: StreamInfo, tempURL: URL) async throws {
        let totalBytes = streamInfo.contentLength
        if totalBytes >= 262_144 {
            let ranges = MusicLibraryCore.computeByteRanges(totalBytes: totalBytes, maxChannels: 8)
            if ranges.count > 1 {
                do {
                    try await downloadParallelRanges(
                        remoteURL: remoteURL,
                        userAgent: streamInfo.userAgent,
                        totalBytes: totalBytes,
                        ranges: ranges,
                        tempURL: tempURL
                    )
                    return
                } catch {
                    try? FileManager.default.removeItem(at: tempURL)
                    if Task.isCancelled { throw error }
                }
            }
        }
        try await downloadSingleStream(
            remoteURL: remoteURL,
            userAgent: streamInfo.userAgent,
            expectedBytes: totalBytes,
            tempURL: tempURL
        )
    }

    private func downloadParallelRanges(
        remoteURL: URL,
        userAgent: String,
        totalBytes: Int64,
        ranges: [(start: Int64, end: Int64)],
        tempURL: URL
    ) async throws {
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        let preAlloc = try FileHandle(forWritingTo: tempURL)
        try preAlloc.truncate(atOffset: UInt64(totalBytes))
        try preAlloc.close()

        let session = self.downloadSession
        let counter = ProgressCounter(total: totalBytes)

        try await withThrowingTaskGroup(of: Void.self) { group in
            for r in ranges {
                group.addTask {
                    var req = URLRequest(url: remoteURL)
                    req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
                    req.setValue("bytes=\(r.start)-\(r.end)", forHTTPHeaderField: "Range")
                    req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

                    let (data, resp) = try await session.data(for: req)
                    guard let http = resp as? HTTPURLResponse,
                          http.statusCode == 206 || http.statusCode == 200 else {
                        throw URLError(.badServerResponse)
                    }
                    let expectedLen = Int(r.end - r.start + 1)
                    let sliceData = (http.statusCode == 200 && data.count >= Int(r.end + 1))
                        ? data.subdata(in: Int(r.start)..<Int(r.end + 1))
                        : data
                    guard sliceData.count == expectedLen else {
                        throw URLError(.cannotDecodeRawData)
                    }

                    let fh = try FileHandle(forWritingTo: tempURL)
                    defer { try? fh.close() }
                    try fh.seek(toOffset: UInt64(r.start))
                    try fh.write(contentsOf: sliceData)

                    let pct = await counter.add(Int64(sliceData.count))
                    await MainActor.run { [weak self] in
                        if let self, self.progress.isRunning {
                            self.progress.percent = max(self.progress.percent, pct)
                        }
                    }
                }
            }
            try await group.waitForAll()
        }
    }

    private func downloadSingleStream(
        remoteURL: URL,
        userAgent: String,
        expectedBytes: Int64,
        tempURL: URL
    ) async throws {
        var req = URLRequest(url: remoteURL)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

        let (bytesStream, resp) = try await downloadSession.bytes(for: req)
        guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let total = http.expectedContentLength > 0 ? http.expectedContentLength : expectedBytes

        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        let fh = try FileHandle(forWritingTo: tempURL)
        defer { try? fh.close() }

        var buffer = Data()
        buffer.reserveCapacity(65_536)
        var downloaded: Int64 = 0
        var lastPct = -1

        for try await byte in bytesStream {
            if Task.isCancelled { throw CancellationError() }
            buffer.append(byte)
            if buffer.count >= 65_536 {
                try fh.write(contentsOf: buffer)
                downloaded += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                if total > 0 {
                    let pct = min(99, Int((downloaded * 100) / total))
                    if pct != lastPct {
                        lastPct = pct
                        progress.percent = pct
                    }
                }
            }
        }
        if !buffer.isEmpty {
            try fh.write(contentsOf: buffer)
            downloaded += Int64(buffer.count)
        }
        if downloaded <= 1024 {
            throw URLError(.zeroByteResource)
        }
    }

    private func sanitizeFilename(_ raw: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:*?\"<>|\r\n\t")
        let parts = raw.components(separatedBy: forbidden).joined(separator: " ")
        let collapsed = parts.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        return String(collapsed.prefix(80)).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private actor ProgressCounter {
    private let total: Int64
    private var done: Int64 = 0

    init(total: Int64) { self.total = max(1, total) }

    func add(_ delta: Int64) -> Int {
        done += delta
        return min(99, Int((done * 100) / total))
    }
}
