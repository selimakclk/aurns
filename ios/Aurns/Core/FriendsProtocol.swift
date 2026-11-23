import Foundation

/// Windows (src/network.c, jam.c, friends.c, device_sync.c) ve Android (FriendsProtocol.kt)
/// ile %100 byte-düzeyi uyumlu saf MQTT 3.1.1 + Arkadaşlar + Canlı Jam + DSYNC1/DFULL1 protokolü.
enum FriendsProtocol {

    static let roomCodeAlphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

    // MARK: - Oda Kodları & Etiketler

    static func generateRoomCode(length: Int = 6) -> String {
        String((0..<length).map { _ in roomCodeAlphabet.randomElement()! })
    }

    static func normalizeRoomCode(_ rawInput: String) -> String {
        let trimmed = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        var out = ""
        out.reserveCapacity(32)
        for ch in trimmed {
            if out.count >= 32 { break }
            switch ch {
            case "a"..."z", "0"..."9", "-", "_": out.append(ch)
            case "A"..."Z": out.append(Character(UnicodeScalar(ch.asciiValue! + 32)))
            case "ç", "Ç": out.append("c")
            case "ğ", "Ğ": out.append("g")
            case "ı", "İ": out.append("i")
            case "ö", "Ö": out.append("o")
            case "ş", "Ş": out.append("s")
            case "ü", "Ü": out.append("u")
            default: continue
            }
        }
        return out.count < 2 ? "" : out
    }

    static func formatRoomDisplayCode(_ jamId: String) -> String {
        jamId.uppercased()
    }

    static func tagToTopicKey(_ tag: String) -> String {
        let trimmed = tag.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        var out = ""
        out.reserveCapacity(tag.count)
        for ch in tag {
            switch ch {
            case "ı", "İ", "I", "i": out.append("i")
            case "ş", "Ş", "s", "S": out.append("s")
            case "ğ", "Ğ", "g", "G": out.append("g")
            case "ü", "Ü", "u", "U": out.append("u")
            case "ö", "Ö", "o", "O": out.append("o")
            case "ç", "Ç", "c", "C": out.append("c")
            case "A"..."Z": out.append(Character(UnicodeScalar(ch.asciiValue! + 32)))
            case "a"..."z", "0"..."9", "-", "_": out.append(ch)
            default: out.append("_")
            }
        }
        return out
    }

    static func extractTagId(_ tag: String) -> String {
        guard !tag.trimmingCharacters(in: .whitespaces).isEmpty else { return "" }
        let src: Substring
        if let hash = tag.firstIndex(of: "#") {
            src = tag[tag.index(after: hash)...]
        } else {
            src = tag[...]
        }
        var out = ""
        for ch in src {
            if ch >= "0" && ch <= "9" {
                out.append(ch)
                if out.count >= 12 { break }
            } else if !out.isEmpty {
                break
            }
        }
        return out
    }

    static func sameUserTag(_ tagA: String, _ tagB: String) -> Bool {
        guard !tagA.trimmingCharacters(in: .whitespaces).isEmpty,
              !tagB.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        let idA = extractTagId(tagA)
        let idB = extractTagId(tagB)
        if idA.count >= 3 && idB.count >= 3 { return idA == idB }
        return tagToTopicKey(tagA) == tagToTopicKey(tagB)
    }

    static func sanitizeField(_ s: String) -> String {
        s.replacingOccurrences(of: "|", with: "-")
         .replacingOccurrences(of: "\r", with: "-")
         .replacingOccurrences(of: "\n", with: "-")
    }

    // MARK: - Jam Yükleri

    static func buildJamStatePayload(
        myTag: String,
        jamId: String,
        title: String,
        ytId: String,
        isPlaying: Bool,
        posMs: Int64
    ) -> String {
        let safePos = max(0, posMs)
        return "JAM_STATE|\(sanitizeField(myTag))|\(jamId)|\(isPlaying ? 1 : 0)|\(safePos)|\(sanitizeField(ytId))|\(sanitizeField(title))"
    }

    static func buildJamPosPayload(myTag: String, jamId: String, playFlag: Int, posMs: Int64) -> String {
        "JAM_POS|\(sanitizeField(myTag))|\(jamId)|\(playFlag)|\(max(0, posMs))"
    }

    // MARK: - Saf MQTT 3.1.1 İkili Kodlayıcı & Çözücü

    static func encodeRemainingLength(_ len: Int) -> [UInt8] {
        var out: [UInt8] = []
        var x = max(0, len)
        repeat {
            var b = UInt8(x % 128)
            x /= 128
            if x > 0 { b |= 0x80 }
            out.append(b)
        } while x > 0
        return out
    }

