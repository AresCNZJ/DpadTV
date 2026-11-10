package com.dpadtv.client

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 网速实测：在 DataSource 层统计真正走网络的字节与耗时。
 *
 * 之前的做法（PlaybackStatsListener.totalBandwidthBytes/TimeMs 差分）拿到的其实是
 * 播放器「加载了多少 / 加载了多久」——ExoPlayer 只按需下载：稳态播放时下载速率
 * 约等于视频码率，HLS 分段请求之间还有空隙，所以显示的数字不是线路真实网速。
 *
 * 这里改为把上游（HTTP）数据源包一层计数代理：只有缓存未命中、真正从网络读数据
 * 时才计数。速率 = 窗口内字节数 / 窗口内活跃读取耗时，即「正在下载时的实际吞吐」。
 * 命中磁盘缓存的部分不经过上游，天然不计入 —— 网速只反映网络，不受本地缓存影响。
 */
class NetSpeedMeter(
    private val windowMs: Long = 2_000,
) {
    private class Sample(val t: Long, val bytes: Long, val activeMs: Long)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()

    /** 一次网络 read：从 startMs 阻塞到 endMs，读到 bytes 字节 */
    fun add(bytes: Long, startMs: Long, endMs: Long) {
        synchronized(lock) { samples.addLast(Sample(endMs, bytes, endMs - startMs)) }
    }

    /**
     * 窗口内实际下载速率（bps）。窗口内没有网络读取（纯缓存/没在下载）返回 null，
     * 调用方显示「测速中…」而不是一个过期的假数。
     */
    fun bitsPerSecond(): Long? {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            while (samples.isNotEmpty() && now - samples.first().t > windowMs) {
                samples.removeFirst()
            }
            var bytes = 0L
            var activeMs = 0L
            for (s in samples) {
                bytes += s.bytes
                activeMs += s.activeMs
            }
            if (activeMs <= 0L || bytes <= 0L) return null
            return bytes * 8000L / activeMs
        }
    }
}

/**
 * 套在上游（网络）数据源外层的计数代理，Media3 数据源的薄装饰器。
 * 只做计时与计数，open/read/close 全部原样转发给上游。
 */
class MeteredDataSource(
    private val upstream: DataSource,
    private val meter: NetSpeedMeter,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val t0 = SystemClock.elapsedRealtime()
        val n = upstream.read(buffer, offset, length)
        val t1 = SystemClock.elapsedRealtime()
        if (n > 0) meter.add(n.toLong(), t0, t1)
        return n
    }

    override fun close() {
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val meter: NetSpeedMeter,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            MeteredDataSource(upstream.createDataSource(), meter)
    }
}
