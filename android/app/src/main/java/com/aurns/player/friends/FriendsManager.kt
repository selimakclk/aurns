package com.aurns.player.friends

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class FriendItem(
    val tag: String,
    val name: String,
    val isOnline: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTrack: String = "Çevrimdışı",
    val lastSeenTimestamp: Long = 0L
)

data class FriendRequest(
    val tag: String,
    val name: String
)

data class JamMember(
    val tag: String,
    val name: String
)

data class JamRoomState(
    val isActive: Boolean = false,
    val isInviting: Boolean = false,
    val hasIncomingInvite: Boolean = false,
    val inviterTag: String = "",
    val inviterName: String = "",
    val pendingJamId: String = "",
    val jamId: String = "",
    val members: List<JamMember> = emptyList(),
    val outgoingInviteCount: Int = 0,
    val lastInvitedTag: String = "",
    val lastInvitedName: String = "",
    val syncStatus: String = "Canlı milisaniye senkronizasyonu aktif",
    val isDownloadingTrack: Boolean = false,
    val currentTrackTitle: String = "",
    val currentYtId: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L
) {
    val totalParticipantCount: Int
        get() = members.size + 1

    fun isFriendInRoom(friendTag: String): Boolean {
        if (!isActive && !isInviting) return false
        if (members.any { it.tag.equals(friendTag, ignoreCase = true) }) return true
        if (isInviting && lastInvitedTag.equals(friendTag, ignoreCase = true)) return true
        return false
    }

    fun formatHeadline(): String {
        if (isInviting && !isActive) {
            return if (outgoingInviteCount > 1) {
                "🎧 JAM ODASI KURULUYOR • $outgoingInviteCount kişiye davet gönderildi"
            } else {
                "🎧 JAM DAVETİ BEKLENİYOR • ${lastInvitedName.ifBlank { lastInvitedTag }}"
            }
        }
        val namesPreview = buildList {
            add("Sen")
            members.take(3).forEach { add(it.name.ifBlank { it.tag }) }
        }.joinToString(", ")
        val extra = if (members.size > 3) " +${members.size - 3}" else ""
        return "🎧 CANLI JAM ODASI ($totalParticipantCount Kişi) • $namesPreview$extra"
    }
}

class FriendsManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("aurns_friends_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _myTag = MutableStateFlow("")
    val myTag: StateFlow<String> = _myTag.asStateFlow()

    private val _myName = MutableStateFlow("")
    val myName: StateFlow<String> = _myName.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendItem>>(emptyList())
    val friends: StateFlow<List<FriendItem>> = _friends.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val pendingRequests: StateFlow<List<FriendRequest>> = _pendingRequests.asStateFlow()

    private val _jamState = MutableStateFlow(JamRoomState())
    val jamState: StateFlow<JamRoomState> = _jamState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var socket: Socket? = null
    private var isRunning = false
    private var outputStream: OutputStream? = null
    private val subscribedTopics = Collections.synchronizedSet(LinkedHashSet<String>())

    private var localIsPlaying = false
    private var localTrackTitle = ""
    private var inviteSentAtMs = 0L
    private var incomingInviteAtMs = 0L
    private var lastStateSenderTag = ""

    // Milisaniye hassasiyetli Jam olay dinleyicileri (Windows src/jam.c ile %100 birebir)
    var onRemoteJamStateRequest: ((title: String, ytId: String, isPlaying: Boolean, posMs: Long, recvTimeMs: Long) -> Unit)? = null
    var onRemoteJamPosRequest: ((playFlag: Int, posMs: Long, recvTimeMs: Long) -> Unit)? = null
    var onRemoteJamAskRequest: ((requesterTag: String) -> Unit)? = null
    var onJamRoomClosed: (() -> Unit)? = null

    private val pendingUserQueries = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        loadFromPrefs()
        startNetworkLoop()
        startKeepAliveAndTimeoutLoop()
    }

    private fun sanitizeField(s: String): String {
        return s.replace('|', '-').replace('\r', '-').replace('\n', '-')
    }

    private fun getDeterministicTagNumber(): Int {
        val androidId = try {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) { "" }

        val seedSource = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            "${Build.BOARD}_${Build.BRAND}_${Build.DEVICE}_${Build.MODEL}_${Build.MANUFACTURER}"
        }
        val seed = kotlin.math.abs(seedSource.hashCode())
        return 1000 + (seed % 9000)
    }

    private fun saveIdentityFile(tag: String, name: String) {
        try {
            val identityFile = File(context.filesDir, "aurns_identity.tag")
            identityFile.writeText("$tag\n$name")
        } catch (_: Exception) {}
    }

    private fun loadFromPrefs() {
        var tag = prefs.getString("my_tag", "") ?: ""
        var name = prefs.getString("my_name", "") ?: ""

        val identityFile = File(context.filesDir, "aurns_identity.tag")
        if (identityFile.exists()) {
            try {
                val lines = identityFile.readLines()
                if (lines.isNotEmpty()) {
                    val fileTag = lines[0].trim()
                    val fileName = if (lines.size > 1) lines[1].trim() else fileTag.substringBefore("#")
                    if (tag.isBlank() && fileTag.isNotBlank()) {
                        tag = fileTag
                        name = fileName
                    }
                }
            } catch (_: Exception) {}
        }

        if (tag.isBlank()) {
            val deviceModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ]"), "").take(10).ifEmpty { "Android" }
            val num = getDeterministicTagNumber()
            tag = "$deviceModel#$num"
            name = deviceModel
        }

        if (!tag.contains("#")) {
            val num = getDeterministicTagNumber()
            tag = "$tag#$num"
        }
        if (name.isBlank()) name = tag.substringBefore("#")

        _myTag.value = tag
        _myName.value = name
        prefs.edit().putString("my_tag", tag).putString("my_name", name).apply()
        saveIdentityFile(tag, name)

        val friendsJson = prefs.getString("friends_json", "[]") ?: "[]"
        val fList = mutableListOf<FriendItem>()
        try {
            val arr = JSONArray(friendsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                fList.add(FriendItem(obj.getString("tag"), obj.getString("name")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _friends.value = fList

        val pendingJson = prefs.getString("pending_json", "[]") ?: "[]"
        val pList = mutableListOf<FriendRequest>()
        try {
            val arr = JSONArray(pendingJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                pList.add(FriendRequest(obj.getString("tag"), obj.getString("name")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _pendingRequests.value = pList
    }

    fun updateMyName(newName: String) {
        val clean = newName.trim().replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ_-]"), "").take(24)
        if (clean.isBlank()) return

        val oldKey = tagToTopicKey(_myTag.value)
        _myName.value = clean
        val curTag = _myTag.value
        val num = if (curTag.contains("#")) curTag.substringAfter("#") else getDeterministicTagNumber().toString()
        val newTag = "$clean#$num"
        _myTag.value = newTag
        prefs.edit().putString("my_name", clean).putString("my_tag", newTag).apply()
        saveIdentityFile(newTag, clean)

        val newKey = tagToTopicKey(newTag)
        scope.launch {
            if (oldKey.isNotBlank() && !oldKey.equals(newKey, ignoreCase = true)) {
                unsubscribeTopic("seloplayer/u/$oldKey")
            }
            subscribeTopic("seloplayer/u/$newKey")
            requestAllStatuses()
        }
    }

    private fun saveFriendsToPrefs() {
        val fArr = JSONArray()
        for (f in _friends.value) {
            fArr.put(JSONObject().apply {
                put("tag", f.tag)
                put("name", f.name)
            })
        }
        val pArr = JSONArray()
        for (p in _pendingRequests.value) {
            pArr.put(JSONObject().apply {
                put("tag", p.tag)
                put("name", p.name)
            })
        }
        prefs.edit()
            .putString("friends_json", fArr.toString())
            .putString("pending_json", pArr.toString())
            .apply()
    }

    fun tagToTopicKey(tag: String): String {
        return tag.lowercase(Locale.ROOT)
            .replace('#', '_')
            .replace('+', '_')
            .replace('/', '_')
            .replace('$', '_')
            .replace(' ', '_')
    }

    private fun roomTopic(jamId: String): String = "seloplayer/jam/$jamId"

    private fun subscribeTopic(topic: String) {
        if (topic.isBlank()) return
        subscribedTopics.add(topic)
        sendMqttSubscribe(outputStream, topic)
    }

    private fun unsubscribeTopic(topic: String) {
        if (topic.isBlank()) return
        subscribedTopics.remove(topic)
        sendMqttUnsubscribe(outputStream, topic)
    }

    private fun startNetworkLoop() {
        isRunning = true
        scope.launch {
            while (isRunning) {
                var s: Socket? = null
                try {
                    val primaryHost = "broker.hivemq.com"
                    val fallbackHost = "broker.emqx.io"
                    s = Socket()
                    try {
                        s.connect(InetSocketAddress(primaryHost, 1883), 4000)
                    } catch (_: Exception) {
                        try { s.close() } catch (_: Exception) {}
                        s = Socket()
                        s.connect(InetSocketAddress(fallbackHost, 1883), 5000)
                    }
                    s.soTimeout = 45000
                    s.tcpNoDelay = true
                    socket = s
                    outputStream = s.getOutputStream()
                    val inStream = s.getInputStream()

                    val clientId = "aurns_and_${System.currentTimeMillis() % 1000000}"
                    sendMqttConnect(outputStream, clientId)

                    _isConnected.value = true
                    val myKey = tagToTopicKey(_myTag.value)
                    if (myKey.isNotBlank()) {
                        subscribedTopics.add("seloplayer/u/$myKey")
                    }
                    val topicsSnapshot = synchronized(subscribedTopics) { subscribedTopics.toList() }
                    for (t in topicsSnapshot) {
                        sendMqttSubscribe(outputStream, t)
                    }

                    requestAllStatuses()

                    val inData = DataInputStream(inStream)
                    while (isRunning && socket?.isConnected == true) {
                        val header = inData.readUnsignedByte()
                        var multiplier = 1
                        var remLen = 0
                        var digit: Int
                        do {
                            digit = inData.readUnsignedByte()
                            remLen += (digit and 0x7F) * multiplier
                            multiplier *= 128
                        } while ((digit and 0x80) != 0)

                        val packetBody = ByteArray(remLen)
                        inData.readFully(packetBody)

                        val pktType = header and 0xF0
                        if (pktType == 0x30 && remLen >= 2) {
                            val topLen = ((packetBody[0].toInt() and 0xFF) shl 8) or (packetBody[1].toInt() and 0xFF)
                            if (2 + topLen <= remLen) {
                                val topic = String(packetBody, 2, topLen, Charsets.UTF_8)
                                val payloadLen = remLen - 2 - topLen
                                val payload = if (payloadLen > 0) String(packetBody, 2 + topLen, payloadLen, Charsets.UTF_8) else ""
                                handleNetworkMessage(topic, payload)
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    _isConnected.value = false
                    try { s?.close() } catch (_: Exception) {}
                    try { socket?.close() } catch (_: Exception) {}
                    delay(1500)
                }
            }
        }
    }

    private fun startKeepAliveAndTimeoutLoop() {
        scope.launch {
            var tickCount = 0
            while (isActive) {
                delay(5000)
                tickCount++
                val now = System.currentTimeMillis()

                // 15 saniyede bir MQTT PINGREQ + arkadas canlilik yoklamasi
                if (tickCount % 3 == 0 && _isConnected.value && outputStream != null) {
                    try {
                        synchronized(outputStream!!) {
                            outputStream?.write(byteArrayOf(0xC0.toByte(), 0x00.toByte()))
                            outputStream?.flush()
                        }
                        requestAllStatuses()
                    } catch (_: Exception) {
                        try { socket?.close() } catch (_: Exception) {}
                    }
                }

                // Cevrimdisi dusen arkadaslari isaretle
                val curFriends = _friends.value
                if (curFriends.any { it.isOnline && now - it.lastSeenTimestamp > 28_000L }) {
                    _friends.value = curFriends.map { f ->
                        if (f.isOnline && now - f.lastSeenTimestamp > 28_000L) {
                            f.copy(isOnline = false, isPlaying = false, currentTrack = "Çevrimdışı")
                        } else f
                    }
                }

                // Jam davet zaman asimlari (45 sn)
                val st = _jamState.value
                if (st.isInviting && !st.isActive && inviteSentAtMs > 0L && now - inviteSentAtMs > 45_000L) {
                    resetJamRoomInternal(unsub = true)
                }
                if (st.hasIncomingInvite && incomingInviteAtMs > 0L && now - incomingInviteAtMs > 45_000L) {
                    _jamState.value = _jamState.value.copy(hasIncomingInvite = false, pendingJamId = "", inviterTag = "", inviterName = "")
                }
            }
        }
    }

    private fun upsertMemberInList(list: List<JamMember>, tag: String, name: String?): Pair<List<JamMember>, Boolean> {
        if (tag.isBlank() || tag.equals(_myTag.value, ignoreCase = true)) return Pair(list, false)
        val mutable = list.toMutableList()
        val idx = mutable.indexOfFirst { it.tag.equals(tag, ignoreCase = true) }
        return if (idx >= 0) {
            if (!name.isNullOrBlank()) {
                mutable[idx] = mutable[idx].copy(name = name)
            }
            Pair(mutable, false)
        } else {
            mutable.add(JamMember(tag = tag, name = name?.takeIf { it.isNotBlank() } ?: tag))
            Pair(mutable, true)
        }
    }

    private fun resetJamRoomInternal(unsub: Boolean) {
        val oldJamId = _jamState.value.jamId
        if (unsub && oldJamId.isNotBlank()) {
            unsubscribeTopic(roomTopic(oldJamId))
        }
        lastStateSenderTag = ""
        inviteSentAtMs = 0L
        val cur = _jamState.value
        _jamState.value = JamRoomState(
            hasIncomingInvite = cur.hasIncomingInvite,
            inviterTag = cur.inviterTag,
            inviterName = cur.inviterName,
            pendingJamId = cur.pendingJamId
        )
        onJamRoomClosed?.invoke()
    }

    fun setJamSyncStatus(status: String, isDownloading: Boolean = false) {
        val cur = _jamState.value
        if (!cur.isActive && !cur.isInviting) return
        _jamState.value = cur.copy(syncStatus = status, isDownloadingTrack = isDownloading)
    }

    private fun handleNetworkMessage(topic: String, payload: String) {
        val myTagNow = _myTag.value
        val myKey = tagToTopicKey(myTagNow)
        val myInbox = "seloplayer/u/$myKey"
        val curJam = _jamState.value
        val activeRoomTopic = if (curJam.jamId.isNotBlank()) roomTopic(curJam.jamId) else ""

        val isInbox = topic.equals(myInbox, ignoreCase = true)
        val isRoom = activeRoomTopic.isNotBlank() && topic.equals(activeRoomTopic, ignoreCase = true)
        if (!isInbox && !isRoom) return

        val parts = payload.split("|")
        if (parts.isEmpty()) return
        val action = parts[0]

        if (isInbox) {
            when (action) {
                "REQ" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        if (!fromTag.equals(myTagNow, ignoreCase = true) &&
                            _friends.value.none { it.tag.equals(fromTag, ignoreCase = true) }
                        ) {
                            val cur = _pendingRequests.value.toMutableList()
                            if (cur.none { it.tag.equals(fromTag, ignoreCase = true) }) {
                                cur.add(FriendRequest(fromTag, fromName.ifBlank { fromTag }))
                                _pendingRequests.value = cur
                                saveFriendsToPrefs()
                            }
                        }
                    }
                    return
                }
                "ACC" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        val cur = _friends.value.toMutableList()
                        val existingIdx = cur.indexOfFirst { it.tag.equals(fromTag, ignoreCase = true) }
                        if (existingIdx < 0) {
                            cur.add(FriendItem(fromTag, fromName.ifBlank { fromTag }, isOnline = true, currentTrack = "Çevrimiçi", lastSeenTimestamp = System.currentTimeMillis()))
                            _friends.value = cur
                            saveFriendsToPrefs()
                        } else {
                            cur[existingIdx] = cur[existingIdx].copy(isOnline = true, lastSeenTimestamp = System.currentTimeMillis())
                            _friends.value = cur
                        }
                        sendPong(fromTag)
                        sendPing(fromTag)
                    }
                    return
                }
                "PING" -> {
                    if (parts.size >= 2) {
                        val fromTag = parts[1]
                        val fromName = parts.getOrNull(2) ?: ""
                        if (_friends.value.any { it.tag.equals(fromTag, ignoreCase = true) }) {
                            updateFriendTouch(fromTag, fromName)
                        }
                        sendPong(fromTag)
                    }
                    return
                }
                "PONG" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        val isPlay = parts.getOrNull(3) == "1"
                        val track = parts.getOrNull(4)?.ifEmpty {
                            if (isPlay) "Müzik Çalıyor" else "Çevrimiçi • Boşta"
                        } ?: "Çevrimiçi • Boşta"

                        updateFriendStatus(fromTag, fromName, isOnline = true, isPlaying = isPlay, currentTrack = track)

                        val key = tagToTopicKey(fromTag)
                        pendingUserQueries[key]?.complete(true)
                        for ((k, def) in pendingUserQueries) {
                            if (k.equals(key, ignoreCase = true)) {
                                def.complete(true)
                            }
                        }
                    }
                    return
                }
                "BYE" -> {
                    if (parts.size >= 2) {
                        val fromTag = parts[1]
                        updateFriendStatus(fromTag, "", isOnline = false, isPlaying = false, currentTrack = "Çevrimdışı")
                    }
                    return
                }
                "JAM_INV", "JAM_INVITE" -> {
                    if (parts.size >= 4) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        val invJamId = parts[3]
                        if (fromTag.isBlank() || invJamId.isBlank() || fromTag.equals(myTagNow, ignoreCase = true)) return
                        if ((curJam.isActive || curJam.isInviting) && curJam.jamId == invJamId) return

                        incomingInviteAtMs = System.currentTimeMillis()
                        _jamState.value = curJam.copy(
                            hasIncomingInvite = true,
                            inviterTag = fromTag,
                            inviterName = fromName.ifBlank { fromTag },
                            pendingJamId = invJamId
                        )
                    }
                    return
                }
            }
        }

        // ==================== ÇOK KİŞİLİ SINIRSIZ JAM ODA PROTOKOLÜ ====================
        when (action) {
            // JAM_JOIN|fromTag|fromName|jamId
            "JAM_JOIN" -> {
                if (parts.size >= 4) {
                    val fromTag = parts[1]
                    val fromName = parts[2]
                    val msgJamId = parts[3]
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || fromTag.equals(myTagNow, ignoreCase = true)) return

                    val (newMembers, isNew) = upsertMemberInList(st.members, fromTag, fromName)
                    val newOutgoing = (st.outgoingInviteCount - 1).coerceAtLeast(0)
                    _jamState.value = st.copy(
                        isActive = true,
                        isInviting = false,
                        members = newMembers,
                        outgoingInviteCount = newOutgoing,
                        syncStatus = if (st.isDownloadingTrack) st.syncStatus else "Canlı milisaniye senkronizasyonu aktif"
                    )

                    if (isNew) {
                        val herePayload = "JAM_HERE|${sanitizeField(myTagNow)}|${sanitizeField(_myName.value)}|$msgJamId"
                        publishToPeer(fromTag, herePayload)
                        if (!st.isDownloadingTrack) {
                            onRemoteJamAskRequest?.invoke(fromTag)
                        }
                    }
                }
            }

            // JAM_HERE|fromTag|fromName|jamId
            "JAM_HERE" -> {
                if (parts.size >= 4) {
                    val fromTag = parts[1]
                    val fromName = parts[2]
                    val msgJamId = parts[3]
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || fromTag.equals(myTagNow, ignoreCase = true)) return

                    val (newMembers, _) = upsertMemberInList(st.members, fromTag, fromName)
                    _jamState.value = st.copy(
                        isActive = true,
                        isInviting = false,
                        members = newMembers
                    )
                }
            }

            // JAM_REJ|fromTag|jamId
            "JAM_REJ" -> {
                if (parts.size >= 3) {
                    val msgJamId = parts[2]
                    val st = _jamState.value
                    if ((st.isActive || st.isInviting) && st.jamId == msgJamId) {
                        val newOutgoing = (st.outgoingInviteCount - 1).coerceAtLeast(0)
                        if (st.isInviting && !st.isActive && st.members.isEmpty() && newOutgoing == 0) {
                            resetJamRoomInternal(unsub = true)
                        } else {
                            _jamState.value = st.copy(outgoingInviteCount = newOutgoing)
                        }
                    }
                }
            }

            // JAM_LEAVE|fromTag|jamId
            "JAM_LEAVE" -> {
                if (parts.size >= 3) {
                    val fromTag = parts[1]
                    val msgJamId = parts[2]
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || fromTag.equals(myTagNow, ignoreCase = true)) return

                    val remaining = st.members.filterNot { it.tag.equals(fromTag, ignoreCase = true) }
                    if (lastStateSenderTag.equals(fromTag, ignoreCase = true)) {
                        lastStateSenderTag = ""
                    }
                    if (remaining.isEmpty() && st.outgoingInviteCount <= 0) {
                        resetJamRoomInternal(unsub = true)
                    } else {
                        _jamState.value = st.copy(members = remaining)
                    }
                }
            }

            // JAM_ASK|fromTag|jamId
            "JAM_ASK" -> {
                if (parts.size >= 3) {
                    val fromTag = parts[1]
                    val msgJamId = parts[2]
                    val st = _jamState.value
                    if (st.isActive && st.jamId == msgJamId && fromTag.isNotBlank() && !fromTag.equals(myTagNow, ignoreCase = true)) {
                        val (newMembers, _) = upsertMemberInList(st.members, fromTag, null)
                        _jamState.value = st.copy(members = newMembers)
                        if (!st.isDownloadingTrack) {
                            onRemoteJamAskRequest?.invoke(fromTag)
                        }
                    }
                }
            }

            // JAM_STATE|fromTag|jamId|isPlaying|posMs|ytId|trackTitle
            "JAM_STATE" -> {
                if (parts.size >= 7) {
                    val fromTag = parts[1]
                    val msgJamId = parts[2]
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || fromTag.equals(myTagNow, ignoreCase = true)) return

                    val isPlay = parts[3] == "1"
                    val posMs = (parts[4].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val ytId = parts[5].trim()
                    val title = parts.drop(6).joinToString("|").trim()
                    val recvNow = System.currentTimeMillis()

                    val (newMembers, _) = upsertMemberInList(st.members, fromTag, null)
                    lastStateSenderTag = fromTag
                    _jamState.value = st.copy(
                        isActive = true,
                        isInviting = false,
                        members = newMembers,
                        currentTrackTitle = title,
                        currentYtId = ytId,
                        isPlaying = isPlay,
                        positionMs = posMs
                    )

                    if (title.isNotEmpty() || ytId.isNotEmpty()) {
                        onRemoteJamStateRequest?.invoke(title, ytId, isPlay, posMs, recvNow)
                    }
                }
            }

            // JAM_POS|fromTag|jamId|playFlag(-1=seek,0=pause,1=play)|posMs
            "JAM_POS" -> {
                if (parts.size >= 5) {
                    val fromTag = parts[1]
                    val msgJamId = parts[2]
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || fromTag.equals(myTagNow, ignoreCase = true)) return

                    val playFlag = parts[3].toIntOrNull() ?: 0
                    val posMs = (parts[4].toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val recvNow = System.currentTimeMillis()

                    val (newMembers, _) = upsertMemberInList(st.members, fromTag, null)
                    lastStateSenderTag = fromTag
                    _jamState.value = st.copy(
                        isActive = true,
                        isInviting = false,
                        members = newMembers,
                        isPlaying = if (playFlag < 0) st.isPlaying else (playFlag == 1),
                        positionMs = posMs
                    )

                    onRemoteJamPosRequest?.invoke(playFlag, posMs, recvNow)
                }
            }
        }
    }

    private fun updateFriendTouch(tag: String, name: String) {
        val cur = _friends.value.toMutableList()
        val idx = cur.indexOfFirst { it.tag.equals(tag, ignoreCase = true) }
        if (idx != -1) {
            val old = cur[idx]
            cur[idx] = old.copy(
                name = if (name.isNotBlank()) name else old.name,
                isOnline = true,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            _friends.value = cur
        }
    }

    private fun updateFriendStatus(tag: String, name: String, isOnline: Boolean, isPlaying: Boolean, currentTrack: String) {
        val cur = _friends.value.toMutableList()
        val idx = cur.indexOfFirst { it.tag.equals(tag, ignoreCase = true) }
        if (idx != -1) {
            val old = cur[idx]
            cur[idx] = old.copy(
                tag = if (tag.isNotBlank()) tag else old.tag,
                name = if (name.isNotBlank()) name else old.name,
                isOnline = isOnline,
                isPlaying = isPlaying,
                currentTrack = currentTrack,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            _friends.value = cur
        }
    }

    // ==================== KULLANICI ARAMA & ARKADAŞ EKLEME ====================

    suspend fun checkUserAndSendRequest(targetTag: String): Result<String> {
        val clean = targetTag.trim().replace("@", "")
        if (clean.isBlank() || !clean.contains("#")) {
            return Result.failure(Exception("Etiket '#' ve numara içermelidir (Örn: akcel#4770)"))
        }
        if (clean.equals(_myTag.value, ignoreCase = true)) {
            return Result.failure(Exception("Kendinize arkadaşlık isteği gönderemezsiniz!"))
        }
        if (_friends.value.any { it.tag.equals(clean, ignoreCase = true) }) {
            return Result.failure(Exception("Bu kullanıcı zaten arkadaş listenizde!"))
        }

        var waitConnectMs = 0
        while (!_isConnected.value && waitConnectMs < 2000) {
            delay(100)
            waitConnectMs += 100
        }

        val targetKey = tagToTopicKey(clean)
        val topic = "seloplayer/u/$targetKey"

        val queryDeferred = CompletableDeferred<Boolean>()
        pendingUserQueries[targetKey] = queryDeferred

        try {
            val pingPayload = "PING|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
            sendMqttPublish(outputStream, topic, pingPayload)

            var isFound = withTimeoutOrNull(3000) {
                queryDeferred.await()
            } ?: false

            if (!isFound) {
                sendMqttPublish(outputStream, topic, pingPayload)
                isFound = withTimeoutOrNull(2000) {
                    queryDeferred.await()
                } ?: false
            }

            if (!isFound) {
                return Result.failure(Exception("Böyle bir kullanıcı yok veya şu an çevrimdışı!"))
            }

            val reqPayload = "REQ|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
            sendMqttPublish(outputStream, topic, reqPayload)
            return Result.success("İstek başarıyla gönderildi!")
        } finally {
            pendingUserQueries.remove(targetKey)
        }
    }

    fun acceptFriendRequest(targetTag: String) {
        val req = _pendingRequests.value.firstOrNull { it.tag.equals(targetTag, ignoreCase = true) } ?: return

        val curF = _friends.value.toMutableList()
        if (curF.none { it.tag.equals(req.tag, ignoreCase = true) }) {
            curF.add(FriendItem(req.tag, req.name, isOnline = true, currentTrack = "Çevrimiçi", lastSeenTimestamp = System.currentTimeMillis()))
            _friends.value = curF
        }

        val curP = _pendingRequests.value.toMutableList()
        curP.removeAll { it.tag.equals(targetTag, ignoreCase = true) }
        _pendingRequests.value = curP
        saveFriendsToPrefs()

        val payload = "ACC|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
        publishToPeer(req.tag, payload)
        sendPong(req.tag)
        sendPing(req.tag)
    }

    fun rejectFriendRequest(targetTag: String) {
        val curP = _pendingRequests.value.toMutableList()
        curP.removeAll { it.tag.equals(targetTag, ignoreCase = true) }
        _pendingRequests.value = curP
        saveFriendsToPrefs()
    }

    fun removeFriend(targetTag: String) {
        val curF = _friends.value.toMutableList()
        curF.removeAll { it.tag.equals(targetTag, ignoreCase = true) }
        _friends.value = curF
        saveFriendsToPrefs()
    }

    fun requestAllStatuses() {
        for (f in _friends.value) {
            sendPing(f.tag)
        }
    }

    private fun publishToPeer(peerTag: String, payload: String) {
        if (peerTag.isBlank()) return
        val key = tagToTopicKey(peerTag)
        if (key.isBlank()) return
        scope.launch {
            sendMqttPublish(outputStream, "seloplayer/u/$key", payload)
        }
    }

    private fun publishToRoom(jamId: String, payload: String) {
        if (jamId.isBlank()) return
        val rTopic = roomTopic(jamId)
        scope.launch {
            sendMqttPublish(outputStream, rTopic, payload)
        }
    }

    private fun sendPing(targetTag: String) {
        val payload = "PING|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
        publishToPeer(targetTag, payload)
    }

    private fun sendPong(targetTag: String) {
        val trackClean = if (localIsPlaying) sanitizeField(localTrackTitle) else ""
        val payload = "PONG|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}|${if (localIsPlaying) 1 else 0}|$trackClean"
        publishToPeer(targetTag, payload)
    }

    // DIKKAT: Yalnizca arkadas durum kartini gunceller; ASLA Jam yayini tetiklemez (pos=0 sifirlama hatasini kokten onler!)
    fun updateLocalPlaybackState(isPlaying: Boolean, trackTitle: String) {
        localIsPlaying = isPlaying
        localTrackTitle = trackTitle
        for (f in _friends.value) {
            sendPong(f.tag)
        }
    }

    // ==================== SINIRSIZ KİŞİLİK JAM KONTROLLERİ ====================

    fun inviteFriendToJam(friendTag: String, friendName: String = "") {
        if (friendTag.isBlank() || friendTag.equals(_myTag.value, ignoreCase = true)) return
        val cur = _jamState.value
        if (cur.members.any { it.tag.equals(friendTag, ignoreCase = true) }) return

        val displayName = friendName.ifBlank {
            _friends.value.firstOrNull { it.tag.equals(friendTag, ignoreCase = true) }?.name ?: friendTag
        }

        val activeJamId = if ((cur.isActive || cur.isInviting) && cur.jamId.isNotBlank()) {
            cur.jamId
        } else {
            val myKey = tagToTopicKey(_myTag.value)
            val newId = "jam_${myKey}_${System.currentTimeMillis() % 1000000}"
            scope.launch { subscribeTopic(roomTopic(newId)) }
            newId
        }

        inviteSentAtMs = System.currentTimeMillis()
        val wasActive = cur.isActive && cur.jamId.isNotBlank()
        _jamState.value = cur.copy(
            isActive = wasActive,
            isInviting = !wasActive,
            jamId = activeJamId,
            outgoingInviteCount = cur.outgoingInviteCount + 1,
            lastInvitedTag = friendTag,
            lastInvitedName = displayName,
            syncStatus = if (wasActive) "$displayName odaya davet edildi • Canlı senkron aktif"
                         else "Davet gönderildi • Katılımcıların kabul etmesi bekleniyor..."
        )

        val payload = "JAM_INV|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}|$activeJamId"
        publishToPeer(friendTag, payload)
    }

    fun acceptJamInvite() {
        val st = _jamState.value
        if (!st.hasIncomingInvite || st.inviterTag.isBlank() || st.pendingJamId.isBlank()) return

        val inviterTag = st.inviterTag
        val inviterName = st.inviterName.ifBlank { inviterTag }
        val targetJamId = st.pendingJamId

        if ((st.isActive || st.isInviting) && st.jamId.isNotBlank() && st.jamId != targetJamId) {
            leaveJamRoom()
        }

        lastStateSenderTag = inviterTag
        _jamState.value = JamRoomState(
            isActive = true,
            isInviting = false,
            hasIncomingInvite = false,
            jamId = targetJamId,
            members = listOf(JamMember(inviterTag, inviterName)),
            syncStatus = "Odaya bağlanıldı • Canlı yayın senkronize ediliyor..."
        )

        val myTagClean = sanitizeField(_myTag.value)
        val myNameClean = sanitizeField(_myName.value)
        val joinPayload = "JAM_JOIN|$myTagClean|$myNameClean|$targetJamId"
        val askPayload = "JAM_ASK|$myTagClean|$targetJamId"

        scope.launch {
            subscribeTopic(roomTopic(targetJamId))
            sendMqttPublish(outputStream, "seloplayer/u/${tagToTopicKey(inviterTag)}", joinPayload)
            sendMqttPublish(outputStream, roomTopic(targetJamId), joinPayload)
            sendMqttPublish(outputStream, "seloplayer/u/${tagToTopicKey(inviterTag)}", askPayload)
        }
    }

    fun rejectJamInvite() {
        val st = _jamState.value
        if (!st.hasIncomingInvite) return

        val inviterTag = st.inviterTag
        val pJamId = st.pendingJamId
        _jamState.value = st.copy(hasIncomingInvite = false, inviterTag = "", inviterName = "", pendingJamId = "")

        if (inviterTag.isNotBlank() && pJamId.isNotBlank()) {
            publishToPeer(inviterTag, "JAM_REJ|${sanitizeField(_myTag.value)}|$pJamId")
        }
    }

    fun leaveJamRoom() {
        val st = _jamState.value
        if (!st.isActive && !st.isInviting) return

        val oldJamId = st.jamId
        val membersSnap = st.members
        val lastInv = st.lastInvitedTag
        val wasInviting = st.isInviting
        val leavePayload = "JAM_LEAVE|${sanitizeField(_myTag.value)}|$oldJamId"

        resetJamRoomInternal(unsub = false)

        scope.launch {
            if (oldJamId.isNotBlank()) {
                sendMqttPublish(outputStream, roomTopic(oldJamId), leavePayload)
                for (m in membersSnap) {
                    sendMqttPublish(outputStream, "seloplayer/u/${tagToTopicKey(m.tag)}", leavePayload)
                }
                if (wasInviting && lastInv.isNotBlank()) {
                    sendMqttPublish(outputStream, "seloplayer/u/${tagToTopicKey(lastInv)}", leavePayload)
                }
                unsubscribeTopic(roomTopic(oldJamId))
            }
        }
    }

    private fun buildStatePayload(jamId: String, title: String, ytId: String, isPlaying: Boolean, posMs: Long): String {
        val cleanTag = sanitizeField(_myTag.value)
        val cleanYt = sanitizeField(ytId)
        val cleanTitle = sanitizeField(title)
        val safePos = posMs.coerceAtLeast(0L)
        return "JAM_STATE|$cleanTag|$jamId|${if (isPlaying) 1 else 0}|$safePos|$cleanYt|$cleanTitle"
    }

    fun broadcastJamState(title: String, ytId: String, isPlaying: Boolean, posMs: Long) {
        val st = _jamState.value
        if (!st.isActive || st.jamId.isBlank()) return
        _jamState.value = st.copy(
            currentTrackTitle = title,
            currentYtId = ytId,
            isPlaying = isPlaying,
            positionMs = posMs.coerceAtLeast(0L),
            syncStatus = if (isPlaying) "✓ Canlı milisaniye senkronize" else "⏸ Ortak duraklatıldı",
            isDownloadingTrack = false
        )
        publishToRoom(st.jamId, buildStatePayload(st.jamId, title, ytId, isPlaying, posMs))
    }

    fun sendJamStateToPeer(targetTag: String, title: String, ytId: String, isPlaying: Boolean, posMs: Long) {
        val st = _jamState.value
        if (!st.isActive || st.jamId.isBlank() || targetTag.isBlank()) return
        publishToPeer(targetTag, buildStatePayload(st.jamId, title, ytId, isPlaying, posMs))
    }

    fun broadcastJamPos(playFlag: Int, posMs: Long) {
        val st = _jamState.value
        if (!st.isActive || st.jamId.isBlank()) return
        val safePos = posMs.coerceAtLeast(0L)
        if (playFlag == 0) {
            _jamState.value = st.copy(isPlaying = false, positionMs = safePos, syncStatus = "⏸ Ortak duraklatıldı")
        } else if (playFlag == 1) {
            _jamState.value = st.copy(isPlaying = true, positionMs = safePos, syncStatus = "✓ Canlı milisaniye senkronize")
        } else {
            _jamState.value = st.copy(positionMs = safePos)
        }
        val payload = "JAM_POS|${sanitizeField(_myTag.value)}|${st.jamId}|$playFlag|$safePos"
        publishToRoom(st.jamId, payload)
    }

    fun requestJamPartnerState() {
        val st = _jamState.value
        if (!st.isActive || st.jamId.isBlank()) return
        val payload = "JAM_ASK|${sanitizeField(_myTag.value)}|${st.jamId}"
        val preferred = lastStateSenderTag.takeIf { s -> s.isNotBlank() && st.members.any { it.tag.equals(s, ignoreCase = true) } }
            ?: st.members.firstOrNull()?.tag
        if (!preferred.isNullOrBlank()) {
            publishToPeer(preferred, payload)
        } else {
            publishToRoom(st.jamId, payload)
        }
    }

    fun onDestroy() {
        leaveJamRoom()
        isRunning = false
        val byePayload = "BYE|${sanitizeField(_myTag.value)}"
        for (f in _friends.value) {
            val targetKey = tagToTopicKey(f.tag)
            sendMqttPublish(outputStream, "seloplayer/u/$targetKey", byePayload)
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    // ==================== HAM MQTT 3.1.1 PAKET KODLAYICILARI ====================

    private fun encodeRemainingLength(len: Int): ByteArray {
        val out = ArrayList<Byte>(4)
        var x = len
        do {
            var b = (x % 128).toByte()
            x /= 128
            if (x > 0) b = (b.toInt() or 0x80).toByte()
            out.add(b)
        } while (x > 0)
        return out.toByteArray()
    }

    private fun sendMqttConnect(out: OutputStream?, clientId: String) {
        if (out == null) return
        val cidBytes = clientId.toByteArray(Charsets.UTF_8)
        val remLen = 10 + 2 + cidBytes.size
        val remHdr = encodeRemainingLength(remLen)
        val pkt = ByteArray(1 + remHdr.size + remLen)
        var i = 0
        pkt[i++] = 0x10.toByte()
        for (b in remHdr) pkt[i++] = b
        pkt[i++] = 0x00.toByte(); pkt[i++] = 0x04.toByte()
        pkt[i++] = 'M'.code.toByte(); pkt[i++] = 'Q'.code.toByte(); pkt[i++] = 'T'.code.toByte(); pkt[i++] = 'T'.code.toByte()
        pkt[i++] = 0x04.toByte()
        pkt[i++] = 0x02.toByte()
        pkt[i++] = 0x00.toByte(); pkt[i++] = 0x1E.toByte()
        pkt[i++] = (cidBytes.size shr 8).toByte()
        pkt[i++] = (cidBytes.size and 0xFF).toByte()
        System.arraycopy(cidBytes, 0, pkt, i, cidBytes.size)

        synchronized(out) {
            out.write(pkt)
            out.flush()
        }
    }

    private fun sendMqttSubscribe(out: OutputStream?, topic: String) {
        if (out == null || topic.isBlank()) return
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val remLen = 2 + 2 + topBytes.size + 1
        val remHdr = encodeRemainingLength(remLen)
        val pkt = ByteArray(1 + remHdr.size + remLen)
        var i = 0
        pkt[i++] = 0x82.toByte()
        for (b in remHdr) pkt[i++] = b
        pkt[i++] = 0x00.toByte(); pkt[i++] = 0x01.toByte()
        pkt[i++] = (topBytes.size shr 8).toByte()
        pkt[i++] = (topBytes.size and 0xFF).toByte()
        System.arraycopy(topBytes, 0, pkt, i, topBytes.size)
        pkt[pkt.size - 1] = 0x00.toByte()

        try {
            synchronized(out) {
                out.write(pkt)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    private fun sendMqttUnsubscribe(out: OutputStream?, topic: String) {
        if (out == null || topic.isBlank()) return
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val remLen = 2 + 2 + topBytes.size
        val remHdr = encodeRemainingLength(remLen)
        val pkt = ByteArray(1 + remHdr.size + remLen)
        var i = 0
        pkt[i++] = 0xA2.toByte() // UNSUBSCRIBE (QoS 1)
        for (b in remHdr) pkt[i++] = b
        pkt[i++] = 0x00.toByte(); pkt[i++] = 0x02.toByte()
        pkt[i++] = (topBytes.size shr 8).toByte()
        pkt[i++] = (topBytes.size and 0xFF).toByte()
        System.arraycopy(topBytes, 0, pkt, i, topBytes.size)

        try {
            synchronized(out) {
                out.write(pkt)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    private fun sendMqttPublish(out: OutputStream?, topic: String, payload: String) {
        if (out == null || topic.isBlank()) return
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val payBytes = payload.toByteArray(Charsets.UTF_8)
        val remLen = 2 + topBytes.size + payBytes.size
        val remHdr = encodeRemainingLength(remLen)

        val pkt = ByteArray(1 + remHdr.size + remLen)
        var idx = 0
        pkt[idx++] = 0x30.toByte()
        for (b in remHdr) pkt[idx++] = b
        pkt[idx++] = (topBytes.size shr 8).toByte()
        pkt[idx++] = (topBytes.size and 0xFF).toByte()
        System.arraycopy(topBytes, 0, pkt, idx, topBytes.size)
        idx += topBytes.size
        System.arraycopy(payBytes, 0, pkt, idx, payBytes.size)

        try {
            synchronized(out) {
                out.write(pkt)
                out.flush()
            }
        } catch (_: Exception) {}
    }
}
