#include "network.h"
#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define BROKER_COUNT    2
#define BROKER_PORT     "1883"
#define MAX_TOPICS      40
#define DEDUP_SLOTS     32
#define DEDUP_WINDOW_MS 900

typedef struct {
    const char *host;
    SOCKET      sock;
    HANDLE      hThread;
    volatile BOOL connected;
    int         slotIndex;
} BrokerSlot;

typedef struct {
    uint64_t hash;
    DWORD    tick;
} DedupEntry;

static BrokerSlot g_brokers[BROKER_COUNT] = {
    { "broker.emqx.io",   INVALID_SOCKET, NULL, FALSE, 0 },
    { "broker.hivemq.com", INVALID_SOCKET, NULL, FALSE, 1 }
};

static HWND             g_hNotifyWnd = NULL;
static char             g_clientTag[64] = {0};
static volatile BOOL    g_running = FALSE;
static CRITICAL_SECTION g_cs;

static char       g_topics[MAX_TOPICS][128];
static int        g_topicCount = 0;
static DedupEntry g_dedup[DEDUP_SLOTS];
static int        g_dedupCursor = 0;
static USHORT     g_nextPacketId = 1;

static int SendAll(SOCKET s, const char *buf, int len) {
    if (s == INVALID_SOCKET) return -1;
    int total = 0;
    while (total < len) {
        int sent = send(s, buf + total, len - total, 0);
        if (sent <= 0) return -1;
        total += sent;
    }
    return total;
}

static int RecvExact(SOCKET s, unsigned char *buf, int len) {
    int total = 0;
    while (total < len) {
        int got = recv(s, (char*)buf + total, len - total, 0);
        if (got <= 0) return -1;
        total += got;
    }
    return total;
}

static int EncodeRemainingLength(int remLen, unsigned char *out) {
    int idx = 0;
    int x = remLen;
    do {
        unsigned char b = (unsigned char)(x % 128);
        x /= 128;
        if (x > 0) b |= 0x80;
        out[idx++] = b;
    } while (x > 0 && idx < 4);
    return idx;
}

static BOOL RawConnect(SOCKET s, const char *clientId) {
    int cidLen = (int)strlen(clientId);
    if (cidLen <= 0 || cidLen > 96) return FALSE;

    unsigned char pkt[160];
    int idx = 0;
    pkt[idx++] = 0x10; // CONNECT
    int remLen = 10 + 2 + cidLen;
    idx += EncodeRemainingLength(remLen, &pkt[idx]);

    pkt[idx++] = 0x00; pkt[idx++] = 0x04;
    pkt[idx++] = 'M';  pkt[idx++] = 'Q'; pkt[idx++] = 'T'; pkt[idx++] = 'T';
    pkt[idx++] = 0x04; // MQTT 3.1.1
    pkt[idx++] = 0x02; // Clean Session
    pkt[idx++] = 0x00; pkt[idx++] = 0x1E; // 30s KeepAlive
    pkt[idx++] = (unsigned char)(cidLen >> 8);
    pkt[idx++] = (unsigned char)(cidLen & 0xFF);
    memcpy(&pkt[idx], clientId, cidLen);
    idx += cidLen;

    DWORD rcvTimeout = 4000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*)&rcvTimeout, sizeof(rcvTimeout));

    if (SendAll(s, (char*)pkt, idx) <= 0) return FALSE;

    unsigned char connack[4] = {0};
    if (RecvExact(s, connack, 4) != 4) return FALSE;

    rcvTimeout = 0;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*)&rcvTimeout, sizeof(rcvTimeout));

    return (connack[0] == 0x20 && connack[3] == 0x00);
}

