package com.aurns.player.friends

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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

data class JamRoomState(
    val isActive: Boolean = false,
    val isInviting: Boolean = false,
    val hasIncomingInvite: Boolean = false,
    val inviterTag: String = "",
    val inviterName: String = "",
    val partnerTag: String = "",
    val partnerName: String = "",
    val jamId: String = "",
    val currentTrackTitle: String = "",
    val isPlaying: Boolean = false,
    val positionSec: Int = 0
)

class FriendsManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("aurns_friends_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Kimlik Bilgileri
    private val _myTag = MutableStateFlow("")
    val myTag: StateFlow<String> = _myTag.asStateFlow()

    private val _myName = MutableStateFlow("")
    val myName: StateFlow<String> = _myName.asStateFlow()

    // Arkadaşlar ve Bekleyen İstekler
    private val _friends = MutableStateFlow<List<FriendItem>>(emptyList())
    val friends: StateFlow<List<FriendItem>> = _friends.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val pendingRequests: StateFlow<List<FriendRequest>> = _pendingRequests.asStateFlow()

    // Jam Canlı Senkronizasyon Durumu (Windows ve Android Birebir Uyumlu)
    private val _jamState = MutableStateFlow(JamRoomState())
    val jamState: StateFlow<JamRoomState> = _jamState.asStateFlow()

    // Ağ Bağlantı Durumu
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var socket: Socket? = null
    private var isRunning = false
    private var outputStream: OutputStream? = null

    // Yerel oynatma durumu
    private var localIsPlaying = false
    private var localTrackTitle = ""

    // Jam dinleyici callback'leri
    var onRemoteJamPlayRequest: ((title: String, posSec: Int) -> Unit)? = null
    var onRemoteJamPosRequest: ((playFlag: Int, posSec: Int) -> Unit)? = null

    // Kullanıcı varlık / çevrimiçi kontrol sorguları
    private val pendingUserQueries = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val BROKERS = listOf("broker.hivemq.com", "broker.emqx.io")

    init {
        loadFromPrefs()
        startNetworkLoop()
        startKeepAliveLoop()
    }

    private fun loadFromPrefs() {
        var tag = prefs.getString("my_tag", "") ?: ""
        var name = prefs.getString("my_name", "") ?: ""

        if (tag.isBlank()) {
            val deviceModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ]"), "").take(10).ifEmpty { "Android" }
            val num = 1000 + Random.nextInt(9000)
            tag = "$deviceModel#$num"
            name = deviceModel
            prefs.edit().putString("my_tag", tag).putString("my_name", name).apply()
        }

        _myTag.value = tag
        _myName.value = name

        // Arkadaş listesini yükle
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

        // Bekleyen istekleri yükle
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

    // KULLANICI ADINI GÜNCELLEME (Etiket Numarası #1234 Bizim Verdiğimiz Sabit Kimliktir, Asla Değişmez)
    fun updateMyName(newName: String) {
        val clean = newName.trim().replace(Regex("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ_-]"), "").take(20)
        if (clean.isNotBlank()) {
            _myName.value = clean
            val tag = _myTag.value
            // # ile başlayan numara sabit kalır!
            val hashPart = if (tag.contains("#")) "#" + tag.substringAfter("#") else "#${1000 + Random.nextInt(9000)}"
            val newTag = "$clean$hashPart"
            _myTag.value = newTag
            prefs.edit().putString("my_name", clean).putString("my_tag", newTag).apply()

            // Yeni gelen kutumuza abone ol
            subscribeToMyInbox()
            requestAllStatuses()
        }
    }

    private fun saveFriendsToPrefs() {
        val fArr = JSONArray()
        for (f in _friends.value) {
            val obj = JSONObject().apply {
                put("tag", f.tag)
                put("name", f.name)
            }
            fArr.put(obj)
        }

        val pArr = JSONArray()
        for (p in _pendingRequests.value) {
            val obj = JSONObject().apply {
                put("tag", p.tag)
                put("name", p.name)
            }
            pArr.put(obj)
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

    private fun startNetworkLoop() {
        isRunning = true
        scope.launch {
            var brokerIndex = 0
            while (isRunning) {
                val brokerHost = BROKERS[brokerIndex % BROKERS.size]
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(brokerHost, 1883), 5000)
                    s.soTimeout = 45000
                    socket = s
                    outputStream = s.getOutputStream()
                    val inputStream = s.getInputStream()

                    val clientId = "aurns_and_${System.currentTimeMillis() % 100000}"
                    sendMqttConnect(outputStream, clientId)

                    _isConnected.value = true
                    subscribeToMyInbox()

                    // Arkadaşların canlı durumunu iste
                    requestAllStatuses()

                    val buffer = ByteArray(4096)
                    while (isRunning && socket?.isConnected == true) {
                        val read = inputStream.read(buffer)
                        if (read <= 0) break
                        handleIncomingMqttPacket(buffer, read)
                    }
                } catch (e: Exception) {
                    brokerIndex++
                } finally {
                    _isConnected.value = false
                    try { socket?.close() } catch (ignored: Exception) {}
                    delay(2000)
                }
            }
        }
    }

    private fun startKeepAliveLoop() {
        scope.launch {
            while (isActive) {
                delay(20000)
                if (_isConnected.value && outputStream != null) {
                    try {
                        synchronized(outputStream!!) {
                            outputStream?.write(byteArrayOf(0xC0.toByte(), 0x00.toByte()))
                            outputStream?.flush()
                        }
                    } catch (e: Exception) {
                        try { socket?.close() } catch (ignored: Exception) {}
                    }
                }
            }
        }
    }

    private fun subscribeToMyInbox() {
        val myKey = tagToTopicKey(_myTag.value)
        if (myKey.isNotBlank()) {
            sendMqttSubscribe(outputStream, "seloplayer/u/$myKey")
        }
    }

    private fun handleIncomingMqttPacket(buf: ByteArray, len: Int) {
        val pktType = buf[0].toInt() and 0xF0
        if (pktType != 0x30) return // PUBLISH değilse atla

        var idx = 1
        var multiplier = 1
        var remLen = 0
        while (idx < len) {
            val digit = buf[idx].toInt() and 0xFF
            remLen += (digit and 0x7F) * multiplier
            multiplier *= 128
            idx++
            if ((digit and 0x80) == 0) break
        }

        if (idx + 2 > len) return
        val topLen = ((buf[idx].toInt() and 0xFF) shl 8) or (buf[idx + 1].toInt() and 0xFF)
        idx += 2
        if (idx + topLen > len) return

        val topic = String(buf, idx, topLen, Charsets.UTF_8)
        idx += topLen
        val payloadLen = len - idx
        val payload = if (payloadLen > 0) String(buf, idx, payloadLen, Charsets.UTF_8) else ""

        handleNetworkMessage(topic, payload)
    }

    private fun handleNetworkMessage(topic: String, payload: String) {
        val myKey = tagToTopicKey(_myTag.value)
        val myInbox = "seloplayer/u/$myKey"

        if (topic.equals(myInbox, ignoreCase = true)) {
            val parts = payload.split("|")
            if (parts.isEmpty()) return
            val action = parts[0]

            when (action) {
                // Arkadaşlık İsteği: REQ|fromTag|fromName
                "REQ" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        if (fromTag != _myTag.value && _friends.value.none { it.tag.equals(fromTag, ignoreCase = true) }) {
                            val cur = _pendingRequests.value.toMutableList()
                            if (cur.none { it.tag.equals(fromTag, ignoreCase = true) }) {
                                cur.add(FriendRequest(fromTag, fromName))
                                _pendingRequests.value = cur
                                saveFriendsToPrefs()
                            }
                        }
                    }
                }

                // İstek Kabul Edildi: ACC|fromTag|fromName
                "ACC" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        val cur = _friends.value.toMutableList()
                        if (cur.none { it.tag.equals(fromTag, ignoreCase = true) }) {
                            cur.add(FriendItem(fromTag, fromName, isOnline = true, currentTrack = "Çevrimiçi"))
                            _friends.value = cur
                            saveFriendsToPrefs()
                        }
                        sendPong(fromTag)
                        sendPing(fromTag)
                    }
                }

                // Durum Sorgusu: PING|fromTag|fromName
                "PING" -> {
                    if (parts.size >= 2) {
                        val fromTag = parts[1]
                        // Kim sorarsa sorsun canli oldugumuzu bildirmek icin aninda PONG ile yanitla!
                        sendPong(fromTag)
                    }
                }

                // Durum Yanıtı: PONG|fromTag|fromName|isPlaying|currentTrack
                "PONG" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        val isPlay = parts.getOrNull(3) == "1"
                        val track = parts.getOrNull(4)?.ifEmpty { if (isPlay) "Müzik Çalıyor" else "Çevrimiçi" } ?: "Çevrimiçi"

                        updateFriendStatus(fromTag, fromName, isOnline = true, isPlaying = isPlay, currentTrack = track)

                        // Varlik / cevrimici sorgusu yapan coroutine'i tetikle
                        val key = tagToTopicKey(fromTag)
                        pendingUserQueries[key]?.complete(true)
                    }
                }

                // Çevrimdışı Bildirimi: BYE|fromTag
                "BYE" -> {
                    if (parts.size >= 2) {
                        val fromTag = parts[1]
                        updateFriendStatus(fromTag, "", isOnline = false, isPlaying = false, currentTrack = "Çevrimdışı")
                    }
                }

                // ==================== JAM PROTOKOLÜ (WINDOWS & ANDROID BİREBİR) ====================

                // 1. JAM DAVETİ: JAM_INV|fromTag|fromName|jamId (veya JAM_INVITE)
                "JAM_INV", "JAM_INVITE" -> {
                    if (parts.size >= 4) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        val jamId = parts[3]
                        if (fromTag != _myTag.value) {
                            _jamState.value = _jamState.value.copy(
                                hasIncomingInvite = true,
                                inviterTag = fromTag,
                                inviterName = fromName,
                                jamId = jamId
                            )
                        }
                    }
                }

                // 2. JAM KABUL: JAM_JOIN|fromTag|fromName|jamId
                "JAM_JOIN" -> {
                    if (parts.size >= 4) {
                        val fromTag = parts[1]
                        val fromName = parts[2]
                        val jamId = parts[3]
                        val curJam = _jamState.value
                        if (curJam.isInviting && curJam.partnerTag.equals(fromTag, ignoreCase = true)) {
                            _jamState.value = JamRoomState(
                                isActive = true,
                                isInviting = false,
                                partnerTag = fromTag,
                                partnerName = fromName,
                                jamId = jamId
                            )
                            // Aktif parcayi hemen partnerimize gonder
                            if (localTrackTitle.isNotEmpty()) {
                                broadcastJamState(localTrackTitle, localIsPlaying, 0)
                            }
                        }
                    }
                }

                // 3. JAM RED: JAM_REJ|fromTag|jamId
                "JAM_REJ" -> {
                    val curJam = _jamState.value
                    if (curJam.isInviting) {
                        _jamState.value = JamRoomState()
                    }
                }

                // 4. JAM AYRIL: JAM_LEAVE|fromTag|jamId
                "JAM_LEAVE" -> {
                    _jamState.value = JamRoomState()
                }

                // 5. JAM ŞARKI DURUMU: JAM_STATE|fromTag|jamId|isPlaying|posSec|ytId|trackTitle
                "JAM_STATE" -> {
                    val curJam = _jamState.value
                    if (curJam.isActive && parts.size >= 7) {
                        val isPlay = parts[3] == "1"
                        val posSec = parts[4].toIntOrNull() ?: 0
                        val title = parts[6]

                        _jamState.value = curJam.copy(
                            currentTrackTitle = title,
                            isPlaying = isPlay,
                            positionSec = posSec
                        )

                        if (title.isNotEmpty()) {
                            onRemoteJamPlayRequest?.invoke(title, posSec)
                        }
                    }
                }

                // 6. JAM POZİSYON / DURAKLAT / SAR: JAM_POS|fromTag|jamId|playFlag|posSec
                "JAM_POS" -> {
                    val curJam = _jamState.value
                    if (curJam.isActive && parts.size >= 5) {
                        val playFlag = parts[3].toIntOrNull() ?: 0
                        val posSec = parts[4].toIntOrNull() ?: 0

                        _jamState.value = curJam.copy(
                            isPlaying = (playFlag == 1),
                            positionSec = posSec
                        )

                        onRemoteJamPosRequest?.invoke(playFlag, posSec)
                    }
                }
            }
        }
    }

    private fun updateFriendStatus(tag: String, name: String, isOnline: Boolean, isPlaying: Boolean, currentTrack: String) {
        val cur = _friends.value.toMutableList()
        val idx = cur.indexOfFirst { it.tag.equals(tag, ignoreCase = true) }
        if (idx != -1) {
            val old = cur[idx]
            cur[idx] = old.copy(
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
        val clean = targetTag.trim()
        if (clean.isBlank() || !clean.contains("#")) {
            return Result.failure(Exception("Etiket '#' içermelidir (Örn: Selim#1234)"))
        }
        if (clean.equals(_myTag.value, ignoreCase = true)) {
            return Result.failure(Exception("Kendinize arkadaşlık isteği gönderemezsiniz!"))
        }
        if (_friends.value.any { it.tag.equals(clean, ignoreCase = true) }) {
            return Result.failure(Exception("Bu kullanıcı zaten arkadaş listenizde!"))
        }

        val targetKey = tagToTopicKey(clean)
        val topic = "seloplayer/u/$targetKey"

        val queryDeferred = CompletableDeferred<Boolean>()
        pendingUserQueries[targetKey] = queryDeferred

        try {
            // Kullaniciyi ag uzerinden canli sorgulamak icin PING gonder
            val pingPayload = "PING|${_myTag.value}|${_myName.value}"
            sendMqttPublish(outputStream, topic, pingPayload)

            // 2.2 saniye boyunca kullanicidan PONG yaniti bekle
            val isFound = withTimeoutOrNull(2200) {
                queryDeferred.await()
            } ?: false

            if (!isFound) {
                return Result.failure(Exception("Böyle bir kullanıcı yok veya şu an çevrimdışı!"))
            }

            // Kullanici bulundu, istek paketini ilet
            val reqPayload = "REQ|${_myTag.value}|${_myName.value}"
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
            curF.add(FriendItem(req.tag, req.name, isOnline = true, currentTrack = "Çevrimiçi"))
            _friends.value = curF
        }

        val curP = _pendingRequests.value.toMutableList()
        curP.removeAll { it.tag.equals(targetTag, ignoreCase = true) }
        _pendingRequests.value = curP
        saveFriendsToPrefs()

        val targetKey = tagToTopicKey(req.tag)
        val topic = "seloplayer/u/$targetKey"
        val payload = "ACC|${_myTag.value}|${_myName.value}"

        scope.launch {
            sendMqttPublish(outputStream, topic, payload)
            sendPong(req.tag)
            sendPing(req.tag)
        }
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

    private fun sendPing(targetTag: String) {
        val targetKey = tagToTopicKey(targetTag)
        val topic = "seloplayer/u/$targetKey"
        val payload = "PING|${_myTag.value}|${_myName.value}"
        scope.launch {
            sendMqttPublish(outputStream, topic, payload)
        }
    }

    private fun sendPong(targetTag: String) {
        val targetKey = tagToTopicKey(targetTag)
        val topic = "seloplayer/u/$targetKey"
        val trackClean = localTrackTitle.replace('|', '-')
        val payload = "PONG|${_myTag.value}|${_myName.value}|${if (localIsPlaying) 1 else 0}|$trackClean"
        scope.launch {
            sendMqttPublish(outputStream, topic, payload)
        }
    }

    fun updateLocalPlaybackState(isPlaying: Boolean, trackTitle: String) {
        localIsPlaying = isPlaying
        localTrackTitle = trackTitle

        // Çevrimiçi tüm arkadaşlara bildir
        for (f in _friends.value) {
            sendPong(f.tag)
        }

        // Eğer aktif Jam oturumundaysak partnere anında bildir
        if (_jamState.value.isActive) {
            broadcastJamState(trackTitle, isPlaying, 0)
        }
    }

    // ==================== JAM CANLI SENKRONİZASYON (CROSS-PLATFORM) ====================

    fun inviteFriendToJam(friendTag: String, friendName: String = "") {
        if (friendTag.isBlank()) return
        val partnerKey = tagToTopicKey(friendTag)
        val myKey = tagToTopicKey(_myTag.value)
        val jamId = "jam_${myKey}_${System.currentTimeMillis() % 1000000}"

        _jamState.value = JamRoomState(
            isActive = false,
            isInviting = true,
            partnerTag = friendTag,
            partnerName = if (friendName.isNotBlank()) friendName else friendTag,
            jamId = jamId
        )

        // Windows ve Android uyumlu JAM_INV paketi
        val payload = "JAM_INV|${_myTag.value}|${_myName.value}|$jamId"
        scope.launch {
            sendMqttPublish(outputStream, "seloplayer/u/$partnerKey", payload)
        }
    }

    fun acceptJamInvite() {
        val st = _jamState.value
        if (!st.hasIncomingInvite || st.inviterTag.isBlank()) return

        val partnerTag = st.inviterTag
        val partnerName = st.inviterName
        val jamId = st.jamId

        _jamState.value = JamRoomState(
            isActive = true,
            isInviting = false,
            hasIncomingInvite = false,
            partnerTag = partnerTag,
            partnerName = partnerName,
            jamId = jamId
        )

        // Windows ve Android uyumlu JAM_JOIN paketi
        val partnerKey = tagToTopicKey(partnerTag)
        val payload = "JAM_JOIN|${_myTag.value}|${_myName.value}|$jamId"
        scope.launch {
            sendMqttPublish(outputStream, "seloplayer/u/$partnerKey", payload)
        }

        if (localTrackTitle.isNotEmpty()) {
            broadcastJamState(localTrackTitle, localIsPlaying, 0)
        }
    }

    fun rejectJamInvite() {
        val st = _jamState.value
        if (!st.hasIncomingInvite) return

        val partnerKey = tagToTopicKey(st.inviterTag)
        val payload = "JAM_REJ|${_myTag.value}|${st.jamId}"
        _jamState.value = JamRoomState()

        scope.launch {
            sendMqttPublish(outputStream, "seloplayer/u/$partnerKey", payload)
        }
    }

    fun leaveJamRoom() {
        val st = _jamState.value
        if (st.isActive && st.partnerTag.isNotBlank()) {
            val partnerKey = tagToTopicKey(st.partnerTag)
            val payload = "JAM_LEAVE|${_myTag.value}|${st.jamId}"
            scope.launch {
                sendMqttPublish(outputStream, "seloplayer/u/$partnerKey", payload)
            }
        }
        _jamState.value = JamRoomState()
    }

    fun broadcastJamState(title: String, isPlaying: Boolean, posSec: Int) {
        val st = _jamState.value
        if (!st.isActive || st.partnerTag.isBlank()) return

        val partnerKey = tagToTopicKey(st.partnerTag)
        val cleanTitle = title.replace('|', '-')
        val payload = "JAM_STATE|${_myTag.value}|${st.jamId}|${if (isPlaying) 1 else 0}|$posSec||$cleanTitle"
        scope.launch {
            sendMqttPublish(outputStream, "seloplayer/u/$partnerKey", payload)
        }
    }

    fun broadcastJamPos(playFlag: Int, posSec: Int) {
        val st = _jamState.value
        if (!st.isActive || st.partnerTag.isBlank()) return

        val partnerKey = tagToTopicKey(st.partnerTag)
        val payload = "JAM_POS|${_myTag.value}|${st.jamId}|$playFlag|$posSec"
        scope.launch {
            sendMqttPublish(outputStream, "seloplayer/u/$partnerKey", payload)
        }
    }

    fun onDestroy() {
        isRunning = false
        for (f in _friends.value) {
            val targetKey = tagToTopicKey(f.tag)
            sendMqttPublish(outputStream, "seloplayer/u/$targetKey", "BYE|${_myTag.value}")
        }
        try { socket?.close() } catch (ignored: Exception) {}
    }

    // ==================== HAM MQTT PAKETLERİ ====================

    private fun sendMqttConnect(out: OutputStream?, clientId: String) {
        if (out == null) return
        val cidBytes = clientId.toByteArray(Charsets.UTF_8)
        val remLen = 10 + 2 + cidBytes.size
        val pkt = ByteArray(2 + remLen)
        pkt[0] = 0x10.toByte() // CONNECT
        pkt[1] = remLen.toByte()
        pkt[2] = 0x00.toByte(); pkt[3] = 0x04.toByte()
        pkt[4] = 'M'.code.toByte(); pkt[5] = 'Q'.code.toByte(); pkt[6] = 'T'.code.toByte(); pkt[7] = 'T'.code.toByte()
        pkt[8] = 0x04.toByte() // Level 4 (3.1.1)
        pkt[9] = 0x02.toByte() // Clean Session
        pkt[10] = 0x00.toByte(); pkt[11] = 0x1E.toByte() // 30s KeepAlive
        pkt[12] = (cidBytes.size shr 8).toByte()
        pkt[13] = (cidBytes.size and 0xFF).toByte()
        System.arraycopy(cidBytes, 0, pkt, 14, cidBytes.size)

        synchronized(out) {
            out.write(pkt)
            out.flush()
        }
    }

    private fun sendMqttSubscribe(out: OutputStream?, topic: String) {
        if (out == null || topic.isBlank()) return
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val remLen = 2 + 2 + topBytes.size + 1
        val pkt = ByteArray(2 + remLen)
        pkt[0] = 0x82.toByte() // SUBSCRIBE (QoS 1)
        pkt[1] = remLen.toByte()
        pkt[2] = 0x00.toByte(); pkt[3] = 0x01.toByte() // Packet ID = 1
        pkt[4] = (topBytes.size shr 8).toByte()
        pkt[5] = (topBytes.size and 0xFF).toByte()
        System.arraycopy(topBytes, 0, pkt, 6, topBytes.size)
        pkt[pkt.size - 1] = 0x00.toByte() // Requested QoS 0

        try {
            synchronized(out) {
                out.write(pkt)
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendMqttPublish(out: OutputStream?, topic: String, payload: String) {
        if (out == null || topic.isBlank()) return
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val payBytes = payload.toByteArray(Charsets.UTF_8)
        val remLen = 2 + topBytes.size + payBytes.size

        val header = mutableListOf<Byte>()
        header.add(0x30.toByte()) // PUBLISH (QoS 0)
        var x = remLen
        do {
            var encodedByte = (x % 128).toByte()
            x /= 128
            if (x > 0) encodedByte = (encodedByte.toInt() or 0x80).toByte()
            header.add(encodedByte)
        } while (x > 0)

        val pkt = ByteArray(header.size + remLen)
        for (i in header.indices) pkt[i] = header[i]
        var idx = header.size
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
