package com.aurns.player.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String
)

data class StreamInfo(
    val url: String,
    val mimeType: String,
    val fileExt: String,
    val bitrate: Int,
    val contentLength: Long,
    val userAgent: String
)

object InnertubeResolver {

    val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(12, 3, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val UA_VISIONOS =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    const val UA_IOS =
        "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"

    @Volatile private var cachedVisitorId: String? = null
    @Volatile private var visitorIdTimestamp: Long = 0L

    private suspend fun getFastVisitorId(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!cachedVisitorId.isNullOrEmpty() && (now - visitorIdTimestamp) < 6 * 3600 * 1000L) {
            return@withContext cachedVisitorId
        }

        try {
            val reqBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250101.00.00")
                        put("hl", "tr")
                        put("gl", "TR")
                    })
                })
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id")
                .header("User-Agent", UA_DESKTOP)
                .header("Content-Type", "application/json")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .build()

            sharedHttpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val vis = json.optJSONObject("responseContext")?.optString("visitorData")
                if (!vis.isNullOrEmpty()) {
                    cachedVisitorId = vis
                    visitorIdTimestamp = now
                    return@withContext vis
                }
            }
        } catch (_: Exception) {}
        return@withContext cachedVisitorId
    }

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        try {
            val reqBody = JSONObject().apply {
                put("query", query)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250101.00.00")
                        put("hl", "tr")
                        put("gl", "TR")
                    })
                })
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", UA_DESKTOP)
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .build()

            sharedHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)

                val renderers = mutableListOf<JSONObject>()
                findVideoRenderers(json, renderers)

                for (vr in renderers) {
                    val videoId = vr.optString("videoId")
                    if (videoId.isEmpty()) continue

                    val titleObj = vr.optJSONObject("title")
                    val title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: titleObj?.optString("simpleText") ?: "Bilinmeyen Parça"

                    val ownerObj = vr.optJSONObject("ownerText")
                        ?: vr.optJSONObject("shortBylineText")
                        ?: vr.optJSONObject("longBylineText")
                    val artist = ownerObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "YouTube"

                    val lengthObj = vr.optJSONObject("lengthText")
                    val duration = lengthObj?.optString("simpleText")
                        ?: lengthObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""

                    val thumbs = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                        thumbs.getJSONObject(thumbs.length() - 1).optString("url")
                    } else {
                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    }

                    results.add(SearchResult(videoId, title, artist, duration, thumbUrl))
                    if (results.size >= 30) break
                }
            }
        } catch (_: Exception) {}
        return@withContext results
    }

    suspend fun resolveAudioStream(videoId: String): StreamInfo? = withContext(Dispatchers.IO) {
        tryResolveWithVisionOs(videoId)?.let { return@withContext it }
        tryResolveWithIos(videoId)?.let { return@withContext it }
        return@withContext null
    }

    private suspend fun tryResolveWithVisionOs(videoId: String): StreamInfo? {
        val visitorId = getFastVisitorId()
        return try {
            val reqBody = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20753)
                    })
                })
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "VISIONOS")
                        put("clientVersion", "1.02")
                        put("deviceMake", "Apple")
                        put("deviceModel", "RealityDevice17,1")
                        put("userAgent", UA_VISIONOS)
                        put("osName", "visionOS")
                        put("osVersion", "26.5.23O471")
                        put("hl", "en")
                        put("gl", "US")
                        if (!visitorId.isNullOrEmpty()) {
                            put("visitorData", visitorId)
                        }
                    })
                })
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", UA_VISIONOS)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", "101")
                .header("X-YouTube-Client-Version", "1.02")

            if (!visitorId.isNullOrEmpty()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorId)
            }

            sharedHttpClient.newCall(reqBuilder.post(reqBody.toString().toRequestBody(JSON_MEDIA)).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                extractBestAudio(JSONObject(bodyStr), UA_VISIONOS)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryResolveWithIos(videoId: String): StreamInfo? {
        return try {
            val reqBody = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "20.03.02")
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("userAgent", UA_IOS)
                        put("osName", "iPhone")
                        put("osVersion", "18.2.1.22C161")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", UA_IOS)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", "5")
                .header("X-YouTube-Client-Version", "20.03.02")
                .post(reqBody.toString().toRequestBody(JSON_MEDIA))
                .build()

            sharedHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                extractBestAudio(JSONObject(bodyStr), UA_IOS)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractBestAudio(json: JSONObject, ua: String): StreamInfo? {
        val adaptive = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            ?: return null

        var bestMp4: StreamInfo? = null
        var bestMp4Bitrate = 0
        var bestAny: StreamInfo? = null
        var bestAnyBitrate = 0

        for (i in 0 until adaptive.length()) {
            val fmt = adaptive.getJSONObject(i)
            val mime = fmt.optString("mimeType", "")
            if (!mime.startsWith("audio/")) continue

            val url = fmt.optString("url")
            if (url.isEmpty()) continue

            val bitrate = fmt.optInt("bitrate", 0)
            val contentLength = fmt.optLong("contentLength", 0L)
            val isMp4 = mime.startsWith("audio/mp4")
            val cleanMime = if (isMp4) "audio/mp4" else "audio/webm"
            val ext = if (isMp4) "m4a" else "ogg"
            val info = StreamInfo(url, cleanMime, ext, bitrate, contentLength, ua)

            if (isMp4 && bitrate > bestMp4Bitrate) {
                bestMp4Bitrate = bitrate
                bestMp4 = info
            }
            if (bitrate > bestAnyBitrate) {
                bestAnyBitrate = bitrate
                bestAny = info
            }
        }
        return bestMp4 ?: bestAny
    }

    private fun findVideoRenderers(obj: Any?, list: MutableList<JSONObject>) {
        when (obj) {
            is JSONObject -> {
                if (obj.has("videoRenderer")) {
                    obj.optJSONObject("videoRenderer")?.let { list.add(it) }
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findVideoRenderers(obj.opt(key), list)
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    findVideoRenderers(obj.opt(i), list)
                }
            }
        }
    }
}
