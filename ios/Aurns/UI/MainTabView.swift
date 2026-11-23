import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct MainTabView: View {
    @EnvironmentObject var app: AppCoordinator

    var body: some View {
        MainTabShell(
            app: app,
            player: app.player,
            library: app.library,
            downloads: app.downloads,
            friends: app.friends
        )
    }
}

private struct MainTabShell: View {
    @ObservedObject var app: AppCoordinator
    @ObservedObject var player: AudioPlayerEngine
    @ObservedObject var library: LibraryManager
    @ObservedObject var downloads: AudioDownloadManager
    @ObservedObject var friends: FriendsNetworkManager
    @Environment(\.aurnsPalette) var pal

    var body: some View {
        ZStack(alignment: .top) {
            pal.bgShell.ignoresSafeArea()

            VStack(spacing: 0) {
                if friends.jamState.isActive || friends.jamState.isInviting {
                    liveJamBanner
                }
                if friends.jamState.hasIncomingInvite {
                    incomingJamInviteBanner
                }

                Group {
                    switch app.selectedTab {
                    case .library:
                        LibraryView(app: app, library: library, player: player)
                    case .search:
                        SearchView(app: app, library: library, downloads: downloads, player: player)
                    case .friends:
                        FriendsView(app: app, friends: friends)
                    case .settings:
                        SettingsView(app: app, library: library, friends: friends)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                if downloads.progress.isRunning || downloads.progress.error != nil || downloads.progress.isDone {
                    downloadBanner
                }

                if let track = player.currentTrack {
                    miniPlayerDock(track)
                }

                bottomTabBar
            }

            if let toast = app.toastMessage {
                Text(toast)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(pal.greenAccent.opacity(0.96)))
                    .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
                    .padding(.top, 12)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(99)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: app.toastMessage)
        .fullScreenCover(isPresented: $app.showNowPlaying) {
            NowPlayingSheet(app: app, player: player, friends: friends)
                .environment(\.aurnsPalette, pal)
        }
        .alert(
            "Mevcut Liste Eşitlensin mi?",
            isPresented: Binding(
                get: { app.pendingInitialSyncPromptCode != nil },
                set: { if !$0 { app.pendingInitialSyncPromptCode = nil } }
            )
        ) {
            Button("Evet, Eşitle") {
                app.confirmInitialLibrarySync()
            }
            Button("Hayır, Sadece Yeni İnenler", role: .cancel) {
                app.pendingInitialSyncPromptCode = nil
                app.showToast("Bağlandı • Bundan sonra inen şarkılar otomatik eşitlenecek")
            }
        } message: {
            Text("Cihazlar '\(app.pendingInitialSyncPromptCode ?? "")' koduyla bağlandı. Şu anda kitaplıklardaki mevcut şarkılar da iki cihaz arasında eşitlensin mi?")
        }
    }

    // MARK: - Canlı Jam Üst Çubuğu

    private var liveJamBanner: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(pal.greenHover)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(friends.jamState.formatHeadline())
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(pal.textPri)
                    .lineLimit(1)
                Text(friends.jamState.syncStatus)
                    .font(.system(size: 11))
                    .foregroundColor(pal.greenText)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if !friends.jamState.roomDisplayCode.isEmpty {
                Button {
                    UIPasteboard.general.string = friends.jamState.roomDisplayCode
                    app.showToast("Jam kodu kopyalandı: \(friends.jamState.roomDisplayCode)")
                } label: {
                    Text(friends.jamState.roomDisplayCode)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(RoundedRectangle(cornerRadius: 6).fill(pal.greenAccent))
                }
            }
            Button {
                friends.leaveJamRoom()
            } label: {
                Text("Ayrıl")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(pal.danger)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 6).fill(pal.dangerBg))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(pal.greenSoftCard)
        .overlay(alignment: .bottom) { pal.divider.frame(height: 1) }
    }

