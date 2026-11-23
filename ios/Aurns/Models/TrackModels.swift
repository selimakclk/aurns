import Foundation

struct Track: Identifiable, Hashable, Codable {
    let id: String         // Dosya göreli anahtarı veya "stream:<ytId>"
    let title: String
    let artist: String
    let fileURL: URL?
    let streamURL: URL?
    let streamUserAgent: String?
    let durationSec: Int
    let ytId: String?
    let thumbnailURL: String?

    var isOnlineStream: Bool { fileURL == nil && streamURL != nil }
    var storageKey: String { fileURL?.lastPathComponent ?? id }
}

struct UserPlaylist: Identifiable, Hashable, Codable {
    let id: String
    var name: String
    var trackKeys: [String]
}

struct SearchResult: Identifiable, Hashable {
    var id: String { videoId }
    let videoId: String
    let title: String
    let artist: String
    let durationText: String
    let thumbnailURL: String
}

struct StreamInfo: Hashable {
    let url: String
    let mimeType: String
    let fileExt: String
    let bitrate: Int
    let contentLength: Int64
    let userAgent: String
}

struct DownloadProgress: Equatable {
    var title: String = ""
    var percent: Int = 0
    var isRunning: Bool = false
    var isDone: Bool = false
    var error: String? = nil
    var headline: String = ""
    var activeVideoId: String = ""
    var queuedVideoIds: Set<String> = []
    var queueCount: Int = 0
}

struct FriendItem: Identifiable, Hashable, Codable {
    var id: String { tag }
    let tag: String
    var name: String
    var isOnline: Bool = false
    var isPlaying: Bool = false
    var currentTrack: String = "Çevrimdışı"
    var lastSeenAt: TimeInterval = 0
}

struct FriendRequest: Identifiable, Hashable, Codable {
    var id: String { tag }
    let tag: String
    let name: String
}

struct JamMember: Identifiable, Hashable {
    var id: String { tag }
    let tag: String
    let name: String
}

struct JamRoomState: Equatable {
    var isActive: Bool = false
    var isInviting: Bool = false
    var isHost: Bool = false
    var jamId: String = ""
    var members: [JamMember] = []
    var outgoingInviteCount: Int = 0
    var lastInvitedTag: String = ""
    var lastInvitedName: String = ""
    var pendingInviteFromTag: String? = nil
    var pendingInviteFromName: String? = nil
    var pendingInviteJamId: String? = nil
    var syncStatus: String = "Canlı milisaniye senkronizasyonu aktif"
    var isDownloadingMissing: Bool = false
    var missingTrackTitle: String = ""
    var currentTrackTitle: String = ""
    var currentYtId: String = ""
    var isPlaying: Bool = false
    var positionMs: Int64 = 0

    var hasIncomingInvite: Bool {
        guard let pid = pendingInviteJamId, !pid.isEmpty else { return false }
        return true
    }
    var totalParticipantCount: Int { (isActive || isInviting) ? members.count + 1 : 0 }
    var roomDisplayCode: String { FriendsProtocol.formatRoomDisplayCode(jamId) }

    func isFriendInRoom(_ friendTag: String) -> Bool {
        guard isActive || isInviting else { return false }
        if members.contains(where: { FriendsProtocol.sameUserTag($0.tag, friendTag) }) { return true }
        if isInviting && !lastInvitedTag.isEmpty && FriendsProtocol.sameUserTag(lastInvitedTag, friendTag) { return true }
        return false
    }

    func formatHeadline() -> String {
        let codeTag = roomDisplayCode.isEmpty ? "" : " [\(roomDisplayCode)]"
        if isInviting && !isActive {
            if outgoingInviteCount > 1 {
                return "JAM\(codeTag) KURULUYOR • \(outgoingInviteCount) kişiye davet gönderildi"
            }
            let first = lastInvitedName.isEmpty ? (members.first?.name ?? "Arkadaş") : lastInvitedName
            return "JAM\(codeTag) DAVETİ • \(first) bekleniyor..."
        }
        if !isActive { return "" }
        if members.isEmpty {
            return "CANLI JAM\(codeTag) • Oda koduyla katılım bekleniyor"
        }
        let preview = members.prefix(3).map { $0.name }.joined(separator: ", ")
        let extra = members.count > 3 ? " +\(members.count - 3)" : ""
        return "CANLI JAM\(codeTag) (\(totalParticipantCount) Kişi) • Sen, \(preview)\(extra)"
    }
}

struct DeviceSyncState: Equatable {
    var syncCode: String = ""
    var autoDownload: Bool = true
    var initialSyncEnabled: Bool = false
    var partnerLabel: String = ""

    var isPaired: Bool { !syncCode.isEmpty }
}

struct TrackMatchInput: Hashable {
    let title: String
    let artist: String
    let ytId: String?
    let path: String
}

struct M3uTrackEntry: Hashable {
    let title: String
    let pathOrUri: String
    let ytId: String?
}

struct ParsedM3uEntry: Hashable {
    let title: String
    let ytId: String
}

struct M3uImportResult: Equatable {
    let playlistName: String
    let matchedCount: Int
    let matchedIndices: [Int]
    let missingTracks: [ParsedM3uEntry]
}

struct RemoteUpdateMetadata: Equatable {
    let version: String
    let changelog: String
    let hasUpdate: Bool
    let downloadURL: String
}

enum RepeatMode: Int, CaseIterable {
    case off = 0
    case all = 1
    case one = 2

    func next() -> RepeatMode {
        RepeatMode(rawValue: (rawValue + 1) % 3) ?? .off
    }
}
