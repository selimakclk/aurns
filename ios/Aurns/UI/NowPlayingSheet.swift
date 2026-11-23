import SwiftUI
import AVKit
import UIKit

struct NowPlayingSheet: View {
    @ObservedObject var app: AppCoordinator
    @ObservedObject var player: AudioPlayerEngine
    @ObservedObject var friends: FriendsNetworkManager
    @Environment(\.aurnsPalette) var pal
    @Environment(\.dismiss) var dismiss

    @State private var isScrubbing = false
    @State private var scrubMs: Double = 0

    var body: some View {
        let track = player.currentTrack
        let split = track.map { MusicLibraryCore.splitDisplaySongAndArtist(title: $0.title, artist: $0.artist) }
        let totalMs = max(1.0, Double(max(player.durationMs, Int64(track?.durationSec ?? 0) * 1000)))
        let currentMs = isScrubbing ? scrubMs : Double(player.positionMs)

        ZStack {
            LinearGradient(
                colors: [pal.nowPlayingTop, pal.bgShell],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 22) {
                // Üst Kapatma ve AirPlay Barı
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(pal.textPri)
                            .frame(width: 36, height: 36)
                    }
                    Spacer()
                    VStack(spacing: 2) {
                        Text(friends.jamState.isActive ? "CANLI JAM [\(friends.jamState.roomDisplayCode)]" : "ŞİMDİ ÇALIYOR")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(friends.jamState.isActive ? pal.greenText : pal.textDim)
                        if track?.isOnlineStream == true {
                            Text("Çevrimiçi Akış (İndirilmeden)")
                                .font(.system(size: 10))
                                .foregroundColor(pal.greenText)
                        }
                    }
                    Spacer()
                    AirPlayRoutePicker(tintColor: UIColor(pal.textPri))
                        .frame(width: 36, height: 36)
                }
                .padding(.horizontal, 18)
                .padding(.top, 8)

                Spacer(minLength: 8)

                // Büyük Kapak Görseli
                Group {
                    if let art = player.currentArtwork {
                        Image(uiImage: art).resizable().scaledToFill()
                    } else {
                        Image("AppLogo").resizable().scaledToFill()
                    }
                }
                .frame(width: 290, height: 290)
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .shadow(color: pal.greenAccent.opacity(0.45), radius: 22, y: 10)

                // Başlık & Sanatçı
                VStack(spacing: 6) {
                    Text(split?.song ?? "Parça Seçilmedi")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(pal.textPri)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                    Text(split?.artist ?? "")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(pal.textDim)
                        .lineLimit(1)
                }
                .padding(.horizontal, 24)

                // İlerleme Çubuğu
                VStack(spacing: 4) {
                    Slider(
                        value: Binding(
                            get: { min(currentMs, totalMs) },
                            set: { val in
                                isScrubbing = true
                                scrubMs = val
                            }
                        ),
                        in: 0...totalMs,
                        onEditingChanged: { editing in
                            if !editing {
                                isScrubbing = false
                                app.seekToMs(Int64(scrubMs), broadcastJam: true)
                            }
                        }
                    )
                    .tint(pal.greenHover)

                    HStack {
                        Text(formatTimeMMSS(Int64(currentMs)))
                        Spacer()
                        Text(formatTimeMMSS(Int64(totalMs)))
                    }
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(pal.textDim)
                }
                .padding(.horizontal, 24)

                // Oynatma Kontrolleri
                HStack(spacing: 28) {
                    Button {
                        app.isShuffle.toggle()
                    } label: {
                        Image(systemName: "shuffle")
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundColor(app.isShuffle ? pal.greenText : pal.textDim)
                    }

                    Button {
                        app.playPrevious()
                    } label: {
                        Image(systemName: "backward.end.fill")
                            .font(.system(size: 26))
                            .foregroundColor(pal.textPri)
                    }

                    Button {
                        app.togglePlayPause()
                    } label: {
                        ZStack {
                            Circle()
                                .fill(pal.playBg)
                                .frame(width: 68, height: 68)
                            if player.isBuffering {
                                ProgressView().tint(pal.playFg)
                            } else {
                                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                    .font(.system(size: 28, weight: .bold))
                                    .foregroundColor(pal.playFg)
                            }
                        }
                    }

                    Button {
                        app.playNext(userInitiated: true)
                    } label: {
                        Image(systemName: "forward.end.fill")
                            .font(.system(size: 26))
                            .foregroundColor(pal.textPri)
                    }

                    Button {
                        app.repeatMode = app.repeatMode.next()
                    } label: {
                        Image(systemName: app.repeatMode == .one ? "repeat.1" : "repeat")
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundColor(app.repeatMode == .off ? pal.textDim : pal.greenText)
                    }
                }
                .padding(.top, 4)

                Spacer(minLength: 16)
            }
        }
    }
}

private struct AirPlayRoutePicker: UIViewRepresentable {
    let tintColor: UIColor

    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = tintColor
        view.activeTintColor = UIColor(Color(hex: 0x2652D7))
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        uiView.tintColor = tintColor
    }
}
