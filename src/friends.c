#include "friends.h"
#include "network.h"
#include <shlobj.h>
#include <shlwapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define FRIENDS_FILE L"friends.json"

static wchar_t g_myTag[32] = {0};
static wchar_t g_myName[64] = {0};
static wchar_t g_myFixedId[16] = {0};

static FriendItem g_friends[MAX_FRIENDS];
static int g_friendCount = 0;

static FriendRequest g_pending[MAX_REQUESTS];
static int g_pendingCount = 0;

static BOOL    g_localIsPlaying = FALSE;
static wchar_t g_localTrack[128] = {0};

static wchar_t g_lastProbedTag[64] = {0};
static wchar_t g_resolvedProbeTag[32] = {0};
static char    g_lastProbeRegTopic[128] = {0};
static BOOL    g_lastProbedFound = FALSE;

static void SendPingToTag(const wchar_t *targetTag);
static void SendPongToTag(const wchar_t *targetTag);

static void GetFriendsFilePath(wchar_t *outPath, int maxLen) {
    GetModuleFileNameW(NULL, outPath, maxLen);
    PathRemoveFileSpecW(outPath);
    PathAppendW(outPath, FRIENDS_FILE);
}

static BOOL GetAppDataIdentityPath(wchar_t *outPath, int maxLen) {
    if (!outPath || maxLen < MAX_PATH) return FALSE;
    wchar_t appData[MAX_PATH] = {0};
    if (!GetEnvironmentVariableW(L"APPDATA", appData, MAX_PATH - 1) || !appData[0]) {
        return FALSE;
    }
    _snwprintf(outPath, maxLen - 1, L"%s\\Aurns", appData);
    outPath[maxLen - 1] = L'\0';
    CreateDirectoryW(outPath, NULL);
    PathAppendW(outPath, L"identity.tag");
    return TRUE;
}

static void SavePersistentIdentityBackup(void) {
    wchar_t idPath[MAX_PATH] = {0};
    if (!GetAppDataIdentityPath(idPath, MAX_PATH)) return;
    FILE *f = _wfopen(idPath, L"wb");
    if (!f) return;

    char uId[32] = {0}, uName[128] = {0}, uTag[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myFixedId, -1, uId, sizeof(uId) - 1, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myName, -1, uName, sizeof(uName) - 1, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uTag, sizeof(uTag) - 1, NULL, NULL);
    fprintf(f, "%s\n%s\n%s\n", uId, uName, uTag);
    fclose(f);
}

static BOOL LoadPersistentIdentityBackup(wchar_t *outId, int idMax, wchar_t *outName, int nameMax) {
    wchar_t idPath[MAX_PATH] = {0};
    if (!GetAppDataIdentityPath(idPath, MAX_PATH)) return FALSE;
    FILE *f = _wfopen(idPath, L"rb");
    if (!f) return FALSE;

    char line1[64] = {0}, line2[128] = {0};
    BOOL ok = FALSE;
    if (fgets(line1, sizeof(line1) - 1, f)) {
        for (int i = 0; line1[i]; i++) {
            if (line1[i] == '\r' || line1[i] == '\n') { line1[i] = '\0'; break; }
        }
        if (fgets(line2, sizeof(line2) - 1, f)) {
            for (int i = 0; line2[i]; i++) {
                if (line2[i] == '\r' || line2[i] == '\n') { line2[i] = '\0'; break; }
            }
        }
        if (line1[0] >= '0' && line1[0] <= '9') {
            MultiByteToWideChar(CP_UTF8, 0, line1, -1, outId, idMax - 1);
            if (line2[0]) {
                MultiByteToWideChar(CP_UTF8, 0, line2, -1, outName, nameMax - 1);
            }
            ok = TRUE;
        }
    }
    fclose(f);
    return ok;
}

static void SanitizePipeUtf8(char *s) {
    if (!s) return;
    for (int i = 0; s[i]; i++) {
        if (s[i] == '|' || s[i] == '\r' || s[i] == '\n') s[i] = '-';
    }
}

static void ComputeHardwareFixedId(wchar_t *outId, int maxLen) {
    DWORD volSerial = 0;
    GetVolumeInformationW(L"C:\\", NULL, 0, &volSerial, NULL, NULL, NULL, 0);
    wchar_t compName[MAX_COMPUTERNAME_LENGTH + 1] = {0};
    DWORD cSize = MAX_COMPUTERNAME_LENGTH + 1;
    GetComputerNameW(compName, &cSize);

    unsigned int seed = (volSerial ? volSerial : 0x5E1100u);
    for (int i = 0; compName[i]; i++) {
        seed = seed * 31 + compName[i];
    }
    int num = 1000 + ((int)(seed % 9000));
    _snwprintf(outId, maxLen - 1, L"%04d", num);
    outId[maxLen - 1] = L'\0';
}

static void CleanDisplayNameW(const wchar_t *src, wchar_t *out, int maxChars) {
    if (!out || maxChars <= 0) return;
    out[0] = L'\0';
    if (!src) return;
    int cIdx = 0;
    for (int i = 0; src[i] && cIdx < maxChars - 1; i++) {
        wchar_t ch = src[i];
        if (ch >= 32 && ch != L'#' && ch != L'\"' && ch != L'|' && ch != L'\\') {
            if (ch == L' ' && cIdx == 0) continue;
            out[cIdx++] = ch;
        }
    }
    while (cIdx > 0 && out[cIdx - 1] == L' ') cIdx--;
    out[cIdx] = L'\0';
}

