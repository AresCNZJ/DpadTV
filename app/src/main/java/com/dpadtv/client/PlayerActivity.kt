package com.dpadtv.client

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 原生播放页（Media3 / ExoPlayer）。
 *
 * · **整季作为播放列表**：一集放完自动进下一集，不用退回详情页手动点
 * · 续播：按播放地址记位置，每 5 秒落盘，退出/切后台也存
 * · 进度条：按键时浮出 4 秒，显示 当前/总时长
 * · H.264 必过；H.265 取决于 SoC 硬解，失败会明确提示而不是黑屏
 *
 * 遥控器：OK = 播放/暂停，左右 = ±10 秒，返回 = 退出
 */
class PlayerActivity : Activity() {

    /** 播放时带过来的封面地址，用于写入观看历史 */
    private var coverUrl: String = ""

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val HIDE_UI_DELAY_MS = 4_000L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleText: TextView
    private lateinit var progressRow: LinearLayout
    private lateinit var seekBar: ProgressBar
    private lateinit var timeText: TextView
    private lateinit var bufferingText: TextView
    private lateinit var prefs: Prefs

    private var urls: List<String> = emptyList()
    private var currentIndex = 0
    private var seriesTitle = ""
    private var baseText = ""
    private var pendingSeek = -1L

    private val handler = Handler(Looper.getMainLooper())

    private val saveTick = object : Runnable {
        override fun run() {
            persistProgress()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }
    private val uiTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }
    private val hideUi = Runnable {
        handler.removeCallbacks(uiTick)
        progressRow.visibility = View.GONE
    }

    private val clearPendingSeek = Runnable { pendingSeek = -1L }

    /**
     * 快进/快退。按住不放时（repeatCount > 0）每步 15 秒，
     * 而且基于「目标位置」累加 —— seekTo 是异步的，若按当前位置算，连按会互相覆盖，
     * 手感上就像按了没反应。
     */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0)
        val base = if (pendingSeek >= 0) pendingSeek else p.currentPosition
        val raw = base + deltaMs
        val target = if (dur > 0) raw.coerceIn(0, dur) else raw.coerceAtLeast(0)
        pendingSeek = target
        p.seekTo(target)
        handler.removeCallbacks(clearPendingSeek)
        handler.postDelayed(clearPendingSeek, 1_500)
        showProgress()
    }

    private fun currentUrl(): String = urls.getOrNull(currentIndex).orEmpty()

    /** 片名 + 集数（单集时只显示片名） */
    private fun labelFor(index: Int): String {
        if (urls.size <= 1) return seriesTitle.ifEmpty { "正在播放" }
        return "$seriesTitle · 第 ${index + 1}/${urls.size} 集"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        prefs = Prefs(this)
        urls = intent.getStringArrayListExtra(EXTRA_URLS)?.toList().orEmpty()
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        seriesTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        coverUrl = intent.getStringExtra(EXTRA_COVER).orEmpty()

        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        titleText = TextView(this).apply {
            baseText = labelFor(currentIndex)
            setText(baseText)
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            setTextSize(14f)
            setPadding(48, 28, 48, 0)
        }
        root.addView(titleText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))

        progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 20, 48, 20)
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
        }
        seekBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeText = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(14f)
            setPadding(24, 0, 0, 0)
        }
        progressRow.addView(seekBar)
        progressRow.addView(timeText)
        root.addView(progressRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        bufferingText = TextView(this).apply {
            setText("缓冲中…")
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            setTextSize(18f)
            visibility = View.VISIBLE
        }
        root.addView(bufferingText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))

        setContentView(root)

        if (urls.isEmpty()) {
            Toast.makeText(this, "没有播放地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        // 自动连播到下一集：同步索引、标题与观看历史
                        currentIndex = exo.currentMediaItemIndex
                        baseText = labelFor(currentIndex)
                        titleText.text = baseText
                        titleText.setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
                        bufferingText.visibility = View.VISIBLE
                        if (urls.size > 1 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            Toast.makeText(
                                this@PlayerActivity,
                                "自动播放：第 ${currentIndex + 1} 集",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        bufferingText.visibility =
                            if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (playbackState == Player.STATE_ENDED) {
                            // 整季放完，清掉最后一集的进度
                            prefs.clearProgress(currentUrl())
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val msg = if (error.errorCodeName.contains("DECODER", true)) {
                            "解码失败：这个片源可能是 H.265 / 不支持的编码 · 按返回键换源"
                        } else {
                            "播放失败：${error.errorCodeName} · 按返回键换源"
                        }
                        baseText = msg
                        titleText.text = msg
                        titleText.setTextColor(android.graphics.Color.parseColor("#fca5a5"))
                        bufferingText.visibility = View.GONE
                    }
                })
                // 整季一次性塞进播放列表：ExoPlayer 会自己顺序播下去
                val items = urls.map { MediaItem.fromUri(it) }
                val resume = prefs.progressOf(currentUrl())
                exo.setMediaItems(items, currentIndex, if (resume > 5_000L) resume else 0L)
                exo.prepare()
                if (resume > 5_000L) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "从上次位置继续（${formatTime(resume)}）",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                exo.playWhenReady = true
            }
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
    }

    /* ------------------------------------------------------------------ 进度 */

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration
        if (dur > 0) {
            seekBar.progress = (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000)
            timeText.text = formatTime(p.currentPosition) + " / " + formatTime(dur)
        } else {
            seekBar.progress = 0
            timeText.text = formatTime(p.currentPosition) + " / --:--"
        }
    }

    private fun showProgress() {
        progressRow.visibility = View.VISIBLE
        updateProgress()
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        handler.postDelayed(hideUi, HIDE_UI_DELAY_MS)
    }

    private fun persistProgress() {
        val p = player ?: return
        if (p.isPlaying || p.playbackState == Player.STATE_READY) {
            val url = currentUrl()
            if (url.isEmpty()) return
            prefs.saveProgress(url, p.currentPosition)
            // 观看历史完全由 App 本地维护（不依赖网页版/服务端）
            // 只要播过 5 秒就记历史：原来要求 duration>0 且播满 30 秒，
            // 而不少 HLS 源拿不到 duration，结果一条记录都不会留下（真机已复现）
            if (p.currentPosition > 5_000L) {
                prefs.upsertHistory(
                    HistoryEntry(
                        title = labelFor(currentIndex),
                        playUrl = url,
                        positionMs = p.currentPosition,
                        durationMs = p.duration.coerceAtLeast(0L),
                        updatedAt = System.currentTimeMillis(),
                        cover = coverUrl,
                    ),
                )
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (p.isPlaying) p.pause() else p.play()
                showProgress()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(if (event.repeatCount > 0) 15_000L else 10_000L)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(if (event.repeatCount > 0) -15_000L else -10_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    showProgress()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (p.hasPreviousMediaItem()) {
                    p.seekToPreviousMediaItem()
                    showProgress()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                showProgress()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        persistProgress()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(uiTick)
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(clearPendingSeek)
        player?.release()
        player = null
        super.onDestroy()
    }
}
