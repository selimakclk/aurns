#include "jam.h"
#include "friends.h"
#include "network.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    JamState  state;
    char      jamId[64];

    JamMember members[MAX_JAM_MEMBERS];
    int       memberCount;

    wchar_t   lastInvitedTag[32];
    wchar_t   lastInvitedName[64];
    int       outgoingInviteCount;
    DWORD     inviteSentTick;
    wchar_t   lastStateSenderTag[32];

    wchar_t   syncStatus[128];
    BOOL      isDownloading;
    wchar_t   missingTrack[256];

    BOOL      hasPendingInvite;
    char      pendingJamId[64];
    wchar_t   pendingTag[32];
    wchar_t   pendingName[64];
    DWORD     pendingTick;

    JamRemoteSync pendingRemoteSync;
    wchar_t       askTargets[8][32];
    int           askTargetCount;
} JamSession;

static JamSession g_jam;

static void SanitizeFieldUtf8(char *s) {
    if (!s) return;
    for (int i = 0; s[i]; i++) {
        if (s[i] == '|' || s[i] == '\r' || s[i] == '\n') s[i] = '-';
    }
}

static void FormatRoomTopic(const char *jamId, char *outTopic, int maxLen) {
    if (!outTopic || maxLen <= 0) return;
    if (!jamId || !jamId[0]) {
        outTopic[0] = '\0';
        return;
    }
    snprintf(outTopic, maxLen, "seloplayer/jam/%s", jamId);
}

static void PublishToPeerInbox(const wchar_t *peerTag, const char *payload) {
    if (!peerTag || !peerTag[0] || !payload) return;
    char peerKey[64] = {0};
    Network_TagToTopicKeyW(peerTag, peerKey, sizeof(peerKey));
    if (!peerKey[0]) return;
    char inbox[160];
    snprintf(inbox, sizeof(inbox), "seloplayer/u/%s", peerKey);
    Network_Publish(inbox, payload);
}

static void PublishToRoom(const char *payload) {
    if (!g_jam.jamId[0] || !payload) return;
    char roomTopic[160];
    FormatRoomTopic(g_jam.jamId, roomTopic, sizeof(roomTopic));
    Network_Publish(roomTopic, payload);
}

static int FindMemberIndex(const wchar_t *tag) {
    if (!tag || !tag[0]) return -1;
    for (int i = 0; i < g_jam.memberCount; i++) {
        if (_wcsicmp(g_jam.members[i].tag, tag) == 0) return i;
    }
    return -1;
}

// Yeni uye eklendiyse TRUE, zaten listede varsa FALSE doner
static BOOL UpsertMember(const wchar_t *tag, const wchar_t *name) {
    if (!tag || !tag[0]) return FALSE;
    wchar_t myTag[32] = {0};
    Friends_GetMyTag(myTag, 32);
    if (myTag[0] && _wcsicmp(tag, myTag) == 0) return FALSE;

    int idx = FindMemberIndex(tag);
    if (idx >= 0) {
        if (name && name[0]) {
            wcsncpy(g_jam.members[idx].name, name, 63);
            g_jam.members[idx].name[63] = L'\0';
        }
        return FALSE;
    }
    if (g_jam.memberCount >= MAX_JAM_MEMBERS) return FALSE;
    idx = g_jam.memberCount++;
    wcsncpy(g_jam.members[idx].tag, tag, 31);
    g_jam.members[idx].tag[31] = L'\0';
    wcsncpy(g_jam.members[idx].name, (name && name[0]) ? name : tag, 63);
    g_jam.members[idx].name[63] = L'\0';
    return TRUE;
}

static void RemoveMember(const wchar_t *tag) {
    int idx = FindMemberIndex(tag);
    if (idx < 0) return;
    for (int i = idx; i < g_jam.memberCount - 1; i++) {
        g_jam.members[i] = g_jam.members[i + 1];
    }
    g_jam.memberCount--;
    if (_wcsicmp(g_jam.lastStateSenderTag, tag) == 0) {
        g_jam.lastStateSenderTag[0] = L'\0';
    }
}