    static func encodeMqttConnect(clientId: String, keepAliveSec: Int = 60) -> Data {
        let cid = Array(clientId.utf8)
        let remLen = 10 + 2 + cid.count
        var pkt: [UInt8] = [0x10]
        pkt.append(contentsOf: encodeRemainingLength(remLen))
        pkt.append(contentsOf: [0x00, 0x04, 0x4D, 0x51, 0x54, 0x54]) // "MQTT"
        pkt.append(0x04) // MQTT 3.1.1
        pkt.append(0x02) // Clean Session
        pkt.append(UInt8((keepAliveSec >> 8) & 0xFF))
        pkt.append(UInt8(keepAliveSec & 0xFF))
        pkt.append(UInt8((cid.count >> 8) & 0xFF))
        pkt.append(UInt8(cid.count & 0xFF))
        pkt.append(contentsOf: cid)
        return Data(pkt)
    }

    static func encodeMqttSubscribe(packetId: UInt16, topic: String) -> Data? {
        guard !topic.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let top = Array(topic.utf8)
        let remLen = 2 + 2 + top.count + 1
        var pkt: [UInt8] = [0x82]
        pkt.append(contentsOf: encodeRemainingLength(remLen))
        pkt.append(UInt8((packetId >> 8) & 0xFF))
        pkt.append(UInt8(packetId & 0xFF))
        pkt.append(UInt8((top.count >> 8) & 0xFF))
        pkt.append(UInt8(top.count & 0xFF))
        pkt.append(contentsOf: top)
        pkt.append(0x00) // QoS 0
        return Data(pkt)
    }

    static func encodeMqttUnsubscribe(packetId: UInt16, topic: String) -> Data? {
        guard !topic.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let top = Array(topic.utf8)
        let remLen = 2 + 2 + top.count
        var pkt: [UInt8] = [0xA2]
        pkt.append(contentsOf: encodeRemainingLength(remLen))
        pkt.append(UInt8((packetId >> 8) & 0xFF))
        pkt.append(UInt8(packetId & 0xFF))
        pkt.append(UInt8((top.count >> 8) & 0xFF))
        pkt.append(UInt8(top.count & 0xFF))
        pkt.append(contentsOf: top)
        return Data(pkt)
    }

    static func encodeMqttPublish(topic: String, payload: String, retain: Bool = false) -> Data? {
        guard !topic.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let top = Array(topic.utf8)
        let pay = Array(payload.utf8)
        let remLen = 2 + top.count + pay.count
        var pkt: [UInt8] = [retain ? 0x31 : 0x30]
        pkt.append(contentsOf: encodeRemainingLength(remLen))
        pkt.append(UInt8((top.count >> 8) & 0xFF))
        pkt.append(UInt8(top.count & 0xFF))
        pkt.append(contentsOf: top)
        pkt.append(contentsOf: pay)
        return Data(pkt)
    }

    static let mqttPingReq = Data([0xC0, 0x00])

    static func decodeMqttPublishPacket(_ data: Data) -> (topic: String, payload: String)? {
        let bytes = Array(data)
        guard bytes.count >= 4 else { return nil }
        guard (bytes[0] & 0xF0) == 0x30 else { return nil }

        var multiplier = 1
        var remLen = 0
        var idx = 1
        while idx < bytes.count {
            let enc = Int(bytes[idx])
            idx += 1
            remLen += (enc & 0x7F) * multiplier
            if (enc & 0x80) == 0 { break }
            multiplier *= 128
            if multiplier > 128 * 128 * 128 { return nil }
        }
        guard idx + remLen <= bytes.count, idx + 2 <= bytes.count else { return nil }

        let topLen = (Int(bytes[idx]) << 8) | Int(bytes[idx + 1])
        idx += 2
        guard idx + topLen <= bytes.count else { return nil }
        guard let topic = String(bytes: bytes[idx..<(idx + topLen)], encoding: .utf8) else { return nil }
        idx += topLen

        let qos = (bytes[0] & 0x06) >> 1
        if qos > 0 {
            guard idx + 2 <= bytes.count else { return nil }
            idx += 2
        }
        let bodyEnd = min(bytes.count, idx + max(0, remLen - (2 + topLen + (qos > 0 ? 2 : 0))))
        let payload = String(bytes: bytes[idx..<bodyEnd], encoding: .utf8) ?? ""
        return (topic, payload)
    }

