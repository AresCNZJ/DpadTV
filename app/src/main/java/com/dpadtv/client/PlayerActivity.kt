package com.dpadtv.client

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 原生播放页（Media3 / ExoPlayer）。
 *
 * 按键逻辑（电视遥控器）：
 *   · OK           暂停 / 播放
 *   · 上           打开倍速菜单
 *   · 下 / 菜单键   呼出底部操作栏（选集/上下集/倍速/解码/画幅/重播/刷新）
 *   · 左右         按设置里的步长快退 / 快进，按住连发
 *   · 返回         退出播放
 *
 * 操作栏出现时，按键交给操作栏自己处理（左右移动、OK 执行），不再触发 seek。
 * 选集面板打开时，按键交给网格（方向键选择、OK 跳转、返回关闭）。
 *
 * 片名默认隐藏，只在暂停、操作栏打开、或出错时出现；切集时会短暂闪一下。
 * 缓冲时在「缓冲中…」下面显示实时网速（按累计字节差分计算）。
 */
class PlayerActivity : Activity() {

    private var coverUrl: String = ""

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"

        /** 「从头播放」：忽略续播记录，从 0 开始 */
        const val EXTRA_FROM_START = "from_start"

        private const val SAVE_INTERVAL_MS = 5_000L
        private const val HIDE_UI_DELAY_MS = 4_000L
        private const val HIDE_ACTIONS_DELAY_MS = 6_000L
        private const val TITLE_HOLD_MS = 3_000L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleText: TextView
    private lateinit var speedBadge: TextView
    private lateinit var progressRow: LinearLayout
    private lateinit var playPauseIcon: ImageView
    private lateinit var seekBar: ProgressBar
    private lateinit var timeText: TextView
    private lateinit var bufferingBox: LinearLayout
    private lateinit var bufferingText: TextView
    private lateinit var speedText: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var speedActionBtn: TextView
    private lateinit var decodeActionBtn: TextView
    private lateinit var aspectActionBtn: TextView
    private lateinit var actionScroll: HorizontalScrollView
    private lateinit var seekHint: TextView
    private lateinit var pickerOverlay: FrameLayout
    private lateinit var pickerGrid: GridView
    private lateinit var pickerTitle: TextView
    private lateinit var prefs: Prefs

    private var urls: List<String> = emptyList()
    private var currentIndex = 0
    private var seriesTitle = ""
    private var baseText = ""
    private var pendingSeek = -1L
    private var hasError = false
    private var aspectMode = 0
    private var fromStart = false
    private var seekStepMs = 15_000L
    private var pickerVisible = false

    private val handler = Handler(Looper.getMainLooper())

    /** 网速采样：用累计字节/累计耗时做差分 */
    private val statsListener = PlaybackStatsListener(false, null)
    private var lastBytes = 0L
    private var lastMs = 0L

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

    private val speedTick = object : Runnable {
        override fun run() {
            if (!::speedText.isInitialized || speedText.visibility != View.VISIBLE) return
            val stats = statsListener.playbackStats
            val bytes = stats?.totalBandwidthBytes ?: 0L
            val ms = stats?.totalBandwidthTimeMs ?: 0L
            if (ms > lastMs && bytes >= lastBytes) {
                val db = bytes - lastBytes
                val dm = ms - lastMs
                if (dm > 0) speedText.text = "网速 " + formatSpeed(db * 8000L / dm)
            }
            lastBytes = bytes
            lastMs = ms
            handler.postDelayed(this, 500)
        }
    }

    private val clearPendingSeek = Runnable { pendingSeek = -1L }

    /** 片名胶囊的「临时展示」计时：切集时闪 3 秒，之后跟着暂停/控制条状态走 */
    private var titleHold = 0L

    private val hideTitle = Runnable {
        titleHold = 0L
        updateChrome()
    }

    private val hideSeekHint = Runnable {
        if (::seekHint.isInitialized) seekHint.visibility = View.GONE
    }