static void RebuildMyTagFromNameAndId(void) {
    if (!g_myFixedId[0]) {
        ComputeHardwareFixedId(g_myFixedId, 16);
    }
    if (!g_myName[0]) {
        wchar_t user[64] = {0};
        DWORD uLen = 64;
        if (!GetUserNameW(user, &uLen) || !user[0]) {
            wcscpy(user, L"Kullanici");
        }
        CleanDisplayNameW(user, g_myName, 25);
        if (!g_myName[0]) wcscpy(g_myName, L"Kullanici");
    }
    _snwprintf(g_myTag, 31, L"%s#%s", g_myName, g_myFixedId);
    g_myTag[31] = L'\0';
}

BOOL Friends_SameUserTag(const wchar_t *tagA, const wchar_t *tagB) {
    if (!tagA || !tagB || !tagA[0] || !tagB[0]) return FALSE;
    char idA[16] = {0}, idB[16] = {0};
    Network_ExtractTagIdW(tagA, idA, sizeof(idA));
    Network_ExtractTagIdW(tagB, idB, sizeof(idB));
    if (idA[0] && idB[0] && strlen(idA) >= 3 && strlen(idB) >= 3) {
        return (strcmp(idA, idB) == 0);
    }
    return (_wcsicmp(tagA, tagB) == 0);
}

BOOL Friends_IsMyInboxTopic(const char *topic) {
    if (!topic || !topic[0]) return FALSE;

    char myId[16] = {0};
    Network_ExtractTagIdW(g_myTag, myId, sizeof(myId));
    if (myId[0]) {
        char idTopic[128];
        snprintf(idTopic, sizeof(idTopic), "seloplayer/id/%s", myId);
        if (_stricmp(topic, idTopic) == 0) return TRUE;

        char mboxTopic[128];
        snprintf(mboxTopic, sizeof(mboxTopic), "seloplayer/mbox/%s", myId);
        if (_stricmp(topic, mboxTopic) == 0) return TRUE;
    }

    char myKey[64] = {0};
    Network_TagToTopicKeyW(g_myTag, myKey, sizeof(myKey));
    if (myKey[0]) {
        char legacyTopic[160];
        snprintf(legacyTopic, sizeof(legacyTopic), "seloplayer/u/%s", myKey);
        if (_stricmp(topic, legacyTopic) == 0) return TRUE;
    }
    return FALSE;
}

void Friends_PublishToPeer(const wchar_t *peerTag, const char *payload) {
    if (!peerTag || !peerTag[0] || !payload) return;

    char peerId[16] = {0};
    Network_ExtractTagIdW(peerTag, peerId, sizeof(peerId));
    if (peerId[0]) {
        char idTopic[128];
        snprintf(idTopic, sizeof(idTopic), "seloplayer/id/%s", peerId);
        Network_Publish(idTopic, payload);
    }

    // Yalnizca isim kismi da varsa geriye donuk seloplayer/u/<key> kanalina da gonder
    const wchar_t *hash = wcschr(peerTag, L'#');
    if (!hash || hash > peerTag) {
        char peerKey[64] = {0};
        Network_TagToTopicKeyW(peerTag, peerKey, sizeof(peerKey));
        if (peerKey[0]) {
            char legacyTopic[160];
            snprintf(legacyTopic, sizeof(legacyTopic), "seloplayer/u/%s", peerKey);
            Network_Publish(legacyTopic, payload);
        }
    }
}

static void PublishMyDirectoryRecord(void) {
    char myId[16] = {0};
    Network_ExtractTagIdW(g_myTag, myId, sizeof(myId));
    if (!myId[0]) return;

    char uMyTag[128] = {0}, uMyName[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uMyTag, sizeof(uMyTag) - 1, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myName, -1, uMyName, sizeof(uMyName) - 1, NULL, NULL);
    SanitizePipeUtf8(uMyTag);
    SanitizePipeUtf8(uMyName);

    char regTopic[128];
    snprintf(regTopic, sizeof(regTopic), "seloplayer/reg/%s", myId);
    char regPayload[320];
    snprintf(regPayload, sizeof(regPayload), "REG|%s|%s", uMyTag, uMyName);
    Network_PublishEx(regTopic, regPayload, TRUE);
}

void Friends_SubscribeMyChannels(void) {
    char myId[16] = {0};
    Network_ExtractTagIdW(g_myTag, myId, sizeof(myId));
    if (myId[0]) {
        char idTopic[128];
        snprintf(idTopic, sizeof(idTopic), "seloplayer/id/%s", myId);
        Network_Subscribe(idTopic);

        char mboxTopic[128];
        snprintf(mboxTopic, sizeof(mboxTopic), "seloplayer/mbox/%s", myId);
        Network_Subscribe(mboxTopic);
    }

    char myKey[64] = {0};
    Network_TagToTopicKeyW(g_myTag, myKey, sizeof(myKey));
    if (myKey[0]) {
        char legacyTopic[160];
        snprintf(legacyTopic, sizeof(legacyTopic), "seloplayer/u/%s", myKey);
        Network_Subscribe(legacyTopic);
    }
}

