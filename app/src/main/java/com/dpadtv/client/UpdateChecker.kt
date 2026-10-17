package com.dpadtv.client

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查 GitHub Releases 的最新 tag。
 *
 * 只请求 `/releases/latest` 拿 tag 名，不下载任何二进制；
 * 请求失败（断网、限流、仓库没发 Release）都会安静地返回 null，不影响使用。
 */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/AresCNZJ/DpadTV/releases/latest"

    fun check(onResult: (String?) -> Unit) {
        Thread {
            val tag = try {
                val conn = URL(API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "DpadTV-Android")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    JSONObject(body).optString("tag_name", "").ifEmpty { null }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            } finally {
                // 不主动断开连接池里的长连接，交给 JVM 回收
            }
            Handler(Looper.getMainLooper()).post { onResult(tag) }
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