    // MARK: - Cihaz Senkronizasyonu (DSYNC1 & DFULL1 — PC ↔ Android ↔ iPhone)

    struct DeviceSyncWireEntry: Codable, Equatable {
        let tsSec: Int64
        let ytId: String
        let title: String
    }

    struct ParsedDeviceSyncMessage: Equatable {
        let senderDevId: String
        let senderPlatform: String
        let senderName: String
        let partnerLabel: String
        let entries: [DeviceSyncWireEntry]
    }

    struct DeviceSyncFullItem: Equatable {
        let ytId: String
        let title: String
    }

    struct ParsedDeviceSyncFullMessage: Equatable {
        let senderDevId: String
        let senderPlatform: String
        let senderName: String
        let partnerLabel: String
        let chunkIdx: Int
        let totalChunks: Int
        let items: [DeviceSyncFullItem]
    }

    static func sanitizeSyncField(_ raw: String, maxLen: Int) -> String {
        var out = ""
        for ch in raw {
            if out.count >= maxLen { break }
            if ch == "|" || ch == "~" || ch == ";" || ch == "\r" || ch == "\n" || ch == "\t" {
                out.append(" ")
            } else {
                out.append(ch)
            }
        }
        return out.trimmingCharacters(in: .whitespaces)
    }

    static func normalizeDeviceSyncCode(_ raw: String) -> String? {
        var clean = ""
        for ch in raw.uppercased() {
            if ((ch >= "A" && ch <= "Z") || (ch >= "0" && ch <= "9")) && clean.count < 12 {
                clean.append(ch)
            }
        }
        return clean.count >= 6 ? clean : nil
    }

    static func generateDeviceSyncCode(length: Int = 8) -> String {
        String((0..<length).map { _ in roomCodeAlphabet.randomElement()! })
    }

    static func deviceSyncWildcardTopic(_ syncCode: String) -> String {
        "aurns/sync/\(syncCode.lowercased())/dev/+"
    }

    static func deviceSyncPublishTopic(_ syncCode: String, devId: String) -> String {
        "aurns/sync/\(syncCode.lowercased())/dev/\(devId)"
    }

    static func deviceSyncFullWildcardTopic(_ syncCode: String) -> String {
        "aurns/sync/\(syncCode.lowercased())/full/+/+"
    }

    static func deviceSyncFullChunkTopic(_ syncCode: String, devId: String, chunkIdx: Int) -> String {
        "aurns/sync/\(syncCode.lowercased())/full/\(devId)/\(chunkIdx)"
    }

    static func encodeDeviceSyncPayload(
        devId: String,
        platform: String = "IOS",
        displayName: String,
        entries: [DeviceSyncWireEntry],
        maxListChars: Int = 780
    ) -> String {
        let safeName = sanitizeSyncField(displayName.isEmpty ? "iPhone" : displayName, maxLen: 32)
        let finalName = safeName.isEmpty ? "iPhone" : safeName
        var buf = ""
        var actualCount = 0
        for e in entries {
            let one = "\(actualCount > 0 ? ";" : "")\(e.tsSec)~\(e.ytId)~\(sanitizeSyncField(e.title, maxLen: 36))"
            if buf.count + one.count > maxListChars { break }
            buf += one
            actualCount += 1
        }
        return "DSYNC1|\(devId)|\(platform)|\(finalName)|\(actualCount)|\(buf)"
    }