    private val hideUi = Runnable {
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        handler.removeCallbacks(uiTick)
        // 暂停时控制条常驻；操作栏/选集中也不隐藏
        if (!paused && !isActionBarVisible() && !pickerVisible) {
            progressRow.visibility = View.GONE
        }
        updateChrome()
    }

    private val hideActions = Runnable {
        // 操作栏到点只收自己；进度条/标题走 collapseToProgress() 再挂 4 秒
        if (isActionBarVisible()) collapseToProgress()
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
        fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        seekStepMs = prefs.seekStepSeconds.coerceIn(5, 120) * 1000L

        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // 片名：默认隐藏，暂停 / 操作栏 / 出错时才显示。做成半透明胶囊，
        // 直接铺白字在亮画面上会看不清（原来的做法）。
        titleText = TextView(this).apply {
            baseText = labelFor(currentIndex)
            setText(baseText)
            setTextColor(Ui.TEXT)
            setTextSize(15f)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = Ui.bg(this@PlayerActivity, 0x99000000.toInt(), Ui.R_BTN)
            visibility = View.GONE
        }
        val titleWrap = FrameLayout(this).apply {
            isFocusable = false
            setPadding(dp(28), dp(24), dp(28), 0)
            addView(titleText, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(
            titleWrap,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        // 右上角倍速角标：非 1.0 倍速时常驻，避免用户忘了自己开过倍速
        speedBadge = TextView(this).apply {
            setTextColor(Ui.ACCENT)
            setTextSize(13f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = Ui.bg(this@PlayerActivity, 0x99000000.toInt(), Ui.R_BTN)
            visibility = View.GONE
        }
        root.addView(
            speedBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.RIGHT,
            ).apply {
                topMargin = dp(24)
                rightMargin = dp(28)
            },
        )

        // 进度条：左播放/暂停图标 + 进度 + 时间 + 倍速标签（都不可聚焦）
        progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(10), dp(28), dp(10))
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
        }
        playPauseIcon = ImageView(this).apply {
            setPadding(0, 0, dp(12), 0)
        }
        seekBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = getDrawable(R.drawable.player_progress)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeText = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(14f)
            setPadding(24, 0, 0, 0)
        }
        progressRow.addView(
            playPauseIcon,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        progressRow.addView(seekBar)
        progressRow.addView(timeText)
        root.addView(
            progressRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        // 底部操作栏（默认隐藏）。单集影片没有「选集/上下集」，只留播放相关的项，
        // 免得点了一圈都是「已是最后一集」的提示。
        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (urls.size > 1) {
            actionBar.addView(actionButton("选集") { showEpisodePicker() })
            actionBar.addView(actionButton("上集") {
                if (player?.hasPreviousMediaItem() == true) {
                    player?.seekToPreviousMediaItem()
                } else {
                    toast("已是第一集")
                }
                showActions()
            })
            actionBar.addView(actionButton("下集") {
                if (player?.hasNextMediaItem() == true) {
                    player?.seekToNextMediaItem()
                } else {
                    toast("已是最后一集")
                }
                showActions()
            })
        }
        speedActionBtn = actionButton("倍速 X1.00") { showSpeedMenu() }
        actionBar.addView(speedActionBtn)
        decodeActionBtn = actionButton(decodeLabel()) { showDecodeMenu() }
        actionBar.addView(decodeActionBtn)
        aspectActionBtn = actionButton(aspectLabel()) { showAspectMenu() }
        actionBar.addView(aspectActionBtn)
        actionBar.addView(actionButton("重播") {
            player?.seekTo(currentIndex, 0L)
            player?.play()
            showActions()
        })
        actionBar.addView(actionButton("刷新") {
            player?.let {
                it.seekTo(currentIndex, it.currentPosition)
                it.prepare()
            }
            toast("已重新加载")
            showActions()
        })

        actionScroll = HorizontalScrollView(this).apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(28), 0, dp(28), 0)
            addView(actionBar)
            visibility = View.GONE
        }
        root.addView(
            actionScroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { bottomMargin = dp(56) },
        )

        // 缓冲提示：转圈 + 文案 + 网速。原来只有一行字，看不出「是在转还是卡死了」
        bufferingText = TextView(this).apply {
            setText("缓冲中…")
            setTextColor(Ui.TEXT)
            setTextSize(17f)
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.VISIBLE
        }
        speedText = TextView(this).apply {
            setText("测速中…")
            setTextColor(Ui.TEXT_DIM)
            setTextSize(13f)
            setPadding(0, dp(6), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.VISIBLE
        }
        bufferingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                ProgressBar(this@PlayerActivity).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(10)
                },
            )
            addView(bufferingText)
            addView(speedText)
        }
        root.addView(
            bufferingBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // 快进/快退提示：屏幕正中的大号气泡
        seekHint = TextView(this).apply {
            setTextSize(22f)
            setTextColor(Ui.BRIGHT)
            setPadding(dp(30), dp(16), dp(30), dp(16))
            background = Ui.bg(this@PlayerActivity, 0xCC0B101A.toInt(), Ui.R_CARD)
            visibility = View.GONE
        }
        root.addView(
            seekHint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { bottomMargin = dp(90) },   // 抬高一点，别和中央的「缓冲中」叠在一起
        )

        buildPicker(root)

        setContentView(root)

        if (urls.isEmpty()) {
            Toast.makeText(this, "没有播放地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .build()

        // 分片级磁盘缓存：看过的片段落盘，回拖/重看不再重复下载
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("DpadTV-Android")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(PlaybackCache.get(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory =
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory)

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setRenderersFactory(buildRenderers())
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addAnalyticsListener(statsListener)
                exo.setPlaybackSpeed(prefs.playbackSpeed)
                exo.pauseAtEndOfMediaItems = !prefs.autoNext

                exo.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentIndex = exo.currentMediaItemIndex
                        hasError = false
                        baseText = labelFor(currentIndex)
                        if (!hasError) {
                            titleText.text = baseText
                            titleText.setTextColor(Ui.TEXT)
                        }
                        bufferingBox.visibility = View.VISIBLE
                        updateChrome()
                        // 切集时把片名闪一下：不然自动连播时用户不知道放到第几集了
                        flashTitle()
                        if (urls.size > 1 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            toast("自动播放：第 ${currentIndex + 1} 集")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val buffering = playbackState == Player.STATE_BUFFERING
                        bufferingBox.visibility = if (buffering) View.VISIBLE else View.GONE
                        speedText.visibility = if (buffering) View.VISIBLE else View.GONE
                        handler.removeCallbacks(speedTick)
                        if (buffering) {
                            lastBytes = 0L
                            lastMs = 0L
                            handler.post(speedTick)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            prefs.clearProgress(currentUrl())
                            // 全部放完后把操作栏亮出来，直接就能选集/换片
                            showActions()
                        }
                        updateChrome()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseIcon()
                        updateChrome()
                        if (!isPlaying) showProgress()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val msg = if (error.errorCodeName.contains("DECODER", true)) {
                            "解码失败：这个片源可能是 H.265 / 不支持的编码"
                        } else {
                            "播放失败：${error.errorCodeName}"
                        }
                        hasError = true
                        baseText = "$msg  ·  按菜单键换集或换源"
                        titleText.text = baseText
                        titleText.setTextColor(Ui.DANGER)
                        bufferingBox.visibility = View.GONE
                        speedText.visibility = View.GONE
                        updateChrome()
                        // 出错时把操作栏顶出来：用户不用先摸索按键就能换下一集
                        showActions()
                    }
                })

                val items = urls.map { MediaItem.fromUri(it) }
                // 「从头播放」要真的从头：清掉这一集的历史进度，否则下次还会续播
                if (fromStart) prefs.clearProgress(currentUrl())
                val resume = if (prefs.resumeEnabled && !fromStart) prefs.progressOf(currentUrl()) else 0L
                val threshold = prefs.resumeThresholdSeconds * 1000L
                exo.setMediaItems(items, currentIndex, if (resume > threshold) resume else 0L)
                exo.prepare()
                if (resume > threshold) {
                    toast("从上次位置继续（${formatTime(resume)}）")
                }
                exo.playWhenReady = true
            }

        updateSpeedAction()
        if (kotlin.math.abs(prefs.playbackSpeed - 1f) > 0.01f) {
            toast("当前倍速 ${speedLabel(prefs.playbackSpeed)}（在上键菜单里可改回 X1.00）")
        }
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
        updatePlayPauseIcon()
        updateChrome()
        flashTitle()
    }

    /* ------------------------------------------------------------ 选集面板 */

    /**
     * 选集网格。原先只能靠「上集 / 下集」一个个跳，40 集的剧想直接看第 30 集
     * 要按 29 次遥控器。这里给一个可 D-pad 浏览的网格，OK 直接跳转、返回关闭。
     */
    private fun buildPicker(root: FrameLayout) {
        pickerOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = Ui.bg(this@PlayerActivity, Ui.BG, Ui.R_CARD, 0, 1, Ui.STROKE)
        }
        pickerTitle = TextView(this).apply {
            setTextSize(17f)
            setTextColor(Ui.TEXT)
            setPadding(0, 0, 0, dp(14))
        }
        panel.addView(pickerTitle)

        pickerGrid = GridView(this).apply {
            numColumns = 8
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isFocusable = true
            isFocusableInTouchMode = true
            // 选中格：淡蓝填充 + 2dp 描边，和主界面列表同一套
            setSelector(Ui.bg(this@PlayerActivity, 0x1A38BDF8, Ui.R_BTN, 0, 2, Ui.ACCENT))
            adapter = object : BaseAdapter() {
                override fun getCount() = urls.size
                override fun getItem(position: Int): Any = position
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (convertView as? TextView)
                        ?: TextView(this@PlayerActivity).apply {
                            gravity = Gravity.CENTER
                            setTextSize(15f)
                            layoutParams = android.widget.AbsListView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, dp(40),
                            )
                        }
                    tv.text = "${position + 1}"
                    // 正在播放的这一集常亮，方便一眼看到自己在哪
                    tv.setTextColor(
                        if (position == currentIndex) Ui.ACCENT else Ui.TEXT_DIM,
                    )
                    return tv
                }
            }
        }
        pickerGrid.setOnItemClickListener { _, _, position, _ ->
            if (position != currentIndex) {
                player?.seekTo(position, 0L)
                player?.play()
            }
            hideEpisodePicker()
        }
        panel.addView(
            pickerGrid,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40) * 3 + dp(12),
            ),
        )

        pickerOverlay.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = dp(120)
                rightMargin = dp(120)
            },
        )
        root.addView(
            pickerOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun showEpisodePicker() {
        if (urls.size <= 1) return
        setActionBarVisible(false)
        pickerTitle.text = "${seriesTitle.ifEmpty { "选集" }}  ·  共 ${urls.size} 集  ·  返回键关闭"
        // 高度跟着集数走：3 集就是一行，200 集就滚动，别让空面板占满屏幕
        val rows = ((urls.size + 7) / 8).coerceIn(1, 5)
        pickerGrid.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rows * dp(46),
        )
        pickerOverlay.visibility = View.VISIBLE
        pickerVisible = true
        progressRow.visibility = View.VISIBLE
        handler.removeCallbacks(hideUi)
        pickerGrid.setSelection(currentIndex)
        pickerGrid.requestFocus()
        updateChrome()
    }

    private fun hideEpisodePicker() {
        pickerOverlay.visibility = View.GONE
        pickerVisible = false
        showProgress()
    }

    /* ------------------------------------------------------------ 显示逻辑 */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 片名可见性：出错 / 暂停 / 控制条或操作栏显示中 / 切集后的 3 秒 */
    private fun updateChrome() {
        if (!::titleText.isInitialized) return
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        val chromeVisible = (::progressRow.isInitialized && progressRow.visibility == View.VISIBLE) ||
            isActionBarVisible() || pickerVisible || titleHold > 0
        titleText.visibility = if (hasError || paused || chromeVisible) View.VISIBLE else View.GONE
        updateSpeedBadge()
    }

    /** 切集时把片名胶囊亮 3 秒，然后交回给 updateChrome 的常态判断 */
    private fun flashTitle() {
        if (!::titleText.isInitialized) return
        titleHold = TITLE_HOLD_MS
        titleText.visibility = View.VISIBLE
        handler.removeCallbacks(hideTitle)
        handler.postDelayed(hideTitle, TITLE_HOLD_MS)
    }

    private fun updateSpeedBadge() {
        if (!::speedBadge.isInitialized) return
        val s = player?.playbackParameters?.speed ?: prefs.playbackSpeed
        val show = kotlin.math.abs(s - 1f) > 0.01f
        speedBadge.text = speedLabel(s)
        speedBadge.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration
        // 连续快进时进度条要跟着目标点走，否则按三下右键、条子才挪一下
        val pos = if (pendingSeek >= 0) pendingSeek else p.currentPosition
        if (dur > 0) {
            seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            // 已缓冲部分画在已播放之后（secondaryProgress）
            seekBar.secondaryProgress = (p.bufferedPosition * 1000 / dur).toInt().coerceIn(0, 1000)
            timeText.text = formatTime(pos) + " / " + formatTime(dur)
        } else {
            seekBar.progress = 0
            seekBar.secondaryProgress = 0
            timeText.text = formatTime(pos) + " / --:--"
        }
        updatePlayPauseIcon()
    }

    /** 用 Canvas 画播放/暂停图标，省掉两张 png */
    private fun drawPauseIcon(paused: Boolean): android.graphics.Bitmap {
        val size = dp(16)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.TEXT
            style = android.graphics.Paint.Style.FILL
        }
        if (paused) {
            val w = size * 0.20f
            val gap = size * 0.18f
            val top = size * 0.10f
            c.drawRect(size / 2f - gap / 2f - w, top, size / 2f - gap / 2f, size - top, paint)
            c.drawRect(size / 2f + gap / 2f, top, size / 2f + gap / 2f + w, size - top, paint)
        } else {
            val path = android.graphics.Path()
            path.moveTo(size * 0.26f, size * 0.12f)
            path.lineTo(size * 0.84f, size * 0.5f)
            path.lineTo(size * 0.26f, size * 0.88f)
            path.close()
            c.drawPath(path, paint)
        }
        return bmp
    }

    private fun updatePlayPauseIcon() {
        if (!::playPauseIcon.isInitialized) return
        val playing = player?.isPlaying == true
        playPauseIcon.setImageBitmap(drawPauseIcon(paused = playing))
    }

    private fun formatSpeed(bitsPerSecond: Long): String {
        if (bitsPerSecond <= 0L) return "测速中…"
        val kbps = bitsPerSecond / 1000.0
        return if (kbps < 1000) "${kbps.toInt()} KB/s"
        else String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1000.0)
    }

    /* ------------------------------------------------------------ 控制条 */

    private fun showProgress() {
        progressRow.visibility = View.VISIBLE
        updateProgress()
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        if (!isActionBarVisible() && !pickerVisible) {
            handler.postDelayed(hideUi, HIDE_UI_DELAY_MS)
        }
        updateChrome()
    }

    private fun isActionBarVisible(): Boolean =
        ::actionScroll.isInitialized && actionScroll.visibility == View.VISIBLE

    private fun setActionBarVisible(visible: Boolean) {
        if (!::actionScroll.isInitialized) return
        actionScroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            actionBar.clearFocus()
        }
    }

    /** 下键 / 菜单键：呼出操作栏，焦点落到第一个按钮 */
    private fun showActions() {
        if (pickerVisible) return
        setActionBarVisible(true)
        progressRow.visibility = View.VISIBLE
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        updateChrome()
        if (actionBar.childCount > 0) {
            actionBar.post {
                actionBar.getChildAt(0).requestFocus()
            }
        }
        handler.removeCallbacks(hideActions)
        handler.postDelayed(hideActions, HIDE_ACTIONS_DELAY_MS)
    }

    /**
     * 收起操作栏，控制条退到「只显示进度条」的中间态 —— 也就是按 OK 暂停后那种状态，
     * 4 秒后由 hideUi 把进度条和标题一起收掉。
     *
     * 之前的 bug：菜单键/返回键收起操作栏时只藏了操作栏，而 hideUi 在 showActions()
     * 里被移除后没人再排上，进度条和标题从此常驻屏幕（6 秒超时路径同样中招）。
     */
    private fun collapseToProgress() {
        setActionBarVisible(false)
        progressRow.visibility = View.VISIBLE
        handler.removeCallbacks(hideActions)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        handler.removeCallbacks(hideUi)
        handler.postDelayed(hideUi, HIDE_UI_DELAY_MS)
        updateChrome()
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Ui.TEXT)
        setTextSize(14f)
        setPadding(dp(20), dp(11), dp(20), dp(11))
        // 常态深色胶囊，聚焦 = 亮蓝填充 + 深色文字。
        // 视频画面上背景是黑的，反色比细描边更容易在 3 米外看清焦点。
        val normal = Ui.bg(this@PlayerActivity, 0xFF26344A.toInt(), Ui.R_BTN)
        val focused = Ui.bg(this@PlayerActivity, Ui.ACCENT, Ui.R_BTN, 0, 2, Ui.BRIGHT)
        background = normal
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { v, has ->
            v.background = if (has) focused else normal
            (v as TextView).setTextColor(if (has) Ui.ON_ACCENT else Ui.TEXT)
        }
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.rightMargin = dp(10)
        layoutParams = lp
    }

    /** 倍速显示成 X1.00 这种形式（大写 X + 两位小数） */
    private fun speedLabel(v: Float): String =
        "X" + String.format(java.util.Locale.US, "%.2f", v)

    private fun updateSpeedAction() {
        if (!::speedActionBtn.isInitialized) return
        val s = player?.playbackParameters?.speed ?: prefs.playbackSpeed
        speedActionBtn.text = "倍速 " + speedLabel(s)
        updateSpeedBadge()
    }

    /** 把当前值写进按钮标签：不点开菜单也知道现在是「自动解码、适应屏幕」 */
    private fun decodeLabel(): String = "解码 " + when (prefs.decodeMode) {
        1 -> "软解"
        2 -> "硬解"
        else -> "自动"
    }

    private fun aspectLabel(): String = "画幅 " + when (aspectMode) {
        1 -> "裁切"
        2 -> "拉伸"
        else -> "适应"
    }

    private fun showSpeedMenu() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { speedLabel(it) }
        val cur = speeds.indexOfFirst {
            kotlin.math.abs(it - (player?.playbackParameters?.speed ?: 1f)) < 0.01f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels.toTypedArray(), cur.coerceAtLeast(-1)) { d, which ->
                player?.setPlaybackSpeed(speeds[which])
                prefs.playbackSpeed = speeds[which]
                updateSpeedAction()
                d.dismiss()
                showProgress()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDecodeMenu() {
        val names = arrayOf("自动（推荐）", "强制软解", "强制硬解")
        android.app.AlertDialog.Builder(this)
            .setTitle("解码方式（下次打开视频生效）")
            .setSingleChoiceItems(names, prefs.decodeMode.coerceIn(0, 2)) { d, which ->
                prefs.decodeMode = which
                decodeActionBtn.text = decodeLabel()
                d.dismiss()
                toast("已设为：${names[which]}，重新打开视频生效")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAspectMenu() {
        val names = arrayOf("适应屏幕", "裁切铺满", "拉伸铺满")
        android.app.AlertDialog.Builder(this)
            .setTitle("显示比例")
            .setSingleChoiceItems(names, aspectMode) { d, which ->
                aspectMode = which
                playerView.resizeMode = when (which) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                aspectActionBtn.text = aspectLabel()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 解码方式：0 自动 / 1 强制软解 / 2 强制硬解 */
    private fun buildRenderers(): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val mode = prefs.decodeMode
        if (mode != 0) {
            factory.setMediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                if (mode == 1) {
                    all.filterNot { it.hardwareAccelerated }
                } else {
                    all.filter { it.hardwareAccelerated }.ifEmpty { all }
                }
            }
        }
        return factory
    }

    /* ------------------------------------------------------------ 进度与退出 */

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
        flashSeekHint(target, deltaMs)
    }

    /** 快进/快退时在屏幕正中给出「跳了多少、落到几分几秒」——底部小字 3 米外看不清 */
    private fun flashSeekHint(targetMs: Long, deltaMs: Long) {
        if (!::seekHint.isInitialized) return
        val secs = kotlin.math.abs(deltaMs) / 1000
        val arrow = if (deltaMs >= 0) "▶▶" else "◀◀"
        val action = if (deltaMs >= 0) "快进" else "快退"
        seekHint.text = "$arrow  $action $secs 秒    " + formatTime(targetMs)
        seekHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekHint)
        handler.postDelayed(hideSeekHint, 900L)
    }

    private fun currentUrl(): String = urls.getOrNull(currentIndex).orEmpty()

    private fun labelFor(index: Int): String {
        if (urls.size <= 1) return seriesTitle.ifEmpty { "正在播放" }
        return "$seriesTitle · 第 ${index + 1}/${urls.size} 集"
    }

    private fun persistProgress() {
        val p = player ?: return
        if (p.isPlaying || p.playbackState == Player.STATE_READY) {
            val url = currentUrl()
            if (url.isEmpty()) return
            prefs.saveProgress(url, p.currentPosition)
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

    /** 超过一小时要显示 1:23:20，否则「83:20」这种分钟数会让人读不出来 */
    private fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
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
        // 选集面板打开时：返回键收起，其余（方向键 / OK）全部交给网格
        if (pickerVisible) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU ||
                    keyCode == KeyEvent.KEYCODE_M)
            ) {
                hideEpisodePicker()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        val p = player ?: return super.onKeyDown(keyCode, event)

        // 操作栏打开时：按键全部交给菜单（按钮可聚焦、可点击）
        if (isActionBarVisible()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                handler.removeCallbacks(hideActions)
                handler.postDelayed(hideActions, HIDE_ACTIONS_DELAY_MS)
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_M,
                    -> {
                        collapseToProgress()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // 已经在操作栏上，再按下键就收起
                        collapseToProgress()
                        return true
                    }
                }
            }
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            -> {
                if (p.isPlaying) p.pause() else p.play()
                showProgress()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showSpeedMenu()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showActions()
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> {
                showActions()
                true
            }
            // 步长取自「播放设置」：原来这里写死 10/15 秒，那个设置项其实一直是失效的
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(seekStepMs)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(-seekStepMs)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(seekStepMs * 3)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-seekStepMs * 3)
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
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(hideActions)
        handler.removeCallbacks(clearPendingSeek)
        handler.removeCallbacks(hideSeekHint)
        handler.removeCallbacks(hideTitle)
        player?.release()
        player = null
        super.onDestroy()
    }
}
