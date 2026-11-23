import SwiftUI
import UIKit

struct FriendsView: View {
    @ObservedObject var app: AppCoordinator
    @ObservedObject var friends: FriendsNetworkManager
    @Environment(\.aurnsPalette) var pal

    @State private var subTab = 0 // 0: Arkadaşlar, 1: İstekler, 2: Ekle
    @State private var jamCodeInput = ""
    @State private var addTagInput = ""
    @State private var addStatusMessage = ""
    @State private var addStatusIsError = false
    @State private var isSendingRequest = false
    @State private var showRenameAlert = false
    @State private var renameInput = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                identityCard
                liveJamCard

                Picker("Sekme", selection: $subTab) {
                    Text("Arkadaşlar (\(friends.friends.count))").tag(0)
                    Text("İstekler (\(friends.pendingRequests.count))").tag(1)
                    Text("Arkadaş Ekle").tag(2)
                }
                .pickerStyle(.segmented)

                if subTab == 0 {
                    friendsListSection
                } else if subTab == 1 {
                    requestsSection
                } else {
                    addFriendSection
                }
            }
            .padding(14)
        }
        .background(pal.bgShell)
        .alert("Kullanıcı Adını Değiştir", isPresented: $showRenameAlert) {
            TextField("Yeni ad", text: $renameInput)
            Button("Kaydet") {
                friends.updateMyName(renameInput)
                app.showToast("Kullanıcı adı güncellendi")
            }
            Button("İptal", role: .cancel) {}
        }
    }

    // MARK: - Kimlik Kartı

    private var identityCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle()
                    .fill(friends.isConnected ? Color.green : pal.amber)
                    .frame(width: 9, height: 9)
                Text(friends.isConnected ? "Canlı Ağ Bağlı" : "Bağlanıyor...")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(pal.textDim)
                Spacer()
                Button {
                    renameInput = friends.myName
                    showRenameAlert = true
                } label: {
                    Label("Adı Değiştir", systemImage: "pencil")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(pal.greenText)
                }
            }

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("SENİN ETİKETİN")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(pal.textMuted)
                    Text(friends.myTag)
                        .font(.system(size: 18, weight: .bold, design: .monospaced))
                        .foregroundColor(pal.textPri)
                }
                Spacer()
                Button {
                    UIPasteboard.general.string = friends.myTag
                    app.showToast("Etiket kopyalandı: \(friends.myTag)")
                } label: {
                    Label("Kopyala", systemImage: "doc.on.doc")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(pal.greenAccent))
                }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(pal.bgCard))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.borderSubtle, lineWidth: 1))
    }

    // MARK: - Canlı Jam Oda Kartı

    private var liveJamCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "person.wave.2.fill")
                    .foregroundColor(pal.greenText)
                Text("Canlı Jam (Sınırsız Katılımcı)")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(pal.textPri)
                Spacer()
                if friends.jamState.isActive || friends.jamState.isInviting {
                    Text("\(friends.jamState.totalParticipantCount) Kişi")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(pal.greenText)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(pal.greenSoftPill))
                }
            }

            if friends.jamState.isActive || friends.jamState.isInviting {
                Text(friends.jamState.syncStatus)
                    .font(.system(size: 12))
                    .foregroundColor(pal.textDim)

                HStack(spacing: 8) {
                    Button {
                        UIPasteboard.general.string = friends.jamState.roomDisplayCode
                        app.showToast("Oda kodu kopyalandı: \(friends.jamState.roomDisplayCode)")
                    } label: {
                        Label("Kod: \(friends.jamState.roomDisplayCode)", systemImage: "doc.on.doc")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 10).fill(pal.greenAccent))
                    }

                    Button {
                        friends.leaveJamRoom()
                    } label: {
                        Text("Odadan Ayrıl")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(pal.danger)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 10).fill(pal.dangerBg))
                    }
                }
            } else {
                Button {
                    let code = friends.createJamRoom()
                    let disp = FriendsProtocol.formatRoomDisplayCode(code)
                    UIPasteboard.general.string = disp
                    app.showToast("Jam odası kuruldu ve kod kopyalandı: \(disp)")
                } label: {
                    Label("Yeni Jam Odası Oluştur", systemImage: "plus.circle.fill")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(pal.greenAccent))
                }

                HStack(spacing: 8) {
                    TextField("Oda kodu (Örn: ATBTXL)", text: $jamCodeInput)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundColor(pal.textPri)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 9)
                        .background(RoundedRectangle(cornerRadius: 10).fill(pal.bgInput))

                    Button("Katıl") {
                        if friends.joinJamRoomByCode(jamCodeInput) {
                            jamCodeInput = ""
                            app.showToast("Jam odasına bağlanılıyor...")
                        } else {
                            app.showToast("Geçerli bir oda kodu girin")
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
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.greenAccent.opacity(0.4), lineWidth: 1))
    }

    // MARK: - Arkadaşlar Listesi

    private var friendsListSection: some View {
        VStack(spacing: 8) {
            if friends.friends.isEmpty {
                Text("Henüz arkadaşın yok. 'Arkadaş Ekle' sekmesinden etiketle ekle.")
                    .font(.system(size: 13))
                    .foregroundColor(pal.textMuted)
                    .multilineTextAlignment(.center)
                    .padding(.vertical, 28)
            } else {
                ForEach(friends.friends) { f in
                    HStack(spacing: 10) {
                        Circle()
                            .fill(f.isOnline ? Color.green : pal.textMuted)
                            .frame(width: 10, height: 10)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.name)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(pal.textPri)
                            Text(f.currentTrack)
                                .font(.system(size: 12))
                                .foregroundColor(f.isPlaying ? pal.greenText : pal.textDim)
                                .lineLimit(1)
                        }
                        Spacer()

                        if !friends.jamState.isFriendInRoom(f.tag) {
                            Button {
                                friends.inviteFriendToJam(f)
                                app.showToast("\(f.name) Jam'e davet edildi")
                            } label: {
                                Text("Jam")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Capsule().fill(pal.greenAccent))
                            }
                        } else {
                            Text("Odada ✓")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(pal.greenText)
                        }

                        Button {
                            friends.removeFriend(f)
                            app.showToast("\(f.name) arkadaşlardan çıkarıldı")
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 13))
                                .foregroundColor(pal.danger)
                                .padding(6)
                        }
                    }
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(pal.bgCard))
                }
            }
        }
    }

    // MARK: - Gelen İstekler

    private var requestsSection: some View {
        VStack(spacing: 8) {
            if friends.pendingRequests.isEmpty {
                Text("Bekleyen arkadaşlık isteği yok.")
                    .font(.system(size: 13))
                    .foregroundColor(pal.textMuted)
                    .padding(.vertical, 28)
            } else {
                ForEach(friends.pendingRequests) { req in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(req.name)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(pal.textPri)
                            Text(req.tag)
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(pal.textDim)
                        }
                        Spacer()
                        Button("Kabul Et") {
                            friends.acceptFriendRequest(req)
                            app.showToast("\(req.name) eklendi")
                        }
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Capsule().fill(pal.greenAccent))

                        Button("Reddet") {
                            friends.rejectFriendRequest(req)
                        }
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(pal.danger)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Capsule().fill(pal.dangerBg))
                    }
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(pal.bgCard))
                }
            }
        }
    }

    // MARK: - Arkadaş Ekle

    private var addFriendSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Arkadaşının PC, Android veya iPhone etiketini yaz:")
                .font(.system(size: 12))
                .foregroundColor(pal.textDim)

            TextField("Örn: selo#4770 veya #4770", text: $addTagInput)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .foregroundColor(pal.textPri)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 10).fill(pal.bgInput))

            Button {
                guard !isSendingRequest else { return }
                isSendingRequest = true
                addStatusMessage = ""
                let target = addTagInput
                Task {
                    let res = await friends.checkUserAndSendRequest(targetTag: target)
                    isSendingRequest = false
                    switch res {
                    case .success(let msg):
                        addStatusIsError = false
                        addStatusMessage = msg
                        addTagInput = ""
                    case .failure(let err):
                        addStatusIsError = true
                        addStatusMessage = err.localizedDescription
                    }
                }
            } label: {
                HStack {
                    if isSendingRequest { ProgressView().tint(.white).scaleEffect(0.8) }
                    Text(isSendingRequest ? "Gönderiliyor..." : "Arkadaşlık İsteği Gönder")
                        .font(.system(size: 14, weight: .bold))
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 10).fill(pal.greenAccent))
            }

            if !addStatusMessage.isEmpty {
                Text(addStatusMessage)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(addStatusIsError ? pal.danger : pal.greenText)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(pal.bgCard))
    }
}
