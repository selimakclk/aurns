import XCTest
@testable import Aurns

final class AurnsCoreTests: XCTestCase {

    // MARK: - Türkçe Katlama & Arama

    func testTurkishFoldingAndSearchTokens() {
        XCTAssertEqual(MusicLibraryCore.foldTurkish("Şarkı İŞIKLI YOL Öğüt Üzüm Çiçek"), "sarki isikli yol ogut uzum cicek")
        XCTAssertTrue(MusicLibraryCore.matchesSearchTokens(title: "Işıklı Yol Sözleri", artist: "İzel", query: "izel isikli"))
        XCTAssertTrue(MusicLibraryCore.matchesSearchTokens(title: "Yalın - Halbuki", artist: "Yerel Parça", query: "HALBUKI yalin"))
        XCTAssertFalse(MusicLibraryCore.matchesSearchTokens(title: "Işıklı Yol", artist: "İzel", query: "tarkan"))
    }

    // MARK: - YouTube ID & URL Çıkarımı

    func testExtractYouTubeIdAndUrls() {
        XCTAssertEqual(MusicLibraryCore.extractYouTubeId("Yalın - Halbuki [dQw4w9WgXcQ].m4a"), "dQw4w9WgXcQ")
        XCTAssertEqual(MusicLibraryCore.extractYouTubeId("Track [_AbCdEf12345_].mp3"), "AbCdEf12345")
        XCTAssertNil(MusicLibraryCore.extractYouTubeId("Track [short].m4a"))

        XCTAssertEqual(MusicLibraryCore.extractYtIdFromUrl("https://youtu.be/dQw4w9WgXcQ?t=12"), "dQw4w9WgXcQ")
        XCTAssertEqual(MusicLibraryCore.extractYtIdFromUrl("https://www.youtube.com/watch?v=AbCdEf12345&list=RD1"), "AbCdEf12345")
        XCTAssertEqual(MusicLibraryCore.extractYtIdFromUrl("https://www.youtube.com/shorts/ZyXwVu98765"), "ZyXwVu98765")
        XCTAssertNil(MusicLibraryCore.extractYtIdFromUrl("https://example.com/music"))
    }

    // MARK: - Katı Remix / Mashup / Milli Sürüm Ayrımı

    func testStrictRemixMashupMilliSeparation() {
        let localTracks: [TrackMatchInput] = [
            TrackMatchInput(
                title: "Yalın - Halbuki",
                artist: "Yalın",
                ytId: "OrigId12345",
                path: "Yalın - Halbuki [OrigId12345].m4a"
            ),
            TrackMatchInput(
                title: "Tarkan - Kuzu Kuzu",
                artist: "Tarkan",
                ytId: nil,
                path: "Tarkan - Kuzu Kuzu.mp3"
            )
        ]

        // Birebir aynı videoId -> eşleşir
        XCTAssertEqual(
            MusicLibraryCore.findExactLocalTrackForYoutube(
                tracks: localTracks,
                targetTitle: "Yalın - Halbuki (Official Video)",
                targetYtId: "OrigId12345"
            ),
            0
        )

        // Farklı videoId (mashup / remix / milli versiyon) -> ASLA eşleşmez!
        XCTAssertEqual(
            MusicLibraryCore.findExactLocalTrackForYoutube(
                tracks: localTracks,
                targetTitle: "Yalın x Yung Ouzo - HALBUKİ X GODBLESS",
                targetYtId: "MashupId999"
            ),
            -1
        )
        XCTAssertEqual(
            MusicLibraryCore.findExactLocalTrackForYoutube(
                tracks: localTracks,
                targetTitle: "Yalın - Halbuki (Milli Remix)",
                targetYtId: "MilliId8888"
            ),
            -1
        )

        // ytId taşımayan yerel MP3 ile başlık birebir tutuyorsa eşleşir, remix ise eşleşmez!
        XCTAssertEqual(
            MusicLibraryCore.findExactLocalTrackForYoutube(
                tracks: localTracks,
                targetTitle: "Tarkan - Kuzu Kuzu (Official Audio)",
                targetYtId: "NewYtId1111"
            ),
            1
        )
        XCTAssertEqual(
            MusicLibraryCore.findExactLocalTrackForYoutube(
                tracks: localTracks,
                targetTitle: "Tarkan - Kuzu Kuzu Milli Versiyon",
                targetYtId: "MilliYt2222"
            ),
            -1
        )
    }

