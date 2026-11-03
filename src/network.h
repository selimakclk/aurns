#ifndef NETWORK_H
#define NETWORK_H

#include <winsock2.h>
#include <windows.h>

#define WM_APP_NETWORK_EVENT (WM_APP + 400)

typedef enum {
    NET_EVENT_CONNECTED = 1,
    NET_EVENT_DISCONNECTED,
    NET_EVENT_MESSAGE
} NetEventType;

typedef struct {
    NetEventType type;
    char topic[128];
    char payload[1024];
} NetEvent;

BOOL Network_Init(HWND hWnd, const char *clientTag);
BOOL Network_IsConnected(void);
BOOL Network_Subscribe(const char *topic);
BOOL Network_Unsubscribe(const char *topic);
BOOL Network_Publish(const char *topic, const char *payload);
BOOL Network_PublishEx(const char *topic, const char *payload, BOOL retain);
void Network_Close(void);

void Network_TagToTopicKeyW(const wchar_t *tag, char *out, int maxLen);
void Network_TagToTopicKeyA(const char *tag, char *out, int maxLen);
void Network_ExtractTagIdW(const wchar_t *tag, char *outId, int maxLen);
void Network_ExtractTagIdA(const char *tag, char *outId, int maxLen);

#endif // NETWORK_H
