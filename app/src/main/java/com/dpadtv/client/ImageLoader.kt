package com.dpadtv.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * 极简海报图加载器（不引 Coil/Glide）。
 *
 * 三件事必须做对，否则电视盒子上会立刻出问题：
 *   1. 走站点的 /api/proxy —— 豆瓣封面有防盗链，直连 403
 *   2. inSampleSize 降采样到目标尺寸附近 —— 不降采样的话几十张原图能把老设备的内存打爆
 *   3. 在 IO 线程下载、主线程交付；ImageView 复用时要校验 URL，避免GridView 滚动串图
 */
class ImageLoader(private val api: Api) {

    private val cache = LruCache<String, Bitmap>(160)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, reqWidth: Int, reqHeight: Int, onReady: (Bitmap?) -> Unit) {
        if (url.isEmpty()) {
            onReady(null)
            return
        }
        val key = "$url@${reqWidth}x$reqHeight"
        cache.get(key)?.let {
            onReady(it)
            return
        }
        pool.execute {
            val bmp = download(url, reqWidth, reqHeight)
            if (bmp != null) cache.put(key, bmp)
            main.post { onReady(bmp) }
        }
    }

    private fun download(url: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bytes = api.imageBytes(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
    /** 清空内存中的海报缓存，返回清掉的张数 */
    fun clearCache(): Int {
        val n = cache.size()
        cache.evictAll()
        return n
    }

    fun cacheSize(): Int = cache.size()
}
