import Foundation
import Network
import UIKit

/// Windows PC (src/network.c, jam.c, device_sync.c) ve Android (FriendsManager.kt) ile
/// %100 byte-düzeyi uyumlu çift brokerli (EMQX + HiveMQ) TLS/TCP MQTT 3.1.1,
/// Arkadaşlar, sınırsız katılımcılı Canlı Jam ve kalıcı PC ↔ Android ↔ iPhone DSYNC1/DFULL1 motoru.
@MainActor
final class FriendsNetworkManager: ObservableObject {

    @Published var myTag: String = ""
    @Published var myName: String = ""
    @Published var isConnected: Bool = false
    @Published var friends: [FriendItem] = []
    @Published var pendingRequests: [FriendRequest] = []
    @Published var jamState = JamRoomState()
    @Published var deviceSyncState = DeviceSyncState()

    // Canlı Jam & Cihaz Senkronizasyonu olay dinleyicileri
    var onRemoteJamStateRequest: ((_ title: String, _ ytId: String, _ isPlaying: Bool, _ posMs: Int64, _ recvTimeMs: Int64) -> Void)?
    var onRemoteJamPosRequest: ((_ playFlag: Int, _ posMs: Int64, _ recvTimeMs: Int64) -> Void)?
    var onRemoteJamAskRequest: ((_ requesterTag: String) -> Void)?
    var onJamRoomClosed: (() -> Void)?
    var onDeviceSyncTracksReceived: ((_ items: [(ytId: String, title: String)]) -> Void)?

    private final class BrokerSlot {
        let host: String
        let index: Int
        var conn: NWConnection?
        var connected = false
        var recvBuffer = Data()
        var awaitingConnack = false
        var reconnectTask: Task<Void, Never>?

        init(host: String, index: Int) {
            self.host = host
            self.index = index
        }
    }

    private let brokers: [BrokerSlot] = [
        BrokerSlot(host: "broker.emqx.io", index: 0),
        BrokerSlot(host: "broker.hivemq.com", index: 1)
    ]
    private let netQueue = DispatchQueue(label: "com.aurns.mqtt", qos: .utility)
    private let defaults = UserDefaults.standard

    private var myFixedId: String = ""
    private var subscribedTopics = Set<String>()
    private var packetIdCounter: UInt16 = 1
    private var isRunning = false
    private var keepAliveTask: Task<Void, Never>?

    private var dedupHashes = [UInt64](repeating: 0, count: 32)
    private var dedupTimes = [Int64](repeating: 0, count: 32)
    private var dedupCursor = 0

    private var localIsPlaying = false
    private var localTrackTitle = ""
    private var inviteSentAtMs: Int64 = 0
    private var incomingInviteAtMs: Int64 = 0
    private var lastStateSenderTag = ""

    // Cihaz Senkronizasyonu (DSYNC1 + DFULL1)
    private var syncPairedAtSec: Int64 = 0
    private var syncInitialEnabled: Bool = false
    private var syncPublishedFullChunks: Int = 0
    private var syncOutLog: [FriendsProtocol.DeviceSyncWireEntry] = []
    private var syncSeenYtIds: [String] = []
    private var syncLastFullItems: [FriendsProtocol.DeviceSyncFullItem] = []
    private var lastPeerSyncTopic: String = ""
    private var lastPeerSyncPayload: String = ""

    private var pendingUserProbes: [String: CheckedContinuation<String?, Never>] = [:]

    init() {
        loadIdentityAndFriends()
        loadDeviceSyncPrefs()
        startNetwork()
    }

    func shutdown() {
        isRunning = false
        keepAliveTask?.cancel()
        let bye = "BYE|\(FriendsProtocol.sanitizeField(myTag))"
        for f in friends { publishToPeer(f.tag, payload: bye) }
        for b in brokers {
            b.reconnectTask?.cancel()
            b.conn?.cancel()
            b.conn = nil
            b.connected = false
        }
        isConnected = false
    }

