package com.dpadtv.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LibreTV 的 HTTP 契约（均由本机实测确认，不是照文档猜的）：
 *
 *   GET  /api/status                                  -> {passwordRequired, verified, version, ...}
 *   POST /api/auth   {"password"}                     -> {"success":true} + Set-Cookie ltv_session（90 天）
 *   POST /api/search {"wd","sources":[...],"filterAdult"}
 *                                                     -> {list:[{sourceName,vodId,name,pic,typeName,
 *                                                          year,area,remarks,sourceUrl,isAdult}], failures:[]}
 *   GET  /api/detail?id=<vodId>&source=<JSON 编码>     -> {episodes:[m3u8 字符串...], videoInfo:{...}}
 *        ↑ 注意 episodes 是**纯 URL 字符串数组**，不是集对象
 *   GET  /api/douban?type&tag&pageStart&pageSize       -> 豆瓣条目（首页推荐）
 *   GET  /api/source-list?url=<订阅地址>               -> {sources:[{name,url,detail,isAdult}], liveSources:[...]}
 */
/**
 * /api/status 的返回。重点：defaultSources / defaultLiveSources 是 LibreTV 为
 * 「第三方客户端」下发的服务端配置 —— 网页版自己用 localStorage，原生端读不到那玩意，
 * 必须走这两个字段，否则就会出现「服务器明明配了片源，客户端还让你再配一遍」。
 */
data class AppStatus(
    val passwordRequired: Boolean,
    val verified: Boolean,
    val version: String,
    val defaultSources: List<SourceConfig> = emptyList(),
    val defaultLiveSources: List<String> = emptyList(),
    /** DEFAULT_SUBSCRIPTIONS：服务端预置的 SourceList 订阅链接（需再用 /api/source-list 解析） */
    val defaultSubscriptions: List<String> = emptyList(),
)