static BOOL RawSendSubscribe(SOCKET s, const char *topic) {
    int topLen = (int)strlen(topic);
    if (topLen <= 0 || topLen > 200) return FALSE;
    unsigned char pkt[260];
    int idx = 0;
    pkt[idx++] = 0x82; // SUBSCRIBE
    int remLen = 2 + 2 + topLen + 1;
    idx += EncodeRemainingLength(remLen, &pkt[idx]);

    USHORT pid = g_nextPacketId++;
    if (g_nextPacketId == 0) g_nextPacketId = 1;
    pkt[idx++] = (unsigned char)(pid >> 8);
    pkt[idx++] = (unsigned char)(pid & 0xFF);
    pkt[idx++] = (unsigned char)(topLen >> 8);
    pkt[idx++] = (unsigned char)(topLen & 0xFF);
    memcpy(&pkt[idx], topic, topLen);
    idx += topLen;
    pkt[idx++] = 0x00; // QoS 0

    return (SendAll(s, (char*)pkt, idx) > 0);
}

static BOOL RawSendUnsubscribe(SOCKET s, const char *topic) {
    int topLen = (int)strlen(topic);
    if (topLen <= 0 || topLen > 200) return FALSE;
    unsigned char pkt[260];
    int idx = 0;
    pkt[idx++] = 0xA2; // UNSUBSCRIBE
    int remLen = 2 + 2 + topLen;
    idx += EncodeRemainingLength(remLen, &pkt[idx]);

    USHORT pid = g_nextPacketId++;
    if (g_nextPacketId == 0) g_nextPacketId = 1;
    pkt[idx++] = (unsigned char)(pid >> 8);
    pkt[idx++] = (unsigned char)(pid & 0xFF);
    pkt[idx++] = (unsigned char)(topLen >> 8);
    pkt[idx++] = (unsigned char)(topLen & 0xFF);
    memcpy(&pkt[idx], topic, topLen);
    idx += topLen;

    return (SendAll(s, (char*)pkt, idx) > 0);
}

static BOOL RawPublish(SOCKET s, const char *topic, const char *payload, BOOL retain) {
    int topLen = (int)strlen(topic);
    int payLen = payload ? (int)strlen(payload) : 0;
    int remLen = 2 + topLen + payLen;
    if (topLen <= 0 || remLen > 4000) return FALSE;

    unsigned char pkt[4200];
    int idx = 0;
    pkt[idx++] = retain ? 0x31 : 0x30; // PUBLISH QoS 0 (+ RETAIN if requested)
    idx += EncodeRemainingLength(remLen, &pkt[idx]);

    pkt[idx++] = (unsigned char)(topLen >> 8);
    pkt[idx++] = (unsigned char)(topLen & 0xFF);
    memcpy(&pkt[idx], topic, topLen);
    idx += topLen;
    if (payLen > 0) {
        memcpy(&pkt[idx], payload, payLen);
        idx += payLen;
    }

    return (SendAll(s, (char*)pkt, idx) > 0);
}

static uint64_t HashMessage(const char *topic, const char *payload) {
    uint64_t h = 1469598103934665603ULL;
    if (topic) {
        for (int i = 0; topic[i]; i++) {
            h ^= (unsigned char)topic[i];
            h *= 1099511628211ULL;
        }
    }
    h ^= 0xFFu;
    h *= 1099511628211ULL;
    if (payload) {
        for (int i = 0; payload[i]; i++) {
            h ^= (unsigned char)payload[i];
            h *= 1099511628211ULL;
        }
    }
    return h;
}

static BOOL ShouldDropDuplicate(const char *topic, const char *payload) {
    // Bos retained silme paketlerini veya farkli konulari engelleme, ayni mesaji 900ms pencerede tekillestir
    if (!payload || !payload[0]) return TRUE;
    uint64_t h = HashMessage(topic, payload);
    DWORD now = GetTickCount();
    BOOL isDup = FALSE;

    EnterCriticalSection(&g_cs);
    for (int i = 0; i < DEDUP_SLOTS; i++) {
        if (g_dedup[i].hash == h && g_dedup[i].tick != 0 && (now - g_dedup[i].tick) < DEDUP_WINDOW_MS) {
            isDup = TRUE;
            break;
        }
    }
    if (!isDup) {
        g_dedup[g_dedupCursor].hash = h;
        g_dedup[g_dedupCursor].tick = now ? now : 1;
        g_dedupCursor = (g_dedupCursor + 1) % DEDUP_SLOTS;
    }
    LeaveCriticalSection(&g_cs);
    return isDup;
}

