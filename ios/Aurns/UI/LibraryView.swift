import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @ObservedObject var app: AppCoordinator
    @ObservedObject var library: LibraryManager
    @ObservedObject var player: AudioPlayerEngine
    @Environment(\.aurnsPalette) var pal

    @State private var isMultiSelect = false
    @State private var selectedKeys = Set<String>()
    @State private var isReordering = false
    @State private var showAudioImportPicker = false
    @State private var showCreatePlaylistAlert = false
    @State private var newPlaylistName = ""
    @State private var renamingPlaylist: UserPlaylist? = nil
    @State private var renameText = ""
    @State private var addToPlaylistTargetKeys: [String] = []
    @State private var showAddToPlaylistSheet = false
    @State private var shareFileURL: URL? = nil
    @State private var trackPendingDelete: Track? = nil

    var body: some View {
        VStack(spacing: 0) {
            heroHeader
            playlistChipsBar

            if isMultiSelect {
                multiSelectActionBar
            }

            if library.displayedTracks.isEmpty {
                emptyState
            } else {
                List {
                    ForEach(library.displayedTracks) { track in
                        trackRow(track)
                            .listRowBackground(
                                (player.currentTrack?.id == track.id ? pal.activeRowBg : pal.bgShell)
                            )
                            .listRowInsets(EdgeInsets(top: 4, leading: 14, bottom: 4, trailing: 14))
                            .listRowSeparatorTint(pal.divider)
                    }
                    .onMove { src, dst in
                        library.moveTracks(from: src, to: dst)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(pal.bgShell)
                .environment(\.editMode, .constant(isReordering ? .active : .inactive))
                .refreshable {
                    await library.scanLibrary()
                }
            }
        }
        .background(pal.bgShell)
        .sheet(isPresented: $showAudioImportPicker) {
            DocumentPickerSheet(
                contentTypes: [.audio, .folder],
                allowMultiple: true
            ) { urls in
                Task {
                    let count = await library.importAudioURLs(urls)
                    app.showToast(count > 0 ? "\(count) müzik dosyası eklendi" : "Uygun ses dosyası bulunamadı")
                }
            }
        }
        .sheet(isPresented: $showAddToPlaylistSheet) {
            addToPlaylistSheet
        }
        .sheet(isPresented: Binding(
            get: { shareFileURL != nil },
            set: { if !$0 { shareFileURL = nil } }
        )) {
            if let url = shareFileURL {
                ShareSheet(items: [url])
            }
        }
        .alert("Yeni Çalma Listesi", isPresented: $showCreatePlaylistAlert) {
            TextField("Liste adı (Örn: Yolculuk)", text: $newPlaylistName)
            Button("Oluştur") {
                if let created = library.createPlaylist(name: newPlaylistName) {
                    library.selectedPlaylistId = created.id
                    app.showToast("'\(created.name)' oluşturuldu")
                }
                newPlaylistName = ""
            }
            Button("İptal", role: .cancel) { newPlaylistName = "" }
        }
        .alert(
            "Listeyi Yeniden Adlandır",
            isPresented: Binding(
                get: { renamingPlaylist != nil },
                set: { if !$0 { renamingPlaylist = nil } }
            )
        ) {
            TextField("Yeni ad", text: $renameText)
            Button("Kaydet") {
                if let pl = renamingPlaylist {
                    library.renamePlaylist(id: pl.id, newName: renameText)
                }
                renamingPlaylist = nil
            }
            Button("İptal", role: .cancel) { renamingPlaylist = nil }
        }
        .alert(
            "Dosyayı Cihazdan Sil",
            isPresented: Binding(
                get: { trackPendingDelete != nil },
                set: { if !$0 { trackPendingDelete = nil } }
            )
        ) {
            Button("Sil", role: .destructive) {
                if let t = trackPendingDelete {
                    if player.currentTrack?.id == t.id { player.stop() }
                    library.deleteTrackFromDevice(t)
                    app.showToast("Cihazdan silindi")
                }
                trackPendingDelete = nil
            }
            Button("Vazgeç", role: .cancel) { trackPendingDelete = nil }
        } message: {
            Text("'\(trackPendingDelete?.title ?? "")' iPhone depolama alanından kalıcı olarak silinsin mi?")
        }
    }

    // MARK: - Üst Hero Başlık

    private var heroHeader: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image("AppLogo")
                    .resizable()
                    .scaledToFill()
                    .frame(width: 42, height: 42)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .shadow(color: pal.greenHover.opacity(0.4), radius: 8)

                VStack(alignment: .leading, spacing: 2) {
                    Text(library.activePlaylist?.name ?? "Aurns")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(pal.textPri)
                        .lineLimit(1)
                    Text("\(library.displayedTracks.count) şarkı")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(pal.textDim)
                }
                Spacer()

                headerIconButton(
                    systemName: isReordering ? "checkmark" : "arrow.up.arrow.down",
                    active: isReordering
                ) {
                    isMultiSelect = false
                    selectedKeys.removeAll()
                    isReordering.toggle()
                }

                headerIconButton(
                    systemName: isMultiSelect ? "checklist.checked" : "checklist",
                    active: isMultiSelect
                ) {
                    isReordering = false
                    isMultiSelect.toggle()
                    if !isMultiSelect { selectedKeys.removeAll() }
                }

                headerIconButton(systemName: "folder.badge.plus", active: false) {
                    showAudioImportPicker = true
                }
            }

            HStack(spacing: 10) {
                Button {
                    app.playAllOrShuffle(shuffle: false)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "play.fill").font(.system(size: 13))
                        Text("Çal").font(.system(size: 14, weight: .bold))
                    }
                    .foregroundColor(pal.playFg)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(pal.playBg))
                }

                Button {
                    app.playAllOrShuffle(shuffle: true)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "shuffle").font(.system(size: 13))
                        Text("Karıştır").font(.system(size: 14, weight: .bold))
                    }
                    .foregroundColor(pal.textPri)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(pal.bgCard))
                    .overlay(Capsule().stroke(pal.border, lineWidth: 1))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .background(
            LinearGradient(
                colors: [pal.heroTop, pal.bgShell],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private func headerIconButton(systemName: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(active ? .white : pal.textPri)
                .frame(width: 34, height: 34)
                .background(Circle().fill(active ? pal.greenAccent : pal.bgCard.opacity(0.9)))
        }
    }

    // MARK: - Sınırsız Çalma Listesi Çipleri

    private var playlistChipsBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                playlistChip(title: "Tüm Şarkılar (\(library.allTracks.count))", isSelected: library.selectedPlaylistId == nil) {
                    library.selectedPlaylistId = nil
                }

                ForEach(library.userPlaylists) { pl in
                    playlistChip(title: "\(pl.name) (\(pl.trackKeys.count))", isSelected: library.selectedPlaylistId == pl.id) {
                        library.selectedPlaylistId = pl.id
                    }
                    .contextMenu {
                        Button("Yeniden Adlandır") {
                            renamingPlaylist = pl
                            renameText = pl.name
                        }
                        Button("Listeyi Dışa Aktar (.m3u8)") {
                            if let url = library.exportPlaylistToTempFile(playlist: pl) {
                                shareFileURL = url
                            } else {
                                app.showToast("Bu listede dışa aktarılacak şarkı yok")
                            }
                        }
                        Button("Listeyi Sil", role: .destructive) {
                            library.deletePlaylist(id: pl.id)
                            app.showToast("Liste silindi")
                        }
                    }
                }

                Button {
                    newPlaylistName = ""
                    showCreatePlaylistAlert = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus").font(.system(size: 11, weight: .bold))
                        Text("Yeni Liste").font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundColor(pal.greenText)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Capsule().fill(pal.greenSoftPill))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
    }

    private func playlistChip(title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: isSelected ? .bold : .medium))
                .foregroundColor(isSelected ? pal.selFg : pal.textDim)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Capsule().fill(isSelected ? pal.selBg : pal.bgCard))
                .overlay(Capsule().stroke(isSelected ? Color.clear : pal.borderSubtle, lineWidth: 1))
        }
    }

    // MARK: - Çoklu Seçim Çubuğu (Listeye Ekle + Kaldır + Vazgeç)

    private var multiSelectActionBar: some View {
        HStack(spacing: 8) {
            Text("\(selectedKeys.count) seçili")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(pal.textPri)
            Spacer()

            Button {
                guard !selectedKeys.isEmpty else { return }
                addToPlaylistTargetKeys = Array(selectedKeys)
                showAddToPlaylistSheet = true
            } label: {
                Text("Listeye Ekle")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(pal.greenAccent))
            }
            .disabled(selectedKeys.isEmpty)

            Button {
                guard !selectedKeys.isEmpty else { return }
                let count = selectedKeys.count
                library.removeTracksFromCurrentView(keys: selectedKeys)
                selectedKeys.removeAll()
                isMultiSelect = false
                app.showToast("\(count) şarkı kaldırıldı")
            } label: {
                Text("Kaldır")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(pal.danger)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(pal.dangerBg))
            }
            .disabled(selectedKeys.isEmpty)

            Button("Vazgeç") {
                isMultiSelect = false
                selectedKeys.removeAll()
            }
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(pal.textDim)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(pal.bgCard)
        .overlay(alignment: .bottom) { pal.divider.frame(height: 1) }
    }

    // MARK: - Şarkı Satırı

    private func trackRow(_ track: Track) -> some View {
        let isCurrent = player.currentTrack?.id == track.id
        let isChecked = selectedKeys.contains(track.storageKey)
        let split = MusicLibraryCore.splitDisplaySongAndArtist(title: track.title, artist: track.artist)

        return HStack(spacing: 12) {
            if isMultiSelect {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundColor(isChecked ? pal.greenHover : pal.textMuted)
            }

            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(isCurrent ? pal.greenSoftPill : pal.bgCard)
                    .frame(width: 44, height: 44)
                if let art = player.cachedArtwork(for: track.storageKey) {
                    Image(uiImage: art)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 44, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    Image(systemName: isCurrent ? "waveform" : "music.note")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(isCurrent ? pal.greenText : pal.textMuted)
                }
            }

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(split.song)
                        .font(.system(size: 14, weight: isCurrent ? .bold : .medium))
                        .foregroundColor(isCurrent ? pal.greenText : pal.textPri)
                        .lineLimit(1)
                    if track.isOnlinePlaceholder || track.isOnlineStream {
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

            Spacer(minLength: 6)

            if isCurrent {
                EqualizerBarsView(isPlaying: player.isPlaying, color: pal.greenHover)
            } else if track.durationSec > 0 {
                Text(formatTimeMMSS(Int64(track.durationSec) * 1000))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(pal.textMuted)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture {
            if isMultiSelect {
                if isChecked { selectedKeys.remove(track.storageKey) }
                else { selectedKeys.insert(track.storageKey) }
            } else {
                app.playTrack(track)
            }
        }
        .contextMenu {
            Button {
                app.playTrack(track)
            } label: { Label("Şimdi Çal", systemImage: "play.fill") }

            Button {
                addToPlaylistTargetKeys = [track.storageKey]
                showAddToPlaylistSheet = true
            } label: { Label("Çalma Listesine Ekle", systemImage: "text.badge.plus") }

            if let pid = library.selectedPlaylistId {
                Button {
                    library.removeTrackFromPlaylist(playlistId: pid, key: track.storageKey)
                    app.showToast("Listeden çıkarıldı")
                } label: { Label("Bu Listeden Çıkar", systemImage: "minus.circle") }
            }

            Button {
                isMultiSelect = true
                selectedKeys.insert(track.storageKey)
            } label: { Label("Birden Fazla Seç", systemImage: "checklist") }

            if let fileURL = track.fileURL {
                Button {
                    shareFileURL = fileURL
                } label: { Label("Dosyayı Paylaş", systemImage: "square.and.arrow.up") }
            }

            Button(role: .destructive) {
                library.removeTracksFromCurrentView(keys: [track.storageKey])
                app.showToast("Kitaplıktan kaldırıldı")
            } label: { Label("Kitaplıktan Kaldır", systemImage: "eye.slash") }

            Button(role: .destructive) {
                trackPendingDelete = track
            } label: { Label("Cihazdan Kalıcı Sil", systemImage: "trash") }
        }
    }

    // MARK: - Boş Durum & Çalma Listesine Ekle Sheet

    private var emptyState: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "music.note.house")
                .font(.system(size: 46))
                .foregroundColor(pal.textMuted)
            Text(library.selectedPlaylistId == nil ? "Kitaplığın şimdilik boş" : "Bu çalma listesi boş")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(pal.textPri)
            Text("Ara sekmesinden YouTube şarkılarını 8-kanallı hızda indirebilir veya Dosyalar uygulamasından müzik aktarabilirsin.")
                .font(.system(size: 13))
                .foregroundColor(pal.textDim)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            HStack(spacing: 10) {
                Button("YouTube'da Ara") { app.selectedTab = .search }
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(pal.greenAccent))

                Button("Dosyalar'dan Al") { showAudioImportPicker = true }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(pal.textPri)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(pal.bgCard))
                    .overlay(Capsule().stroke(pal.border, lineWidth: 1))
            }
            Spacer()
        }
    }

    private var addToPlaylistSheet: some View {
        NavigationStack {
            List {
                if library.userPlaylists.isEmpty {
                    Text("Henüz özel çalma listen yok. Önce + Yeni Liste ile oluştur.")
                        .foregroundColor(pal.textDim)
                } else {
                    ForEach(library.userPlaylists) { pl in
                        Button {
                            library.addTracksToPlaylist(playlistId: pl.id, keys: addToPlaylistTargetKeys)
                            showAddToPlaylistSheet = false
                            isMultiSelect = false
                            selectedKeys.removeAll()
                            app.showToast("'\(pl.name)' listesine eklendi")
                        } label: {
                            HStack {
                                Image(systemName: "music.note.list").foregroundColor(pal.greenText)
                                Text(pl.name).foregroundColor(pal.textPri)
                                Spacer()
                                Text("\(pl.trackKeys.count) şarkı").font(.caption).foregroundColor(pal.textDim)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Listeye Ekle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Kapat") { showAddToPlaylistSheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("+ Yeni") {
                        showAddToPlaylistSheet = false
                        showCreatePlaylistAlert = true
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