void Friends_OnNetworkConnected(void) {
    Friends_SubscribeMyChannels();
    PublishMyDirectoryRecord();
}

void Friends_Init(void) {
    g_friendCount = 0;
    g_pendingCount = 0;
    g_myTag[0] = L'\0';
    g_myName[0] = L'\0';
    g_myFixedId[0] = L'\0';
    memset(g_friends, 0, sizeof(g_friends));
    memset(g_pending, 0, sizeof(g_pending));

    wchar_t backupId[16] = {0}, backupName[64] = {0};
    BOOL hasBackup = LoadPersistentIdentityBackup(backupId, 16, backupName, 64);

    wchar_t filePath[MAX_PATH];
    GetFriendsFilePath(filePath, MAX_PATH);

    FILE *f = _wfopen(filePath, L"rb");
    if (!f) f = _wfopen(FRIENDS_FILE, L"rb");
    if (!f) {
        if (hasBackup) {
            wcsncpy(g_myFixedId, backupId, 15);
            CleanDisplayNameW(backupName, g_myName, 25);
        }
        RebuildMyTagFromNameAndId();
        Friends_Save();
        return;
    }

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (sz <= 0 || sz > 65536) {
        fclose(f);
        if (hasBackup) {
            wcsncpy(g_myFixedId, backupId, 15);
            CleanDisplayNameW(backupName, g_myName, 25);
        }
        RebuildMyTagFromNameAndId();
        Friends_Save();
        return;
    }

    char *buf = (char*)malloc(sz + 1);
    if (!buf) {
        fclose(f);
        RebuildMyTagFromNameAndId();
        return;
    }
    size_t got = fread(buf, 1, sz, f);
    buf[got] = '\0';
    fclose(f);

    char *pTag = strstr(buf, "\"my_tag\"");
    if (pTag) {
        char *col = strchr(pTag, ':');
        char *q1 = col ? strchr(col, '\"') : NULL;
        char *q2 = q1 ? strchr(q1 + 1, '\"') : NULL;
        if (q2) {
            *q2 = '\0';
            MultiByteToWideChar(CP_UTF8, 0, q1 + 1, -1, g_myTag, 31);
            *q2 = '\"';
        }
    }

    char *pName = strstr(buf, "\"my_name\"");
    if (pName) {
        char *col = strchr(pName, ':');
        char *q1 = col ? strchr(col, '\"') : NULL;
        char *q2 = q1 ? strchr(q1 + 1, '\"') : NULL;
        if (q2) {
            *q2 = '\0';
            MultiByteToWideChar(CP_UTF8, 0, q1 + 1, -1, g_myName, 63);
            *q2 = '\"';
        }
    }

    // Sabit etiket numarasini kilitle: varsa mevcut dosyadan/yedekten al, yoksa donanimdan uret
    char parsedId[16] = {0};
    Network_ExtractTagIdW(g_myTag, parsedId, sizeof(parsedId));
    if (hasBackup && backupId[0]) {
        // Eger friends.json icinde zaten gecerli bir #numara varsa onu koru, yoksa yedegi kullan
        if (parsedId[0]) {
            MultiByteToWideChar(CP_UTF8, 0, parsedId, -1, g_myFixedId, 15);
        } else {
            wcsncpy(g_myFixedId, backupId, 15);
        }
        if (!g_myName[0] && backupName[0]) {
            CleanDisplayNameW(backupName, g_myName, 25);
        }
    } else if (parsedId[0]) {
        MultiByteToWideChar(CP_UTF8, 0, parsedId, -1, g_myFixedId, 15);
    }

    if (!g_myName[0] && g_myTag[0]) {
        wchar_t tmpName[64] = {0};
        wcsncpy(tmpName, g_myTag, 63);
        wchar_t *hp = wcschr(tmpName, L'#');
        if (hp) *hp = L'\0';
        CleanDisplayNameW(tmpName, g_myName, 25);
    }
    RebuildMyTagFromNameAndId();

    char *pFriends = strstr(buf, "\"friends\"");
    if (pFriends) {
        char *openB = strchr(pFriends, '[');
        char *closeB = openB ? strchr(openB, ']') : NULL;
        if (openB && closeB) {
            char *cur = openB;
            while (cur < closeB && g_friendCount < MAX_FRIENDS) {
                char *obj = strchr(cur, '{');
                if (!obj || obj > closeB) break;
                char *objEnd = strchr(obj, '}');
                if (!objEnd || objEnd > closeB) break;

                char saved = *objEnd;
                *objEnd = '\0';

                wchar_t fTag[32] = {0};
                wchar_t fName[64] = {0};

                char *kTag = strstr(obj, "\"tag\"");
                if (kTag) {
                    char *c = strchr(kTag, ':');
                    char *q1 = c ? strchr(c, '\"') : NULL;
                    char *q2 = q1 ? strchr(q1 + 1, '\"') : NULL;
                    if (q2) {
                        *q2 = '\0';
                        MultiByteToWideChar(CP_UTF8, 0, q1 + 1, -1, fTag, 31);
                        *q2 = '\"';
                    }
                }

                char *kName = strstr(obj, "\"name\"");
                if (kName) {
                    char *c = strchr(kName, ':');
                    char *q1 = c ? strchr(c, '\"') : NULL;
                    char *q2 = q1 ? strchr(q1 + 1, '\"') : NULL;
                    if (q2) {
                        *q2 = '\0';
                        MultiByteToWideChar(CP_UTF8, 0, q1 + 1, -1, fName, 63);
                        *q2 = '\"';
                    }
                }

                *objEnd = saved;
                if (fTag[0]) {
                    wcsncpy(g_friends[g_friendCount].tag, fTag, 31);
                    wcsncpy(g_friends[g_friendCount].name, fName[0] ? fName : fTag, 63);
                    g_friends[g_friendCount].isOnline = FALSE;
                    g_friends[g_friendCount].isPlaying = FALSE;
                    wcscpy(g_friends[g_friendCount].currentTrack, L"Çevrimdışı");
                    g_friendCount++;
                }
                cur = objEnd + 1;
            }
        }
    }

    char *pPending = strstr(buf, "\"pending\"");
    if (pPending) {
        char *openB = strchr(pPending, '[');
        char *closeB = openB ? strchr(openB, ']') : NULL;
        if (openB && closeB) {
            char *cur = openB;
            while (cur < closeB && g_pendingCount < MAX_REQUESTS) {
                char *obj = strchr(cur, '{');
                if (!obj || obj > closeB) break;
                char *objEnd = strchr(obj, '}');
                if (!objEnd || objEnd > closeB) break;

                char saved = *objEnd;
                *objEnd = '\0';

                wchar_t rTag[32] = {0};
                wchar_t rName[64] = {0};

                char *kTag = strstr(obj, "\"tag\"");
                if (kTag) {
                    char *c = strchr(kTag, ':');
                    char *q1 = c ? strchr(c, '\"') : NULL;
                    char *q2 = q1 ? strchr(q1 + 1, '\"') : NULL;
                    if (q2) {
                        *q2 = '\0';
                        MultiByteToWideChar(CP_UTF8, 0, q1 + 1, -1, rTag, 31);
                        *q2 = '\"';
                    }
                }

                char *kName = strstr(obj, "\"name\"");
                if (kName) {
                    char *c = strchr(kName, ':');
                    char *q1 = c ? strchr(c, '\"') : NULL;
                    char *q2 = q1 ? strchr(q1 + 1, '\"') : NULL;
                    if (q2) {
                        *q2 = '\0';
                        MultiByteToWideChar(CP_UTF8, 0, q1 + 1, -1, rName, 63);
                        *q2 = '\"';
                    }
                }

                *objEnd = saved;
                if (rTag[0]) {
                    wcsncpy(g_pending[g_pendingCount].tag, rTag, 31);
                    wcsncpy(g_pending[g_pendingCount].name, rName[0] ? rName : rTag, 63);
                    g_pendingCount++;
                }
                cur = objEnd + 1;
            }
        }
    }

    free(buf);
    SavePersistentIdentityBackup();
}