static void NotifyEvent(NetEventType type, const char *topic, const char *payload) {
    if (!g_hNotifyWnd) return;
    NetEvent *evt = (NetEvent*)calloc(1, sizeof(NetEvent));
    if (!evt) return;
    evt->type = type;
    if (topic) strncpy(evt->topic, topic, sizeof(evt->topic) - 1);
    if (payload) strncpy(evt->payload, payload, sizeof(evt->payload) - 1);

    if (!PostMessageW(g_hNotifyWnd, WM_APP_NETWORK_EVENT, (WPARAM)evt, 0)) {
        free(evt);
    }
}

static SOCKET ConnectToHost(const char *host, const char *port) {
    struct addrinfo hints = {0}, *res = NULL;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    if (getaddrinfo(host, port, &hints, &res) != 0) return INVALID_SOCKET;

    SOCKET s = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (s == INVALID_SOCKET) {
        freeaddrinfo(res);
        return INVALID_SOCKET;
    }

    BOOL nodelay = TRUE;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (const char*)&nodelay, sizeof(nodelay));

    u_long mode = 1;
    ioctlsocket(s, FIONBIO, &mode);
    connect(s, res->ai_addr, (int)res->ai_addrlen);
    freeaddrinfo(res);

    fd_set writeSet, errSet;
    FD_ZERO(&writeSet);
    FD_ZERO(&errSet);
    FD_SET(s, &writeSet);
    FD_SET(s, &errSet);
    struct timeval tv = { 4, 0 };
    int sel = select(0, NULL, &writeSet, &errSet, &tv);
    if (sel <= 0 || FD_ISSET(s, &errSet) || !FD_ISSET(s, &writeSet)) {
        closesocket(s);
        return INVALID_SOCKET;
    }

    int soErr = 0;
    int optLen = sizeof(soErr);
    getsockopt(s, SOL_SOCKET, SO_ERROR, (char*)&soErr, &optLen);
    if (soErr != 0) {
        closesocket(s);
        return INVALID_SOCKET;
    }

    mode = 0;
    ioctlsocket(s, FIONBIO, &mode);
    return s;
}