    // MARK: - Başlatma & Kimlik

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000.0) }

    private func cleanDisplayName(_ raw: String) -> String {
        var out = ""
        for ch in raw {
            if let asc = ch.asciiValue, asc < 32 { continue }
            if ch == "#" || ch == "\"" || ch == "|" || ch == "\\" { continue }
            if ch == " " && out.isEmpty { continue }
            out.append(ch)
            if out.count >= 24 { break }
        }
        return out.trimmingCharacters(in: .whitespaces)
    }

    private func deterministicTagNumber() -> String {
        let seed = UIDevice.current.identifierForVendor?.uuidString ?? UIDevice.current.name
        var h: UInt64 = 0x5E1100
        for b in seed.utf8 {
            h = h &* 31 &+ UInt64(b)
        }
        return String(1000 + Int(h % 9000))
    }

    private func loadIdentityAndFriends() {
        let savedId = defaults.string(forKey: "aurns_fixed_id") ?? ""
        let savedName = defaults.string(forKey: "aurns_my_name") ?? ""
        myFixedId = savedId.count >= 3 ? savedId : deterministicTagNumber()
        let fallbackName = cleanDisplayName(UIDevice.current.name)
        let resolvedName = cleanDisplayName(savedName).isEmpty
            ? (fallbackName.isEmpty ? "iPhone" : String(fallbackName.prefix(14)))
            : cleanDisplayName(savedName)

        myName = resolvedName
        myTag = "\(resolvedName)#\(myFixedId)"
        defaults.set(myFixedId, forKey: "aurns_fixed_id")
        defaults.set(myName, forKey: "aurns_my_name")

        if let fData = defaults.data(forKey: "aurns_friends_v1"),
           let decoded = try? JSONDecoder().decode([FriendItem].self, from: fData) {
            friends = decoded.map {
                FriendItem(tag: $0.tag, name: $0.name, isOnline: false, isPlaying: false, currentTrack: "Çevrimdışı", lastSeenAt: 0)
            }
        }
        if let pData = defaults.data(forKey: "aurns_pending_v1"),
           let decoded = try? JSONDecoder().decode([FriendRequest].self, from: pData) {
            pendingRequests = decoded
        }
    }

    private func saveFriends() {
        if let fData = try? JSONEncoder().encode(friends) {
            defaults.set(fData, forKey: "aurns_friends_v1")
        }
        if let pData = try? JSONEncoder().encode(pendingRequests) {
            defaults.set(pData, forKey: "aurns_pending_v1")
        }
    }

    func updateMyName(_ newName: String) {
        let clean = cleanDisplayName(newName)
        guard !clean.isEmpty else { return }
        let oldKey = FriendsProtocol.tagToTopicKey(myTag)
        myName = clean
        myTag = "\(clean)#\(myFixedId)"
        defaults.set(myName, forKey: "aurns_my_name")

        let newKey = FriendsProtocol.tagToTopicKey(myTag)
        if !oldKey.isEmpty && oldKey.caseInsensitiveCompare(newKey) != .orderedSame {
            unsubscribeTopic("seloplayer/u/\(oldKey)")
        }
        subscribeCoreChannels()
        publishDirectoryRecord()

        let namePayload = "NAME_CHG|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(clean))"
        for f in friends {
            let fid = FriendsProtocol.extractTagId(f.tag)
            if !fid.isEmpty {
                publishAll(topic: "seloplayer/id/\(fid)", payload: namePayload)
                publishAll(topic: "seloplayer/mbox/\(fid)/name_\(myFixedId)", payload: namePayload, retain: true)
            }
            sendPong(to: f.tag)
        }
        requestAllStatuses()
    }

    // MARK: - Çift Brokerli MQTT 3.1.1 (TLS 8883 → TCP 1883)

    func startNetwork() {
        guard !isRunning else { return }
        isRunning = true
        for slot in brokers {
            connectBroker(slot, useTLS: true)
        }
        startKeepAliveLoop()
    }

    func reconnectIfNeeded() {
        if !isRunning {
            startNetwork()
            return
        }
        for slot in brokers where !slot.connected && slot.conn == nil {
            connectBroker(slot, useTLS: true)
        }
        if isConnected {
            requestAllStatuses()
            reannounceActiveJamRoom()
            publishMyDeviceSyncFeed()
            if syncInitialEnabled { publishMyFullLibraryChunks() }
        }
    }

    private func connectBroker(_ slot: BrokerSlot, useTLS: Bool) {
        guard isRunning else { return }
        slot.reconnectTask?.cancel()
        slot.conn?.cancel()
        slot.conn = nil
        slot.connected = false
        slot.recvBuffer.removeAll(keepingCapacity: true)
        slot.awaitingConnack = true
        refreshConnectionFlag()

        let port: NWEndpoint.Port = useTLS ? 8883 : 1883
        let params: NWParameters = useTLS ? .tls : .tcp
        if let tcpOpt = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOpt.noDelay = true
            tcpOpt.connectionTimeout = 4
        }

        let conn = NWConnection(host: NWEndpoint.Host(slot.host), port: port, using: params)
        slot.conn = conn

        conn.stateUpdateHandler = { [weak self] state in
            Task { @MainActor [weak self] in
                guard let self, slot.conn === conn else { return }
                switch state {
                case .ready:
                    let cid = "auios\(slot.index)\(self.myFixedId)\(Int.random(in: 1000...9999))"
                    let pkt = FriendsProtocol.encodeMqttConnect(clientId: cid, keepAliveSec: 60)
                    self.sendRaw(slot: slot, data: pkt)
                    self.receiveLoop(slot: slot, conn: conn)
                case .failed, .cancelled:
                    self.handleBrokerDisconnect(slot: slot, triedTLS: useTLS)
                default:
                    break
                }
            }
        }
        conn.start(queue: netQueue)
    }

    private func handleBrokerDisconnect(slot: BrokerSlot, triedTLS: Bool) {
        let wasConnected = slot.connected
        slot.connected = false
        slot.conn?.cancel()
        slot.conn = nil
        slot.recvBuffer.removeAll(keepingCapacity: false)
        refreshConnectionFlag()

        guard isRunning else { return }
        slot.reconnectTask?.cancel()
        slot.reconnectTask = Task { [weak self] in
            if !wasConnected && triedTLS {
                // TLS 8883 engelliyse gecikmeden TCP 1883'ü dene
                try? await Task.sleep(nanoseconds: 250_000_000)
                await MainActor.run { self?.connectBroker(slot, useTLS: false) }
            } else {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                await MainActor.run { self?.connectBroker(slot, useTLS: true) }
            }
        }
    }

    private func receiveLoop(slot: BrokerSlot, conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, isComplete, error in
            Task { @MainActor [weak self] in
                guard let self, slot.conn === conn else { return }
                if let data, !data.isEmpty {
                    slot.recvBuffer.append(data)
                    self.drainMqttBuffer(slot: slot)
                }
                if isComplete || error != nil {
                    self.handleBrokerDisconnect(slot: slot, triedTLS: false)
                    return
                }
                self.receiveLoop(slot: slot, conn: conn)
            }
        }
    }

    private func drainMqttBuffer(slot: BrokerSlot) {
        while slot.recvBuffer.count >= 2 {
            let bytes = [UInt8](slot.recvBuffer)
            var multiplier = 1
            var remLen = 0
            var idx = 1
            var lengthDone = false

            while idx < bytes.count {
                let enc = Int(bytes[idx])
                idx += 1
                remLen += (enc & 0x7F) * multiplier
                if (enc & 0x80) == 0 {
                    lengthDone = true
                    break
                }
                multiplier *= 128
                if multiplier > 128 * 128 * 128 {
                    handleBrokerDisconnect(slot: slot, triedTLS: false)
                    return
                }
            }
            if !lengthDone { return }
            let totalLen = idx + remLen
            if bytes.count < totalLen { return }

            let packetData = Data(bytes[0..<totalLen])
            slot.recvBuffer.removeFirst(totalLen)
            processMqttPacket(slot: slot, packet: packetData)
        }
    }

    private func processMqttPacket(slot: BrokerSlot, packet: Data) {
        guard let first = packet.first else { return }
        let pktType = first & 0xF0

        if pktType == 0x20 { // CONNACK
            guard packet.count >= 4, packet[3] == 0 else {
                handleBrokerDisconnect(slot: slot, triedTLS: false)
                return
            }
            slot.awaitingConnack = false
            slot.connected = true
            refreshConnectionFlag()

            collectCoreTopics()
            for t in subscribedTopics {
                if let sub = FriendsProtocol.encodeMqttSubscribe(packetId: nextPacketId(), topic: t) {
                    sendRaw(slot: slot, data: sub)
                }
            }
            publishDirectoryRecord()
            requestAllStatuses()
            reannounceActiveJamRoom()
            publishMyDeviceSyncFeed()
            if syncInitialEnabled { publishMyFullLibraryChunks() }
            return
        }

        if pktType == 0x30 { // PUBLISH
            guard let (topic, payload) = FriendsProtocol.decodeMqttPublishPacket(packet) else { return }
            if !shouldDropDuplicate(topic: topic, payload: payload) {
                handleNetworkMessage(topic: topic, payload: payload)
            }
        }
    }

    private func sendRaw(slot: BrokerSlot, data: Data) {
        guard let conn = slot.conn else { return }
        conn.send(content: data, completion: .contentProcessed { [weak self] err in
            if err != nil {
                Task { @MainActor [weak self] in
                    self?.handleBrokerDisconnect(slot: slot, triedTLS: false)
                }
            }
        })
    }

    private func refreshConnectionFlag() {
        isConnected = brokers.contains { $0.connected }
    }

    private func nextPacketId() -> UInt16 {
        let v = packetIdCounter
        packetIdCounter = packetIdCounter == UInt16.max ? 1 : (packetIdCounter + 1)
        return v
    }

    private func collectCoreTopics() {
        if !myFixedId.isEmpty {
            subscribedTopics.insert("seloplayer/id/\(myFixedId)")
            subscribedTopics.insert("seloplayer/mbox/\(myFixedId)")
            subscribedTopics.insert("seloplayer/mbox/\(myFixedId)/+")
        }
        let myKey = FriendsProtocol.tagToTopicKey(myTag)
        if !myKey.isEmpty {
            subscribedTopics.insert("seloplayer/u/\(myKey)")
        }
        for f in friends {
            let fid = FriendsProtocol.extractTagId(f.tag)
            if !fid.isEmpty { subscribedTopics.insert("seloplayer/reg/\(fid)") }
        }
        if !jamState.jamId.isEmpty && (jamState.isActive || jamState.isInviting) {
            subscribedTopics.insert(roomTopic(jamState.jamId))
        }
        if !deviceSyncState.syncCode.isEmpty {
            subscribedTopics.insert(FriendsProtocol.deviceSyncWildcardTopic(deviceSyncState.syncCode))
            if syncInitialEnabled {
                subscribedTopics.insert(FriendsProtocol.deviceSyncFullWildcardTopic(deviceSyncState.syncCode))
            }
        }
    }

    private func subscribeCoreChannels() {
        collectCoreTopics()
        for t in subscribedTopics { subscribeTopic(t) }
    }

    private func subscribeTopic(_ topic: String) {
        let clean = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        subscribedTopics.insert(clean)
        guard let pkt = FriendsProtocol.encodeMqttSubscribe(packetId: nextPacketId(), topic: clean) else { return }
        for slot in brokers where slot.connected {
            sendRaw(slot: slot, data: pkt)
        }
    }

    private func unsubscribeTopic(_ topic: String) {
        let clean = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        subscribedTopics.remove(clean)
        guard let pkt = FriendsProtocol.encodeMqttUnsubscribe(packetId: nextPacketId(), topic: clean) else { return }
        for slot in brokers where slot.connected {
            sendRaw(slot: slot, data: pkt)
        }
    }

    @discardableResult
    private func publishAll(topic: String, payload: String, retain: Bool = false) -> Bool {
        let clean = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty,
              let pkt = FriendsProtocol.encodeMqttPublish(topic: clean, payload: payload, retain: retain) else {
            return false
        }
        var sent = false
        for slot in brokers where slot.connected {
            sendRaw(slot: slot, data: pkt)
            sent = true
        }
        return sent
    }

    private func publishDirectoryRecord() {
        guard !myFixedId.isEmpty else { return }
        let reg = "REG|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))"
        publishAll(topic: "seloplayer/reg/\(myFixedId)", payload: reg, retain: true)
    }

    private func roomTopic(_ jamId: String) -> String { "seloplayer/jam/\(jamId)" }

    private func isMyInboxTopic(_ topic: String) -> Bool {
        let low = topic.lowercased()
        if !myFixedId.isEmpty {
            if low == "seloplayer/id/\(myFixedId)" || low == "seloplayer/mbox/\(myFixedId)" || low.hasPrefix("seloplayer/mbox/\(myFixedId)/") {
                return true
            }
        }
        let myKey = FriendsProtocol.tagToTopicKey(myTag)
        return !myKey.isEmpty && low == "seloplayer/u/\(myKey)"
    }

    private func shouldDropDuplicate(topic: String, payload: String) -> Bool {
        guard !payload.isEmpty else { return true }
        var h: UInt64 = 14695981039346656037
        for b in topic.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        h = (h ^ 0xFF) &* 1099511628211
        for b in payload.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        let now = nowMs()
        for i in 0..<32 {
            if dedupHashes[i] == h && dedupTimes[i] != 0 && (now - dedupTimes[i]) < 450 {
                return true
            }
        }
        dedupHashes[dedupCursor] = h
        dedupTimes[dedupCursor] = now
        dedupCursor = (dedupCursor + 1) % 32
        return false
    }

    private func startKeepAliveLoop() {
        keepAliveTask?.cancel()
        keepAliveTask = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard let self, self.isRunning else { break }
                tick += 1
                if tick % 2 == 0 {
                    for slot in self.brokers where slot.connected {
                        self.sendRaw(slot: slot, data: FriendsProtocol.mqttPingReq)
                    }
                    if self.isConnected { self.requestAllStatuses() }
                }

                let nowSec = Date().timeIntervalSince1970
                if self.friends.contains(where: { $0.isOnline && nowSec - $0.lastSeenAt > 32.0 }) {
                    self.friends = self.friends.map { f in
                        if f.isOnline && nowSec - f.lastSeenAt > 32.0 {
                            return FriendItem(tag: f.tag, name: f.name, isOnline: false, isPlaying: false, currentTrack: "Çevrimdışı", lastSeenAt: f.lastSeenAt)
                        }
                        return f
                    }
                }

                let nowMillis = self.nowMs()
                if self.jamState.isInviting && !self.jamState.isActive && self.inviteSentAtMs > 0 && nowMillis - self.inviteSentAtMs > 180_000 {
                    self.resetJamRoomInternal(unsub: true)
                }
                if self.jamState.hasIncomingInvite && self.incomingInviteAtMs > 0 && nowMillis - self.incomingInviteAtMs > 180_000 {
                    self.jamState.pendingInviteFromTag = nil
                    self.jamState.pendingInviteFromName = nil
                    self.jamState.pendingInviteJamId = nil
                }
            }
        }
    }

    // MARK: - Gelen Ağ Mesajı Yönlendiricisi

    private func handleNetworkMessage(topic: String, payload: String) {
        if topic.lowercased().hasPrefix("aurns/sync/") {
            handleDeviceSyncMessage(topic: topic, payload: payload)
            return
        }

        if topic.lowercased().hasPrefix("seloplayer/reg/") {
            if payload.hasPrefix("REG|") {
                let p = payload.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
                if p.count >= 2 {
                    let regTag = p[1].trimmingCharacters(in: .whitespaces)
                    let regName = (p.count >= 3 ? p[2] : "").trimmingCharacters(in: .whitespaces)
                    if !regTag.isEmpty {
                        completeMatchingProbes(foundTag: regTag)
                        updateFriendIdentity(newTag: regTag, newName: regName)
                    }
                }
            }
            return
        }

        let isInbox = isMyInboxTopic(topic)
        let isRoom = !jamState.jamId.isEmpty && topic.caseInsensitiveCompare(roomTopic(jamState.jamId)) == .orderedSame
        guard isInbox || isRoom else { return }

        let clearMbox = (!myFixedId.isEmpty &&
            (topic.caseInsensitiveCompare("seloplayer/mbox/\(myFixedId)") == .orderedSame ||
             topic.lowercased().hasPrefix("seloplayer/mbox/\(myFixedId)/"))) ? topic : ""

        let parts = payload.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard let action = parts.first, !action.isEmpty else { return }

        if isInbox {
            switch action {
            case "NAME_CHG":
                if parts.count >= 3 {
                    updateFriendIdentity(newTag: parts[1].trimmingCharacters(in: .whitespaces), newName: parts[2].trimmingCharacters(in: .whitespaces))
                }
                if !clearMbox.isEmpty { publishAll(topic: clearMbox, payload: "", retain: true) }
                return

            case "REQ":
                if parts.count >= 3 {
                    let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
                    let fromName = parts[2].trimmingCharacters(in: .whitespaces)
                    if !fromTag.isEmpty && !FriendsProtocol.sameUserTag(fromTag, myTag) {
                        if friends.contains(where: { FriendsProtocol.sameUserTag($0.tag, fromTag) }) {
                            let acc = "ACC|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))"
                            publishToPeer(fromTag, payload: acc)
                            let fid = FriendsProtocol.extractTagId(fromTag)
                            if !fid.isEmpty {
                                publishAll(topic: "seloplayer/mbox/\(fid)/acc_\(myFixedId)", payload: acc, retain: true)
                            }
                        } else if !pendingRequests.contains(where: { FriendsProtocol.sameUserTag($0.tag, fromTag) }) {
                            pendingRequests.append(FriendRequest(tag: fromTag, name: fromName.isEmpty ? fromTag : fromName))
                            saveFriends()
                        }
                    }
                }
                if !clearMbox.isEmpty { publishAll(topic: clearMbox, payload: "", retain: true) }
                return

            case "ACC":
                if parts.count >= 3 {
                    let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
                    let fromName = parts[2].trimmingCharacters(in: .whitespaces)
                    if !fromTag.isEmpty && !FriendsProtocol.sameUserTag(fromTag, myTag) {
                        upsertFriendOnline(tag: fromTag, name: fromName, isPlaying: false, track: "Çevrimiçi")
                        sendPong(to: fromTag)
                        sendPing(to: fromTag)
                    }
                }
                if !clearMbox.isEmpty { publishAll(topic: clearMbox, payload: "", retain: true) }
                return

            case "PING":
                if parts.count >= 2 {
                    let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
                    let fromName = (parts.count >= 3 ? parts[2] : "").trimmingCharacters(in: .whitespaces)
                    if !fromTag.isEmpty && !FriendsProtocol.sameUserTag(fromTag, myTag) {
                        touchFriend(tag: fromTag, name: fromName)
                        sendPong(to: fromTag)
                    }
                }
                return

            case "PONG":
                if parts.count >= 3 {
                    let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
                    let fromName = parts[2].trimmingCharacters(in: .whitespaces)
                    let isPlay = parts.count >= 4 && parts[3] == "1"
                    let trackRaw = parts.count >= 5 ? parts[4] : ""
                    let track = trackRaw.isEmpty ? (isPlay ? "Müzik Çalıyor" : "Çevrimiçi • Boşta") : trackRaw
                    if !fromTag.isEmpty {
                        updateFriendStatus(tag: fromTag, name: fromName, isOnline: true, isPlaying: isPlay, currentTrack: track)
                        completeMatchingProbes(foundTag: fromTag)
                    }
                }
                return

            case "BYE":
                if parts.count >= 2 {
                    updateFriendStatus(tag: parts[1].trimmingCharacters(in: .whitespaces), name: "", isOnline: false, isPlaying: false, currentTrack: "Çevrimdışı")
                }
                return

            case "JAM_INV", "JAM_INVITE":
                if parts.count >= 4 {
                    let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
                    let fromName = parts[2].trimmingCharacters(in: .whitespaces)
                    let invJamId = parts[3].trimmingCharacters(in: .whitespaces)
                    if !fromTag.isEmpty && !invJamId.isEmpty && !FriendsProtocol.sameUserTag(fromTag, myTag) {
                        if (jamState.isActive || jamState.isInviting) && jamState.jamId == invJamId { return }
                        incomingInviteAtMs = nowMs()
                        jamState.pendingInviteFromTag = fromTag
                        jamState.pendingInviteFromName = fromName.isEmpty ? fromTag : fromName
                        jamState.pendingInviteJamId = invJamId
                    }
                }
                return
            default:
                break
            }
        }

        handleJamMessage(action: action, parts: parts)
    }

    private func handleJamMessage(action: String, parts: [String]) {
        switch action {
        case "JAM_JOIN":
            guard parts.count >= 4 else { return }
            let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
            let fromName = parts[2].trimmingCharacters(in: .whitespaces)
            let msgJamId = parts[3].trimmingCharacters(in: .whitespaces)
            guard (jamState.isActive || jamState.isInviting), jamState.jamId == msgJamId,
                  !fromTag.isEmpty, !FriendsProtocol.sameUserTag(fromTag, myTag) else { return }

            let isNew = upsertJamMember(tag: fromTag, name: fromName)
            jamState.isActive = true
            jamState.isInviting = false
            if isNew { jamState.outgoingInviteCount = max(0, jamState.outgoingInviteCount - 1) }
            if !jamState.isDownloadingMissing {
                jamState.syncStatus = "Canlı milisaniye senkronizasyonu aktif"
            }
            if isNew {
                let here = "JAM_HERE|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))|\(msgJamId)"
                publishToPeer(fromTag, payload: here)
                publishAll(topic: roomTopic(msgJamId), payload: here)
            }
            if !jamState.isDownloadingMissing {
                onRemoteJamAskRequest?(fromTag)
            }

        case "JAM_HERE":
            guard parts.count >= 4 else { return }
            let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
            let fromName = parts[2].trimmingCharacters(in: .whitespaces)
            let msgJamId = parts[3].trimmingCharacters(in: .whitespaces)
            guard (jamState.isActive || jamState.isInviting), jamState.jamId == msgJamId,
                  !fromTag.isEmpty, !FriendsProtocol.sameUserTag(fromTag, myTag) else { return }
            let isNew = upsertJamMember(tag: fromTag, name: fromName)
            jamState.isActive = true
            jamState.isInviting = false
            if isNew && !jamState.isDownloadingMissing && lastStateSenderTag.isEmpty {
                jamState.syncStatus = "Odaya bağlanıldı • Canlı senkron hazır"
            }

        case "JAM_REJ":
            guard parts.count >= 3 else { return }
            let msgJamId = parts[2].trimmingCharacters(in: .whitespaces)
            guard (jamState.isActive || jamState.isInviting), jamState.jamId == msgJamId else { return }
            jamState.outgoingInviteCount = max(0, jamState.outgoingInviteCount - 1)
            if jamState.members.isEmpty && jamState.outgoingInviteCount == 0 && ((jamState.isInviting && !jamState.isActive) || !jamState.isHost) {
                resetJamRoomInternal(unsub: true)
            }

        case "JAM_LEAVE":
            guard parts.count >= 3 else { return }
            let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
            let msgJamId = parts[2].trimmingCharacters(in: .whitespaces)
            guard (jamState.isActive || jamState.isInviting), jamState.jamId == msgJamId,
                  !fromTag.isEmpty, !FriendsProtocol.sameUserTag(fromTag, myTag) else { return }
            jamState.members.removeAll { FriendsProtocol.sameUserTag($0.tag, fromTag) }
            if FriendsProtocol.sameUserTag(lastStateSenderTag, fromTag) { lastStateSenderTag = "" }
            if jamState.members.isEmpty && jamState.outgoingInviteCount <= 0 && !jamState.isHost {
                resetJamRoomInternal(unsub: true)
            } else if jamState.members.isEmpty && jamState.isHost && !jamState.isDownloadingMissing {
                jamState.syncStatus = "Katılımcı ayrıldı • Oda (\(jamState.roomDisplayCode)) açık"
            }

        case "JAM_ASK":
            guard parts.count >= 3 else { return }
            let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
            let msgJamId = parts[2].trimmingCharacters(in: .whitespaces)
            guard (jamState.isActive || jamState.isInviting), jamState.jamId == msgJamId,
                  !fromTag.isEmpty, !FriendsProtocol.sameUserTag(fromTag, myTag) else { return }
            _ = upsertJamMember(tag: fromTag, name: "")
            jamState.isActive = true
            jamState.isInviting = false
            if !jamState.isDownloadingMissing {
                onRemoteJamAskRequest?(fromTag)
            }

        case "JAM_STATE":
            guard parts.count >= 7 else { return }
            let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
            let msgJamId = parts[2].trimmingCharacters(in: .whitespaces)
            guard (jamState.isActive || jamState.isInviting), jamState.jamId == msgJamId,
                  !fromTag.isEmpty, !FriendsProtocol.sameUserTag(fromTag, myTag) else { return }
            let isPlay = parts[3] == "1"
            let posMs = max(0, Int64(parts[4]) ?? 0)
            let ytId = parts[5].trimmingCharacters(in: .whitespaces)
            let title = parts.dropFirst(6).joined(separator: "|").trimmingCharacters(in: .whitespaces)
            let recvNow = nowMs()

            _ = upsertJamMember(tag: fromTag, name: "")
            lastStateSenderTag = fromTag
            jamState.isActive = true
            jamState.isInviting = false
            jamState.currentTrackTitle = title
            jamState.currentYtId = ytId
            jamState.isPlaying = isPlay
            jamState.positionMs = posMs

            if !title.isEmpty || !ytId.isEmpty {
                onRemoteJamStateRequest?(title, ytId, isPlay, posMs, recvNow)
            }

        case "JAM_POS":
            guard parts.count >= 5 else { return }
            let fromTag = parts[1].trimmingCharacters(in: .whitespaces)
            let msgJamId = parts[2].trimmingCharacters(in: .whitespaces)
            guard (jamState.isActive || jamState.isInviting), jamState.jamId == msgJamId,
                  !fromTag.isEmpty, !FriendsProtocol.sameUserTag(fromTag, myTag) else { return }
            let playFlag = Int(parts[3]) ?? 0
            let posMs = max(0, Int64(parts[4]) ?? 0)
            let recvNow = nowMs()

            _ = upsertJamMember(tag: fromTag, name: "")
            lastStateSenderTag = fromTag
            jamState.isActive = true
            jamState.isInviting = false
            if playFlag >= 0 { jamState.isPlaying = (playFlag == 1) }
            jamState.positionMs = posMs
            onRemoteJamPosRequest?(playFlag, posMs, recvNow)

        default:
            break
        }
    }

    // MARK: - Arkadaşlar CRUD

    func checkUserAndSendRequest(targetTag rawInput: String) async -> Result<String, Error> {
        let raw = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "@", with: "")
        let targetId = FriendsProtocol.extractTagId(raw)
        let clean = (!targetId.isEmpty && !raw.contains("#")) ? "#\(targetId)" : raw.replacingOccurrences(of: " ", with: "")

        func fail(_ msg: String) -> Result<String, Error> {
            .failure(NSError(domain: "AurnsFriends", code: 1, userInfo: [NSLocalizedDescriptionKey: msg]))
        }

        guard !clean.isEmpty, (targetId.count >= 3 || clean.contains("#")) else {
            return fail("Etiket '#' ve numara içermelidir (Örn: selo#4770 veya #4770)")
        }
        if FriendsProtocol.sameUserTag(clean, myTag) {
            return fail("Kendinize arkadaşlık isteği gönderemezsiniz.")
        }
        if friends.contains(where: { FriendsProtocol.sameUserTag($0.tag, clean) }) {
            return fail("Bu kullanıcı zaten arkadaş listenizde.")
        }

        reconnectIfNeeded()
        for _ in 0..<25 where !isConnected {
            try? await Task.sleep(nanoseconds: 120_000_000)
        }
        guard isConnected else {
            return fail("Ağ bağlantısı kurulamadı. İnterneti kontrol edip tekrar deneyin.")
        }

        let probeKey = targetId.isEmpty ? FriendsProtocol.tagToTopicKey(clean) : targetId
        let regTopic = targetId.isEmpty ? "" : "seloplayer/reg/\(targetId)"
        if !regTopic.isEmpty { subscribeTopic(regTopic) }

        publishToPeer(clean, payload: "PING|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))")

        let resolvedTag = await probeUserWithTimeout(key: probeKey, ms: 850)
        if !regTopic.isEmpty { unsubscribeTopic(regTopic) }

        let finalTag = (resolvedTag?.isEmpty == false) ? resolvedTag! : clean
        let finalId = FriendsProtocol.extractTagId(finalTag).isEmpty ? targetId : FriendsProtocol.extractTagId(finalTag)

        let reqPayload = "REQ|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))"
        let sentPeer = publishToPeer(finalTag, payload: reqPayload)
        var sentMbox = false
        if !finalId.isEmpty {
            let r1 = publishAll(topic: "seloplayer/mbox/\(finalId)/req_\(myFixedId)", payload: reqPayload, retain: true)
            let r2 = publishAll(topic: "seloplayer/mbox/\(finalId)", payload: reqPayload, retain: true)
            sentMbox = r1 || r2
        }
        return (sentPeer || sentMbox)
            ? .success("İstek gönderildi!")
            : fail("İstek gönderilemedi, ağ bağlantısı koptu.")
    }

    private func probeUserWithTimeout(key: String, ms: UInt64) async -> String? {
        guard !key.isEmpty else { return nil }
        return await withCheckedContinuation { cont in
            pendingUserProbes[key] = cont
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: ms * 1_000_000)
                if let waiting = self?.pendingUserProbes.removeValue(forKey: key) {
                    waiting.resume(returning: nil)
                }
            }
        }
    }

    private func completeMatchingProbes(foundTag: String) {
        let fid = FriendsProtocol.extractTagId(foundTag)
        let fkey = FriendsProtocol.tagToTopicKey(foundTag)
        let matchingKeys = pendingUserProbes.keys.filter { k in
            (!fid.isEmpty && k == fid) ||
            k.caseInsensitiveCompare(fkey) == .orderedSame ||
            FriendsProtocol.sameUserTag(k, foundTag)
        }
        for k in matchingKeys {
            if let cont = pendingUserProbes.removeValue(forKey: k) {
                cont.resume(returning: foundTag)
            }
        }
    }

    func acceptFriendRequest(_ req: FriendRequest) {
        upsertFriendOnline(tag: req.tag, name: req.name, isPlaying: false, track: "Çevrimiçi")
        pendingRequests.removeAll { FriendsProtocol.sameUserTag($0.tag, req.tag) }
        saveFriends()

        let acc = "ACC|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))"
        publishToPeer(req.tag, payload: acc)
        let fid = FriendsProtocol.extractTagId(req.tag)
        if !fid.isEmpty {
            publishAll(topic: "seloplayer/mbox/\(fid)/acc_\(myFixedId)", payload: acc, retain: true)
        }
        sendPong(to: req.tag)
        sendPing(to: req.tag)
    }

    func rejectFriendRequest(_ req: FriendRequest) {
        pendingRequests.removeAll { FriendsProtocol.sameUserTag($0.tag, req.tag) }
        saveFriends()
    }

    func removeFriend(_ friend: FriendItem) {
        friends.removeAll { FriendsProtocol.sameUserTag($0.tag, friend.tag) }
        saveFriends()
    }

    func requestAllStatuses() {
        for f in friends { sendPing(to: f.tag) }
    }

    func updateLocalPlaybackState(isPlaying: Bool, trackTitle: String) {
        localIsPlaying = isPlaying
        localTrackTitle = trackTitle
        for f in friends { sendPong(to: f.tag) }
    }

    @discardableResult
    private func publishToPeer(_ peerTag: String, payload: String) -> Bool {
        guard !peerTag.isEmpty else { return false }
        var sent = false
        let fid = FriendsProtocol.extractTagId(peerTag)
        if !fid.isEmpty && publishAll(topic: "seloplayer/id/\(fid)", payload: payload) { sent = true }
        if !peerTag.hasPrefix("#") {
            let key = FriendsProtocol.tagToTopicKey(peerTag)
            if !key.isEmpty && publishAll(topic: "seloplayer/u/\(key)", payload: payload) { sent = true }
        }
        return sent
    }

    private func sendPing(to tag: String) {
        publishToPeer(tag, payload: "PING|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))")
    }

    private func sendPong(to tag: String) {
        let track = localIsPlaying ? FriendsProtocol.sanitizeField(localTrackTitle) : ""
        publishToPeer(tag, payload: "PONG|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))|\(localIsPlaying ? 1 : 0)|\(track)")
    }

    private func upsertFriendOnline(tag: String, name: String, isPlaying: Bool, track: String) {
        let now = Date().timeIntervalSince1970
        if let idx = friends.firstIndex(where: { FriendsProtocol.sameUserTag($0.tag, tag) }) {
            let old = friends[idx]
            friends[idx] = FriendItem(
                tag: tag,
                name: name.isEmpty ? old.name : name,
                isOnline: true,
                isPlaying: isPlaying,
                currentTrack: track,
                lastSeenAt: now
            )
        } else {
            friends.append(FriendItem(
                tag: tag,
                name: name.isEmpty ? tag : name,
                isOnline: true,
                isPlaying: isPlaying,
                currentTrack: track,
                lastSeenAt: now
            ))
        }
        saveFriends()
    }

    private func touchFriend(tag: String, name: String) {
        guard let idx = friends.firstIndex(where: { FriendsProtocol.sameUserTag($0.tag, tag) }) else { return }
        friends[idx].isOnline = true
        friends[idx].lastSeenAt = Date().timeIntervalSince1970
        if !name.isEmpty && friends[idx].name != name {
            friends[idx].name = name
            saveFriends()
        }
    }

    private func updateFriendIdentity(newTag: String, newName: String) {
        guard let idx = friends.firstIndex(where: { FriendsProtocol.sameUserTag($0.tag, newTag) }) else { return }
        let resolved = newName.isEmpty ? String(newTag.split(separator: "#").first ?? Substring(friends[idx].name)) : newName
        if friends[idx].name != resolved || friends[idx].tag != newTag {
            friends[idx] = FriendItem(
                tag: newTag,
                name: resolved,
                isOnline: friends[idx].isOnline,
                isPlaying: friends[idx].isPlaying,
                currentTrack: friends[idx].currentTrack,
                lastSeenAt: friends[idx].lastSeenAt
            )
            saveFriends()
        }
    }

    private func updateFriendStatus(tag: String, name: String, isOnline: Bool, isPlaying: Bool, currentTrack: String) {
        guard let idx = friends.firstIndex(where: { FriendsProtocol.sameUserTag($0.tag, tag) }) else { return }
        let old = friends[idx]
        friends[idx] = FriendItem(
            tag: isOnline ? tag : old.tag,
            name: name.isEmpty ? old.name : name,
            isOnline: isOnline,
            isPlaying: isPlaying,
            currentTrack: currentTrack,
            lastSeenAt: isOnline ? Date().timeIntervalSince1970 : old.lastSeenAt
        )
        if old.tag != friends[idx].tag || old.name != friends[idx].name { saveFriends() }
    }

    // MARK: - Sınırsız Katılımcılı Canlı Jam

    @discardableResult
    private func upsertJamMember(tag: String, name: String) -> Bool {
        guard !tag.isEmpty, !FriendsProtocol.sameUserTag(tag, myTag) else { return false }
        if let idx = jamState.members.firstIndex(where: { FriendsProtocol.sameUserTag($0.tag, tag) }) {
            if !name.isEmpty { jamState.members[idx] = JamMember(tag: tag, name: name) }
            return false
        }
        let disp = name.isEmpty ? (friends.first(where: { FriendsProtocol.sameUserTag($0.tag, tag) })?.name ?? tag) : name
        jamState.members.append(JamMember(tag: tag, name: disp))
        return true
    }

    private func resetJamRoomInternal(unsub: Bool) {
        if unsub && !jamState.jamId.isEmpty {
            unsubscribeTopic(roomTopic(jamState.jamId))
        }
        lastStateSenderTag = ""
        inviteSentAtMs = 0
        let invTag = jamState.pendingInviteFromTag
        let invName = jamState.pendingInviteFromName
        let invId = jamState.pendingInviteJamId
        jamState = JamRoomState(
            pendingInviteFromTag: invTag,
            pendingInviteFromName: invName,
            pendingInviteJamId: invId
        )
        onJamRoomClosed?()
    }

    func setJamSyncStatus(_ status: String, isDownloading: Bool = false, missingTitle: String = "") {
        guard jamState.isActive || jamState.isInviting else { return }
        jamState.syncStatus = status
        jamState.isDownloadingMissing = isDownloading
        jamState.missingTrackTitle = missingTitle
    }

    private func publishRoomJoinAndAsk(jamId: String, includeAsk: Bool) {
        let topic = roomTopic(jamId)
        let myT = FriendsProtocol.sanitizeField(myTag)
        let myN = FriendsProtocol.sanitizeField(myName)
        publishAll(topic: topic, payload: "JAM_JOIN|\(myT)|\(myN)|\(jamId)")
        if includeAsk {
            publishAll(topic: topic, payload: "JAM_ASK|\(myT)|\(jamId)")
        }
    }

    private func reannounceActiveJamRoom() {
        guard (jamState.isActive || jamState.isInviting), !jamState.jamId.isEmpty else { return }
        subscribeTopic(roomTopic(jamState.jamId))
        publishRoomJoinAndAsk(jamId: jamState.jamId, includeAsk: !jamState.isHost && !jamState.isDownloadingMissing)
    }

    @discardableResult
    func createJamRoom() -> String {
        if jamState.isActive && !jamState.jamId.isEmpty { return jamState.jamId }
        let newId = FriendsProtocol.normalizeRoomCode(FriendsProtocol.generateRoomCode())
        let disp = FriendsProtocol.formatRoomDisplayCode(newId)
        jamState = JamRoomState(
            isActive: true,
            isInviting: false,
            isHost: true,
            jamId: newId,
            members: [],
            syncStatus: "Oda oluşturuldu (\(disp)) • Kodu paylaş"
        )
        reconnectIfNeeded()
        subscribeTopic(roomTopic(newId))
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 120_000_000)
            self?.publishRoomJoinAndAsk(jamId: newId, includeAsk: false)
        }
        return newId
    }

    @discardableResult
    func joinJamRoomByCode(_ rawCode: String) -> Bool {
        let norm = FriendsProtocol.normalizeRoomCode(rawCode)
        guard !norm.isEmpty else { return false }
        let disp = FriendsProtocol.formatRoomDisplayCode(norm)
        if (jamState.isActive || jamState.isInviting) && jamState.jamId != norm {
            leaveJamRoom()
        }
        lastStateSenderTag = ""
        jamState = JamRoomState(
            isActive: true,
            isInviting: false,
            isHost: false,
            jamId: norm,
            members: [],
            syncStatus: "\(disp) odasına bağlanılıyor..."
        )
        reconnectIfNeeded()
        subscribeTopic(roomTopic(norm))
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 150_000_000)
            self?.publishRoomJoinAndAsk(jamId: norm, includeAsk: true)
            try? await Task.sleep(nanoseconds: 800_000_000)
            if let s = self, s.jamState.isActive && s.jamState.jamId == norm && s.jamState.currentTrackTitle.isEmpty {
                s.publishRoomJoinAndAsk(jamId: norm, includeAsk: true)
            }
        }
        return true
    }

    func inviteFriendToJam(_ friend: FriendItem) {
        guard !FriendsProtocol.sameUserTag(friend.tag, myTag), !jamState.isFriendInRoom(friend.tag) else { return }
        var needSub = false
        let activeId: String
        if (jamState.isActive || jamState.isInviting) && !jamState.jamId.isEmpty {
            activeId = jamState.jamId
        } else {
            activeId = FriendsProtocol.normalizeRoomCode(FriendsProtocol.generateRoomCode())
            needSub = true
        }
        inviteSentAtMs = nowMs()
        let wasActive = jamState.isActive && !jamState.jamId.isEmpty
        jamState.isActive = wasActive
        jamState.isInviting = !wasActive
        jamState.isHost = (wasActive || jamState.isInviting) ? jamState.isHost : true
        jamState.jamId = activeId
        jamState.outgoingInviteCount += 1
        jamState.lastInvitedTag = friend.tag
        jamState.lastInvitedName = friend.name
        jamState.syncStatus = wasActive ? "\(friend.name) odaya davet edildi" : "Davet gönderildi • Kabul bekleniyor..."

        let payload = "JAM_INV|\(FriendsProtocol.sanitizeField(myTag))|\(FriendsProtocol.sanitizeField(myName))|\(activeId)"
        Task { @MainActor [weak self] in
            guard let self else { return }
            if needSub {
                self.subscribeTopic(self.roomTopic(activeId))
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            self.sendPing(to: friend.tag)
            self.publishToPeer(friend.tag, payload: payload)
        }
    }

    func acceptJamInvite() {
        guard let invTag = jamState.pendingInviteFromTag,
              let invJamId = jamState.pendingInviteJamId, !invJamId.isEmpty else { return }
        let invName = jamState.pendingInviteFromName ?? invTag
        if (jamState.isActive || jamState.isInviting) && jamState.jamId != invJamId {
            leaveJamRoom()
        }
        lastStateSenderTag = invTag
        jamState = JamRoomState(
            isActive: true,
            isInviting: false,
            isHost: false,
            jamId: invJamId,
            members: [JamMember(tag: invTag, name: invName)],
            syncStatus: "Odaya bağlanıldı • Canlı yayın senkronize ediliyor..."
        )
        let myT = FriendsProtocol.sanitizeField(myTag)
        let myN = FriendsProtocol.sanitizeField(myName)
        let joinP = "JAM_JOIN|\(myT)|\(myN)|\(invJamId)"
        let askP = "JAM_ASK|\(myT)|\(invJamId)"

        subscribeTopic(roomTopic(invJamId))
        Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: 160_000_000)
            self.publishToPeer(invTag, payload: joinP)
            self.publishAll(topic: self.roomTopic(invJamId), payload: joinP)
            self.publishToPeer(invTag, payload: askP)
            self.publishAll(topic: self.roomTopic(invJamId), payload: askP)
            try? await Task.sleep(nanoseconds: 850_000_000)
            if self.jamState.isActive && self.jamState.jamId == invJamId && self.jamState.currentTrackTitle.isEmpty {
                self.requestJamPartnerState()
            }
        }
    }

    func rejectJamInvite() {
        guard let invTag = jamState.pendingInviteFromTag,
              let pJamId = jamState.pendingInviteJamId else { return }
        jamState.pendingInviteFromTag = nil
        jamState.pendingInviteFromName = nil
        jamState.pendingInviteJamId = nil
        publishToPeer(invTag, payload: "JAM_REJ|\(FriendsProtocol.sanitizeField(myTag))|\(pJamId)")
    }

    func leaveJamRoom() {
        guard jamState.isActive || jamState.isInviting else { return }
        let snap = jamState
        let oldId = snap.jamId
        resetJamRoomInternal(unsub: false)
        guard !oldId.isEmpty else { return }
        let leaveP = "JAM_LEAVE|\(FriendsProtocol.sanitizeField(myTag))|\(oldId)"
        publishAll(topic: roomTopic(oldId), payload: leaveP)
        for m in snap.members { publishToPeer(m.tag, payload: leaveP) }
        if snap.isInviting && !snap.lastInvitedTag.isEmpty {
            publishToPeer(snap.lastInvitedTag, payload: leaveP)
        }
        unsubscribeTopic(roomTopic(oldId))
    }

    func broadcastJamState(title: String, ytId: String, isPlaying: Bool, posMs: Int64) {
        guard (jamState.isActive || jamState.isInviting), !jamState.jamId.isEmpty else { return }
        jamState.currentTrackTitle = title
        jamState.currentYtId = ytId
        jamState.isPlaying = isPlaying
        jamState.positionMs = max(0, posMs)
        jamState.syncStatus = isPlaying ? "✓ Canlı milisaniye senkronize" : "⏸ Ortak duraklatıldı"
        jamState.isDownloadingMissing = false

        let payload = FriendsProtocol.buildJamStatePayload(
            myTag: myTag, jamId: jamState.jamId, title: title, ytId: ytId, isPlaying: isPlaying, posMs: posMs
        )
        publishAll(topic: roomTopic(jamState.jamId), payload: payload)
        for m in jamState.members { publishToPeer(m.tag, payload: payload) }
    }

    func sendJamStateToPeer(targetTag: String, title: String, ytId: String, isPlaying: Bool, posMs: Int64) {
        guard (jamState.isActive || jamState.isInviting), !jamState.jamId.isEmpty, !targetTag.isEmpty else { return }
        let payload = FriendsProtocol.buildJamStatePayload(
            myTag: myTag, jamId: jamState.jamId, title: title, ytId: ytId, isPlaying: isPlaying, posMs: posMs
        )
        publishToPeer(targetTag, payload: payload)
        publishAll(topic: roomTopic(jamState.jamId), payload: payload)
    }

    func broadcastJamPos(playFlag: Int, posMs: Int64) {
        guard (jamState.isActive || jamState.isInviting), !jamState.jamId.isEmpty else { return }
        let safePos = max(0, posMs)
        if playFlag == 0 {
            jamState.isPlaying = false
            jamState.syncStatus = "⏸ Ortak duraklatıldı"
        } else if playFlag == 1 {
            jamState.isPlaying = true
            jamState.syncStatus = "✓ Canlı milisaniye senkronize"
        }
        jamState.positionMs = safePos
        let payload = FriendsProtocol.buildJamPosPayload(myTag: myTag, jamId: jamState.jamId, playFlag: playFlag, posMs: safePos)
        publishAll(topic: roomTopic(jamState.jamId), payload: payload)
        for m in jamState.members { publishToPeer(m.tag, payload: payload) }
    }

    func requestJamPartnerState() {
        guard (jamState.isActive || jamState.isInviting), !jamState.jamId.isEmpty else { return }
        let payload = "JAM_ASK|\(FriendsProtocol.sanitizeField(myTag))|\(jamState.jamId)"
        if !lastStateSenderTag.isEmpty { publishToPeer(lastStateSenderTag, payload: payload) }
        else if let first = jamState.members.first { publishToPeer(first.tag, payload: payload) }
        publishAll(topic: roomTopic(jamState.jamId), payload: payload)
    }

    // MARK: - Cihaz Senkronizasyonu (PC ↔ Android ↔ iPhone Kalıcı DSYNC1 + DFULL1)

    private func mySyncDeviceId() -> String { "ios_\(myFixedId.isEmpty ? "0001" : myFixedId)" }

    @discardableResult
    func generateAndPairDeviceSync() -> String {
        let code = FriendsProtocol.generateDeviceSyncCode()
        _ = pairDeviceSync(code)
        return code
    }

    @discardableResult
    func pairDeviceSync(_ rawCode: String) -> Bool {
        guard let norm = FriendsProtocol.normalizeDeviceSyncCode(rawCode) else { return false }
        let oldCode = deviceSyncState.syncCode
        let sameCode = oldCode.caseInsensitiveCompare(norm) == .orderedSame
        if !oldCode.isEmpty && !sameCode {
            clearPublishedFullChunks(code: oldCode)
            unsubscribeTopic(FriendsProtocol.deviceSyncWildcardTopic(oldCode))
            unsubscribeTopic(FriendsProtocol.deviceSyncFullWildcardTopic(oldCode))
        }
        if !sameCode {
            syncPairedAtSec = Int64(Date().timeIntervalSince1970)
            syncInitialEnabled = false
            syncPublishedFullChunks = 0
            syncOutLog.removeAll()
            syncSeenYtIds.removeAll()
            syncLastFullItems.removeAll()
            lastPeerSyncTopic = ""
            lastPeerSyncPayload = ""
        }
        deviceSyncState = DeviceSyncState(
            syncCode: norm,
            autoDownload: deviceSyncState.autoDownload,
            initialSyncEnabled: syncInitialEnabled,
            partnerLabel: sameCode ? deviceSyncState.partnerLabel : ""
        )
        saveDeviceSyncPrefs()
        subscribeTopic(FriendsProtocol.deviceSyncWildcardTopic(norm))
        publishMyDeviceSyncFeed()
        return true
    }

    @discardableResult
    func enableInitialLibrarySync(tracks: [Track]) -> Int {
        let code = deviceSyncState.syncCode
        guard !code.isEmpty else { return 0 }
        let wireItems = tracks.compactMap { t -> FriendsProtocol.DeviceSyncFullItem? in
            let title = t.title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { return nil }
            let yt = t.ytId ?? MusicLibraryCore.extractYouTubeId(t.storageKey) ?? ""
            return FriendsProtocol.DeviceSyncFullItem(ytId: yt, title: title)
        }
        syncInitialEnabled = true
        syncLastFullItems = wireItems
        deviceSyncState.initialSyncEnabled = true
        saveDeviceSyncPrefs()
        subscribeTopic(FriendsProtocol.deviceSyncFullWildcardTopic(code))
        publishMyFullLibraryChunks()
        return wireItems.count
    }

    func unpairDeviceSync() {
        let oldCode = deviceSyncState.syncCode
        if !oldCode.isEmpty {
            clearPublishedFullChunks(code: oldCode)
            unsubscribeTopic(FriendsProtocol.deviceSyncWildcardTopic(oldCode))
            unsubscribeTopic(FriendsProtocol.deviceSyncFullWildcardTopic(oldCode))
        }
        syncPairedAtSec = 0
        syncInitialEnabled = false
        syncPublishedFullChunks = 0
        syncOutLog.removeAll()
        syncSeenYtIds.removeAll()
        syncLastFullItems.removeAll()
        lastPeerSyncTopic = ""
        lastPeerSyncPayload = ""
        deviceSyncState = DeviceSyncState(syncCode: "", autoDownload: deviceSyncState.autoDownload, initialSyncEnabled: false, partnerLabel: "")
        saveDeviceSyncPrefs()
    }

    func setDeviceSyncAutoDownload(_ enabled: Bool) {
        guard deviceSyncState.autoDownload != enabled else { return }
        deviceSyncState.autoDownload = enabled
        saveDeviceSyncPrefs()
        if enabled && deviceSyncState.isPaired && !lastPeerSyncTopic.isEmpty && !lastPeerSyncPayload.isEmpty {
            handleDeviceSyncMessage(topic: lastPeerSyncTopic, payload: lastPeerSyncPayload)
        }
    }

    func notifyLocalDownloadForSync(ytId: String, title: String) {
        let code = deviceSyncState.syncCode
        guard !code.isEmpty, MusicLibraryCore.isValidYtId(ytId) else { return }
        if syncSeenYtIds.contains(where: { $0.caseInsensitiveCompare(ytId) == .orderedSame }) { return }
        syncOutLog.removeAll { $0.ytId.caseInsensitiveCompare(ytId) == .orderedSame }
        while syncOutLog.count >= 14 { syncOutLog.removeFirst() }
        syncOutLog.append(FriendsProtocol.DeviceSyncWireEntry(
            tsSec: Int64(Date().timeIntervalSince1970),
            ytId: ytId,
            title: FriendsProtocol.sanitizeSyncField(title.isEmpty ? ytId : title, maxLen: 40)
        ))
        saveDeviceSyncPrefs()
        publishMyDeviceSyncFeed()
    }

    private func publishMyDeviceSyncFeed() {
        let code = deviceSyncState.syncCode
        guard !code.isEmpty else { return }
        let devId = mySyncDeviceId()
        let payload = FriendsProtocol.encodeDeviceSyncPayload(
            devId: devId,
            platform: "IOS",
            displayName: myName.isEmpty ? "iPhone" : myName,
            entries: syncOutLog
        )
        publishAll(topic: FriendsProtocol.deviceSyncPublishTopic(code, devId: devId), payload: payload, retain: true)
    }

    private func publishMyFullLibraryChunks() {
        let code = deviceSyncState.syncCode
        guard !code.isEmpty, syncInitialEnabled, !syncLastFullItems.isEmpty else { return }
        let devId = mySyncDeviceId()
        let payloads = FriendsProtocol.encodeDeviceSyncFullChunks(
            devId: devId,
            platform: "IOS",
            displayName: myName.isEmpty ? "iPhone" : myName,
            items: syncLastFullItems
        )
        for (i, body) in payloads.enumerated() {
            publishAll(topic: FriendsProtocol.deviceSyncFullChunkTopic(code, devId: devId, chunkIdx: i), payload: body, retain: true)
        }
        if syncPublishedFullChunks > payloads.count {
            for c in payloads.count..<min(32, syncPublishedFullChunks) {
                publishAll(topic: FriendsProtocol.deviceSyncFullChunkTopic(code, devId: devId, chunkIdx: c), payload: "", retain: true)
            }
        }
        syncPublishedFullChunks = payloads.count
        saveDeviceSyncPrefs()
    }

    private func clearPublishedFullChunks(code: String) {
        guard !code.isEmpty, syncPublishedFullChunks > 0 else { return }
        let devId = mySyncDeviceId()
        for c in 0..<min(32, syncPublishedFullChunks) {
            publishAll(topic: FriendsProtocol.deviceSyncFullChunkTopic(code, devId: devId, chunkIdx: c), payload: "", retain: true)
        }
    }

    private func handleDeviceSyncMessage(topic: String, payload: String) {
        let code = deviceSyncState.syncCode
        let myDev = mySyncDeviceId()

        if payload.hasPrefix("DFULL1|") {
            guard syncInitialEnabled,
                  let full = FriendsProtocol.parseDeviceSyncFullMessage(syncCode: code, topic: topic, payload: payload),
                  full.senderDevId != myDev else { return }
            if deviceSyncState.partnerLabel != full.partnerLabel {
                deviceSyncState.partnerLabel = full.partnerLabel
                saveDeviceSyncPrefs()
            }
            guard deviceSyncState.autoDownload, !full.items.isEmpty else { return }

            var batch: [(String, String)] = []
            var seenChanged = false
            for item in full.items {
                if item.ytId.count == 11 {
                    if syncSeenYtIds.contains(where: { $0.caseInsensitiveCompare(item.ytId) == .orderedSame }) { continue }
                    if syncSeenYtIds.count >= 512 { syncSeenYtIds.removeFirst() }
                    syncSeenYtIds.append(item.ytId)
                    seenChanged = true
                    batch.append((item.ytId, item.title))
                } else if !item.title.isEmpty {
                    batch.append(("", item.title))
                }
            }
            if seenChanged { saveDeviceSyncPrefs() }
            if !batch.isEmpty { onDeviceSyncTracksReceived?(batch) }
            return
        }

        guard let parsed = FriendsProtocol.parseDeviceSyncMessage(syncCode: code, topic: topic, payload: payload),
              parsed.senderDevId != myDev else { return }

        lastPeerSyncTopic = topic
        lastPeerSyncPayload = payload

        if deviceSyncState.partnerLabel != parsed.partnerLabel {
            deviceSyncState.partnerLabel = parsed.partnerLabel
            saveDeviceSyncPrefs()
        }
        guard deviceSyncState.autoDownload, !parsed.entries.isEmpty else { return }

        var batch: [(String, String)] = []
        var seenChanged = false
        for e in parsed.entries {
            if syncPairedAtSec > 0 && e.tsSec < syncPairedAtSec - 120 { continue }
            if syncSeenYtIds.contains(where: { $0.caseInsensitiveCompare(e.ytId) == .orderedSame }) { continue }
            if syncSeenYtIds.count >= 512 { syncSeenYtIds.removeFirst() }
            syncSeenYtIds.append(e.ytId)
            seenChanged = true
            batch.append((e.ytId, e.title))
        }
        if seenChanged { saveDeviceSyncPrefs() }
        if !batch.isEmpty { onDeviceSyncTracksReceived?(batch) }
    }

    private func loadDeviceSyncPrefs() {
        let code = defaults.string(forKey: "dsync_code") ?? ""
        let autoDl = defaults.object(forKey: "dsync_auto_dl") as? Bool ?? true
        let partner = defaults.string(forKey: "dsync_partner") ?? ""
        syncPairedAtSec = Int64(defaults.integer(forKey: "dsync_paired_at"))
        syncInitialEnabled = code.isEmpty ? false : defaults.bool(forKey: "dsync_initial_sync")
        syncPublishedFullChunks = code.isEmpty ? 0 : defaults.integer(forKey: "dsync_full_chunks")
        if !code.isEmpty {
            if let data = defaults.data(forKey: "dsync_out_log"),
               let decoded = try? JSONDecoder().decode([FriendsProtocol.DeviceSyncWireEntry].self, from: data) {
                syncOutLog = decoded
            }
            syncSeenYtIds = defaults.stringArray(forKey: "dsync_seen_ids") ?? []
        }
        deviceSyncState = DeviceSyncState(
            syncCode: code,
            autoDownload: autoDl,
            initialSyncEnabled: syncInitialEnabled,
            partnerLabel: code.isEmpty ? "" : partner
        )
    }

    private func saveDeviceSyncPrefs() {
        defaults.set(deviceSyncState.syncCode, forKey: "dsync_code")
        defaults.set(deviceSyncState.autoDownload, forKey: "dsync_auto_dl")
        defaults.set(deviceSyncState.partnerLabel, forKey: "dsync_partner")
        defaults.set(Int(syncPairedAtSec), forKey: "dsync_paired_at")
        defaults.set(syncInitialEnabled, forKey: "dsync_initial_sync")
        defaults.set(syncPublishedFullChunks, forKey: "dsync_full_chunks")
        if let data = try? JSONEncoder().encode(syncOutLog) {
            defaults.set(data, forKey: "dsync_out_log")
        }
        defaults.set(syncSeenYtIds, forKey: "dsync_seen_ids")
    }
}