void Friends_Save(void) {
    SavePersistentIdentityBackup();

    wchar_t filePath[MAX_PATH];
    GetFriendsFilePath(filePath, MAX_PATH);

    FILE *f = _wfopen(filePath, L"wb");
    if (!f) f = _wfopen(FRIENDS_FILE, L"wb");
    if (!f) return;

    char uTag[128] = {0};
    char uName[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uTag, 127, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myName, -1, uName, 127, NULL, NULL);

    fprintf(f, "{\n  \"my_tag\": \"%s\",\n  \"my_name\": \"%s\",\n  \"friends\": [\n", uTag, uName);
    for (int i = 0; i < g_friendCount; i++) {
        char fTag[128] = {0};
        char fName[128] = {0};
        WideCharToMultiByte(CP_UTF8, 0, g_friends[i].tag, -1, fTag, 127, NULL, NULL);
        WideCharToMultiByte(CP_UTF8, 0, g_friends[i].name, -1, fName, 127, NULL, NULL);
        fprintf(f, "    { \"tag\": \"%s\", \"name\": \"%s\" }%s\n", fTag, fName, (i == g_friendCount - 1) ? "" : ",");
    }
    fprintf(f, "  ],\n  \"pending\": [\n");
    for (int i = 0; i < g_pendingCount; i++) {
        char rTag[128] = {0};
        char rName[128] = {0};
        WideCharToMultiByte(CP_UTF8, 0, g_pending[i].tag, -1, rTag, 127, NULL, NULL);
        WideCharToMultiByte(CP_UTF8, 0, g_pending[i].name, -1, rName, 127, NULL, NULL);
        fprintf(f, "    { \"tag\": \"%s\", \"name\": \"%s\" }%s\n", rTag, rName, (i == g_pendingCount - 1) ? "" : ",");
    }
    fprintf(f, "  ]\n}\n");

    fclose(f);
}

void Friends_GetMyTag(wchar_t *buf, int maxLen) {
    if (buf && maxLen > 0) {
        wcsncpy(buf, g_myTag, maxLen - 1);
        buf[maxLen - 1] = L'\0';
    }
}

void Friends_GetMyName(wchar_t *buf, int maxLen) {
    if (buf && maxLen > 0) {
        wcsncpy(buf, g_myName, maxLen - 1);
        buf[maxLen - 1] = L'\0';
    }
}

