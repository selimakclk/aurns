import SwiftUI

struct SearchView: View {
    @ObservedObject var app: AppCoordinator
    @ObservedObject var library: LibraryManager
    @ObservedObject var downloads: AudioDownloadManager
    @ObservedObject var player: AudioPlayerEngine
    @Environment(\.aurnsPalette) var pal

    @State private var query = ""
    @State private var isSearching = false
    @State private var ytResults: [SearchResult] = []
    @State private var searchTask: Task<Void, Never>? = nil
    @State private var pendingAddToPlaylistItem: SearchResult? = nil
    @State private var showCreatePlaylistAlert = false
    @State private var newPlaylistName = ""

    private var localMatches: [Track] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        return library.allTracks.filter {
            MusicLibraryCore.matchesSearchTokens(title: $0.title, artist: $0.artist, query: q)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                Text("Ara ve İndir")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(pal.textPri)

                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(pal.textDim)
                    TextField("Şarkı, sanatçı veya YouTube linki...", text: $query)
                        .foregroundColor(pal.textPri)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .onSubmit { triggerSearch(immediate: true) }
                        .onChange(of: query) { _ in triggerSearch(immediate: false) }

                    if !query.isEmpty {
                        Button {
                            query = ""
                            ytResults = []
                            searchTask?.cancel()
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(pal.textMuted)
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 12).fill(pal.bgInput))
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 10)
            .background(pal.bgSurface)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if !localMatches.isEmpty {
                        sectionHeader("KİTAPLIĞINDAKİ PARÇALAR", badge: "YEREL", count: localMatches.count)
                        ForEach(localMatches.prefix(10)) { track in
                            localMatchRow(track)
                        }
                    }