static void EnqueueAskTarget(const wchar_t *targetTag) {
    if (!targetTag || !targetTag[0]) return;
    for (int i = 0; i < g_jam.askTargetCount; i++) {
        if (_wcsicmp(g_jam.askTargets[i], targetTag) == 0) return;
    }
    if (g_jam.askTargetCount < 8) {
        wcsncpy(g_jam.askTargets[g_jam.askTargetCount], targetTag, 31);
        g_jam.askTargets[g_jam.askTargetCount][31] = L'\0';
        g_jam.askTargetCount++;
    }
}

static void ResetRoomInternal(BOOL unsubRoom) {
    if (unsubRoom && g_jam.jamId[0]) {
        char roomTopic[160];
        FormatRoomTopic(g_jam.jamId, roomTopic, sizeof(roomTopic));
        Network_Unsubscribe(roomTopic);
    }
    g_jam.state = JAM_STATE_NONE;
    g_jam.jamId[0] = '\0';
    g_jam.memberCount = 0;
    g_jam.lastInvitedTag[0] = L'\0';
    g_jam.lastInvitedName[0] = L'\0';
    g_jam.outgoingInviteCount = 0;
    g_jam.lastStateSenderTag[0] = L'\0';
    g_jam.syncStatus[0] = L'\0';
    g_jam.isDownloading = FALSE;
    g_jam.missingTrack[0] = L'\0';
    g_jam.pendingRemoteSync.valid = FALSE;
    g_jam.askTargetCount = 0;
}

void Jam_Init(void) {
    memset(&g_jam, 0, sizeof(g_jam));
}

JamState Jam_GetState(void) {
    Jam_CheckTimeouts();
    return g_jam.state;
}

BOOL Jam_IsActive(void) {
    return (Jam_GetState() == JAM_STATE_ACTIVE);
}

void Jam_CheckTimeouts(void) {
    DWORD now = GetTickCount();
    if (g_jam.state == JAM_STATE_INVITING) {
        if (now - g_jam.inviteSentTick > 45000) {
            ResetRoomInternal(TRUE);
        }
    }
    if (g_jam.hasPendingInvite) {
        if (now - g_jam.pendingTick > 45000) {
            g_jam.hasPendingInvite = FALSE;
        }
    }
}

int Jam_GetMemberCount(void) {
    return g_jam.memberCount;
}

BOOL Jam_IsFriendInJam(const wchar_t *friendTag) {
    if (g_jam.state == JAM_STATE_NONE || !friendTag || !friendTag[0]) return FALSE;
    if (FindMemberIndex(friendTag) >= 0) return TRUE;
    if (g_jam.state == JAM_STATE_INVITING && _wcsicmp(g_jam.lastInvitedTag, friendTag) == 0) return TRUE;
    return FALSE;
}

void Jam_FormatBannerHeadline(wchar_t *buf, int maxLen) {
    if (!buf || maxLen <= 0) return;
    if (g_jam.state == JAM_STATE_INVITING) {
        if (g_jam.outgoingInviteCount > 1) {
            _snwprintf(buf, maxLen - 1, L"JAM ODASI KURULUYOR  •  %d kişiye davet gönderildi", g_jam.outgoingInviteCount);
        } else {
            _snwprintf(buf, maxLen - 1, L"JAM DAVETİ BEKLENİYOR  •  %s",
                       g_jam.lastInvitedName[0] ? g_jam.lastInvitedName : g_jam.lastInvitedTag);
        }
        buf[maxLen - 1] = L'\0';
        return;
    }

    int totalPeople = g_jam.memberCount + 1; // Sen + katilimcilar
    wchar_t names[192] = L"Sen";
    int showMax = (g_jam.memberCount < 3) ? g_jam.memberCount : 3;
    for (int i = 0; i < showMax; i++) {
        wcsncat(names, L", ", 191 - wcslen(names));
        const wchar_t *nm = g_jam.members[i].name[0] ? g_jam.members[i].name : g_jam.members[i].tag;
        wcsncat(names, nm, 191 - wcslen(names));
    }
    if (g_jam.memberCount > 3) {
        wchar_t extra[32];
        _snwprintf(extra, 31, L" +%d", g_jam.memberCount - 3);
        extra[31] = L'\0';
        wcsncat(names, extra, 191 - wcslen(names));
    }
    _snwprintf(buf, maxLen - 1, L"CANLI JAM ODASI (%d Kişi)  •  %s", totalPeople, names);
    buf[maxLen - 1] = L'\0';
}