data class SourceConfig(
    val name: String,
    val url: String,
    val detail: String = "",
    val isAdult: Boolean = false,
    /** 停用的源不参与搜索 */
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
        if (detail.isNotEmpty()) put("detail", detail)
        put("isAdult", isAdult)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): SourceConfig? {
            val url = o.optString("url", "")
            if (url.isEmpty()) return null
            return SourceConfig(
                name = o.optString("name", url.substringAfter("//").substringBefore('/')),
                url = url,
                detail = o.optString("detail", ""),
                isAdult = o.optBoolean("isAdult", false),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

data class SearchItem(
    val sourceName: String,
    val vodId: String,
    val name: String,
    val pic: String,
    val typeName: String,
    val year: String,
    val remarks: String,
    val sourceUrl: String,
)

data class VideoInfo(
    val title: String,
    val cover: String,
    val desc: String,
    val remarks: String,
    val year: String,
    val area: String,
    val actor: String,
)

data class Detail(val episodes: List<String>, val info: VideoInfo)

data class DoubanItem(val title: String, val cover: String, val rating: String)

data class SearchResult(val items: List<SearchItem>, val failures: Int)

data class Channel(val name: String, val url: String, val group: String, val logo: String)

class HttpError(val status: Int, val body: String) : Exception("HTTP $status")

class Api(baseUrl: String) {

    val baseUrl: String = baseUrl.trimEnd('/')
    private var cookie: String? = null

    fun restoreCookie(value: String?) {
        cookie = value?.takeIf { it.isNotEmpty() }
    }

    fun currentCookie(): String? = cookie

    private fun open(path: String): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            throw HttpError(code, err)
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        // 抓 Set-Cookie（LibreTV 只发一条 ltv_session）
        conn.headerFields.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?.let { raw -> cookie = raw.substringBefore(';') }
        return text
    }

    fun status(): AppStatus {
        val o = JSONObject(readBody(open("/api/status")))
        val srcArr = o.optJSONArray("defaultSources") ?: JSONArray()
        val defaultSources = (0 until srcArr.length()).mapNotNull { i ->
            srcArr.optJSONObject(i)?.let { SourceConfig.fromJson(it) }
        }
        val liveArr = o.optJSONArray("defaultLiveSources") ?: JSONArray()
        val defaultLive = (0 until liveArr.length()).mapNotNull { i ->
            when (val v = liveArr.opt(i)) {
                is String -> v.ifEmpty { null }
                is JSONObject -> v.optString("url", "").ifEmpty { null }
                else -> null
            }
        }
        val subsArr = o.optJSONArray("defaultSubscriptions") ?: JSONArray()
        val defaultSubs = (0 until subsArr.length()).mapNotNull { i ->
            subsArr.opt(i) as? String
        }
        return AppStatus(
            passwordRequired = o.optBoolean("passwordRequired", false),
            verified = o.optBoolean("verified", false),
            version = o.optString("version", "?"),
            defaultSources = defaultSources,
            defaultLiveSources = defaultLive,
            defaultSubscriptions = defaultSubs,
        )
    }

    /** 登录成功后不仅看 success，还要复查 status.verified —— 会话没生效要明确报出来。 */
    fun login(password: String): AppStatus {
        val conn = open("/api/auth").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("password", password).toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = readBody(conn)
        val ok = JSONObject(text).optBoolean("success", false)
        if (!ok) throw HttpError(401, text)
        return status()
    }

    fun search(keyword: String, sources: List<SourceConfig>): SearchResult {
        val payload = JSONObject().apply {
            put("wd", keyword)
            put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
            put("filterAdult", true)
        }
        val conn = open("/api/search").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val root = JSONObject(readBody(conn))
        val list = root.optJSONArray("list") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            SearchItem(
                sourceName = o.optString("sourceName", ""),
                vodId = o.optString("vodId", ""),
                name = o.optString("name", ""),
                pic = o.optString("pic", ""),
                typeName = o.optString("typeName", ""),
                year = o.optString("year", ""),
                remarks = o.optString("remarks", ""),
                sourceUrl = o.optString("sourceUrl", ""),
            )
        }
        // 公共采集站经常挂，把「几个源无响应」透出来，否则用户只会觉得「结果怎么这么少」
        val failures = root.optJSONArray("failures")?.length() ?: 0
        return SearchResult(items, failures)
    }

    fun detail(vodId: String, source: SourceConfig): Detail {
        val sourceJson = source.toJson().toString()
        val path = "/api/detail?id=" + enc(vodId) + "&source=" + enc(sourceJson)
        val root = JSONObject(readBody(open(path)))
        if (root.has("error")) throw IllegalStateException(root.optString("error"))
        val eps = root.optJSONArray("episodes") ?: JSONArray()
        val urls = ArrayList<String>(eps.length())
        for (i in 0 until eps.length()) {
            val v = eps.optString(i, "")
            if (v.isNotEmpty()) urls.add(v)
        }
        val vi = root.optJSONObject("videoInfo") ?: JSONObject()
        val info = VideoInfo(
            title = vi.optString("title", ""),
            cover = vi.optString("cover", ""),
            desc = vi.optString("desc", ""),
            remarks = vi.optString("remarks", ""),
            year = vi.optString("year", ""),
            area = vi.optString("area", ""),
            actor = vi.optString("actor", ""),
        )
        return Detail(urls, info)
    }

    /** 首页推荐。字段名在服务端可能是 list/subjects、cover/pic/img，这里做容错。 */
    fun douban(type: String, tag: String, pageStart: Int, pageSize: Int): List<DoubanItem> {
        val path = "/api/douban?type=" + enc(type) + "&tag=" + enc(tag) +
            "&pageStart=" + pageStart + "&pageSize=" + pageSize
        val root = JSONObject(readBody(open(path)))
        // 实测返回 {"items":[{id,title,cover,rating,isTv}]}；list/subjects 仅作兼容
        val arr = root.optJSONArray("items")
            ?: root.optJSONArray("list")
            ?: root.optJSONArray("subjects")
            ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DoubanItem(
                title = o.optString("title", o.optString("name", "")),
                cover = o.optString("cover", o.optString("pic", o.optString("img", ""))),
                rating = o.optString("rating", o.optString("rate", "")),
            )
        }
    }

    /**
     * App 直连拉取文本。**不经过服务器**，所以不受服务端防 SSRF 校验限制，
     * 可以用内网地址（例如把 sources.json 放在 NAS 的共享目录/静态服务上）。
     * 用它来导入自己维护的源列表，就不必把源写死进 docker 环境变量。
     */
    fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw HttpError(conn.responseCode, "")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 图片走站点代理：豆瓣封面有防盗链，直连 403；顺带复用会话 Cookie。 */
    fun imageUrl(picUrl: String): String = baseUrl + "/api/proxy?url=" + enc(picUrl)

    fun imageBytes(picUrl: String): ByteArray? {
        val conn = URL(imageUrl(picUrl)).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            cookie?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 从订阅地址导入。注意：LibreTV 有防 SSRF 校验，**内网地址会被 403 拒绝**
     * （"目标地址不在允许范围内（仅支持公网 http/https）"），所以放在自己 NAS/电脑上的
     * sources.json 不能用这条路径。局域网文件请走 [parseSourceList] 本地解析。
     */
    /** 测试单个采集站（服务端代发请求，避免跨域/UA 问题）。返回 (是否可用, 结果数或状态码) */
    fun testSource(url: String): Pair<Boolean, Int> {
        val payload = JSONObject().put("url", url)
        val conn = open("/api/source/test").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val o = JSONObject(readBody(conn))
        return o.optBoolean("ok", false) to o.optInt("count", o.optInt("status", 0))
    }

    fun importSourceList(subscriptionUrl: String): List<SourceConfig> =
        parseSourceList(readBody(open("/api/source-list?url=" + enc(subscriptionUrl))))

    /** 本地解析。兼容三种格式：网页版导出（LibreTV-Settings）、LibreTV-SourceList、TVBOX 配置。 */
    fun parseSourceList(text: String): List<SourceConfig> {
        exportSources(text)?.let { return it }
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            try {
                JSONObject().put("sources", JSONArray(text))
            } catch (e2: Exception) {
                return emptyList()
            }
        }
        val arr = root.optJSONArray("sources") ?: root.optJSONArray("sites") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // TVBOX 的 sites 用 api 字段，且可能是 Spider（跳过）
            if (o.has("api") && o.optString("type", "") !in listOf("1", "0", "")) return@mapNotNull null
            if (o.has("api")) {
                SourceConfig.fromJson(
                    JSONObject().put("name", o.optString("name", o.optString("key", "")))
                        .put("url", o.optString("api", ""))
                        .put("isAdult", false),
                )
            } else {
                SourceConfig.fromJson(o)
            }
        }
    }

    /**
     * 网页版「设置 → 导出」生成的文件（LibreTV-Settings_*.json）：
     *   { data: { "libretv-settings": "<JSON 字符串>", viewingHistory: "<JSON 字符串>" } }
     * 内层是 zustand 的持久化结构，真正的片源在 state.customAPIs，
     * 启用与顺序在 state.selectedKeys（值对应 customAPIs 的 key，如 custom_1）。
     */
    private fun exportState(text: String): JSONObject? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        val data = root.optJSONObject("data") ?: return null
        val inner = data.optString("libretv-settings", "")
        if (inner.isEmpty()) return null
        return try {
            JSONObject(inner).optJSONObject("state") ?: return null
        } catch (e: Exception) {
            null
        }
    }

    private fun exportSources(text: String): List<SourceConfig>? {
        val state = exportState(text) ?: return null
        val apis = state.optJSONArray("customAPIs") ?: return emptyList()
        val byKey = LinkedHashMap<String, SourceConfig>()
        for (i in 0 until apis.length()) {
            val o = apis.optJSONObject(i) ?: continue
            val sc = SourceConfig.fromJson(o) ?: continue
            byKey[o.optString("key", sc.url)] = sc
        }
        val selected = state.optJSONArray("selectedKeys")
        if (selected == null || selected.length() == 0) return byKey.values.toList()
        val ordered = (0 until selected.length()).mapNotNull { byKey[selected.optString(it, "")] }
        // selectedKeys 里一个都对不上时，退回全部（宁多勿少，用户可在 App 里删）
        return if (ordered.isEmpty()) byKey.values.toList() else ordered
    }

    /** 同一份导出文件里的直播源（M3U 订阅地址）。 */
    fun parseLiveFromExport(text: String): List<String> {
        val state = exportState(text) ?: return emptyList()
        val out = LinkedHashSet<String>()
        val subs = state.optJSONArray("liveSubscriptions")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val o = subs.optJSONObject(i)
                val u = if (o != null) o.optString("url", "") else subs.optString(i, "")
                if (u.isNotEmpty()) out.add(u)
            }
        }
        val sel = state.optJSONArray("liveSelectedUrls")
        if (sel != null) {
            for (i in 0 until sel.length()) {
                val u = sel.optString(i, "")
                if (u.isNotEmpty()) out.add(u)
            }
        }
        return out.toList()
    }

    /** 直播频道。实测返回 {"channels":[{id,name,url,logo,group,tvgId}], "groups":[…]}[。 */
    fun liveChannels(m3uUrl: String): List<Channel> {
        val root = JSONObject(readBody(open("/api/live/playlist?url=" + enc(m3uUrl))))
        val arr = root.optJSONArray("channels") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url", "")
            if (url.isEmpty()) {
                null
            } else {
                Channel(
                    name = o.optString("name", ""),
                    url = url,
                    group = o.optString("group", "其他"),
                    logo = o.optString("logo", ""),
                )
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
