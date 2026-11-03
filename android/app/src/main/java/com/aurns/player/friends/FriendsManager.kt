package com.aurns.player.friends

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
        if (members.any { FriendsManager.sameUserTag(it.tag, friendTag) }) return true
        if (isInviting && FriendsManager.sameUserTag(lastInvitedTag, friendTag)) return true
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

    companion object {
        private const val DEDUP_SLOTS = 32
        private const val DEDUP_WINDOW_MS = 900L

        // Windows src/network.c Network_TagToTopicKeyW ile %100 birebir Türkçe->ASCII katlama!
        fun tagToTopicKey(tag: String): String {
            if (tag.isBlank()) return ""
            val sb = StringBuilder(tag.length)
            for (ch in tag) {
                val mapped: Char = when (ch) {
                    'ı', 'İ', 'I', 'i' -> 'i'
                    'ş', 'Ş', 's', 'S' -> 's'
                    'ğ', 'Ğ', 'g', 'G' -> 'g'
                    'ü', 'Ü', 'u', 'U' -> 'u'
                    'ö', 'Ö', 'o', 'O' -> 'o'
                    'ç', 'Ç', 'c', 'C' -> 'c'
                    in 'A'..'Z' -> (ch.code - 'A'.code + 'a'.code).toChar()
                    in 'a'..'z', in '0'..'9', '-', '_' -> ch
                    else -> '_'
                }
                sb.append(mapped)
            }
            return sb.toString()
        }

        // Windows src/network.c Network_ExtractTagIdW ile %100 birebir sabit #numara çıkarıcı!
        fun extractTagId(tag: String): String {
            if (tag.isBlank()) return ""
            val hashIdx = tag.indexOf('#')
            val src = if (hashIdx >= 0) tag.substring(hashIdx + 1) else tag
            val sb = StringBuilder(8)
            for (ch in src) {
                if (ch in '0'..'9') {
                    sb.append(ch)
                    if (sb.length >= 12) break
                } else if (sb.isNotEmpty()) {
                    break
                }
            }
            return sb.toString()
        }

        // Windows src/friends.c Friends_SameUserTag ile %100 birebir: isim değişse de #numara aynıysa aynı kişidir!
        fun sameUserTag(tagA: String, tagB: String): Boolean {
            if (tagA.isBlank() || tagB.isBlank()) return false
            val idA = extractTagId(tagA)
            val idB = extractTagId(tagB)
            if (idA.length >= 3 && idB.length >= 3) {
                return idA == idB
            }
            return tagToTopicKey(tagA) == tagToTopicKey(tagB)
        }
    }

    private class BrokerConn(val host: String, val slotIndex: Int) {
        @Volatile var socket: Socket? = null
        @Volatile var output: OutputStream? = null
        @Volatile var connected: Boolean = false
    }

    private val brokers = arrayOf(
        BrokerConn("broker.emqx.io", 0),
        BrokerConn("broker.hivemq.com", 1)
    )

    private val prefs = context.getSharedPreferences("aurns_friends_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val packetIdGen = AtomicInteger(1)

    private val dedupHashes = LongArray(DEDUP_SLOTS)
    private val dedupTimes = LongArray(DEDUP_SLOTS)
    private var dedupCursor = 0
    private val dedupLock = Any()

    private var myFixedId: String = ""

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

    @Volatile private var isRunning = false
    private val subscribedTopics = Collections.synchronizedSet(LinkedHashSet<String>())

    private var localIsPlaying = false
    private var localTrackTitle = ""
    private var inviteSentAtMs = 0L
    private var incomingInviteAtMs = 0L
    private var lastStateSenderTag = ""

    private var jamWakeLock: PowerManager.WakeLock? = null
    private var jamWifiLock: WifiManager.WifiLock? = null

    // Milisaniye hassasiyetli Jam olay dinleyicileri (Windows src/jam.c ile %100 birebir)
    var onRemoteJamStateRequest: ((title: String, ytId: String, isPlaying: Boolean, posMs: Long, recvTimeMs: Long) -> Unit)? = null
    var onRemoteJamPosRequest: ((playFlag: Int, posMs: Long, recvTimeMs: Long) -> Unit)? = null
    var onRemoteJamAskRequest: ((requesterTag: String) -> Unit)? = null
    var onJamRoomClosed: (() -> Unit)? = null

    private val pendingUserQueries = ConcurrentHashMap<String, CompletableDeferred<String>>()

    init {
        loadFromPrefs()
        startDualBrokerNetwork()
        startKeepAliveAndTimeoutLoop()
    }

    private fun sanitizeField(s: String): String {
        return s.replace('|', '-').replace('\r', '-').replace('\n', '-')
    }

    private fun sanitizeTopic(topic: String): String {
        return topic.replace('#', '_').replace('+', '_')
    }

    private fun cleanDisplayName(raw: String): String {
        val sb = StringBuilder(24)
        for (ch in raw) {
            if (ch.code >= 32 && ch != '#' && ch != '"' && ch != '|' && ch != '\\') {
                if (ch == ' ' && sb.isEmpty()) continue
                sb.append(ch)
                if (sb.length >= 24) break
            }
        }
        return sb.toString().trim()
    }

    private fun getDeterministicTagNumber(): Int {
        val androidId = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: ""
        } catch (_: Exception) { "" }

        val seedSource = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            "${Build.BOARD}_${Build.BRAND}_${Build.DEVICE}_${Build.MODEL}_${Build.MANUFACTURER}"
        }
        var h = 0x5E1100
        for (ch in seedSource) {
            h = h * 31 + ch.code
        }
        val positive = h and 0x7FFFFFFF
        return 1000 + (positive % 9000)
    }

    private fun getExternalIdentityBackupFile(): File? {
        return try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val aurnsDir = File(musicDir, "Aurns")
            if (!aurnsDir.exists()) aurnsDir.mkdirs()
            File(aurnsDir, ".aurns_identity.tag")
        } catch (_: Exception) {
            null
        }
    }

    private fun readIdentityTriple(file: File?): Triple<String, String, String>? {
        if (file == null || !file.exists()) return null
        return try {
            val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            when {
                lines.size >= 3 && lines[0].all { it.isDigit() } -> {
                    Triple(lines[0], lines[1], lines[2])
                }
                lines.isNotEmpty() && lines[0].contains("#") -> {
                    val fTag = lines[0]
                    val fId = extractTagId(fTag)
                    val fName = if (lines.size >= 2) lines[1] else fTag.substringBefore("#")
                    Triple(fId, fName, fTag)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun persistIdentityEverywhere(fixedId: String, name: String, fullTag: String) {
        prefs.edit()
            .putString("my_fixed_id", fixedId)
            .putString("my_name", name)
            .putString("my_tag", fullTag)
            .apply()

        val content = "$fixedId\n$name\n$fullTag\n"
        try {
            File(context.filesDir, "aurns_identity.tag").writeText(content)
        } catch (_: Exception) {}

        try {
            getExternalIdentityBackupFile()?.writeText(content)
        } catch (_: Exception) {}
    }

    private fun loadFromPrefs() {
        val prefsFixedId = (prefs.getString("my_fixed_id", "") ?: "").trim()
        val prefsTag = (prefs.getString("my_tag", "") ?: "").trim()
        val prefsName = (prefs.getString("my_name", "") ?: "").trim()

        val internalBackup = readIdentityTriple(File(context.filesDir, "aurns_identity.tag"))
        val externalBackup = readIdentityTriple(getExternalIdentityBackupFile())

        // 1. Sabit #numara kimliğini kilitle (var olan numarayı ASLA bozma, yoksa donanımdan üret)
        val parsedPrefsId = extractTagId(prefsTag)
        val resolvedFixedId = when {
            prefsFixedId.length >= 3 && prefsFixedId.all { it.isDigit() } -> prefsFixedId
            parsedPrefsId.length >= 3 -> parsedPrefsId
            internalBackup != null && internalBackup.first.length >= 3 -> internalBackup.first
            externalBackup != null && externalBackup.first.length >= 3 -> externalBackup.first
            else -> getDeterministicTagNumber().toString()
        }
        myFixedId = resolvedFixedId

        // 2. Kalıcı kullanıcı adını yükle (kullanıcı değiştirene kadar kalıcıdır)
        val defaultModelName = cleanDisplayName(Build.MODEL).take(12).ifBlank { "Kullanici" }
        val resolvedName = cleanDisplayName(prefsName)
            .ifBlank { cleanDisplayName(internalBackup?.second ?: "") }
            .ifBlank { cleanDisplayName(externalBackup?.second ?: "") }
            .ifBlank { cleanDisplayName(prefsTag.substringBefore("#", "")) }
            .ifBlank { defaultModelName }

        val fullTag = "$resolvedName#$myFixedId"
        _myName.value = resolvedName
        _myTag.value = fullTag
        persistIdentityEverywhere(myFixedId, resolvedName, fullTag)

        val friendsJson = prefs.getString("friends_json", "[]") ?: "[]"
        val fList = mutableListOf<FriendItem>()
        try {
            val arr = JSONArray(friendsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val t = obj.optString("tag", "").trim()
                val n = obj.optString("name", "").trim()
                if (t.isNotBlank() && fList.none { sameUserTag(it.tag, t) }) {
                    fList.add(FriendItem(tag = t, name = n.ifBlank { t }))
                }
            }
        } catch (_: Exception) {}
        _friends.value = fList

        val pendingJson = prefs.getString("pending_json", "[]") ?: "[]"
        val pList = mutableListOf<FriendRequest>()
        try {
            val arr = JSONArray(pendingJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val t = obj.optString("tag", "").trim()
                val n = obj.optString("name", "").trim()
                if (t.isNotBlank() && pList.none { sameUserTag(it.tag, t) }) {
                    pList.add(FriendRequest(tag = t, name = n.ifBlank { t }))
                }
            }
        } catch (_: Exception) {}
        _pendingRequests.value = pList
    }

    fun updateMyName(newName: String) {
        val clean = cleanDisplayName(newName)
        if (clean.isBlank()) return

        if (myFixedId.length < 3) {
            myFixedId = extractTagId(_myTag.value).takeIf { it.length >= 3 }
                ?: getDeterministicTagNumber().toString()
        }

        val oldKey = tagToTopicKey(_myTag.value)
        val newTag = "$clean#$myFixedId"

        _myName.value = clean
        _myTag.value = newTag
        persistIdentityEverywhere(myFixedId, clean, newTag)

        val newKey = tagToTopicKey(newTag)
        scope.launch {
            if (oldKey.isNotBlank() && !oldKey.equals(newKey, ignoreCase = true)) {
                unsubscribeTopic("seloplayer/u/$oldKey")
            }
            subscribeMyCoreChannels()
            publishMyDirectoryRecord()
            for (f in _friends.value) {
                sendPong(f.tag)
            }
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

    private fun roomTopic(jamId: String): String = "seloplayer/jam/$jamId"

    private fun updateJamLocks(keepAwake: Boolean) {
        try {
            if (keepAwake) {
                if (jamWakeLock == null) {
                    val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    jamWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aurns::JamWakeLock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (jamWakeLock?.isHeld != true) {
                    jamWakeLock?.acquire(3 * 60 * 60 * 1000L)
                }

                if (jamWifiLock == null) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    jamWifiLock = wm?.createWifiLock(mode, "Aurns::JamWifiLock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (jamWifiLock?.isHeld != true) {
                    jamWifiLock?.acquire()
                }
            } else {
                if (jamWakeLock?.isHeld == true) jamWakeLock?.release()
                if (jamWifiLock?.isHeld == true) jamWifiLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun subscribeMyCoreChannels() {
        val id = myFixedId.ifBlank { extractTagId(_myTag.value) }
        if (id.isNotBlank()) {
            subscribedTopics.add("seloplayer/id/$id")
            subscribedTopics.add("seloplayer/mbox/$id")
        }
        val myKey = tagToTopicKey(_myTag.value)
        if (myKey.isNotBlank()) {
            subscribedTopics.add("seloplayer/u/$myKey")
        }

        val topicsSnap = synchronized(subscribedTopics) { subscribedTopics.toList() }
        for (b in brokers) {
            if (b.connected) {
                for (t in topicsSnap) {
                    sendMqttSubscribe(b.output, t)
                }
            }
        }
    }

    private fun publishMyDirectoryRecord() {
        val id = myFixedId.ifBlank { extractTagId(_myTag.value) }
        if (id.isBlank()) return
        val regTopic = "seloplayer/reg/$id"
        val regPayload = "REG|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
        publishAll(regTopic, regPayload, retain = true)
    }

    private fun isMyInboxTopic(topic: String): Boolean {
        if (topic.isBlank()) return false
        val id = myFixedId.ifBlank { extractTagId(_myTag.value) }
        if (id.isNotBlank()) {
            if (topic.equals("seloplayer/id/$id", ignoreCase = true)) return true
            if (topic.equals("seloplayer/mbox/$id", ignoreCase = true)) return true
        }
        val myKey = tagToTopicKey(_myTag.value)
        if (myKey.isNotBlank() && topic.equals("seloplayer/u/$myKey", ignoreCase = true)) return true
        return false
    }

    private fun subscribeTopic(topic: String) {
        if (topic.isBlank()) return
        val safe = sanitizeTopic(topic)
        subscribedTopics.add(safe)
        for (b in brokers) {
            if (b.connected) {
                sendMqttSubscribe(b.output, safe)
            }
        }
    }

    private fun unsubscribeTopic(topic: String) {
        if (topic.isBlank()) return
        val safe = sanitizeTopic(topic)
        subscribedTopics.remove(safe)
        for (b in brokers) {
            if (b.connected) {
                sendMqttUnsubscribe(b.output, safe)
            }
        }
    }

    private fun publishAll(topic: String, payload: String, retain: Boolean = false) {
        if (topic.isBlank()) return
        val safe = sanitizeTopic(topic)
        for (b in brokers) {
            if (b.connected) {
                sendMqttPublish(b.output, safe, payload, retain)
            }
        }
    }

    private fun hashMessage(topic: String, payload: String): Long {
        var h = -3750763034362895579L // FNV-1a 64-bit offset basis
        for (c in topic) {
            h = h xor (c.code and 0xFF).toLong()
            h *= 1099511628211L
        }
        h = h xor 0xFFL
        h *= 1099511628211L
        for (c in payload) {
            h = h xor (c.code and 0xFF).toLong()
            h *= 1099511628211L
        }
        return h
    }

    private fun shouldDropDuplicate(topic: String, payload: String): Boolean {
        if (payload.isEmpty()) return true
        val h = hashMessage(topic, payload)
        val now = System.currentTimeMillis()
        synchronized(dedupLock) {
            for (i in 0 until DEDUP_SLOTS) {
                if (dedupHashes[i] == h && dedupTimes[i] != 0L && (now - dedupTimes[i]) < DEDUP_WINDOW_MS) {
                    return true
                }
            }
            dedupHashes[dedupCursor] = h
            dedupTimes[dedupCursor] = now
            dedupCursor = (dedupCursor + 1) % DEDUP_SLOTS
            return false
        }
    }

    private fun connectBrokerSocket(host: String): Socket {
        // 1. Önce TLS 8883 dene (Mobil operatör 1883 port engellerini ve CGNAT filtrelerini %100 aşar)
        try {
            val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            ssl.tcpNoDelay = true
            ssl.connect(InetSocketAddress(host, 8883), 3500)
            ssl.soTimeout = 4500
            ssl.startHandshake()
            return ssl
        } catch (_: Exception) {}

        // 2. Yedek olarak standart TCP 1883 dene
        val tcp = Socket()
        tcp.tcpNoDelay = true
        tcp.connect(InetSocketAddress(host, 1883), 4000)
        return tcp
    }

    private fun startDualBrokerNetwork() {
        isRunning = true
        for (broker in brokers) {
            scope.launch {
                runSingleBrokerLoop(broker)
            }
        }
    }

    private suspend fun runSingleBrokerLoop(broker: BrokerConn) {
        while (isRunning) {
            var s: Socket? = null
            try {
                s = connectBrokerSocket(broker.host)
                s.soTimeout = 4500
                val out = s.getOutputStream()
                val inData = DataInputStream(s.getInputStream())

                // %100 saf ASCII alfanumerik ClientId + CONNACK doğrulaması
                val cleanDigits = myFixedId.filter { it.isLetterOrDigit() }.ifBlank { "0000" }
                val clientId = "auand${broker.slotIndex}${cleanDigits}${System.currentTimeMillis() % 1000000L}"
                sendMqttConnect(out, clientId)

                val connack = ByteArray(4)
                inData.readFully(connack)
                if ((connack[0].toInt() and 0xFF) != 0x20 || connack[3].toInt() != 0) {
                    throw IllegalStateException("MQTT CONNACK reddedildi")
                }

                s.soTimeout = 45000
                broker.socket = s
                broker.output = out
                broker.connected = true
                _isConnected.value = brokers.any { it.connected }

                val id = myFixedId.ifBlank { extractTagId(_myTag.value) }
                if (id.isNotBlank()) {
                    subscribedTopics.add("seloplayer/id/$id")
                    subscribedTopics.add("seloplayer/mbox/$id")
                }
                val myKey = tagToTopicKey(_myTag.value)
                if (myKey.isNotBlank()) {
                    subscribedTopics.add("seloplayer/u/$myKey")
                }

                val topicsSnapshot = synchronized(subscribedTopics) { subscribedTopics.toList() }
                for (t in topicsSnapshot) {
                    sendMqttSubscribe(out, t)
                }

                // Bu broker üzerine kalıcı dizin kartımızı (RETAIN=1) yaz
                if (id.isNotBlank()) {
                    val regPayload = "REG|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
                    sendMqttPublish(out, "seloplayer/reg/$id", regPayload, retain = true)
                }

                requestAllStatuses()

                while (isRunning && s.isConnected && !s.isClosed) {
                    val header = inData.readUnsignedByte()
                    var multiplier = 1
                    var remLen = 0
                    var digit: Int
                    do {
                        digit = inData.readUnsignedByte()
                        remLen += (digit and 0x7F) * multiplier
                        multiplier *= 128
                    } while ((digit and 0x80) != 0 && multiplier <= 128 * 128 * 128)

                    if (remLen < 0 || remLen > 65535) break
                    val packetBody = ByteArray(remLen)
                    if (remLen > 0) {
                        inData.readFully(packetBody)
                    }

                    val pktType = header and 0xF0
                    if (pktType == 0x30 && remLen >= 2) {
                        val topLen = ((packetBody[0].toInt() and 0xFF) shl 8) or (packetBody[1].toInt() and 0xFF)
                        val qos = (header shr 1) and 0x03
                        val pktIdBytes = if (qos > 0) 2 else 0

                        if (2 + topLen + pktIdBytes <= remLen) {
                            val topic = String(packetBody, 2, topLen, Charsets.UTF_8)
                            val payOffset = 2 + topLen + pktIdBytes
                            val payloadLen = remLen - payOffset
                            val payload = if (payloadLen > 0) {
                                String(packetBody, payOffset, payloadLen, Charsets.UTF_8)
                            } else ""

                            if (!shouldDropDuplicate(topic, payload)) {
                                try {
                                    handleNetworkMessage(topic, payload)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                broker.connected = false
                broker.output = null
                broker.socket = null
                _isConnected.value = brokers.any { it.connected }
                try { s?.close() } catch (_: Exception) {}
                if (isRunning) delay(1500)
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

                // 10 saniyede bir her iki brokera da MQTT PINGREQ + canlılık yoklaması gönder
                if (tickCount % 2 == 0) {
                    for (b in brokers) {
                        val out = b.output
                        if (b.connected && out != null) {
                            try {
                                synchronized(out) {
                                    out.write(byteArrayOf(0xC0.toByte(), 0x00.toByte()))
                                    out.flush()
                                }
                            } catch (_: Exception) {
                                try { b.socket?.close() } catch (_: Exception) {}
                            }
                        }
                    }
                    if (_isConnected.value) {
                        requestAllStatuses()
                    }
                }

                // Çevrimdışı düşen arkadaşları işaretle (32 sn tolerans — Windows src/friends.c ile birebir)
                val curFriends = _friends.value
                if (curFriends.any { it.isOnline && now - it.lastSeenTimestamp > 32_000L }) {
                    _friends.value = curFriends.map { f ->
                        if (f.isOnline && now - f.lastSeenTimestamp > 32_000L) {
                            f.copy(isOnline = false, isPlaying = false, currentTrack = "Çevrimdışı")
                        } else f
                    }
                }

                // Jam davet zaman aşımları (45 sn)
                val st = _jamState.value
                if (st.isInviting && !st.isActive && inviteSentAtMs > 0L && now - inviteSentAtMs > 45_000L) {
                    resetJamRoomInternal(unsub = true)
                }
                if (st.hasIncomingInvite && incomingInviteAtMs > 0L && now - incomingInviteAtMs > 45_000L) {
                    _jamState.value = _jamState.value.copy(
                        hasIncomingInvite = false,
                        pendingJamId = "",
                        inviterTag = "",
                        inviterName = ""
                    )
                }
            }
        }
    }

    private fun upsertMemberInList(list: List<JamMember>, tag: String, name: String?): Pair<List<JamMember>, Boolean> {
        if (tag.isBlank() || sameUserTag(tag, _myTag.value)) return Pair(list, false)
        val mutable = list.toMutableList()
        val idx = mutable.indexOfFirst { sameUserTag(it.tag, tag) }
        return if (idx >= 0) {
            val updatedName = if (!name.isNullOrBlank()) name else mutable[idx].name
            mutable[idx] = mutable[idx].copy(tag = tag, name = updatedName)
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
        updateJamLocks(keepAwake = false)
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

    private fun completeMatchingProbes(foundTag: String) {
        if (foundTag.isBlank()) return
        val foundId = extractTagId(foundTag)
        val foundKey = tagToTopicKey(foundTag)
        for ((queryKey, def) in pendingUserQueries) {
            if ((foundId.isNotBlank() && queryKey == foundId) ||
                queryKey.equals(foundKey, ignoreCase = true) ||
                sameUserTag(queryKey, foundTag)
            ) {
                def.complete(foundTag)
            }
        }
    }

    private fun handleNetworkMessage(topic: String, payload: String) {
        if (topic.isBlank() || payload.isBlank()) return

        // 0. Dizin kaydı yanıtı: seloplayer/reg/<id> -> REG|realTag|realName
        if (topic.startsWith("seloplayer/reg/", ignoreCase = true)) {
            if (payload.startsWith("REG|")) {
                val parts = payload.split("|")
                val regTag = parts.getOrNull(1)?.trim().orEmpty()
                if (regTag.isNotBlank()) {
                    completeMatchingProbes(regTag)
                }
            }
            return
        }

        val myTagNow = _myTag.value
        val curJam = _jamState.value
        val activeRoomTopic = if (curJam.jamId.isNotBlank()) roomTopic(curJam.jamId) else ""

        val isInbox = isMyInboxTopic(topic)
        val isRoom = activeRoomTopic.isNotBlank() && topic.equals(activeRoomTopic, ignoreCase = true)
        if (!isInbox && !isRoom) return

        // Kalıcı posta kutusundan (mbox) okunduysa işledikten sonra temizle
        val id = myFixedId.ifBlank { extractTagId(myTagNow) }
        if (id.isNotBlank() && topic.equals("seloplayer/mbox/$id", ignoreCase = true)) {
            publishAll("seloplayer/mbox/$id", "", retain = true)
        }

        val parts = payload.split("|")
        if (parts.isEmpty()) return
        val action = parts[0]

        if (isInbox) {
            when (action) {
                "REQ" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1].trim()
                        val fromName = parts[2].trim()
                        if (fromTag.isNotBlank() &&
                            !sameUserTag(fromTag, myTagNow) &&
                            _friends.value.none { sameUserTag(it.tag, fromTag) }
                        ) {
                            val cur = _pendingRequests.value.toMutableList()
                            if (cur.none { sameUserTag(it.tag, fromTag) }) {
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
                        val fromTag = parts[1].trim()
                        val fromName = parts[2].trim()
                        if (fromTag.isNotBlank() && !sameUserTag(fromTag, myTagNow)) {
                            val cur = _friends.value.toMutableList()
                            val existingIdx = cur.indexOfFirst { sameUserTag(it.tag, fromTag) }
                            if (existingIdx < 0) {
                                cur.add(
                                    FriendItem(
                                        tag = fromTag,
                                        name = fromName.ifBlank { fromTag },
                                        isOnline = true,
                                        currentTrack = "Çevrimiçi",
                                        lastSeenTimestamp = System.currentTimeMillis()
                                    )
                                )
                                _friends.value = cur
                                saveFriendsToPrefs()
                            } else {
                                val old = cur[existingIdx]
                                val updated = old.copy(
                                    tag = fromTag,
                                    name = fromName.ifBlank { old.name },
                                    isOnline = true,
                                    lastSeenTimestamp = System.currentTimeMillis()
                                )
                                cur[existingIdx] = updated
                                _friends.value = cur
                                if (old.tag != updated.tag || old.name != updated.name) {
                                    saveFriendsToPrefs()
                                }
                            }
                            sendPong(fromTag)
                            sendPing(fromTag)
                        }
                    }
                    return
                }
                "PING" -> {
                    if (parts.size >= 2) {
                        val fromTag = parts[1].trim()
                        val fromName = parts.getOrNull(2)?.trim().orEmpty()
                        if (fromTag.isNotBlank() && !sameUserTag(fromTag, myTagNow)) {
                            if (_friends.value.any { sameUserTag(it.tag, fromTag) }) {
                                updateFriendTouch(fromTag, fromName)
                            }
                            sendPong(fromTag)
                        }
                    }
                    return
                }
                "PONG" -> {
                    if (parts.size >= 3) {
                        val fromTag = parts[1].trim()
                        val fromName = parts[2].trim()
                        val isPlay = parts.getOrNull(3) == "1"
                        val track = parts.getOrNull(4)?.ifEmpty {
                            if (isPlay) "Müzik Çalıyor" else "Çevrimiçi • Boşta"
                        } ?: "Çevrimiçi • Boşta"

                        if (fromTag.isNotBlank()) {
                            updateFriendStatus(fromTag, fromName, isOnline = true, isPlaying = isPlay, currentTrack = track)
                            completeMatchingProbes(fromTag)
                        }
                    }
                    return
                }
                "BYE" -> {
                    if (parts.size >= 2) {
                        val fromTag = parts[1].trim()
                        updateFriendStatus(fromTag, "", isOnline = false, isPlaying = false, currentTrack = "Çevrimdışı")
                    }
                    return
                }
                "JAM_INV", "JAM_INVITE" -> {
                    if (parts.size >= 4) {
                        val fromTag = parts[1].trim()
                        val fromName = parts[2].trim()
                        val invJamId = parts[3].trim()
                        if (fromTag.isBlank() || invJamId.isBlank() || sameUserTag(fromTag, myTagNow)) return
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
                    val fromTag = parts[1].trim()
                    val fromName = parts[2].trim()
                    val msgJamId = parts[3].trim()
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || sameUserTag(fromTag, myTagNow)) return

                    val (newMembers, isNew) = upsertMemberInList(st.members, fromTag, fromName)
                    val newOutgoing = if (isNew) (st.outgoingInviteCount - 1).coerceAtLeast(0) else st.outgoingInviteCount
                    _jamState.value = st.copy(
                        isActive = true,
                        isInviting = false,
                        members = newMembers,
                        outgoingInviteCount = newOutgoing,
                        syncStatus = if (st.isDownloadingTrack) st.syncStatus else "Canlı milisaniye senkronizasyonu aktif"
                    )
                    updateJamLocks(keepAwake = true)

                    if (isNew) {
                        val herePayload = "JAM_HERE|${sanitizeField(myTagNow)}|${sanitizeField(_myName.value)}|$msgJamId"
                        publishToPeer(fromTag, herePayload)
                        publishToRoom(msgJamId, herePayload)
                        if (!st.isDownloadingTrack) {
                            onRemoteJamAskRequest?.invoke(fromTag)
                        }
                    }
                }
            }

            // JAM_HERE|fromTag|fromName|jamId
            "JAM_HERE" -> {
                if (parts.size >= 4) {
                    val fromTag = parts[1].trim()
                    val fromName = parts[2].trim()
                    val msgJamId = parts[3].trim()
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || sameUserTag(fromTag, myTagNow)) return

                    val (newMembers, _) = upsertMemberInList(st.members, fromTag, fromName)
                    _jamState.value = st.copy(
                        isActive = true,
                        isInviting = false,
                        members = newMembers
                    )
                    updateJamLocks(keepAwake = true)
                }
            }

            // JAM_REJ|fromTag|jamId
            "JAM_REJ" -> {
                if (parts.size >= 3) {
                    val msgJamId = parts[2].trim()
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
                    val fromTag = parts[1].trim()
                    val msgJamId = parts[2].trim()
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || sameUserTag(fromTag, myTagNow)) return

                    val remaining = st.members.filterNot { sameUserTag(it.tag, fromTag) }
                    if (sameUserTag(lastStateSenderTag, fromTag)) {
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
                    val fromTag = parts[1].trim()
                    val msgJamId = parts[2].trim()
                    val st = _jamState.value
                    if (st.isActive && st.jamId == msgJamId && fromTag.isNotBlank() && !sameUserTag(fromTag, myTagNow)) {
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
                    val fromTag = parts[1].trim()
                    val msgJamId = parts[2].trim()
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || sameUserTag(fromTag, myTagNow)) return

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
                    updateJamLocks(keepAwake = true)

                    if (title.isNotEmpty() || ytId.isNotEmpty()) {
                        onRemoteJamStateRequest?.invoke(title, ytId, isPlay, posMs, recvNow)
                    }
                }
            }

            // JAM_POS|fromTag|jamId|playFlag(-1=seek,0=pause,1=play)|posMs
            "JAM_POS" -> {
                if (parts.size >= 5) {
                    val fromTag = parts[1].trim()
                    val msgJamId = parts[2].trim()
                    val st = _jamState.value
                    if ((!st.isActive && !st.isInviting) || st.jamId != msgJamId) return
                    if (fromTag.isBlank() || sameUserTag(fromTag, myTagNow)) return

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
        val idx = cur.indexOfFirst { sameUserTag(it.tag, tag) }
        if (idx != -1) {
            val old = cur[idx]
            val updated = old.copy(
                tag = if (tag.isNotBlank()) tag else old.tag,
                name = if (name.isNotBlank()) name else old.name,
                isOnline = true,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            cur[idx] = updated
            _friends.value = cur
            if (old.tag != updated.tag || old.name != updated.name) {
                saveFriendsToPrefs()
            }
        }
    }

    private fun updateFriendStatus(tag: String, name: String, isOnline: Boolean, isPlaying: Boolean, currentTrack: String) {
        val cur = _friends.value.toMutableList()
        val idx = cur.indexOfFirst { sameUserTag(it.tag, tag) }
        if (idx != -1) {
            val old = cur[idx]
            val updated = old.copy(
                tag = if (isOnline && tag.isNotBlank()) tag else old.tag,
                name = if (name.isNotBlank()) name else old.name,
                isOnline = isOnline,
                isPlaying = isPlaying,
                currentTrack = currentTrack,
                lastSeenTimestamp = if (isOnline) System.currentTimeMillis() else old.lastSeenTimestamp
            )
            cur[idx] = updated
            _friends.value = cur
            if (old.tag != updated.tag || old.name != updated.name) {
                saveFriendsToPrefs()
            }
        }
    }

    // ==================== KULLANICI ARAMA & ARKADAŞ EKLEME ====================

    suspend fun checkUserAndSendRequest(targetTag: String): Result<String> {
        val clean = targetTag.trim().removePrefix("@").trim()
        val targetId = extractTagId(clean)

        if (clean.isBlank() || (!clean.contains("#") && targetId.length < 3)) {
            return Result.failure(Exception("Etiket '#' ve numara içermelidir (Örn: selo#4770)"))
        }
        if (sameUserTag(clean, _myTag.value)) {
            return Result.failure(Exception("Kendinize arkadaşlık isteği gönderemezsiniz!"))
        }
        if (_friends.value.any { sameUserTag(it.tag, clean) }) {
            return Result.failure(Exception("Bu kullanıcı zaten arkadaş listenizde!"))
        }

        var waitConnectMs = 0
        while (!_isConnected.value && waitConnectMs < 2500) {
            delay(100)
            waitConnectMs += 100
        }
        if (!_isConnected.value) {
            return Result.failure(Exception("Sunucu bağlantısı kuruluyor, lütfen 2 saniye sonra tekrar deneyin."))
        }

        val probeKey = targetId.ifBlank { tagToTopicKey(clean) }
        val regTopic = if (targetId.isNotBlank()) "seloplayer/reg/$targetId" else ""
        val queryDeferred = CompletableDeferred<String>()
        pendingUserQueries[probeKey] = queryDeferred

        try {
            if (regTopic.isNotBlank()) {
                subscribeTopic(regTopic)
            }
            val pingPayload = "PING|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
            publishToPeerSync(clean, pingPayload)

            var resolvedTag = withTimeoutOrNull(1400) {
                queryDeferred.await()
            }

            if (resolvedTag.isNullOrBlank()) {
                if (regTopic.isNotBlank()) {
                    subscribeTopic(regTopic)
                }
                publishToPeerSync(clean, pingPayload)
                resolvedTag = withTimeoutOrNull(2100) {
                    queryDeferred.await()
                }
            }

            if (resolvedTag.isNullOrBlank()) {
                return Result.failure(Exception("Böyle bir kullanıcı bulunamadı!"))
            }

            if (sameUserTag(resolvedTag, _myTag.value)) {
                return Result.failure(Exception("Kendinize arkadaşlık isteği gönderemezsiniz!"))
            }
            if (_friends.value.any { sameUserTag(it.tag, resolvedTag) }) {
                return Result.failure(Exception("Bu kullanıcı zaten arkadaş listenizde!"))
            }

            val reqPayload = "REQ|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
            publishToPeerSync(resolvedTag, reqPayload)
            val resolvedId = extractTagId(resolvedTag).ifBlank { targetId }
            if (resolvedId.isNotBlank()) {
                publishAll("seloplayer/mbox/$resolvedId", reqPayload, retain = true)
            }

            return Result.success("✓ İstek başarıyla gönderildi!")
        } finally {
            pendingUserQueries.remove(probeKey)
            if (regTopic.isNotBlank()) {
                unsubscribeTopic(regTopic)
            }
        }
    }

    fun acceptFriendRequest(targetTag: String) {
        val req = _pendingRequests.value.firstOrNull { sameUserTag(it.tag, targetTag) } ?: return

        val curF = _friends.value.toMutableList()
        if (curF.none { sameUserTag(it.tag, req.tag) }) {
            curF.add(
                FriendItem(
                    tag = req.tag,
                    name = req.name,
                    isOnline = true,
                    currentTrack = "Çevrimiçi",
                    lastSeenTimestamp = System.currentTimeMillis()
                )
            )
            _friends.value = curF
        }

        val curP = _pendingRequests.value.toMutableList()
        curP.removeAll { sameUserTag(it.tag, targetTag) }
        _pendingRequests.value = curP
        saveFriendsToPrefs()

        val payload = "ACC|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}"
        publishToPeer(req.tag, payload)
        val accId = extractTagId(req.tag)
        if (accId.isNotBlank()) {
            scope.launch {
                publishAll("seloplayer/mbox/$accId", payload, retain = true)
            }
        }
        sendPong(req.tag)
        sendPing(req.tag)
    }

    fun rejectFriendRequest(targetTag: String) {
        val curP = _pendingRequests.value.toMutableList()
        curP.removeAll { sameUserTag(it.tag, targetTag) }
        _pendingRequests.value = curP
        saveFriendsToPrefs()
    }

    fun removeFriend(targetTag: String) {
        val curF = _friends.value.toMutableList()
        curF.removeAll { sameUserTag(it.tag, targetTag) }
        _friends.value = curF
        saveFriendsToPrefs()
    }

    fun requestAllStatuses() {
        for (f in _friends.value) {
            sendPing(f.tag)
        }
    }

    private fun publishToPeerSync(peerTag: String, payload: String) {
        if (peerTag.isBlank()) return
        val peerId = extractTagId(peerTag)
        if (peerId.isNotBlank()) {
            publishAll("seloplayer/id/$peerId", payload, retain = false)
        }
        val hashIdx = peerTag.indexOf('#')
        if (hashIdx != 0) {
            val key = tagToTopicKey(peerTag)
            if (key.isNotBlank()) {
                publishAll("seloplayer/u/$key", payload, retain = false)
            }
        }
    }

    private fun publishToPeer(peerTag: String, payload: String) {
        if (peerTag.isBlank()) return
        scope.launch {
            publishToPeerSync(peerTag, payload)
        }
    }

    private fun publishToRoom(jamId: String, payload: String) {
        if (jamId.isBlank()) return
        val rTopic = roomTopic(jamId)
        scope.launch {
            publishAll(rTopic, payload, retain = false)
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

    // Yalnızca arkadaş durum kartını günceller; ASLA Jam yayını tetiklemez (pos=0 sıfırlama hatasını kökten önler!)
    fun updateLocalPlaybackState(isPlaying: Boolean, trackTitle: String) {
        localIsPlaying = isPlaying
        localTrackTitle = trackTitle
        for (f in _friends.value) {
            sendPong(f.tag)
        }
    }

    // ==================== SINIRSIZ KİŞİLİK JAM KONTROLLERİ ====================

    fun inviteFriendToJam(friendTag: String, friendName: String = "") {
        if (friendTag.isBlank() || sameUserTag(friendTag, _myTag.value)) return
        val cur = _jamState.value
        if (cur.members.any { sameUserTag(it.tag, friendTag) }) return

        val displayName = friendName.ifBlank {
            _friends.value.firstOrNull { sameUserTag(it.tag, friendTag) }?.name ?: friendTag
        }

        val activeJamId = if ((cur.isActive || cur.isInviting) && cur.jamId.isNotBlank()) {
            cur.jamId
        } else {
            val myKey = tagToTopicKey(_myTag.value).ifBlank { "aurns" }
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
        updateJamLocks(keepAwake = true)

        val payload = "JAM_INV|${sanitizeField(_myTag.value)}|${sanitizeField(_myName.value)}|$activeJamId"
        scope.launch {
            sendPing(friendTag)
            publishToPeerSync(friendTag, payload)
        }
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
        updateJamLocks(keepAwake = true)

        val myTagClean = sanitizeField(_myTag.value)
        val myNameClean = sanitizeField(_myName.value)
        val joinPayload = "JAM_JOIN|$myTagClean|$myNameClean|$targetJamId"
        val askPayload = "JAM_ASK|$myTagClean|$targetJamId"

        scope.launch {
            subscribeTopic(roomTopic(targetJamId))
            delay(80)
            publishToPeerSync(inviterTag, joinPayload)
            publishAll(roomTopic(targetJamId), joinPayload, retain = false)
            publishToPeerSync(inviterTag, askPayload)
            publishAll(roomTopic(targetJamId), askPayload, retain = false)

            // Paket kaybına karşı kendi kendini onaran otomatik durum sorgusu
            delay(850)
            val cur1 = _jamState.value
            if (cur1.isActive && cur1.jamId == targetJamId && cur1.currentTrackTitle.isBlank() && !cur1.isDownloadingTrack) {
                requestJamPartnerState()
            }
            delay(1300)
            val cur2 = _jamState.value
            if (cur2.isActive && cur2.jamId == targetJamId && cur2.currentTrackTitle.isBlank() && !cur2.isDownloadingTrack) {
                requestJamPartnerState()
            }
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
                publishAll(roomTopic(oldJamId), leavePayload, retain = false)
                for (m in membersSnap) {
                    publishToPeerSync(m.tag, leavePayload)
                }
                if (wasInviting && lastInv.isNotBlank()) {
                    publishToPeerSync(lastInv, leavePayload)
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
        val payload = buildStatePayload(st.jamId, title, ytId, isPlaying, posMs)
        publishToPeer(targetTag, payload)
        publishToRoom(st.jamId, payload)
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
        val preferred = lastStateSenderTag.takeIf { s -> s.isNotBlank() && st.members.any { sameUserTag(it.tag, s) } }
            ?: st.members.firstOrNull()?.tag
        if (!preferred.isNullOrBlank()) {
            publishToPeer(preferred, payload)
        }
        publishToRoom(st.jamId, payload)
    }

    fun onDestroy() {
        leaveJamRoom()
        updateJamLocks(keepAwake = false)
        isRunning = false
        val byePayload = "BYE|${sanitizeField(_myTag.value)}"
        for (f in _friends.value) {
            publishToPeerSync(f.tag, byePayload)
        }
        for (b in brokers) {
            b.connected = false
            try { b.socket?.close() } catch (_: Exception) {}
        }
    }

    // ==================== HAM MQTT 3.1.1 PAKET KODLAYICILARI ====================

    private fun nextPacketId(): Int {
        val v = packetIdGen.getAndIncrement() and 0xFFFF
        return if (v == 0) 1 else v
    }

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
        val pid = nextPacketId()
        var i = 0
        pkt[i++] = 0x82.toByte()
        for (b in remHdr) pkt[i++] = b
        pkt[i++] = (pid shr 8).toByte()
        pkt[i++] = (pid and 0xFF).toByte()
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
        val pid = nextPacketId()
        var i = 0
        pkt[i++] = 0xA2.toByte()
        for (b in remHdr) pkt[i++] = b
        pkt[i++] = (pid shr 8).toByte()
        pkt[i++] = (pid and 0xFF).toByte()
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

    private fun sendMqttPublish(out: OutputStream?, topic: String, payload: String, retain: Boolean = false) {
        if (out == null || topic.isBlank()) return
        val topBytes = topic.toByteArray(Charsets.UTF_8)
        val payBytes = payload.toByteArray(Charsets.UTF_8)
        val remLen = 2 + topBytes.size + payBytes.size
        val remHdr = encodeRemainingLength(remLen)

        val pkt = ByteArray(1 + remHdr.size + remLen)
        var idx = 0
        pkt[idx++] = (if (retain) 0x31 else 0x30).toByte()
        for (b in remHdr) pkt[idx++] = b
        pkt[idx++] = (topBytes.size shr 8).toByte()
        pkt[idx++] = (topBytes.size and 0xFF).toByte()
        System.arraycopy(topBytes, 0, pkt, idx, topBytes.size)
        idx += topBytes.size
        if (payBytes.isNotEmpty()) {
            System.arraycopy(payBytes, 0, pkt, idx, payBytes.size)
        }

        try {
            synchronized(out) {
                out.write(pkt)
                out.flush()
            }
        } catch (_: Exception) {}
    }
}