void Jam_GetPartnerName(wchar_t *buf, int maxLen) {
    if (!buf || maxLen <= 0) return;
    if (g_jam.memberCount > 0) {
        wcsncpy(buf, g_jam.members[0].name, maxLen - 1);
    } else {
        wcsncpy(buf, g_jam.lastInvitedName, maxLen - 1);
    }
    buf[maxLen - 1] = L'\0';
}

void Jam_GetPartnerTag(wchar_t *buf, int maxLen) {
    if (!buf || maxLen <= 0) return;
    if (g_jam.memberCount > 0) {
        wcsncpy(buf, g_jam.members[0].tag, maxLen - 1);
    } else {
        wcsncpy(buf, g_jam.lastInvitedTag, maxLen - 1);
    }
    buf[maxLen - 1] = L'\0';
}

void Jam_SetSyncStatus(const wchar_t *statusText, BOOL isDownloading, const wchar_t *missingTrack) {
    if (statusText) {
        wcsncpy(g_jam.syncStatus, statusText, 127);
        g_jam.syncStatus[127] = L'\0';
    } else {
        g_jam.syncStatus[0] = L'\0';
    }
    g_jam.isDownloading = isDownloading;
    if (missingTrack && missingTrack[0]) {
        wcsncpy(g_jam.missingTrack, missingTrack, 255);
        g_jam.missingTrack[255] = L'\0';
    } else {
        g_jam.missingTrack[0] = L'\0';
    }
}

void Jam_GetSyncStatus(wchar_t *buf, int maxLen) {
    if (!buf || maxLen <= 0) return;
    if (g_jam.state == JAM_STATE_INVITING) {
        wcsncpy(buf, L"Davet gönderildi • Katılımcıların kabul etmesi bekleniyor...", maxLen - 1);
    } else if (g_jam.syncStatus[0]) {
        wcsncpy(buf, g_jam.syncStatus, maxLen - 1);
    } else {
        wcsncpy(buf, L"Canlı milisaniye senkronizasyonu aktif", maxLen - 1);
    }
    buf[maxLen - 1] = L'\0';
}

BOOL Jam_IsDownloadingTrack(void) {
    return g_jam.isDownloading;
}

void Jam_GetMissingTrack(wchar_t *buf, int maxLen) {
    if (buf && maxLen > 0) {
        wcsncpy(buf, g_jam.missingTrack, maxLen - 1);
        buf[maxLen - 1] = L'\0';
    }
}

BOOL Jam_HasPendingInvite(void) {
    Jam_CheckTimeouts();
    return g_jam.hasPendingInvite;
}

void Jam_GetPendingInviteInfo(wchar_t *nameBuf, wchar_t *tagBuf) {
    if (nameBuf) {
        wcsncpy(nameBuf, g_jam.pendingName, 63);
        nameBuf[63] = L'\0';
    }
    if (tagBuf) {
        wcsncpy(tagBuf, g_jam.pendingTag, 31);
        tagBuf[31] = L'\0';
    }
}

void Jam_InviteFriend(const wchar_t *friendTag, const wchar_t *friendName) {
    if (!friendTag || !friendTag[0]) return;
    if (FindMemberIndex(friendTag) >= 0) return; // Zaten odada!

    wchar_t myTag[32] = {0};
    wchar_t myName[64] = {0};
    Friends_GetMyTag(myTag, 32);
    Friends_GetMyName(myName, 64);
    if (_wcsicmp(friendTag, myTag) == 0) return;

    char uMyTag[128] = {0};
    char uMyName[128] = {0};
    char myKey[64] = {0};
    WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, myName, -1, uMyName, 127, NULL, NULL);
    Network_TagToTopicKeyW(myTag, myKey, sizeof(myKey));
    SanitizeFieldUtf8(uMyTag);
    SanitizeFieldUtf8(uMyName);

    // Henuz oda yoksa yeni oda ac ve abone ol; aktif/bekleyen oda varsa MEVCUT ODAYA ek davet gonder!
    if (g_jam.state == JAM_STATE_NONE || !g_jam.jamId[0]) {
        ResetRoomInternal(FALSE);
        snprintf(g_jam.jamId, sizeof(g_jam.jamId), "jam_%s_%lu", myKey, (unsigned long)GetTickCount());
        g_jam.state = JAM_STATE_INVITING;

        char roomTopic[160];
        FormatRoomTopic(g_jam.jamId, roomTopic, sizeof(roomTopic));
        Network_Subscribe(roomTopic);
    }

    g_jam.inviteSentTick = GetTickCount();
    g_jam.outgoingInviteCount++;
    wcsncpy(g_jam.lastInvitedTag, friendTag, 31);
    g_jam.lastInvitedTag[31] = L'\0';
    wcsncpy(g_jam.lastInvitedName, (friendName && friendName[0]) ? friendName : friendTag, 63);
    g_jam.lastInvitedName[63] = L'\0';

    if (g_jam.state == JAM_STATE_INVITING) {
        Jam_SetSyncStatus(L"Davet gönderildi • Katılımcıların kabul etmesi bekleniyor...", FALSE, NULL);
    } else if (!g_jam.isDownloading) {
        wchar_t msg[128];
        _snwprintf(msg, 127, L"%s odaya davet edildi • Canlı senkron aktif", g_jam.lastInvitedName);
        msg[127] = L'\0';
        Jam_SetSyncStatus(msg, FALSE, NULL);
    }

    char payload[320];
    snprintf(payload, sizeof(payload), "JAM_INV|%s|%s|%s", uMyTag, uMyName, g_jam.jamId);
    PublishToPeerInbox(friendTag, payload);
}

