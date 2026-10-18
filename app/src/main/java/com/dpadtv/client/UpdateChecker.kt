package com.dpadtv.client

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查 GitHub Releases 的最新版本。
 *
 * 只请求 `/releases/latest`（一次），拿 tag 名、更新说明和页面地址；
 * 失败（断网、被墙、限流、仓库没发 Release）静默返回 null，不影响使用。
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/AresCNZJ/DpadTV/releases/latest"

    data class ReleaseInfo(
        val tag: String,
        val notes: String,
        val url: String,
    )

    fun check(onResult: (ReleaseInfo?) -> Unit) {
        Thread {
            val info = try {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "DpadTV-Android")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val o = JSONObject(body)
                    val tag = o.optString("tag_name", "")
                    if (tag.isEmpty()) {
                        null
                    } else {
                        ReleaseInfo(
                            tag = tag,
                            notes = o.optString("body", ""),
                            url = o.optString("html_url", ""),
                        )
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post { onResult(info) }
        }.start()
    }

    /** 比较版本号：远端形如 v1.1.0，本地形如 1.0.0 */
    fun isNewer(tag: String?, current: String): Boolean {
        if (tag.isNullOrBlank()) return false
        val remote = parse(tag)
        val local = parse(current)
        for (i in 0 until maxOf(remote.size, local.size)) {
            val a = remote.getOrElse(i) { 0 }
            val b = local.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')          // 忽略 -beta / -rc 之类的预发布后缀
            .split('.')
            .mapNotNull { it.toIntOrNull() }
}