                    if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                        sectionHeader("YOUTUBE'DA BUL VE İNDİR", badge: "YOUTUBE", count: ytResults.count)
                        if isSearching && ytResults.isEmpty {
                            HStack(spacing: 10) {
                                ProgressView().tint(pal.greenText)
                                Text("YouTube'da aranıyor...")
                                    .font(.system(size: 13))
                                    .foregroundColor(pal.textDim)
                            }
                            .padding(.vertical, 24)
                            .frame(maxWidth: .infinity)
                        } else if ytResults.isEmpty {
                            Text("Sonuç bulunamadı. Farklı kelimelerle deneyin.")
                                .font(.system(size: 13))
                                .foregroundColor(pal.textMuted)
                                .padding(.vertical, 18)
                                .frame(maxWidth: .infinity)
                        } else {
                            let inputs = library.matchInputs
                            ForEach(ytResults) { item in
                                youtubeRow(item, matchInputs: inputs)
                            }
                        }
                    } else {
                        VStack(spacing: 12) {
                            Image(systemName: "magnifyingglass.circle")
                                .font(.system(size: 46))
                                .foregroundColor(pal.textMuted)
                            Text("Şarkı ara, indirmeden direkt çal veya indir")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(pal.textDim)
                            Text("İndirilen şarkılar otomatik olarak kitaplığının en üstüne eklenir.")
                                .font(.system(size: 12))
                                .foregroundColor(pal.textMuted)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 70)
                        .padding(.horizontal, 24)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            .background(pal.bgShell)
        }
        .sheet(isPresented: Binding(
            get: { pendingAddToPlaylistItem != nil },
            set: { if !$0 { pendingAddToPlaylistItem = nil } }
        )) {
            addSearchItemToPlaylistSheet
        }
        .alert("Yeni Çalma Listesi", isPresented: $showCreatePlaylistAlert) {
            TextField("Liste adı (Örn: Favoriler)", text: $newPlaylistName)
            Button("Oluştur ve Ekle") {
                if let created = library.createPlaylist(name: newPlaylistName),
                   let item = pendingAddToPlaylistItem {
                    let key = keyForSearchResult(item)
                    if !key.isEmpty {
                        library.addTracksToPlaylist(playlistId: created.id, keys: [key])
                        app.showToast("'\(created.name)' oluşturuldu ve eklendi (indirmeden)")
                    }
                }
                newPlaylistName = ""
                pendingAddToPlaylistItem = nil
            }
            Button("İptal", role: .cancel) { newPlaylistName = "" }
        }
    }

    private func keyForSearchResult(_ item: SearchResult) -> String {
        let localIdx = MusicLibraryCore.findExactLocalTrackForYoutube(
            tracks: library.matchInputs,
            targetTitle: item.title,
            targetYtId: item.videoId
        )
        if localIdx >= 0 && localIdx < library.allTracks.count {
            return library.allTracks[localIdx].storageKey
        }
        return MusicLibraryCore.formatOnlineYtKey(videoId: item.videoId, title: item.title, artist: item.artist)
    }

    private var addSearchItemToPlaylistSheet: some View {
        NavigationStack {
            List {
                if library.userPlaylists.isEmpty {
                    Text("Henüz özel çalma listen yok. Sağ üstteki '+ Yeni' ile oluşturabilirsin.")
                        .foregroundColor(pal.textDim)
                } else {
                    ForEach(library.userPlaylists) { pl in
                        Button {
                            if let item = pendingAddToPlaylistItem {
                                let key = keyForSearchResult(item)
                                if !key.isEmpty {
                                    library.addTracksToPlaylist(playlistId: pl.id, keys: [key])
                                    app.showToast("'\(pl.name)' listesine eklendi (indirmeden)")
                                }
                            }
                            pendingAddToPlaylistItem = nil
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
            .navigationTitle("İndirmeden Listeye Ekle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Kapat") { pendingAddToPlaylistItem = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("+ Yeni") {
                        newPlaylistName = ""
                        showCreatePlaylistAlert = true
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func triggerSearch(immediate: Bool) {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else {
            ytResults = []
            isSearching = false
            return
        }
        searchTask = Task { @MainActor in
            if !immediate {
                try? await Task.sleep(nanoseconds: 240_000_000)
                if Task.isCancelled { return }
            }
            isSearching = true
            let hits = await InnertubeResolver.shared.search(query: trimmed)
            if !Task.isCancelled {
                ytResults = hits
                isSearching = false
                // İlk 3 YouTube sonucunun akışını ön-ısıt -> ▶ Çal anında (0ms bekleme!)
                for top in hits.prefix(3) {
                    await InnertubeResolver.shared.prefetchAudioStream(videoId: top.videoId)
                }
            }
        }
    }

    private func sectionHeader(_ title: String, badge: String, count: Int) -> some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(pal.textDim)
            Text(badge)
                .font(.system(size: 9, weight: .bold))
                .foregroundColor(badge == "YOUTUBE" ? .white : pal.greenText)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Capsule().fill(badge == "YOUTUBE" ? Color.red.opacity(0.8) : pal.greenSoftPill))
            Spacer()
            Text("\(count) sonuç")
                .font(.system(size: 11))
                .foregroundColor(pal.textMuted)
        }
        .padding(.top, 4)
    }

    private func localMatchRow(_ track: Track) -> some View {
        let split = MusicLibraryCore.splitDisplaySongAndArtist(title: track.title, artist: track.artist)
        return Button {
            app.playTrack(track)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "music.note")
                    .foregroundColor(pal.greenText)
                    .frame(width: 36, height: 36)
                    .background(RoundedRectangle(cornerRadius: 8).fill(pal.greenSoftPill))
                VStack(alignment: .leading, spacing: 2) {
                    Text(split.song)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(pal.textPri)
                        .lineLimit(1)
                    Text(split.artist)
                        .font(.system(size: 12))
                        .foregroundColor(pal.textDim)
                        .lineLimit(1)
                }
                Spacer()
                Image(systemName: "play.fill")
                    .font(.system(size: 13))
                    .foregroundColor(pal.greenText)
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(pal.bgCard))
        }
    }

    private func youtubeRow(_ item: SearchResult, matchInputs: [TrackMatchInput]) -> some View {
        let localIdx = MusicLibraryCore.findExactLocalTrackForYoutube(
            tracks: matchInputs,
            targetTitle: item.title,
            targetYtId: item.videoId
        )
        let isDownloaded = localIdx >= 0
        let isActiveDl = downloads.progress.isRunning && downloads.progress.activeVideoId == item.videoId
        let isQueued = downloads.progress.queuedVideoIds.contains(item.videoId)
        let isStreamingNow = app.streamingVideoId == item.videoId
        let isPlayingThis = player.currentTrack?.ytId == item.videoId && player.isPlaying

        return HStack(spacing: 10) {
            AsyncImage(url: URL(string: item.thumbnailURL)) { phase in
                if let img = phase.image {
                    img.resizable().scaledToFill()
                } else {
                    Rectangle().fill(pal.bgElevated)
                        .overlay(Image(systemName: "music.note").foregroundColor(pal.textMuted))
                }
            }
            .frame(width: 66, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(isPlayingThis ? pal.greenText : pal.textPri)
                    .lineLimit(2)
                Text("\(item.artist) • \(item.durationText)")
                    .font(.system(size: 11))
                    .foregroundColor(pal.textDim)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 5) {
                // İndirmeden Çalma Listesine Ekle (+ Liste)
                Button {
                    pendingAddToPlaylistItem = item
                } label: {
                    Image(systemName: "text.badge.plus")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(pal.textPri)
                        .frame(width: 32, height: 32)
                        .background(RoundedRectangle(cornerRadius: 8).fill(pal.bgElevated))
                }

                // Direkt Çal (İndirmeden çevrimiçi akış — yerelde varsa yerelden çalar!)
                Button {
                    app.playOnlineSearchResult(item)
                } label: {
                    Group {
                        if isStreamingNow {
                            ProgressView().scaleEffect(0.7).tint(pal.textPri)
                        } else {
                            Image(systemName: isPlayingThis ? "waveform" : "play.fill")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(pal.textPri)
                        }
                    }
                    .frame(width: 34, height: 32)
                    .background(RoundedRectangle(cornerRadius: 8).fill(pal.bgElevated))
                }

                // İndir / İndirildi ✓ / %xx
                Button {
                    if isDownloaded && localIdx < library.allTracks.count {
                        app.playTrack(library.allTracks[localIdx])
                    } else if !isActiveDl && !isQueued {
                        downloads.enqueue(videoId: item.videoId, title: item.title, artist: item.artist)
                    }
                } label: {
                    let label: String = {
                        if isDownloaded { return "İndirildi ✓" }
                        if isActiveDl { return "%\(downloads.progress.percent)" }
                        if isQueued { return "Sırada" }
                        return "İndir"
                    }()
                    Text(label)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(isDownloaded ? pal.greenText : .white)
                        .padding(.horizontal, 10)
                        .frame(height: 32)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(isDownloaded ? pal.greenSoftPill : (isActiveDl || isQueued ? pal.bgElevated : pal.greenAccent))
                        )
                }
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(pal.bgCard))
    }
}