void Jam_AcceptInvite(void) {
    if (!g_jam.hasPendingInvite) return;

    char targetJamId[64] = {0};
    wchar_t inviterTag[32] = {0};
    wchar_t inviterName[64] = {0};
    strncpy(targetJamId, g_jam.pendingJamId, 63);
    wcsncpy(inviterTag, g_jam.pendingTag, 31);
    wcsncpy(inviterName, g_jam.pendingName[0] ? g_jam.pendingName : g_jam.pendingTag, 63);
    g_jam.hasPendingInvite = FALSE;

    // Baska bir odadaysak once ondan temizce cik
    if (g_jam.state != JAM_STATE_NONE && strcmp(g_jam.jamId, targetJamId) != 0) {
        Jam_Leave();
    }

    g_jam.state = JAM_STATE_ACTIVE;
    strncpy(g_jam.jamId, targetJamId, sizeof(g_jam.jamId) - 1);
    UpsertMember(inviterTag, inviterName);
    wcsncpy(g_jam.lastStateSenderTag, inviterTag, 31);

    char roomTopic[160];
    FormatRoomTopic(g_jam.jamId, roomTopic, sizeof(roomTopic));
    Network_Subscribe(roomTopic);

    Jam_SetSyncStatus(L"Odaya bağlanıldı • Canlı yayın senkronize ediliyor...", FALSE, NULL);

    wchar_t myTag[32] = {0};
    wchar_t myName[64] = {0};
    Friends_GetMyTag(myTag, 32);
    Friends_GetMyName(myName, 64);
    char uMyTag[128] = {0};
    char uMyName[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, myName, -1, uMyName, 127, NULL, NULL);
    SanitizeFieldUtf8(uMyTag);
    SanitizeFieldUtf8(uMyName);

    // Hem oda konusuna (tum uyeler gorsun) hem davet edenin kutusuna gonder
    char joinPayload[320];
    snprintf(joinPayload, sizeof(joinPayload), "JAM_JOIN|%s|%s|%s", uMyTag, uMyName, g_jam.jamId);
    PublishToPeerInbox(inviterTag, joinPayload);
    PublishToRoom(joinPayload);

    char askPayload[256];
    snprintf(askPayload, sizeof(askPayload), "JAM_ASK|%s|%s", uMyTag, g_jam.jamId);
    PublishToPeerInbox(inviterTag, askPayload);
}