    private var incomingJamInviteBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "waveform.circle.fill")
                .font(.system(size: 20))
                .foregroundColor(pal.greenText)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(friends.jamState.pendingInviteFromName ?? "Arkadaş") Jam daveti gönderdi")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(pal.textPri)
                    .lineLimit(1)
                Text("Birlikte canlı dinlemeye katıl")
                    .font(.system(size: 11))
                    .foregroundColor(pal.textDim)
            }
            Spacer()
            Button("Kabul Et") { friends.acceptJamInvite() }
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule().fill(pal.greenAccent))
            Button("Reddet") { friends.rejectJamInvite() }
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(pal.danger)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(Capsule().fill(pal.dangerBg))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(pal.bgCard)
        .overlay(alignment: .bottom) { pal.divider.frame(height: 1) }
    }

    // MARK: - İndirme İlerleme Çubuğu

    private var downloadBanner: some View {
        let p = downloads.progress
        return VStack(spacing: 5) {
            HStack(spacing: 8) {
                if p.isRunning {
                    ProgressView()
                        .scaleEffect(0.75)
                        .tint(pal.greenText)
                } else if p.error != nil {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(pal.danger)
                        .font(.system(size: 13))
                } else {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(pal.greenText)
                        .font(.system(size: 13))
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(p.error ?? p.title)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(p.error != nil ? pal.danger : pal.textPri)
                        .lineLimit(1)
                    Text(p.queueCount > 0 ? "\(p.headline) • +\(p.queueCount) sırada" : p.headline)
                        .font(.system(size: 10))
                        .foregroundColor(pal.textDim)
                        .lineLimit(1)
                }
                Spacer()
                if p.isRunning {
                    Text("%\(p.percent)")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundColor(pal.greenText)
                    Button {
                        downloads.cancelAll()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(pal.textMuted)
                            .font(.system(size: 16))
                    }
                }
            }
            if p.isRunning {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(pal.barTrack).frame(height: 3)
                        Capsule()
                            .fill(pal.greenHover)
                            .frame(width: geo.size.width * CGFloat(max(0, min(100, p.percent))) / 100.0, height: 3)
                    }
                }
                .frame(height: 3)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(pal.bgCard)
        .overlay(alignment: .top) { pal.divider.frame(height: 1) }
    }

    // MARK: - Mini Oynatıcı

    private func miniPlayerDock(_ track: Track) -> some View {
        let split = MusicLibraryCore.splitDisplaySongAndArtist(title: track.title, artist: track.artist)
        let frac: Double = player.durationMs > 0 ? min(1.0, max(0.0, Double(player.positionMs) / Double(player.durationMs))) : 0

        return VStack(spacing: 0) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(pal.barTrack)
                    Rectangle().fill(pal.greenHover).frame(width: geo.size.width * CGFloat(frac))
                }
            }
            .frame(height: 2)

            HStack(spacing: 12) {
                Group {
                    if let art = player.currentArtwork {
                        Image(uiImage: art).resizable().scaledToFill()
                    } else {
                        Image("AppLogo").resizable().scaledToFill()
                    }
                }
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 8))

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(split.song)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(pal.textPri)
                            .lineLimit(1)
                        if track.isOnlineStream {
                            Text("AKIŞ")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(pal.greenText)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(Capsule().fill(pal.greenSoftPill))
                        }
                    }
                    Text(split.artist)
                        .font(.system(size: 12))
                        .foregroundColor(pal.textDim)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)

                Button {
                    app.togglePlayPause()
                } label: {
                    if player.isBuffering {
                        ProgressView().tint(pal.textPri).frame(width: 36, height: 36)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(pal.textPri)
                            .frame(width: 36, height: 36)
                    }
                }

                Button {
                    app.playNext(userInitiated: true)
                } label: {
                    Image(systemName: "forward.end.fill")
                        .font(.system(size: 17))
                        .foregroundColor(pal.textDim)
                        .frame(width: 32, height: 36)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
            .onTapGesture { app.showNowPlaying = true }
        }
        .background(pal.miniPlayerBg)
        .overlay(alignment: .top) { pal.divider.frame(height: 1) }
    }

    // MARK: - Alt Sekme Çubuğu

    private var bottomTabBar: some View {
        let badgeCount = friends.pendingRequests.count + (friends.jamState.hasIncomingInvite ? 1 : 0)
        return HStack(spacing: 0) {
            tabButton(.library, icon: "music.note.list", title: "Kitaplık", badge: 0)
            tabButton(.search, icon: "magnifyingglass", title: "Ara", badge: 0)
            tabButton(.friends, icon: "person.2.fill", title: "Arkadaşlar", badge: badgeCount)
            tabButton(.settings, icon: "gearshape.fill", title: "Ayarlar", badge: 0)
        }
        .padding(.top, 8)
        .padding(.bottom, 4)
        .background(pal.bgSurface.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { pal.divider.frame(height: 1) }
    }

    private func tabButton(_ tab: AppTab, icon: String, title: String, badge: Int) -> some View {
        let active = app.selectedTab == tab
        return Button {
            app.selectedTab = tab
        } label: {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: icon)
                        .font(.system(size: 19, weight: active ? .bold : .regular))
                    if badge > 0 {
                        Text("\(min(badge, 9))")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 15, height: 15)
                            .background(Circle().fill(pal.danger))
                            .offset(x: 9, y: -5)
                    }
                }
                Text(title)
                    .font(.system(size: 10, weight: active ? .bold : .medium))
            }
            .foregroundColor(active ? pal.greenText : pal.textMuted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
        }
    }
}

// MARK: - Ortak Bileşenler (Ekolayzır + UIKit Paylaşım / Dosya Seçici)

struct EqualizerBarsView: View {
    let isPlaying: Bool
    let color: Color
    @State private var animate = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 2.5) {
            bar(minH: 4, maxH: 14, delay: 0.0)
            bar(minH: 6, maxH: 16, delay: 0.16)
            bar(minH: 3, maxH: 12, delay: 0.32)
        }
        .frame(width: 14, height: 16, alignment: .bottom)
        .onAppear { animate = isPlaying }
        .onChange(of: isPlaying) { val in animate = val }
    }

    private func bar(minH: CGFloat, maxH: CGFloat, delay: Double) -> some View {
        RoundedRectangle(cornerRadius: 1.5)
            .fill(color)
            .frame(width: 3, height: animate ? maxH : minH)
            .animation(
                animate
                    ? .easeInOut(duration: 0.42).repeatForever(autoreverses: true).delay(delay)
                    : .easeOut(duration: 0.2),
                value: animate
            )
    }
}

struct DocumentPickerSheet: UIViewControllerRepresentable {
    let contentTypes: [UTType]
    let allowMultiple: Bool
    let onPick: ([URL]) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: contentTypes, asCopy: false)
        picker.allowsMultipleSelection = allowMultiple
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: ([URL]) -> Void
        init(onPick: @escaping ([URL]) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onPick(urls)
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

func formatTimeMMSS(_ ms: Int64) -> String {
    let totalSec = max(0, Int(ms / 1000))
    let m = totalSec / 60
    let s = totalSec % 60
    return String(format: "%d:%02d", m, s)
}