void Friends_SetMyName(const wchar_t *newName) {
    if (!newName || !newName[0]) return;
    wchar_t clean[64] = {0};
    CleanDisplayNameW(newName, clean, 25);
    if (!clean[0]) return;

    char myOldKey[64] = {0};
    if (g_myTag[0]) {
        Network_TagToTopicKeyW(g_myTag, myOldKey, sizeof(myOldKey));
    }

    // Yalnizca kullanici adi degisir; sabit #etiket numarasi (g_myFixedId) ASLA degismez!
    wcsncpy(g_myName, clean, 63);
    g_myName[63] = L'\0';
    RebuildMyTagFromNameAndId();
    Friends_Save();

    char myNewKey[64] = {0};
    Network_TagToTopicKeyW(g_myTag, myNewKey, sizeof(myNewKey));
    if (myOldKey[0] && _stricmp(myOldKey, myNewKey) != 0) {
        char oldInbox[160];
        snprintf(oldInbox, sizeof(oldInbox), "seloplayer/u/%s", myOldKey);
        Network_Unsubscribe(oldInbox);
    }

    Friends_SubscribeMyChannels();
    PublishMyDirectoryRecord();

    // Arkadaslarimiza yeni ismimizi aninda bildir
    for (int i = 0; i < g_friendCount; i++) {
        SendPongToTag(g_friends[i].tag);
    }
    Friends_RequestAllStatuses();
}

void Friends_CancelProbe(void) {
    if (g_lastProbeRegTopic[0]) {
        Network_Unsubscribe(g_lastProbeRegTopic);
        g_lastProbeRegTopic[0] = '\0';
    }
}

void Friends_ProbeUser(const wchar_t *targetTag) {
    Friends_CancelProbe();
    if (!targetTag || !targetTag[0]) return;

    wcsncpy(g_lastProbedTag, targetTag, 63);
    g_lastProbedTag[63] = L'\0';
    g_resolvedProbeTag[0] = L'\0';
    g_lastProbedFound = FALSE;

    char targetId[16] = {0};
    Network_ExtractTagIdW(targetTag, targetId, sizeof(targetId));
    if (targetId[0]) {
        snprintf(g_lastProbeRegTopic, sizeof(g_lastProbeRegTopic), "seloplayer/reg/%s", targetId);
        Network_Subscribe(g_lastProbeRegTopic);
    }

    SendPingToTag(targetTag);
}

BOOL Friends_CheckUserFound(const wchar_t *targetTag) {
    if (!targetTag || !targetTag[0]) return FALSE;
    if (_wcsicmp(g_lastProbedTag, targetTag) == 0 && g_lastProbedFound) {
        return TRUE;
    }
    return FALSE;
}

int Friends_GetFriendCount(void) {
    return g_friendCount;
}

const FriendItem* Friends_GetFriend(int index) {
    if (index >= 0 && index < g_friendCount) return &g_friends[index];
    return NULL;
}

int Friends_GetPendingCount(void) {
    return g_pendingCount;
}

const FriendRequest* Friends_GetPending(int index) {
    if (index >= 0 && index < g_pendingCount) return &g_pending[index];
    return NULL;
}

void Friends_SetLocalTrackState(BOOL isPlaying, const wchar_t *trackTitle) {
    g_localIsPlaying = isPlaying;
    if (trackTitle && trackTitle[0]) {
        wcsncpy(g_localTrack, trackTitle, 127);
        g_localTrack[127] = L'\0';
    } else if (!isPlaying) {
        g_localTrack[0] = L'\0';
    }
}

static void SendPongToTag(const wchar_t *targetTag) {
    if (!targetTag || !targetTag[0]) return;

    char uMyTag[128] = {0};
    char uMyName[128] = {0};
    char uTrack[192] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uMyTag, 127, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myName, -1, uMyName, 127, NULL, NULL);
    SanitizePipeUtf8(uMyTag);
    SanitizePipeUtf8(uMyName);
    if (g_localIsPlaying && g_localTrack[0]) {
        WideCharToMultiByte(CP_UTF8, 0, g_localTrack, -1, uTrack, 191, NULL, NULL);
        SanitizePipeUtf8(uTrack);
    }

    char payload[512];
    snprintf(payload, sizeof(payload), "PONG|%s|%s|%d|%s", uMyTag, uMyName, g_localIsPlaying ? 1 : 0, uTrack);
    Friends_PublishToPeer(targetTag, payload);
}

static void SendPingToTag(const wchar_t *targetTag) {
    if (!targetTag || !targetTag[0]) return;

    char uMyTag[128] = {0};
    char uMyName[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uMyTag, 127, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myName, -1, uMyName, 127, NULL, NULL);
    SanitizePipeUtf8(uMyTag);
    SanitizePipeUtf8(uMyName);

    char payload[256];
    snprintf(payload, sizeof(payload), "PING|%s|%s", uMyTag, uMyName);
    Friends_PublishToPeer(targetTag, payload);
}

void Friends_RequestAllStatuses(void) {
    for (int i = 0; i < g_friendCount; i++) {
        SendPingToTag(g_friends[i].tag);
    }
}

void Friends_NotifyOffline(void) {
    if (g_friendCount <= 0) return;

    char uMyTag[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uMyTag, 127, NULL, NULL);
    SanitizePipeUtf8(uMyTag);

    char payload[160];
    snprintf(payload, sizeof(payload), "BYE|%s", uMyTag);

    for (int i = 0; i < g_friendCount; i++) {
        Friends_PublishToPeer(g_friends[i].tag, payload);
    }
}