void Jam_RejectInvite(void) {
    if (!g_jam.hasPendingInvite) return;

    wchar_t myTag[32] = {0};
    Friends_GetMyTag(myTag, 32);
    char uMyTag[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
    SanitizeFieldUtf8(uMyTag);

    char payload[256];
    snprintf(payload, sizeof(payload), "JAM_REJ|%s|%s", uMyTag, g_jam.pendingJamId);
    PublishToPeerInbox(g_jam.pendingTag, payload);

    g_jam.hasPendingInvite = FALSE;
}

void Jam_Leave(void) {
    if (g_jam.state == JAM_STATE_NONE) return;

    wchar_t myTag[32] = {0};
    Friends_GetMyTag(myTag, 32);
    char uMyTag[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
    SanitizeFieldUtf8(uMyTag);

    char payload[256];
    snprintf(payload, sizeof(payload), "JAM_LEAVE|%s|%s", uMyTag, g_jam.jamId);
    PublishToRoom(payload);
    for (int i = 0; i < g_jam.memberCount; i++) {
        PublishToPeerInbox(g_jam.members[i].tag, payload);
    }
    if (g_jam.state == JAM_STATE_INVITING && g_jam.lastInvitedTag[0]) {
        PublishToPeerInbox(g_jam.lastInvitedTag, payload);
    }

    ResetRoomInternal(TRUE);
}

static void BuildStatePayload(char *outPayload, int maxLen, BOOL isPlaying, const wchar_t *trackTitle, const wchar_t *ytId, int posMs) {
    wchar_t myTag[32] = {0};
    Friends_GetMyTag(myTag, 32);
    char uMyTag[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
    SanitizeFieldUtf8(uMyTag);

    char uYtId[32] = {0};
    if (ytId && ytId[0]) {
        WideCharToMultiByte(CP_UTF8, 0, ytId, -1, uYtId, 31, NULL, NULL);
        SanitizeFieldUtf8(uYtId);
    }

    char uTrack[320] = {0};
    if (trackTitle && trackTitle[0]) {
        WideCharToMultiByte(CP_UTF8, 0, trackTitle, -1, uTrack, 319, NULL, NULL);
        SanitizeFieldUtf8(uTrack);
    }

    snprintf(outPayload, maxLen, "JAM_STATE|%s|%s|%d|%d|%s|%s",
             uMyTag, g_jam.jamId, isPlaying ? 1 : 0, posMs < 0 ? 0 : posMs, uYtId, uTrack);
}

void Jam_BroadcastState(BOOL isPlaying, const wchar_t *trackTitle, const wchar_t *ytId, int posMs) {
    if (g_jam.state != JAM_STATE_ACTIVE || !g_jam.jamId[0]) return;
    char payload[640];
    BuildStatePayload(payload, sizeof(payload), isPlaying, trackTitle, ytId, posMs);
    PublishToRoom(payload);
}

void Jam_SendStateToPeer(const wchar_t *targetTag, BOOL isPlaying, const wchar_t *trackTitle, const wchar_t *ytId, int posMs) {
    if (g_jam.state != JAM_STATE_ACTIVE || !g_jam.jamId[0] || !targetTag || !targetTag[0]) return;
    char payload[640];
    BuildStatePayload(payload, sizeof(payload), isPlaying, trackTitle, ytId, posMs);
    PublishToPeerInbox(targetTag, payload);
}

static void BroadcastPosFlag(int playFlag, int posMs) {
    if (g_jam.state != JAM_STATE_ACTIVE || !g_jam.jamId[0]) return;

    wchar_t myTag[32] = {0};
    Friends_GetMyTag(myTag, 32);
    char uMyTag[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
    SanitizeFieldUtf8(uMyTag);

    char payload[256];
    snprintf(payload, sizeof(payload), "JAM_POS|%s|%s|%d|%d",
             uMyTag, g_jam.jamId, playFlag, posMs < 0 ? 0 : posMs);
    PublishToRoom(payload);
}

void Jam_BroadcastPause(int posMs) {
    BroadcastPosFlag(0, posMs);
}

void Jam_BroadcastResume(int posMs) {
    BroadcastPosFlag(1, posMs);
}

void Jam_BroadcastSeek(int posMs) {
    BroadcastPosFlag(-1, posMs);
}

void Jam_RequestPartnerStateNow(void) {
    if (g_jam.state != JAM_STATE_ACTIVE || !g_jam.jamId[0]) return;

    wchar_t myTag[32] = {0};
    Friends_GetMyTag(myTag, 32);
    char uMyTag[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
    SanitizeFieldUtf8(uMyTag);

    char payload[256];
    snprintf(payload, sizeof(payload), "JAM_ASK|%s|%s", uMyTag, g_jam.jamId);

    // Oncelikle son yayini yapan kisiye tekil sor, yoksa ilk uyeye veya odaya sor
    if (g_jam.lastStateSenderTag[0] && FindMemberIndex(g_jam.lastStateSenderTag) >= 0) {
        PublishToPeerInbox(g_jam.lastStateSenderTag, payload);
    } else if (g_jam.memberCount > 0) {
        PublishToPeerInbox(g_jam.members[0].tag, payload);
    } else {
        PublishToRoom(payload);
    }
}

BOOL Jam_PollRemoteSync(JamRemoteSync *outSync) {
    if (!g_jam.pendingRemoteSync.valid || !outSync) return FALSE;
    *outSync = g_jam.pendingRemoteSync;
    g_jam.pendingRemoteSync.valid = FALSE;
    return TRUE;
}

BOOL Jam_PollStateRequestTarget(wchar_t *outTargetTag, int maxLen) {
    if (g_jam.askTargetCount <= 0 || !outTargetTag || maxLen <= 0) return FALSE;
    wcsncpy(outTargetTag, g_jam.askTargets[0], maxLen - 1);
    outTargetTag[maxLen - 1] = L'\0';
    for (int i = 0; i < g_jam.askTargetCount - 1; i++) {
        wcscpy(g_jam.askTargets[i], g_jam.askTargets[i + 1]);
    }
    g_jam.askTargetCount--;
    return TRUE;
}

static BOOL NextPipeToken(const char **cursor, char *out, int maxOut) {
    if (!cursor || !*cursor || !out || maxOut <= 0) return FALSE;
    const char *start = *cursor;
    const char *bar = strchr(start, '|');
    if (bar) {
        int len = (int)(bar - start);
        if (len >= maxOut) len = maxOut - 1;
        memcpy(out, start, len);
        out[len] = '\0';
        *cursor = bar + 1;
    } else {
        strncpy(out, start, maxOut - 1);
        out[maxOut - 1] = '\0';
        *cursor = NULL;
    }
    return TRUE;
}

void Jam_HandleNetworkMessage(const char *topic, const char *payload) {
    if (!topic || !payload) return;

    wchar_t myTag[32] = {0};
    wchar_t myName[64] = {0};
    Friends_GetMyTag(myTag, 32);
    Friends_GetMyName(myName, 64);

    char myKey[64] = {0};
    Network_TagToTopicKeyW(myTag, myKey, sizeof(myKey));
    char myInbox[160];
    snprintf(myInbox, sizeof(myInbox), "seloplayer/u/%s", myKey);

    char roomTopic[160] = {0};
    if (g_jam.jamId[0]) {
        FormatRoomTopic(g_jam.jamId, roomTopic, sizeof(roomTopic));
    }

    BOOL isInbox = (_stricmp(topic, myInbox) == 0);
    BOOL isRoom  = (roomTopic[0] && _stricmp(topic, roomTopic) == 0);
    if (!isInbox && !isRoom) return;

    // 1. JAM_INV|fromTag|fromName|jamId (yalnizca gelen kutusu)
    if (isInbox && strncmp(payload, "JAM_INV|", 8) == 0) {
        const char *cur = payload + 8;
        char fTag[64] = {0}, fName[64] = {0}, jamId[64] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) &&
            NextPipeToken(&cur, fName, sizeof(fName)) &&
            cur != NULL) {
            strncpy(jamId, cur, sizeof(jamId) - 1);
            wchar_t wTag[32] = {0};
            MultiByteToWideChar(CP_UTF8, 0, fTag, -1, wTag, 31);
            if (!wTag[0] || !jamId[0] || _wcsicmp(wTag, myTag) == 0) return;
            // Zaten bu odadaysak tekrar bildirim cikarma
            if (g_jam.state != JAM_STATE_NONE && strcmp(g_jam.jamId, jamId) == 0) return;

            g_jam.hasPendingInvite = TRUE;
            g_jam.pendingTick = GetTickCount();
            strncpy(g_jam.pendingJamId, jamId, 63);
            wcsncpy(g_jam.pendingTag, wTag, 31);
            MultiByteToWideChar(CP_UTF8, 0, fName, -1, g_jam.pendingName, 63);
        }
        return;
    }

    // 2. JAM_JOIN|fromTag|fromName|jamId
    if (strncmp(payload, "JAM_JOIN|", 9) == 0) {
        const char *cur = payload + 9;
        char fTag[64] = {0}, fName[64] = {0}, jamId[64] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) &&
            NextPipeToken(&cur, fName, sizeof(fName)) &&
            cur != NULL) {
            strncpy(jamId, cur, sizeof(jamId) - 1);
            if (g_jam.state == JAM_STATE_NONE || strcmp(g_jam.jamId, jamId) != 0) return;

            wchar_t wTag[32] = {0}, wName[64] = {0};
            MultiByteToWideChar(CP_UTF8, 0, fTag, -1, wTag, 31);
            MultiByteToWideChar(CP_UTF8, 0, fName, -1, wName, 63);
            if (!wTag[0] || _wcsicmp(wTag, myTag) == 0) return;

            BOOL isNew = UpsertMember(wTag, wName);
            g_jam.state = JAM_STATE_ACTIVE;
            if (g_jam.outgoingInviteCount > 0) g_jam.outgoingInviteCount--;

            if (isNew) {
                if (!g_jam.isDownloading) {
                    Jam_SetSyncStatus(L"Canlı milisaniye senkronizasyonu aktif", FALSE, NULL);
                }
                // Yeni katilan kisiye kendimizi tanit (odadaki TUM katilimcilari gormesi icin)
                char uMyTag[128] = {0}, uMyName[128] = {0};
                WideCharToMultiByte(CP_UTF8, 0, myTag, -1, uMyTag, 127, NULL, NULL);
                WideCharToMultiByte(CP_UTF8, 0, myName, -1, uMyName, 127, NULL, NULL);
                SanitizeFieldUtf8(uMyTag);
                SanitizeFieldUtf8(uMyName);
                char herePayload[320];
                snprintf(herePayload, sizeof(herePayload), "JAM_HERE|%s|%s|%s", uMyTag, uMyName, g_jam.jamId);
                PublishToPeerInbox(wTag, herePayload);

                // Calan bir sarkimiz varsa yeni katilana anlik konumu tekil gonder
                if (!g_jam.isDownloading) {
                    EnqueueAskTarget(wTag);
                }
            }
        }
        return;
    }

    // 3. JAM_HERE|fromTag|fromName|jamId (odadaki mevcut uyelerin kendisini tanitmasi)
    if (strncmp(payload, "JAM_HERE|", 9) == 0) {
        const char *cur = payload + 9;
        char fTag[64] = {0}, fName[64] = {0}, jamId[64] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) &&
            NextPipeToken(&cur, fName, sizeof(fName)) &&
            cur != NULL) {
            strncpy(jamId, cur, sizeof(jamId) - 1);
            if (g_jam.state == JAM_STATE_NONE || strcmp(g_jam.jamId, jamId) != 0) return;

            wchar_t wTag[32] = {0}, wName[64] = {0};
            MultiByteToWideChar(CP_UTF8, 0, fTag, -1, wTag, 31);
            MultiByteToWideChar(CP_UTF8, 0, fName, -1, wName, 63);
            if (!wTag[0] || _wcsicmp(wTag, myTag) == 0) return;

            UpsertMember(wTag, wName);
            g_jam.state = JAM_STATE_ACTIVE;
        }
        return;
    }

    // 4. JAM_REJ|fromTag|jamId
    if (strncmp(payload, "JAM_REJ|", 8) == 0) {
        const char *cur = payload + 8;
        char fTag[64] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) && cur != NULL) {
            if (g_jam.state != JAM_STATE_NONE && strcmp(g_jam.jamId, cur) == 0) {
                if (g_jam.outgoingInviteCount > 0) g_jam.outgoingInviteCount--;
                if (g_jam.state == JAM_STATE_INVITING && g_jam.memberCount == 0 && g_jam.outgoingInviteCount == 0) {
                    ResetRoomInternal(TRUE);
                }
            }
        }
        return;
    }

    // 5. JAM_LEAVE|fromTag|jamId
    if (strncmp(payload, "JAM_LEAVE|", 10) == 0) {
        const char *cur = payload + 10;
        char fTag[64] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) && cur != NULL) {
            if (g_jam.state != JAM_STATE_NONE && strcmp(g_jam.jamId, cur) == 0) {
                wchar_t wTag[32] = {0};
                MultiByteToWideChar(CP_UTF8, 0, fTag, -1, wTag, 31);
                if (!wTag[0] || _wcsicmp(wTag, myTag) == 0) return;

                RemoveMember(wTag);
                // Odada baska katilimci kalmadiysa ve bekleyen davet yoksa odayi kapat
                if (g_jam.memberCount == 0 && g_jam.outgoingInviteCount == 0) {
                    ResetRoomInternal(TRUE);
                }
            }
        }
        return;
    }

    // 6. JAM_ASK|fromTag|jamId
    if (strncmp(payload, "JAM_ASK|", 8) == 0) {
        const char *cur = payload + 8;
        char fTag[64] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) && cur != NULL) {
            if (g_jam.state == JAM_STATE_ACTIVE && strcmp(g_jam.jamId, cur) == 0) {
                wchar_t wTag[32] = {0};
                MultiByteToWideChar(CP_UTF8, 0, fTag, -1, wTag, 31);
                if (!wTag[0] || _wcsicmp(wTag, myTag) == 0) return;
                UpsertMember(wTag, NULL);
                if (!g_jam.isDownloading) {
                    EnqueueAskTarget(wTag);
                }
            }
        }
        return;
    }

    // 7. JAM_STATE|fromTag|jamId|isPlaying|posMs|ytId|trackTitle
    if (strncmp(payload, "JAM_STATE|", 10) == 0) {
        const char *cur = payload + 10;
        char fTag[64] = {0}, jamId[64] = {0}, sPlay[16] = {0}, sPos[24] = {0}, sYt[32] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) &&
            NextPipeToken(&cur, jamId, sizeof(jamId)) &&
            NextPipeToken(&cur, sPlay, sizeof(sPlay)) &&
            NextPipeToken(&cur, sPos, sizeof(sPos)) &&
            NextPipeToken(&cur, sYt, sizeof(sYt))) {
            const char *uTrack = cur ? cur : "";
            if (g_jam.state != JAM_STATE_NONE && strcmp(g_jam.jamId, jamId) == 0) {
                wchar_t wTag[32] = {0};
                MultiByteToWideChar(CP_UTF8, 0, fTag, -1, wTag, 31);
                if (!wTag[0] || _wcsicmp(wTag, myTag) == 0) return;

                UpsertMember(wTag, NULL);
                g_jam.state = JAM_STATE_ACTIVE;
                wcsncpy(g_jam.lastStateSenderTag, wTag, 31);

                JamRemoteSync rs = {0};
                rs.valid = TRUE;
                rs.posOnly = FALSE;
                rs.isPlaying = (atoi(sPlay) != 0);
                rs.playFlag = rs.isPlaying ? 1 : 0;
                rs.posMs = atoi(sPos);
                if (rs.posMs < 0) rs.posMs = 0;
                rs.recvTick = GetTickCount();
                wcsncpy(rs.senderTag, wTag, 31);
                MultiByteToWideChar(CP_UTF8, 0, sYt, -1, rs.ytId, 15);
                MultiByteToWideChar(CP_UTF8, 0, uTrack, -1, rs.trackTitle, 255);
                g_jam.pendingRemoteSync = rs;
            }
        }
        return;
    }

    // 8. JAM_POS|fromTag|jamId|playFlag(-1=seekOnly,0=pause,1=play)|posMs
    if (strncmp(payload, "JAM_POS|", 8) == 0) {
        const char *cur = payload + 8;
        char fTag[64] = {0}, jamId[64] = {0}, sFlag[16] = {0};
        if (NextPipeToken(&cur, fTag, sizeof(fTag)) &&
            NextPipeToken(&cur, jamId, sizeof(jamId)) &&
            NextPipeToken(&cur, sFlag, sizeof(sFlag)) &&
            cur != NULL) {
            if (g_jam.state != JAM_STATE_NONE && strcmp(g_jam.jamId, jamId) == 0) {
                wchar_t wTag[32] = {0};
                MultiByteToWideChar(CP_UTF8, 0, fTag, -1, wTag, 31);
                if (!wTag[0] || _wcsicmp(wTag, myTag) == 0) return;

                UpsertMember(wTag, NULL);
                g_jam.state = JAM_STATE_ACTIVE;
                wcsncpy(g_jam.lastStateSenderTag, wTag, 31);

                int flag = atoi(sFlag);
                JamRemoteSync rs = {0};
                rs.valid = TRUE;
                rs.posOnly = TRUE;
                rs.playFlag = flag;
                rs.isPlaying = (flag == 1);
                rs.posMs = atoi(cur);
                if (rs.posMs < 0) rs.posMs = 0;
                rs.recvTick = GetTickCount();
                wcsncpy(rs.senderTag, wTag, 31);
                g_jam.pendingRemoteSync = rs;
            }
        }
    }
}