    func testSoftJamTrackMatch() {
        let localTracks = [
            TrackMatchInput(title: "Duman - Senden Daha Güzel", artist: "Duman", ytId: "AbCdEf12345", path: "Duman - Senden Daha Güzel [AbCdEf12345].m4a")
        ]
        XCTAssertEqual(
            MusicLibraryCore.findBestTrackMatch(
                tracks: localTracks,
                targetTitle: "Duman - Senden Daha Güzel (Akustik Canlı)",
                targetYtId: "OtherId9999"
            ),
            0
        )
    }

    // MARK: - İsimli Akıllı M3U8 (#PLAYLIST: + #EXTAURNS:YTID=)

    func testNamedM3u8RoundtripAndMissingTracks() {
        let exported = MusicLibraryCore.buildM3u8Content(
            entries: [
                M3uTrackEntry(title: "İzel - Işıklı Yol", pathOrUri: "Izel [AAAAAAAAAAA].m4a", ytId: "AAAAAAAAAAA"),
                M3uTrackEntry(title: "Teoman - Paramparça", pathOrUri: "Teoman [BBBBBBBBBBB].m4a", ytId: "BBBBBBBBBBB")
            ],
            playlistName: "Gece Yolculuğu"
        )
        XCTAssertTrue(exported.contains("#PLAYLIST:Gece Yolculuğu"))
        XCTAssertTrue(exported.contains("#EXTAURNS:YTID=AAAAAAAAAAA"))

        let existing = [
            TrackMatchInput(title: "İzel - Işıklı Yol", artist: "İzel", ytId: "AAAAAAAAAAA", path: "Izel [AAAAAAAAAAA].m4a")
        ]
        let parsed = MusicLibraryCore.parseM3u8Lines(exported.components(separatedBy: .newlines), existingTracks: existing)
        XCTAssertEqual(parsed.playlistName, "Gece Yolculuğu")
        XCTAssertEqual(parsed.matchedCount, 1)
        XCTAssertEqual(parsed.matchedIndices, [0])
        XCTAssertEqual(parsed.missingTracks.count, 1)
        XCTAssertEqual(parsed.missingTracks.first?.ytId, "BBBBBBBBBBB")
        XCTAssertEqual(parsed.missingTracks.first?.title, "Teoman - Paramparça")
    }

    // MARK: - 8 Kanallı Paralel İndirme Byte Aralığı

    func testParallelDownloadByteRanges() {
        let total: Int64 = 4_194_307
        let ranges = MusicLibraryCore.computeByteRanges(totalBytes: total, maxChannels: 8)
        XCTAssertEqual(ranges.count, 8)
        XCTAssertEqual(ranges.first?.start, 0)
        XCTAssertEqual(ranges.last?.end, total - 1)
        for i in 1..<ranges.count {
            XCTAssertEqual(ranges[i].start, ranges[i - 1].end + 1)
        }

        let small = MusicLibraryCore.computeByteRanges(totalBytes: 100_000, maxChannels: 8)
        XCTAssertEqual(small.count, 1)
        XCTAssertEqual(small.first?.start, 0)
        XCTAssertEqual(small.first?.end, 99_999)
    }

    // MARK: - Sürüm Manifest & Çalma Listeleri

    func testSemVerAndVersionManifest() {
        let body = "\u{FEFF}1.8.0\nhttps://aurns.pages.dev/Aurns-iOS.ipa\nYeni iOS özellikleri\nPerformans artışı"
        let meta = MusicLibraryCore.parseVersionManifest(body, currentVersion: "1.7.9")
        XCTAssertNotNil(meta)
        XCTAssertEqual(meta?.version, "1.8.0")
        XCTAssertEqual(meta?.hasUpdate, true)
        XCTAssertEqual(meta?.downloadURL, "https://aurns.pages.dev/Aurns-iOS.ipa")
        XCTAssertEqual(MusicLibraryCore.compareSemVer("1.7.9", "1.7.9"), 0)
        XCTAssertGreaterThan(MusicLibraryCore.compareSemVer("1.10.0", "1.9.9"), 0)
    }