BOOL Friends_SendRequest(const wchar_t *targetTag) {
    Friends_CancelProbe();
    const wchar_t *effectiveTarget = g_resolvedProbeTag[0] ? g_resolvedProbeTag : targetTag;
    if (!effectiveTarget || !effectiveTarget[0]) return FALSE;

    wchar_t cleanTarget[32] = {0};
    wcsncpy(cleanTarget, effectiveTarget, 31);
    StrTrimW(cleanTarget, L" \t\r\n");

    char targetId[16] = {0};
    Network_ExtractTagIdW(cleanTarget, targetId, sizeof(targetId));
    if (!targetId[0] && !wcschr(cleanTarget, L'#')) return FALSE;

    if (Friends_SameUserTag(cleanTarget, g_myTag)) return FALSE;

    for (int i = 0; i < g_friendCount; i++) {
        if (Friends_SameUserTag(g_friends[i].tag, cleanTarget)) return FALSE;
    }

    char uMyTag[128] = {0};
    char uMyName[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uMyTag, 127, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myName, -1, uMyName, 127, NULL, NULL);
    SanitizePipeUtf8(uMyTag);
    SanitizePipeUtf8(uMyName);

    char payload[512];
    snprintf(payload, sizeof(payload), "REQ|%s|%s", uMyTag, uMyName);

    Friends_PublishToPeer(cleanTarget, payload);
    if (targetId[0]) {
        char mboxTopic[128];
        snprintf(mboxTopic, sizeof(mboxTopic), "seloplayer/mbox/%s", targetId);
        Network_PublishEx(mboxTopic, payload, TRUE);
    }
    return TRUE;
}

BOOL Friends_AcceptRequest(const wchar_t *targetTag) {
    int foundIdx = -1;
    for (int i = 0; i < g_pendingCount; i++) {
        if (Friends_SameUserTag(g_pending[i].tag, targetTag)) {
            foundIdx = i;
            break;
        }
    }
    if (foundIdx < 0) return FALSE;

    wchar_t acceptedTag[32] = {0};
    wcsncpy(acceptedTag, g_pending[foundIdx].tag, 31);

    BOOL alreadyFriend = FALSE;
    for (int i = 0; i < g_friendCount; i++) {
        if (Friends_SameUserTag(g_friends[i].tag, acceptedTag)) {
            alreadyFriend = TRUE;
            g_friends[i].isOnline = TRUE;
            g_friends[i].lastSeenTick = GetTickCount();
            break;
        }
    }

    if (!alreadyFriend && g_friendCount < MAX_FRIENDS) {
        wcsncpy(g_friends[g_friendCount].tag, g_pending[foundIdx].tag, 31);
        wcsncpy(g_friends[g_friendCount].name, g_pending[foundIdx].name, 63);
        g_friends[g_friendCount].isOnline = TRUE;
        g_friends[g_friendCount].isPlaying = FALSE;
        wcscpy(g_friends[g_friendCount].currentTrack, L"Çevrimiçi");
        g_friends[g_friendCount].lastSeenTick = GetTickCount();
        g_friendCount++;
    }

    char uMyTag[128] = {0};
    char uMyName[128] = {0};
    WideCharToMultiByte(CP_UTF8, 0, g_myTag, -1, uMyTag, 127, NULL, NULL);
    WideCharToMultiByte(CP_UTF8, 0, g_myName, -1, uMyName, 127, NULL, NULL);
    SanitizePipeUtf8(uMyTag);
    SanitizePipeUtf8(uMyName);

    char payload[512];
    snprintf(payload, sizeof(payload), "ACC|%s|%s", uMyTag, uMyName);
    Friends_PublishToPeer(acceptedTag, payload);

    char accId[16] = {0};
    Network_ExtractTagIdW(acceptedTag, accId, sizeof(accId));
    if (accId[0]) {
        char mboxTopic[128];
        snprintf(mboxTopic, sizeof(mboxTopic), "seloplayer/mbox/%s", accId);
        Network_PublishEx(mboxTopic, payload, TRUE);
    }

    SendPongToTag(acceptedTag);
    SendPingToTag(acceptedTag);

    for (int i = foundIdx; i < g_pendingCount - 1; i++) {
        g_pending[i] = g_pending[i + 1];
    }
    g_pendingCount--;

    Friends_Save();
    return TRUE;
}

BOOL Friends_RejectRequest(const wchar_t *targetTag) {
    int foundIdx = -1;
    for (int i = 0; i < g_pendingCount; i++) {
        if (Friends_SameUserTag(g_pending[i].tag, targetTag)) {
            foundIdx = i;
            break;
        }
    }
    if (foundIdx < 0) return FALSE;

    for (int i = foundIdx; i < g_pendingCount - 1; i++) {
        g_pending[i] = g_pending[i + 1];
    }
    g_pendingCount--;

    Friends_Save();
    return TRUE;
}

BOOL Friends_Remove(const wchar_t *targetTag) {
    int foundIdx = -1;
    for (int i = 0; i < g_friendCount; i++) {
        if (Friends_SameUserTag(g_friends[i].tag, targetTag)) {
            foundIdx = i;
            break;
        }
    }
    if (foundIdx < 0) return FALSE;

    for (int i = foundIdx; i < g_friendCount - 1; i++) {
        g_friends[i] = g_friends[i + 1];
    }
    g_friendCount--;

    Friends_Save();
    return TRUE;
}