static DWORD WINAPI BrokerThreadProc(LPVOID lpParam) {
    BrokerSlot *slot = (BrokerSlot*)lpParam;

    // %100 saf ASCII alfanumerik ClientId (MQTT 3.1.1 uyumluluk garantisi)
    char safeTag[24] = {0};
    int sIdx = 0;
    for (int i = 0; g_clientTag[i] && sIdx < 12; i++) {
        char c = g_clientTag[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            safeTag[sIdx++] = c;
        }
    }
    if (sIdx == 0) strcpy(safeTag, "pc");

    while (g_running) {
        char clientCid[64];
        snprintf(clientCid, sizeof(clientCid), "au%d%s%lu",
                 slot->slotIndex, safeTag, (unsigned long)(GetTickCount() % 1000000UL));

        SOCKET s = ConnectToHost(slot->host, BROKER_PORT);
        if (s == INVALID_SOCKET) {
            for (int w = 0; w < 25 && g_running; w++) Sleep(100);
            continue;
        }

        if (!RawConnect(s, clientCid)) {
            closesocket(s);
            for (int w = 0; w < 25 && g_running; w++) Sleep(100);
            continue;
        }

        EnterCriticalSection(&g_cs);
        slot->sock = s;
        slot->connected = TRUE;
        for (int i = 0; i < g_topicCount; i++) {
            RawSendSubscribe(s, g_topics[i]);
        }
        LeaveCriticalSection(&g_cs);

        NotifyEvent(NET_EVENT_CONNECTED, NULL, NULL);

        DWORD lastPing = GetTickCount();

        while (g_running) {
            fd_set readSet;
            FD_ZERO(&readSet);
            FD_SET(s, &readSet);
            struct timeval tv = { 4, 0 };

            int sel = select(0, &readSet, NULL, NULL, &tv);
            if (sel == SOCKET_ERROR) break;

            DWORD now = GetTickCount();
            if (now - lastPing >= 18000) {
                EnterCriticalSection(&g_cs);
                const char ping[2] = { (char)0xC0, 0x00 };
                int sent = SendAll(s, ping, 2);
                LeaveCriticalSection(&g_cs);
                if (sent <= 0) break;
                lastPing = now;
            }

            if (sel > 0 && FD_ISSET(s, &readSet)) {
                unsigned char header = 0;
                if (recv(s, (char*)&header, 1, 0) <= 0) break;

                int remLen = 0;
                int multiplier = 1;
                unsigned char digit = 0;
                BOOL lenOk = TRUE;
                do {
                    if (recv(s, (char*)&digit, 1, 0) <= 0) { lenOk = FALSE; break; }
                    remLen += (digit & 127) * multiplier;
                    multiplier *= 128;
                } while ((digit & 128) != 0 && multiplier <= 128 * 128 * 128);
                if (!lenOk) break;

                if ((header & 0xF0) == 0x30 && remLen >= 2 && remLen < 4000) {
                    unsigned char *buf = (unsigned char*)malloc(remLen + 1);
                    if (!buf) break;
                    if (RecvExact(s, buf, remLen) != remLen) {
                        free(buf);
                        break;
                    }

                    int tLen = (buf[0] << 8) | buf[1];
                    int qos = (header >> 1) & 0x03;
                    int pktIdBytes = (qos > 0) ? 2 : 0;

                    if (2 + tLen + pktIdBytes <= remLen && tLen < 127) {
                        char topic[128] = {0};
                        memcpy(topic, &buf[2], tLen);
                        topic[tLen] = '\0';

                        int pOffset = 2 + tLen + pktIdBytes;
                        int pLen = remLen - pOffset;
                        char payload[1024] = {0};
                        if (pLen > 0) {
                            if (pLen > 1023) pLen = 1023;
                            memcpy(payload, &buf[pOffset], pLen);
                            payload[pLen] = '\0';
                        }

                        if (!ShouldDropDuplicate(topic, payload)) {
                            NotifyEvent(NET_EVENT_MESSAGE, topic, payload);
                        }
                    }
                    free(buf);
                } else if (remLen > 0) {
                    unsigned char drain[128];
                    int left = remLen;
                    while (left > 0) {
                        int chunk = (left > (int)sizeof(drain)) ? (int)sizeof(drain) : left;
                        int got = recv(s, (char*)drain, chunk, 0);
                        if (got <= 0) { left = -1; break; }
                        left -= got;
                    }
                    if (left < 0) break;
                }
            }
        }

        BOOL anyStillConnected = FALSE;
        EnterCriticalSection(&g_cs);
        slot->connected = FALSE;
        if (slot->sock != INVALID_SOCKET) {
            closesocket(slot->sock);
            slot->sock = INVALID_SOCKET;
        }
        for (int k = 0; k < BROKER_COUNT; k++) {
            if (g_brokers[k].connected) anyStillConnected = TRUE;
        }
        LeaveCriticalSection(&g_cs);

        if (!anyStillConnected) {
            NotifyEvent(NET_EVENT_DISCONNECTED, NULL, NULL);
        }

        for (int w = 0; w < 15 && g_running; w++) Sleep(100);
    }

    return 0;
}