    func testUserPlaylistsCrudAndDedup() {
        let (lists1, pl) = MusicLibraryCore.createPlaylist(lists: [], rawName: "  Spor  ")
        XCTAssertNotNil(pl)
        XCTAssertEqual(pl?.name, "Spor")
        let lists2 = MusicLibraryCore.addTracksToPlaylist(
            lists: lists1,
            playlistId: pl!.id,
            keys: ["a.m4a", "b.m4a", "a.m4a"]
        )
        XCTAssertEqual(lists2.first?.trackKeys, ["a.m4a", "b.m4a"])
    }

    // MARK: - MQTT 3.1.1 & Arkadaşlar / Jam / DSYNC1 / DFULL1 Protokolü

    func testMqtt311BinaryRoundtripAndRetain() {
        let connect = FriendsProtocol.encodeMqttConnect(clientId: "auios1234")
        XCTAssertEqual(connect.first, 0x10)

        let sub = FriendsProtocol.encodeMqttSubscribe(packetId: 7, topic: "seloplayer/jam/atbtxl")
        XCTAssertNotNil(sub)
        XCTAssertEqual(sub?.first, 0x82)

        let unsub = FriendsProtocol.encodeMqttUnsubscribe(packetId: 8, topic: "seloplayer/jam/atbtxl")
        XCTAssertNotNil(unsub)
        XCTAssertEqual(unsub?.first, 0xA2)

        let pub = FriendsProtocol.encodeMqttPublish(
            topic: "aurns/sync/ab12cd34/dev/ios_4770",
            payload: "DSYNC1|ios_4770|IOS|Selim|1|1700000000~dQw4w9WgXcQ~Işıklı Yol",
            retain: true
        )
        XCTAssertNotNil(pub)
        XCTAssertEqual(pub?.first, 0x31) // RETAIN flag = 1

        let decoded = FriendsProtocol.decodeMqttPublishPacket(pub!)
        XCTAssertEqual(decoded?.topic, "aurns/sync/ab12cd34/dev/ios_4770")
        XCTAssertEqual(decoded?.payload, "DSYNC1|ios_4770|IOS|Selim|1|1700000000~dQw4w9WgXcQ~Işıklı Yol")
    }

    func testFriendsTagNormalizationAndNumericMatch() {
        XCTAssertEqual(FriendsProtocol.tagToTopicKey("Şelim#4770"), "selim_4770")
        XCTAssertEqual(FriendsProtocol.extractTagId("Selim#4770"), "4770")
        XCTAssertTrue(FriendsProtocol.sameUserTag("Selim#4770", "YeniAd#4770"))
        XCTAssertTrue(FriendsProtocol.sameUserTag("#4770", "Selo#4770"))
        XCTAssertFalse(FriendsProtocol.sameUserTag("Selim#4770", "Selim#9999"))
        XCTAssertEqual(FriendsProtocol.normalizeRoomCode("  ŞğÜ-12  "), "sgu-12")
    }

