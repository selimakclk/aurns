import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct SettingsView: View {
    @ObservedObject var app: AppCoordinator
    @ObservedObject var library: LibraryManager
    @ObservedObject var friends: FriendsNetworkManager
    @Environment(\.aurnsPalette) var pal

    @State private var syncCodeInput = ""
    @State private var showExportPlaylistPicker = false
    @State private var exportShareURL: URL? = nil
    @State private var showM3uImportPicker = false
    @State private var showAudioImportPicker = false
    @State private var showClearConfirm = false
    @State private var updateStatus = ""
    @State private var isCheckingUpdate = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Ayarlar")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(pal.textPri)

                themeCard
                deviceSyncCard
                playlistToolsCard
                storageAndUpdateCard
            }
            .padding(14)
        }
        .background(pal.bgShell)
        .sheet(isPresented: $showExportPlaylistPicker) {
            exportPlaylistPickerSheet
        }
        .sheet(isPresented: Binding(
            get: { exportShareURL != nil },
            set: { if !$0 { exportShareURL = nil } }
        )) {
            if let url = exportShareURL { ShareSheet(items: [url]) }
        }
        .sheet(isPresented: $showM3uImportPicker) {
            DocumentPickerSheet(contentTypes: m3uContentTypes, allowMultiple: false) { urls in
                if let first = urls.first { app.importM3u8Playlist(from: first) }
            }
        }
        .sheet(isPresented: $showAudioImportPicker) {
            DocumentPickerSheet(contentTypes: [.audio, .folder], allowMultiple: true) { urls in
                Task {
                    let count = await library.importAudioURLs(urls)
                    app.showToast(count > 0 ? "\(count) müzik dosyası eklendi" : "Uygun ses dosyası bulunamadı")
                }
            }
        }
        .alert("Kitaplık Listesini Temizle", isPresented: $showClearConfirm) {
            Button("Temizle", role: .destructive) {
                library.clearLibraryHideAll()
                app.showToast("Kitaplık temizlendi (dosyalar silinmedi)")
            }
            Button("Vazgeç", role: .cancel) {}
        } message: {
            Text("Şarkılar listeden gizlenir, iPhone depolamasındaki ses dosyaları korunur.")
        }
    }

    private var m3uContentTypes: [UTType] {
        [
            UTType.m3uPlaylist,
            UTType(filenameExtension: "m3u8"),
            UTType(filenameExtension: "m3u"),
            UTType(filenameExtension: "aurns"),
            UTType.plainText,
            UTType.item
        ].compactMap { $0 }
    }

    // MARK: - Tema Kartı

    private var themeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Görünüm", systemImage: "paintbrush.fill")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(pal.textPri)

            Toggle(isOn: $app.isLightTheme) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(app.isLightTheme ? "Açık Tema" : "Koyu Tema")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(pal.textPri)
                    Text("PC ve Android Aurns renk paletiyle birebir")
                        .font(.system(size: 11))
                        .foregroundColor(pal.textDim)
                }
            }
            .tint(pal.greenHover)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(pal.bgCard))
    }

    // MARK: - PC ↔ Android ↔ iPhone Kalıcı Senkronizasyon

    private var deviceSyncCard: some View {
        let sync = friends.deviceSyncState
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Cihaz Senkronizasyonu (PC ↔ iPhone)", systemImage: "arrow.triangle.2.circlepath.circle.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(pal.textPri)
                Spacer()
                if sync.isPaired {
                    Text("BAĞLI")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(pal.greenText)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(pal.greenSoftPill))
                }
            }

            Text("Bir cihazda indirdiğin şarkı diğerine (çevrimdışı kalsa bile internet gelince) otomatik iner.")
                .font(.system(size: 12))
                .foregroundColor(pal.textDim)

            if sync.isPaired {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("EŞLEŞTİRME KODU")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(pal.textMuted)
                        Text(sync.syncCode)
                            .font(.system(size: 18, weight: .bold, design: .monospaced))
                            .foregroundColor(pal.greenText)
                        if !sync.partnerLabel.isEmpty {
                            Text("Bağlı cihaz: \(sync.partnerLabel)")
                                .font(.system(size: 11))
                                .foregroundColor(pal.textDim)
                        }
                    }
                    Spacer()
                    Button {
                        UIPasteboard.general.string = sync.syncCode
                        app.showToast("Kod kopyalandı: \(sync.syncCode)")
                    } label: {
                        Label("Kopyala", systemImage: "doc.on.doc")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(Capsule().fill(pal.greenAccent))
                    }
                }

                Toggle(
                    "Diğer cihazdan inenleri otomatik indir",
                    isOn: Binding(
                        get: { sync.autoDownload },
                        set: { friends.setDeviceSyncAutoDownload($0) }
                    )
                )
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(pal.textPri)
                .tint(pal.greenHover)

                HStack(spacing: 8) {
                    Button {
                        let count = friends.enableInitialLibrarySync(tracks: library.allTracks)
                        app.showToast("Mevcut liste (\(count) şarkı) eşitleniyor")
                    } label: {
                        Text("Mevcut Listeyi Eşitle")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(pal.greenText)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 10).fill(pal.greenSoftPill))
                    }

                    Button {
                        friends.unpairDeviceSync()
                        app.showToast("Cihaz bağlantısı kesildi")
                    } label: {
                        Text("Bağlantıyı Kes")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(pal.danger)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 10).fill(pal.dangerBg))
                    }
                }
            } else {
                Button {
                    let code = app.generateDeviceSyncWithPrompt()
                    UIPasteboard.general.string = code
                    app.showToast("Kod oluşturuldu ve kopyalandı: \(code)")
                } label: {
                    Label("Yeni Bağlantı Kodu Oluştur", systemImage: "qrcode")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(pal.greenAccent))
                }

                HStack(spacing: 8) {
                    TextField("PC / Android kodu gir...", text: $syncCodeInput)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundColor(pal.textPri)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(RoundedRectangle(cornerRadius: 10).fill(pal.bgInput))

                    Button("Bağla") {
                        if app.pairDeviceSyncWithPrompt(rawCode: syncCodeInput) {
                            syncCodeInput = ""
                        } else {
                            app.showToast("En az 6 karakterli geçerli kod girin")
                        }
                    }
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(pal.textPri)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 9)
                    .background(RoundedRectangle(cornerRadius: 10).fill(pal.bgElevated))
                }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(pal.greenSoftCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.greenAccent.opacity(0.35), lineWidth: 1))
    }

    // MARK: - Çalma Listesi & İçe/Dışa Aktarma

    private var playlistToolsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Çalma Listesi ve Dosyalar", systemImage: "square.and.arrow.up.on.square")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(pal.textPri)

            actionRow(icon: "square.and.arrow.up", title: "Çalma Listesini Dışa Aktar (.m3u8)", subtitle: "Hangi listeyi aktaracağını seç, adıyla paylaş") {
                showExportPlaylistPicker = true
            }

            actionRow(icon: "square.and.arrow.down", title: "Çalma Listesini İçe Aktar (.m3u8)", subtitle: "Kendi adıyla yeni liste oluşturur ve eksikleri indirir") {
                showM3uImportPicker = true
            }

            actionRow(icon: "folder.badge.plus", title: "Dosyalardan Müzik Ekle", subtitle: "iPhone Dosyalar uygulamasından MP3/M4A seç") {
                showAudioImportPicker = true
            }

            actionRow(icon: "arrow.counterclockwise", title: "Gizlenen Parçaları Geri Getir", subtitle: "Kitaplıktan gizlediklerini tekrar göster") {
                Task {
                    await library.restoreHiddenTracks()
                    app.showToast("Kitaplık yenilendi")
                }
            }

            actionRow(icon: "trash", title: "Kitaplık Listesini Temizle", subtitle: "Dosyalar silinmez, yalnızca listeden gizlenir", destructive: true) {
                showClearConfirm = true
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(pal.bgCard))
    }

    private func actionRow(icon: String, title: String, subtitle: String, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(destructive ? pal.danger : pal.greenText)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(destructive ? pal.danger : pal.textPri)
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundColor(pal.textDim)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundColor(pal.textMuted)
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: - Depolama & Sürüm Bilgisi

    private var storageAndUpdateCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Depolama ve Sürüm", systemImage: "info.circle.fill")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(pal.textPri)

            Text("İndirilen tüm müzikler iPhone'unda doğrudan 'Dosyalar → Bu iPhone'umda → Aurns' klasöründe saklanır.")
                .font(.system(size: 12))
                .foregroundColor(pal.textDim)

            HStack {
                Text("Aurns iOS v\(AppCoordinator.appVersion)")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(pal.textPri)
                Spacer()
                Button {
                    guard !isCheckingUpdate else { return }
                    isCheckingUpdate = true
                    updateStatus = "Kontrol ediliyor..."
                    Task {
                        let meta = await InnertubeResolver.shared.fetchRemoteUpdateMetadata(currentVersion: AppCoordinator.appVersion)
                        isCheckingUpdate = false
                        if let meta {
                            updateStatus = meta.hasUpdate
                                ? "Yeni sürüm hazır: v\(meta.version)"
                                : "En güncel sürümü kullanıyorsun (v\(meta.version))"
                        } else {
                            updateStatus = "Sunucuya ulaşılamadı"
                        }
                    }
                } label: {
                    Text(isCheckingUpdate ? "Bakılıyor..." : "Güncelleme Kontrol Et")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(pal.greenText)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Capsule().fill(pal.greenSoftPill))
                }
            }

            if !updateStatus.isEmpty {
                Text(updateStatus)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(pal.greenText)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(pal.bgCard))
    }

    // MARK: - Dışa Aktarılacak Çalma Listesi Seçici

    private var exportPlaylistPickerSheet: some View {
        NavigationStack {
            List {
                Button {
                    showExportPlaylistPicker = false
                    if let url = library.exportPlaylistToTempFile(playlist: nil) {
                        exportShareURL = url
                    } else {
                        app.showToast("Dışa aktarılacak şarkı yok")
                    }
                } label: {
                    HStack {
                        Image(systemName: "music.note.list").foregroundColor(pal.greenText)
                        VStack(alignment: .leading) {
                            Text("Tüm Şarkılar").foregroundColor(pal.textPri).fontWeight(.semibold)
                            Text("\(library.allTracks.count) şarkı").font(.caption).foregroundColor(pal.textDim)
                        }
                    }
                }

                ForEach(library.userPlaylists) { pl in
                    Button {
                        showExportPlaylistPicker = false
                        if let url = library.exportPlaylistToTempFile(playlist: pl) {
                            exportShareURL = url
                        } else {
                            app.showToast("'\(pl.name)' listesi boş")
                        }
                    } label: {
                        HStack {
                            Image(systemName: "list.bullet.rectangle").foregroundColor(pal.greenText)
                            VStack(alignment: .leading) {
                                Text(pl.name).foregroundColor(pal.textPri).fontWeight(.semibold)
                                Text("\(pl.trackKeys.count) şarkı").font(.caption).foregroundColor(pal.textDim)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Hangi Liste Aktarılsın?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Vazgeç") { showExportPlaylistPicker = false }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
