package com.dpadtv.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** App 本地观看历史条目。完全本地，不与网页版/其它设备同步。 */
data class HistoryEntry(
    val title: String,
    val playUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val cover: String = "",
)

/** 本地配置：服务器、会话 Cookie、片源列表。全部存在应用私有 SharedPreferences。 */
class Prefs(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("dpadtv_native", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    var cookie: String
        get() = sp.getString("cookie", "") ?: ""
        set(v) = sp.edit().putString("cookie", v).apply()

    fun sources(): MutableList<SourceConfig> {
        val text = sp.getString("sources", "[]") ?: "[]"
        val out = ArrayList<SourceConfig>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> SourceConfig.fromJson(o)?.let { out.add(it) } }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveSources(list: List<SourceConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("sources", arr.toString()).apply()
    }

    fun addSources(newOnes: List<SourceConfig>): Int {
        val current = sources()
        val urlSet = current.map { it.url }.toHashSet()
        var added = 0
        for (s in newOnes) {
            if (urlSet.add(s.url)) {
                current.add(s)
                added++
            }
        }
        saveSources(current)
        return added
    }

    /** 续播进度：按 m3u8 地址记（换线路就是另一条记录，符合预期）。 */
    fun progressOf(playUrl: String): Long = sp.getLong("pos:" + playUrl.hashCode(), 0L)

    fun saveProgress(playUrl: String, positionMs: Long) {
        if (positionMs <= 5_000L) return // 开头几秒不值得记
        sp.edit().putLong("pos:" + playUrl.hashCode(), positionMs).apply()
    }

    fun clearProgress(playUrl: String) {
        sp.edit().remove("pos:" + playUrl.hashCode()).apply()
    }

    /* --------------------------------------------------------------- 直播源 */

    fun liveUrls(): MutableList<String> {
        val text = sp.getString("live_urls", "") ?: ""
        return if (text.isEmpty()) ArrayList() else text.split("\n").toMutableList()
    }

    fun saveLiveUrls(list: List<String>) {
        sp.edit().putString("live_urls", list.joinToString("\n")).apply()
    }

    var activeLiveUrl: String
        get() = sp.getString("active_live", "") ?: ""
        set(v) = sp.edit().putString("active_live", v).apply()

    /* ------------------------------------------------------------- 搜索历史 */

    fun searchHistory(): MutableList<String> {
        val text = sp.getString("search_history", "") ?: ""
        return if (text.isEmpty()) ArrayList() else text.split("\n").toMutableList()
    }

    fun addSearchHistory(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val list = searchHistory()
        list.remove(kw)
        list.add(0, kw)
        while (list.size > 12) list.removeAt(list.size - 1)
        sp.edit().putString("search_history", list.joinToString("\n")).apply()
    }

    fun clearSearchHistory() {
        sp.edit().remove("search_history").apply()
    }

    /* ------------------------------------------------------------- 观看历史 */

    fun history(): MutableList<HistoryEntry> {
        val text = sp.getString("history", "[]") ?: "[]"
        val out = ArrayList<HistoryEntry>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("playUrl", "")
                if (url.isEmpty()) continue
                out.add(
                    HistoryEntry(
                        title = o.optString("title", ""),
                        playUrl = url,
                        positionMs = o.optLong("positionMs", 0),
                        durationMs = o.optLong("durationMs", 0),
                        updatedAt = o.optLong("updatedAt", 0),
                        cover = o.optString("cover", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 写入/更新一条历史（按播放地址去重，最近观看排最前，最多 40 条）。 */
    fun upsertHistory(entry: HistoryEntry) {
        val list = history().filterNot { it.playUrl == entry.playUrl }.toMutableList()
        list.add(0, entry)
        while (list.size > 40) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("title", e.title)
                    .put("playUrl", e.playUrl)
                    .put("positionMs", e.positionMs)
                    .put("durationMs", e.durationMs)
                    .put("updatedAt", e.updatedAt)
                    .put("cover", e.cover),
            )
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun removeHistory(playUrl: String) {
        val arr = JSONArray()
        history().filterNot { it.playUrl == playUrl }.forEach { e ->
            arr.put(
                JSONObject().put("title", e.title)
                    .put("playUrl", e.playUrl)
                    .put("positionMs", e.positionMs)
                    .put("durationMs", e.durationMs)
                    .put("updatedAt", e.updatedAt)
                    .put("cover", e.cover),
            )
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() {
        sp.edit().remove("history").apply()
    }
}