static int FindFriendIndexByTagW(const wchar_t *wTag) {
    for (int i = 0; i < g_friendCount; i++) {
        if (Friends_SameUserTag(g_friends[i].tag, wTag)) return i;
    }
    return -1;
}

void Friends_HandleNetworkMessage(const char *topic, const char *payload) {
    if (!topic || !payload || !payload[0]) return;

    // 0. Kullanici arama dizini yaniti: seloplayer/reg/<id> -> REG|realTag|realName
    if (g_lastProbeRegTopic[0] && _stricmp(topic, g_lastProbeRegTopic) == 0) {
        if (strncmp(payload, "REG|", 4) == 0) {
            const char *p1 = payload + 4;
            const char *p2 = strchr(p1, '|');
            char tag[64] = {0};
            if (p2) {
                int tLen = (int)(p2 - p1);
                if (tLen > 63) tLen = 63;
                strncpy(tag, p1, tLen);
            } else {
                strncpy(tag, p1, 63);
            }
            wchar_t wTag[32] = {0};
            MultiByteToWideChar(CP_UTF8, 0, tag, -1, wTag, 31);
            if (wTag[0] && g_lastProbedTag[0] && Friends_SameUserTag(g_lastProbedTag, wTag)) {
                wcsncpy(g_resolvedProbeTag, wTag, 31);
                g_resolvedProbeTag[31] = L'\0';
                g_lastProbedFound = TRUE;
                Friends_CancelProbe();
            }
        }
        return;
    }

    if (!Friends_IsMyInboxTopic(topic)) return;

    // Eger kalici posta kutusundan (seloplayer/mbox/<myId>) geldiyse okuduktan sonra temizle
    char myId[16] = {0};
    Network_ExtractTagIdW(g_myTag, myId, sizeof(myId));
    char myMboxTopic[128] = {0};
    if (myId[0]) {
        snprintf(myMboxTopic, sizeof(myMboxTopic), "seloplayer/mbox/%s", myId);
        if (_stricmp(topic, myMboxTopic) == 0) {
            Network_PublishEx(myMboxTopic, "", TRUE);
        }
    }

    // 1. REQ|fromTag|fromName
    if (strncmp(payload, "REQ|", 4) == 0) {
        char tag[64] = {0};
        char name[64] = {0};
        const char *p1 = payload + 4;
        const char *p2 = strchr(p1, '|');
        if (p2) {
            int tLen = (int)(p2 - p1);
            if (tLen > 63) tLen = 63;
            strncpy(tag, p1, tLen);
            strncpy(name, p2 + 1, 63);

            wchar_t wTag[32] = {0};
            wchar_t wName[64] = {0};
            MultiByteToWideChar(CP_UTF8, 0, tag, -1, wTag, 31);
            MultiByteToWideChar(CP_UTF8, 0, name, -1, wName, 63);

            if (!wTag[0] || Friends_SameUserTag(wTag, g_myTag)) return;
            if (FindFriendIndexByTagW(wTag) >= 0) return;

            for (int i = 0; i < g_pendingCount; i++) {
                if (Friends_SameUserTag(g_pending[i].tag, wTag)) return;
            }

            if (g_pendingCount < MAX_REQUESTS) {
                wcsncpy(g_pending[g_pendingCount].tag, wTag, 31);
                wcsncpy(g_pending[g_pendingCount].name, wName[0] ? wName : wTag, 63);
                g_pendingCount++;
                Friends_Save();
            }
        }
    }
    // 2. ACC|fromTag|fromName
    else if (strncmp(payload, "ACC|", 4) == 0) {
        char tag[64] = {0};
        char name[64] = {0};
        const char *p1 = payload + 4;
        const char *p2 = strchr(p1, '|');
        if (p2) {
            int tLen = (int)(p2 - p1);
            if (tLen > 63) tLen = 63;
            strncpy(tag, p1, tLen);
            strncpy(name, p2 + 1, 63);

            wchar_t wTag[32] = {0};
            wchar_t wName[64] = {0};
            MultiByteToWideChar(CP_UTF8, 0, tag, -1, wTag, 31);
            MultiByteToWideChar(CP_UTF8, 0, name, -1, wName, 63);
            if (!wTag[0] || Friends_SameUserTag(wTag, g_myTag)) return;

            int idx = FindFriendIndexByTagW(wTag);
            if (idx < 0 && g_friendCount < MAX_FRIENDS) {
                idx = g_friendCount++;
                wcsncpy(g_friends[idx].tag, wTag, 31);
                wcsncpy(g_friends[idx].name, wName[0] ? wName : wTag, 63);
                Friends_Save();
            }
            if (idx >= 0) {
                if (wcscmp(g_friends[idx].tag, wTag) != 0 || (wName[0] && wcscmp(g_friends[idx].name, wName) != 0)) {
                    wcsncpy(g_friends[idx].tag, wTag, 31);
                    if (wName[0]) wcsncpy(g_friends[idx].name, wName, 63);
                    Friends_Save();
                }
                g_friends[idx].isOnline = TRUE;
                g_friends[idx].lastSeenTick = GetTickCount();
                if (!g_friends[idx].currentTrack[0] || wcscmp(g_friends[idx].currentTrack, L"Çevrimdışı") == 0) {
                    wcscpy(g_friends[idx].currentTrack, L"Çevrimiçi");
                }
                SendPongToTag(wTag);
            }
        }
    }
    // 3. PING|fromTag|fromName
    else if (strncmp(payload, "PING|", 5) == 0) {
        char tag[64] = {0};
        char name[64] = {0};
        const char *p1 = payload + 5;
        const char *p2 = strchr(p1, '|');
        if (p2) {
            int tLen = (int)(p2 - p1);
            if (tLen > 63) tLen = 63;
            strncpy(tag, p1, tLen);
            strncpy(name, p2 + 1, 63);
        } else {
            strncpy(tag, p1, 63);
        }

        wchar_t wTag[32] = {0};
        wchar_t wName[64] = {0};
        MultiByteToWideChar(CP_UTF8, 0, tag, -1, wTag, 31);
        MultiByteToWideChar(CP_UTF8, 0, name, -1, wName, 63);

        int idx = FindFriendIndexByTagW(wTag);
        if (idx >= 0) {
            g_friends[idx].isOnline = TRUE;
            g_friends[idx].lastSeenTick = GetTickCount();
            BOOL changed = FALSE;
            if (wTag[0] && wcscmp(g_friends[idx].tag, wTag) != 0) {
                wcsncpy(g_friends[idx].tag, wTag, 31);
                changed = TRUE;
            }
            if (wName[0] && wcscmp(g_friends[idx].name, wName) != 0) {
                wcsncpy(g_friends[idx].name, wName, 63);
                changed = TRUE;
            }
            if (changed) Friends_Save();
        }
        SendPongToTag(wTag);
    }
    // 4. PONG|fromTag|fromName|isPlaying|trackTitle
    else if (strncmp(payload, "PONG|", 5) == 0) {
        const char *p1 = payload + 5;
        const char *p2 = strchr(p1, '|');
        if (!p2) return;

        char tag[64] = {0};
        int tLen = (int)(p2 - p1);
        if (tLen > 63) tLen = 63;
        strncpy(tag, p1, tLen);

        const char *p3 = strchr(p2 + 1, '|');
        if (!p3) return;
        char name[64] = {0};
        int nLen = (int)(p3 - (p2 + 1));
        if (nLen > 63) nLen = 63;
        strncpy(name, p2 + 1, nLen);

        const char *p4 = strchr(p3 + 1, '|');
        int isPlay = atoi(p3 + 1);
        const char *uTrack = p4 ? (p4 + 1) : "";

        wchar_t wTag[32] = {0};
        wchar_t wName[64] = {0};
        MultiByteToWideChar(CP_UTF8, 0, tag, -1, wTag, 31);
        MultiByteToWideChar(CP_UTF8, 0, name, -1, wName, 63);

        if (g_lastProbedTag[0] && Friends_SameUserTag(g_lastProbedTag, wTag)) {
            wcsncpy(g_resolvedProbeTag, wTag, 31);
            g_resolvedProbeTag[31] = L'\0';
            g_lastProbedFound = TRUE;
            Friends_CancelProbe();
        }

        int idx = FindFriendIndexByTagW(wTag);
        if (idx >= 0) {
            BOOL changed = FALSE;
            if (wTag[0] && wcscmp(g_friends[idx].tag, wTag) != 0) {
                wcsncpy(g_friends[idx].tag, wTag, 31);
                changed = TRUE;
            }
            if (wName[0] && wcscmp(g_friends[idx].name, wName) != 0) {
                wcsncpy(g_friends[idx].name, wName, 63);
                changed = TRUE;
            }
            if (changed) Friends_Save();

            g_friends[idx].isOnline = TRUE;
            g_friends[idx].isPlaying = (isPlay != 0);
            g_friends[idx].lastSeenTick = GetTickCount();

            if (isPlay && uTrack[0]) {
                MultiByteToWideChar(CP_UTF8, 0, uTrack, -1, g_friends[idx].currentTrack, 127);
                g_friends[idx].currentTrack[127] = L'\0';
            } else {
                wcscpy(g_friends[idx].currentTrack, L"Çevrimiçi • Boşta");
            }
        }
    }
    // 5. BYE|fromTag
    else if (strncmp(payload, "BYE|", 4) == 0) {
        wchar_t wTag[32] = {0};
        MultiByteToWideChar(CP_UTF8, 0, payload + 4, -1, wTag, 31);
        int idx = FindFriendIndexByTagW(wTag);
        if (idx >= 0) {
            g_friends[idx].isOnline = FALSE;
            g_friends[idx].isPlaying = FALSE;
            wcscpy(g_friends[idx].currentTrack, L"Çevrimdışı");
        }
    }
}

void Friends_CheckTimeouts(void) {
    DWORD now = GetTickCount();
    for (int i = 0; i < g_friendCount; i++) {
        if (g_friends[i].isOnline) {
            if (now - g_friends[i].lastSeenTick > 32000) {
                g_friends[i].isOnline = FALSE;
                g_friends[i].isPlaying = FALSE;
                wcscpy(g_friends[i].currentTrack, L"Çevrimdışı");
            }
        }
    }
}