    func testDeviceSyncDSYNC1AndDFULL1WireParity() {
        let code = FriendsProtocol.normalizeDeviceSyncCode(" ab-12 CD34 ")
        XCTAssertEqual(code, "AB12CD34")

        // PC -> iOS DSYNC1 parse
        let pcPayload = FriendsProtocol.encodeDeviceSyncPayload(
            devId: "pc_1001",
            platform: "PC",
            displayName: "Masaüstü",
            entries: [
                FriendsProtocol.DeviceSyncWireEntry(tsSec: 1730000000, ytId: "dQw4w9WgXcQ", title: "Yalın - Halbuki")
            ]
        )
        let parsedSync = FriendsProtocol.parseDeviceSyncMessage(
            syncCode: "AB12CD34",
            topic: FriendsProtocol.deviceSyncPublishTopic("AB12CD34", devId: "pc_1001"),
            payload: pcPayload
        )
        XCTAssertNotNil(parsedSync)
        XCTAssertEqual(parsedSync?.senderDevId, "pc_1001")
        XCTAssertEqual(parsedSync?.partnerLabel, "PC (Masaüstü)")
        XCTAssertEqual(parsedSync?.entries.count, 1)
        XCTAssertEqual(parsedSync?.entries.first?.ytId, "dQw4w9WgXcQ")

        // iOS -> PC/Android DFULL1 çoklu parça bölme ve çözümleme
        let manyItems = (0..<45).map { i in
            FriendsProtocol.DeviceSyncFullItem(
                ytId: i % 2 == 0 ? String(format: "YtId%07d", i) : "",
                title: "Deneme Şarkı Başlığı Numara \(i)"
            )
        }
        let chunks = FriendsProtocol.encodeDeviceSyncFullChunks(
            devId: "ios_4770",
            platform: "IOS",
            displayName: "iPhone",
            items: manyItems,
            maxListBytes: 740
        )
        XCTAssertGreaterThan(chunks.count, 1)

        var restored: [FriendsProtocol.DeviceSyncFullItem] = []
        for (idx, chunk) in chunks.enumerated() {
            let parsedFull = FriendsProtocol.parseDeviceSyncFullMessage(
                syncCode: "AB12CD34",
                topic: FriendsProtocol.deviceSyncFullChunkTopic("AB12CD34", devId: "ios_4770", chunkIdx: idx),
                payload: chunk
            )
            XCTAssertNotNil(parsedFull)
            XCTAssertEqual(parsedFull?.chunkIdx, idx)
            XCTAssertEqual(parsedFull?.totalChunks, chunks.count)
            XCTAssertEqual(parsedFull?.partnerLabel, "iPhone (iPhone)")
            restored.append(contentsOf: parsedFull?.items ?? [])
        }
        XCTAssertEqual(restored.count, 45)
        XCTAssertEqual(restored[0].ytId, "YtId0000000")
        XCTAssertEqual(restored[1].ytId, "")
    }

    // MARK: - Spotify Embed & İndirmeden Çalma Listesi Anahtarları

    func testSpotifyParserAndOnlinePlaylistKeys() {
        let parsedUrl = MusicLibraryCore.parseSpotifyUrl("https://open.spotify.com/intl-tr/playlist/37i9dQZF1DXcBWIGoYBM5M?si=xyz")
        XCTAssertEqual(parsedUrl?.type, "playlist")
        XCTAssertEqual(parsedUrl?.id, "37i9dQZF1DXcBWIGoYBM5M")
        XCTAssertNil(MusicLibraryCore.parseSpotifyUrl("https://example.com/nope"))

        let sampleHtml = """
        <html><body><script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"state":{"data":{"entity":{"name":"Gece Modu","trackList":[
        {"title":"Işıklı Yol","subtitle":"İzel,\u{00A0}Ercan","duration":259000},
        {"title":"Halbuki","subtitle":"Yalın","duration":203000}]}}}}}}
        </script></body></html>
        """
        let sp = MusicLibraryCore.parseSpotifyEmbedHtml(sampleHtml)
        XCTAssertNotNil(sp)
        XCTAssertEqual(sp?.name, "Gece Modu")
        XCTAssertEqual(sp?.tracks.count, 2)
        XCTAssertEqual(sp?.tracks.first?.artist, "İzel, Ercan")
        XCTAssertEqual(sp?.tracks.first?.durationSec, 259)

        let ytKey = MusicLibraryCore.formatOnlineYtKey(videoId: "dQw4w9WgXcQ", title: "Halbuki", artist: "Yalın")
        XCTAssertTrue(MusicLibraryCore.isOnlinePlaylistKey(ytKey))
        let parsedYt = MusicLibraryCore.parseOnlinePlaylistKey(ytKey)
        XCTAssertEqual(parsedYt?.ytId, "dQw4w9WgXcQ")
        XCTAssertEqual(parsedYt?.title, "Yalın - Halbuki")

        let spKey = MusicLibraryCore.formatOnlineSpKey(artist: "Tate McRae", title: "Housewife")
        XCTAssertTrue(MusicLibraryCore.isOnlinePlaylistKey(spKey))
        let parsedSp = MusicLibraryCore.parseOnlinePlaylistKey(spKey)
        XCTAssertNil(parsedSp?.ytId)
        XCTAssertEqual(parsedSp?.title, "Tate McRae - Housewife")
    }
}
