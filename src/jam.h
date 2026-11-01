#ifndef JAM_H
#define JAM_H

#include <winsock2.h>
#include <windows.h>

#define MAX_JAM_MEMBERS 64

typedef enum {
    JAM_STATE_NONE = 0,
    JAM_STATE_INVITING = 1,
    JAM_STATE_ACTIVE = 2
} JamState;

typedef struct {
    wchar_t tag[32];
    wchar_t name[64];
} JamMember;

typedef struct {
    BOOL    valid;
    BOOL    isPlaying;
    BOOL    posOnly;      // TRUE ise yalnizca duraklatma/devam/sarma guncellemesi (sarki degismedi)
    int     playFlag;     // posOnly iken: -1 = yalnizca sarma (seek), 0 = duraklat, 1 = devam et
    int     posMs;        // Milisaniye hassasiyetinde konum!
    DWORD   recvTick;     // Paketin alindigi anlik GetTickCount()
    wchar_t senderTag[32];
    wchar_t ytId[16];
    wchar_t trackTitle[256];
} JamRemoteSync;

void     Jam_Init(void);
JamState Jam_GetState(void);
BOOL     Jam_IsActive(void);
void     Jam_CheckTimeouts(void);

int      Jam_GetMemberCount(void);
BOOL     Jam_IsFriendInJam(const wchar_t *friendTag);
void     Jam_FormatBannerHeadline(wchar_t *buf, int maxLen);
void     Jam_GetPartnerName(wchar_t *buf, int maxLen);
void     Jam_GetPartnerTag(wchar_t *buf, int maxLen);

void     Jam_SetSyncStatus(const wchar_t *statusText, BOOL isDownloading, const wchar_t *missingTrack);
void     Jam_GetSyncStatus(wchar_t *buf, int maxLen);
BOOL     Jam_IsDownloadingTrack(void);
void     Jam_GetMissingTrack(wchar_t *buf, int maxLen);

BOOL     Jam_HasPendingInvite(void);
void     Jam_GetPendingInviteInfo(wchar_t *nameBuf, wchar_t *tagBuf);

// Sinirsiz katilimcili Jam: Aktif odadayken de yeni arkadaslari ayni odaya davet eder!
void     Jam_InviteFriend(const wchar_t *friendTag, const wchar_t *friendName);
void     Jam_AcceptInvite(void);
void     Jam_RejectInvite(void);
void     Jam_Leave(void);

// Karsidan gelen senkronizasyon olaylarini ve tekil durum isteklerini okur
BOOL     Jam_PollRemoteSync(JamRemoteSync *outSync);
BOOL     Jam_PollStateRequestTarget(wchar_t *outTargetTag, int maxLen);

// Ag mesajlarini isler (hem kisisel kutu hem seloplayer/jam/<jamId> odasi)
void     Jam_HandleNetworkMessage(const char *topic, const char *payload);

// Odaya veya yeni katilan/indirmesi biten tekil katilimciya milisaniye hassasiyetinde durum gonderir
void     Jam_BroadcastState(BOOL isPlaying, const wchar_t *trackTitle, const wchar_t *ytId, int posMs);
void     Jam_SendStateToPeer(const wchar_t *targetTag, BOOL isPlaying, const wchar_t *trackTitle, const wchar_t *ytId, int posMs);
void     Jam_BroadcastPause(int posMs);
void     Jam_BroadcastResume(int posMs);
void     Jam_BroadcastSeek(int posMs);
void     Jam_RequestPartnerStateNow(void);

#endif // JAM_H