BOOL Network_Init(HWND hWnd, const char *clientTag) {
    if (g_running) return TRUE;

    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return FALSE;

    InitializeCriticalSection(&g_cs);
    memset(g_dedup, 0, sizeof(g_dedup));
    g_dedupCursor = 0;

    g_hNotifyWnd = hWnd;
    if (clientTag) strncpy(g_clientTag, clientTag, sizeof(g_clientTag) - 1);
    g_running = TRUE;
    g_topicCount = 0;

    BOOL anyStarted = FALSE;
    for (int i = 0; i < BROKER_COUNT; i++) {
        g_brokers[i].sock = INVALID_SOCKET;
        g_brokers[i].connected = FALSE;
        g_brokers[i].slotIndex = i;
        g_brokers[i].hThread = CreateThread(NULL, 32 * 1024, BrokerThreadProc, &g_brokers[i], STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
        if (g_brokers[i].hThread) anyStarted = TRUE;
    }
    return anyStarted;
}

// Turkce harfleri saf ASCII kucuk harfe katlayan ortak anahtar ureticisi (Android ile %100 birebir!)
void Network_TagToTopicKeyW(const wchar_t *tag, char *out, int maxLen) {
    if (!out || maxLen <= 0) return;
    out[0] = '\0';
    if (!tag) return;

    int o = 0;
    for (int i = 0; tag[i] && o < maxLen - 1; i++) {
        wchar_t wc = tag[i];
        char mapped = 0;
        switch (wc) {
            case L'ı': case L'İ': case L'I': case L'i': mapped = 'i'; break;
            case L'ş': case L'Ş': case L's': case L'S': mapped = 's'; break;
            case L'ğ': case L'Ğ': case L'g': case L'G': mapped = 'g'; break;
            case L'ü': case L'Ü': case L'u': case L'U': mapped = 'u'; break;
            case L'ö': case L'Ö': case L'o': case L'O': mapped = 'o'; break;
            case L'ç': case L'Ç': case L'c': case L'C': mapped = 'c'; break;
            default:
                if (wc >= L'A' && wc <= L'Z') mapped = (char)(wc - L'A' + 'a');
                else if ((wc >= L'a' && wc <= L'z') || (wc >= L'0' && wc <= L'9') || wc == L'-' || wc == L'_') mapped = (char)wc;
                else if (wc == L'#' || wc == L'+' || wc == L'/' || wc == L'$' || wc <= 32) mapped = '_';
                else mapped = '_';
                break;
        }
        if (mapped) out[o++] = mapped;
    }
    out[o] = '\0';
}

void Network_TagToTopicKeyA(const char *tag, char *out, int maxLen) {
    if (!out || maxLen <= 0) return;
    out[0] = '\0';
    if (!tag) return;
    wchar_t wBuf[128] = {0};
    MultiByteToWideChar(CP_UTF8, 0, tag, -1, wBuf, 127);
    Network_TagToTopicKeyW(wBuf, out, maxLen);
}

void Network_ExtractTagIdW(const wchar_t *tag, char *outId, int maxLen) {
    if (!outId || maxLen <= 0) return;
    outId[0] = '\0';
    if (!tag || !tag[0]) return;

    const wchar_t *hash = wcschr(tag, L'#');
    const wchar_t *src = hash ? (hash + 1) : tag;
    int o = 0;
    for (int i = 0; src[i] && o < maxLen - 1; i++) {
        if (src[i] >= L'0' && src[i] <= L'9') {
            outId[o++] = (char)src[i];
        } else if (o > 0) {
            break;
        }
    }
    outId[o] = '\0';
}

void Network_ExtractTagIdA(const char *tag, char *outId, int maxLen) {
    if (!outId || maxLen <= 0) return;
    outId[0] = '\0';
    if (!tag || !tag[0]) return;

    const char *hash = strchr(tag, '#');
    const char *src = hash ? (hash + 1) : tag;
    int o = 0;
    for (int i = 0; src[i] && o < maxLen - 1; i++) {
        if (src[i] >= '0' && src[i] <= '9') {
            outId[o++] = src[i];
        } else if (o > 0) {
            break;
        }
    }
    outId[o] = '\0';
}

static void SanitizeTopic(const char *in, char *out, int maxLen) {
    if (!in || !out || maxLen <= 0) return;
    int i = 0;
    for (; in[i] && i < maxLen - 1; i++) {
        char c = in[i];
        if (c == '#' || c == '+') {
            out[i] = '_';
        } else {
            out[i] = c;
        }
    }
    out[i] = '\0';
}

BOOL Network_IsConnected(void) {
    if (!g_running) return FALSE;
    for (int i = 0; i < BROKER_COUNT; i++) {
        if (g_brokers[i].connected) return TRUE;
    }
    return FALSE;
}

BOOL Network_Subscribe(const char *topic) {
    if (!g_running || !topic || !topic[0]) return FALSE;

    char safeTopic[128] = {0};
    SanitizeTopic(topic, safeTopic, sizeof(safeTopic));

    EnterCriticalSection(&g_cs);
    BOOL found = FALSE;
    for (int i = 0; i < g_topicCount; i++) {
        if (strcmp(g_topics[i], safeTopic) == 0) {
            found = TRUE;
            break;
        }
    }
    if (!found && g_topicCount < MAX_TOPICS) {
        strncpy(g_topics[g_topicCount++], safeTopic, 127);
    }

    BOOL ok = FALSE;
    for (int i = 0; i < BROKER_COUNT; i++) {
        if (g_brokers[i].connected && g_brokers[i].sock != INVALID_SOCKET) {
            if (RawSendSubscribe(g_brokers[i].sock, safeTopic)) ok = TRUE;
        }
    }
    LeaveCriticalSection(&g_cs);
    return ok;
}

BOOL Network_Unsubscribe(const char *topic) {
    if (!g_running || !topic || !topic[0]) return FALSE;

    char safeTopic[128] = {0};
    SanitizeTopic(topic, safeTopic, sizeof(safeTopic));

    EnterCriticalSection(&g_cs);
    for (int i = 0; i < g_topicCount; i++) {
        if (strcmp(g_topics[i], safeTopic) == 0) {
            for (int j = i; j < g_topicCount - 1; j++) {
                memcpy(g_topics[j], g_topics[j + 1], sizeof(g_topics[0]));
            }
            g_topicCount--;
            break;
        }
    }

    BOOL ok = FALSE;
    for (int i = 0; i < BROKER_COUNT; i++) {
        if (g_brokers[i].connected && g_brokers[i].sock != INVALID_SOCKET) {
            if (RawSendUnsubscribe(g_brokers[i].sock, safeTopic)) ok = TRUE;
        }
    }
    LeaveCriticalSection(&g_cs);
    return ok;
}

BOOL Network_PublishEx(const char *topic, const char *payload, BOOL retain) {
    if (!g_running || !topic || !payload) return FALSE;

    char safeTopic[128] = {0};
    SanitizeTopic(topic, safeTopic, sizeof(safeTopic));

    EnterCriticalSection(&g_cs);
    BOOL ok = FALSE;
    for (int i = 0; i < BROKER_COUNT; i++) {
        if (g_brokers[i].connected && g_brokers[i].sock != INVALID_SOCKET) {
            if (RawPublish(g_brokers[i].sock, safeTopic, payload, retain)) ok = TRUE;
        }
    }
    LeaveCriticalSection(&g_cs);
    return ok;
}

BOOL Network_Publish(const char *topic, const char *payload) {
    return Network_PublishEx(topic, payload, FALSE);
}

void Network_Close(void) {
    if (!g_running) return;
    g_running = FALSE;

    EnterCriticalSection(&g_cs);
    for (int i = 0; i < BROKER_COUNT; i++) {
        if (g_brokers[i].sock != INVALID_SOCKET) {
            shutdown(g_brokers[i].sock, SD_BOTH);
            closesocket(g_brokers[i].sock);
            g_brokers[i].sock = INVALID_SOCKET;
        }
        g_brokers[i].connected = FALSE;
    }
    LeaveCriticalSection(&g_cs);

    for (int i = 0; i < BROKER_COUNT; i++) {
        if (g_brokers[i].hThread) {
            WaitForSingleObject(g_brokers[i].hThread, 1000);
            CloseHandle(g_brokers[i].hThread);
            g_brokers[i].hThread = NULL;
        }
    }

    DeleteCriticalSection(&g_cs);
    WSACleanup();
}
