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
        // 从历史页进入播放时拿不到封面（传的是旧记录里的空值），
        // 这里保留已有封面，避免"点一次历史，海报就没了"
        val existing = history().firstOrNull { it.playUrl == entry.playUrl }
        val merged = if (entry.cover.isEmpty() && existing != null) {
            entry.copy(cover = existing.cover, title = entry.title.ifEmpty { existing.title })
        } else {
            entry
        }
        val list = history().filterNot { it.playUrl == merged.playUrl }.toMutableList()
        list.add(0, merged)
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

    /* ------------------------------------------------------------- 播放设置 */

    /** 快进/快退步长（秒） */
    var seekStepSeconds: Int
        get() = sp.getInt("seek_step", 10)
        set(v) = sp.edit().putInt("seek_step", v).apply()

    /** 一集放完是否自动播下一集 */
    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    /** 是否记住播放进度并在下次续播 */
    var resumeEnabled: Boolean
        get() = sp.getBoolean("resume_enabled", true)
        set(v) = sp.edit().putBoolean("resume_enabled", v).apply()

    /** 进度超过这个秒数才续播（避免片头就被"续"到几秒处） */
    var resumeThresholdSeconds: Int
        get() = sp.getInt("resume_threshold", 5)
        set(v) = sp.edit().putInt("resume_threshold", v).apply()

    /** 播放倍速（1.0 = 原速） */
    var playbackSpeed: Float
        get() = sp.getFloat("playback_speed", 1f)
        set(v) = sp.edit().putFloat("playback_speed", v).apply()

    /** 解码方式：0 自动 / 1 强制软解 / 2 强制硬解（下次打开播放器生效） */
    var decodeMode: Int
        get() = sp.getInt("decode_mode", 0)
        set(v) = sp.edit().putInt("decode_mode", v).apply()

    /** 播放磁盘缓存上限（MB），默认 2048 */
    var cacheLimitMb: Int
        get() = sp.getInt("cache_limit_mb", 2048)
        set(v) = sp.edit().putInt("cache_limit_mb", v).apply()

    /** 远端最新 tag（空表示已是最新或还没检查过） */
    var latestTag: String
        get() = sp.getString("latest_tag", "") ?: ""
        set(v) = sp.edit().putString("latest_tag", v).apply()

    /** 上次检查更新的时间戳，避免每次启动都请求 */
    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()
    /** 播放进度存在 pos:<hash> 键里，这里统计并整体清掉 */
    fun progressCount(): Int = sp.all.keys.count { it.startsWith("pos:") }

    fun clearAllProgress() {
        val editor = sp.edit()
        sp.all.keys.filter { it.startsWith("pos:") }.forEach { editor.remove(it) }
        editor.apply()
    }
    /** 首页顶部按钮顺序（id 列表） */
    fun homeBarOrder(): MutableList<String> {
        val text = sp.getString("home_bar_order", "") ?: ""
        if (text.isEmpty()) return HomeBar.DEFAULT.toMutableList()
        return text.split(',').filter { it.isNotEmpty() }.toMutableList()
    }

    fun saveHomeBarOrder(list: List<String>) {
        sp.edit().putString("home_bar_order", list.joinToString(",")).apply()
    }

    /** 首页顶部被隐藏的按钮 id */
    fun homeBarHidden(): Set<String> {
        val text = sp.getString("home_bar_hidden", "") ?: ""
        return if (text.isEmpty()) emptySet() else text.split(',').filter { it.isNotEmpty() }.toSet()
    }

    fun saveHomeBarHidden(ids: Set<String>) {
        sp.edit().putString("home_bar_hidden", ids.joinToString(",")).apply()
    }

    /** 给某条历史补封面（历史页自动补图用） */
    fun updateHistoryCover(playUrl: String, cover: String) {
        val list = history()
        val idx = list.indexOfFirst { it.playUrl == playUrl }
        if (idx < 0) return
        list[idx] = list[idx].copy(cover = cover)
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
}

object HomeBar {
    val DEFAULT = listOf("movie", "tv", "show", "search", "live", "history", "sources", "setup")

    fun label(id: String): String = when (id) {
        "movie" -> "电影"
        "tv" -> "剧集"
        "show" -> "综艺"
        "search" -> "搜索"
        "live" -> "直播"
        "history" -> "继续"
        "sources" -> "片源"
        "setup" -> "设置"
        else -> id
    }
}