    static func parseDeviceSyncMessage(syncCode: String, topic: String, payload: String) -> ParsedDeviceSyncMessage? {
        guard !syncCode.isEmpty else { return nil }
        let prefix = "aurns/sync/\(syncCode.lowercased())/dev/"
        guard topic.lowercased().hasPrefix(prefix), payload.hasPrefix("DSYNC1|") else { return nil }

        let parts = payload.split(separator: "|", maxSplits: 5, omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 6 else { return nil }
        let senderDev = parts[1].trimmingCharacters(in: .whitespaces)
        let senderPlat = parts[2].trimmingCharacters(in: .whitespaces)
        let senderName = parts[3].trimmingCharacters(in: .whitespaces)
        let listPart = parts[5]
        guard !senderDev.isEmpty else { return nil }

        let platLabel = senderPlat.caseInsensitiveCompare("PC") == .orderedSame ? "PC"
            : (senderPlat.caseInsensitiveCompare("IOS") == .orderedSame ? "iPhone" : "Telefon")
        let partnerLabel = senderName.isEmpty ? platLabel : "\(platLabel) (\(senderName))"

        var entries: [DeviceSyncWireEntry] = []
        if !listPart.isEmpty {
            for rawItem in listPart.split(separator: ";") {
                let seg = rawItem.split(separator: "~", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
                guard seg.count >= 3 else { continue }
                let ts = Int64(seg[0]) ?? 0
                let yt = seg[1].trimmingCharacters(in: .whitespaces)
                let title = seg[2].trimmingCharacters(in: .whitespaces)
                guard yt.count == 11 else { continue }
                entries.append(DeviceSyncWireEntry(tsSec: ts, ytId: yt, title: title.isEmpty ? yt : title))
            }
        }
        return ParsedDeviceSyncMessage(
            senderDevId: senderDev,
            senderPlatform: senderPlat,
            senderName: senderName,
            partnerLabel: partnerLabel,
            entries: entries
        )
    }

    static func encodeDeviceSyncFullChunks(
        devId: String,
        platform: String = "IOS",
        displayName: String,
        items: [DeviceSyncFullItem],
        maxListBytes: Int = 740,
        maxChunks: Int = 32
    ) -> [String] {
        guard !items.isEmpty else { return [] }
        let safeName = sanitizeSyncField(displayName.isEmpty ? "iPhone" : displayName, maxLen: 32)
        let finalName = safeName.isEmpty ? "iPhone" : safeName

        var bodies: [String] = []
        var cur = ""
        var curBytes = 0

        for it in items {
            if bodies.count >= maxChunks { break }
            let idPart = it.ytId.count == 11 ? it.ytId : "-"
            let titleSafe = sanitizeSyncField(it.title, maxLen: 34)
            guard !titleSafe.isEmpty else { continue }
            let one = "\(idPart)~\(titleSafe)"
            let oneBytes = one.utf8.count
            let sepBytes = cur.isEmpty ? 0 : 1
            if !cur.isEmpty && curBytes + sepBytes + oneBytes > maxListBytes {
                bodies.append(cur)
                cur = ""
                curBytes = 0
                if bodies.count >= maxChunks { break }
            }
            if !cur.isEmpty {
                cur.append(";")
                curBytes += 1
            }
            cur += one
            curBytes += oneBytes
        }
        if !cur.isEmpty && bodies.count < maxChunks {
            bodies.append(cur)
        }
        let total = bodies.count
        return bodies.enumerated().map { idx, body in
            "DFULL1|\(devId)|\(platform)|\(finalName)|\(idx)|\(total)|\(body)"
        }
    }

    static func parseDeviceSyncFullMessage(syncCode: String, topic: String, payload: String) -> ParsedDeviceSyncFullMessage? {
        guard !syncCode.isEmpty else { return nil }
        let prefix = "aurns/sync/\(syncCode.lowercased())/full/"
        guard topic.lowercased().hasPrefix(prefix), payload.hasPrefix("DFULL1|") else { return nil }

        let parts = payload.split(separator: "|", maxSplits: 6, omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 7,
              let chunkIdx = Int(parts[4]),
              let totalChunks = Int(parts[5]) else { return nil }
        let senderDev = parts[1].trimmingCharacters(in: .whitespaces)
        let senderPlat = parts[2].trimmingCharacters(in: .whitespaces)
        let senderName = parts[3].trimmingCharacters(in: .whitespaces)
        let listPart = parts[6]
        guard !senderDev.isEmpty else { return nil }

        let platLabel = senderPlat.caseInsensitiveCompare("PC") == .orderedSame ? "PC"
            : (senderPlat.caseInsensitiveCompare("IOS") == .orderedSame ? "iPhone" : "Telefon")
        let partnerLabel = senderName.isEmpty ? platLabel : "\(platLabel) (\(senderName))"

        var items: [DeviceSyncFullItem] = []
        if !listPart.isEmpty {
            for rawItem in listPart.split(separator: ";") {
                let seg = rawItem.split(separator: "~", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
                guard seg.count >= 2 else { continue }
                let rawId = seg[0].trimmingCharacters(in: .whitespaces)
                let yt = rawId.count == 11 ? rawId : ""
                let title = seg[1].trimmingCharacters(in: .whitespaces)
                if yt.isEmpty && title.isEmpty { continue }
                items.append(DeviceSyncFullItem(ytId: yt, title: title.isEmpty ? yt : title))
            }
        }
        return ParsedDeviceSyncFullMessage(
            senderDevId: senderDev,
            senderPlatform: senderPlat,
            senderName: senderName,
            partnerLabel: partnerLabel,
            chunkIdx: chunkIdx,
            totalChunks: totalChunks,
            items: items
        )
    }
}
