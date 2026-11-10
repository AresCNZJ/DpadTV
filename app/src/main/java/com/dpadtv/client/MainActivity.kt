package com.dpadtv.client

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原生客户端主界面。刻意不用 XML 布局、不引 AppCompat/Leanback：
 * 只用框架控件，D-pad 焦点是系统原生支持的，老设备上行为最可预测。
 *
 * 屏幕：设置 → 片源 → 首页（搜索 + 结果）→ 详情（选集）→ 播放器（独立 Activity）
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var imageLoader: ImageLoader
    private lateinit var root: FrameLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private var lastResults: List<SearchItem> = emptyList()
    private var recommendItems: List<DoubanItem> = emptyList()
    private var liveChannels: List<Channel> = emptyList()
    private var liveChannelSource: String = ""

    /** 频道测活结果：url -> 能不能连上。换源时清空 */
    private val liveProbeResult = mutableMapOf<String, Boolean>()

    /** 只看测活通过的频道 */
    private var liveOnlyAlive = false

    /** 收藏夹视图：不区分直播源，列出全部收藏 */
    private var liveShowFavorites = false

    /** 当前列表里显示的频道，收藏按钮要用它把「选中的那一行」翻回去 */
    private var liveShown: List<Channel> = emptyList()
    private var liveListView: ListView? = null
    private var mediaType = "movie"
    private var recommendPage = 0
    private var recommendTag = "热门"
    private var isLoadingMore = false
    private var noMoreRecommend = false
    private var suppressAutoLoad = false
    private var pendingGridSelection = -1
    private var lastVisibleIndex = 0
    private var liveGroupFilter = ""
    private var sourceFilter = ""
    /** 搜索框里还没提交的文字，用于页面重建时恢复 */
    private var searchDraft = ""
    private var busyDialog: AlertDialog? = null
    /** 当前界面名，用于实现遥控器 BACK 的层级回退（否则任何界面按返回都会直接退出应用）。 */
    private var currentScreen = "setup"
    /** 设置页是否展开「服务器连接」区块。已连接时默认收起（低频操作不占首屏） */
    private var setupShowFields = false
    private val REQ_PICK_JSON = 1001
    private val REQ_STORAGE = 1002

    /* ---------------------------------------------------------- 焦点视觉 */

    // 电视上最致命的问题就是「看不出焦点在哪」。统一用高对比度方案：
    // 聚焦 = 2dp 亮蓝描边 + 轻微放大。颜色全部取自 Ui，避免各处手写近似色。
    private val ACCENT = Ui.ACCENT
    private val BRIGHT = Ui.BRIGHT
    private val INK = Ui.ON_ACCENT
    private val TEXT_COLOR = Ui.TEXT
    private val PANEL = Ui.SURFACE_HI
    private val BRIGHTER_TEXT = 0xFFCBD5E1.toInt()

    /**
     * 给任意可聚焦控件装上焦点视觉。
     *
     * 关键点（踩过坑）：海报格子整块被 ImageView 占满，如果只改 background，
     * 焦点色会被图片盖住 —— 真机上看起来就是「焦点还是黑的」。
     * 所以额外用 **foreground** 画一圈亮色描边，它画在内容之上，
     * 图片加载成功、失败、纯色占位都能看见焦点。
     *
     * 另外用 InsetDrawable 内缩 3dp，让相邻按钮/行之间天然有缝，
     * 不会因为 LinearLayout 没设 margin 而连成一片。
     *
     * @param textView 需要跟着变色的文字（控件本身就是文字时传它自己）
     * @param focusedBg 聚焦时的填充色（主按钮本身就是蓝色，需要更亮一档才看得出变化）
     */
    private fun applyFocusStyle(
        view: View,
        normalBg: Int,
        textView: TextView? = null,
        normalText: Int = TEXT_COLOR,
        focusedBg: Int = ACCENT,
        onFocusChanged: ((Boolean) -> Unit)? = null,
        focusScale: Float = 1.03f,
        inset: Int = 3,
        focusedOutline: Int = ACCENT,
        radiusDp: Int = Ui.R_BTN,
    ) {
        val radius = radiusDp
        val normal = InsetDrawable(
            GradientDrawable().apply {
                setColor(normalBg)
                cornerRadius = dp(radius).toFloat()
            },
            dp(inset),
        )
        // 焦点态 = 原填充 + 2dp 亮色描边。描边放在背景里而不是 foreground：
        // 实测 Button 上 foreground 不渲染（放大截图里 5 个按钮完全一样），
        // 画进背景则一定能画出来，卡片和按钮也就能共用同一套样式。
        //
        // 描边从早期的 5dp 收到 2dp：5dp 在 1080p 电视上像一圈「相框」，
        // 反而盖住了控件本身的边界；2dp 足够在 3 米外看清，也更接近成品应用的手感。
        val focused = InsetDrawable(
            GradientDrawable().apply {
                setColor(normalBg)
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(2), focusedOutline)
            },
            dp(inset),
        )

        // View.setBackground() 会用背景 drawable 的 padding 覆盖控件自身的 padding！
        // InsetDrawable 会报告 inset 的 padding，于是我们设的 16dp 内边距被无声吃掉，
        // 文字直接顶到边框（真机上表现为「左边距没了、开头几个字符被裁」）。
        // 所以先把原始 padding 存下来，每次换背景后立刻还回去。
        val padLeft = view.paddingLeft
        val padTop = view.paddingTop
        val padRight = view.paddingRight
        val padBottom = view.paddingBottom

        fun render(focusedNow: Boolean) {
            // 焦点只用亮色外框表示：不改填充、不反转文字颜色。
            // （早期用蓝底+深色字，深色主题下反而显得脏；白框在深色背景上已经足够醒目）
            view.background = if (focusedNow) focused else normal
            view.setPadding(padLeft, padTop, padRight, padBottom)
            val s = if (focusedNow) focusScale else 1f
            view.scaleX = s
            view.scaleY = s
        }

        // 立刻按当前状态渲染一次：Adapter 复用时控件可能本来就是聚焦的，
        // 只挂监听不会触发，焦点框就会丢
        render(view.isFocused)
        view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            render(hasFocus)
            // 额外回调（例如输入框聚焦时弹软键盘）必须走这里，
            // 否则会被这里 setOnFocusChangeListener 覆盖掉
            onFocusChanged?.invoke(hasFocus)
        }
    }

    /** 横向工具栏放进可滚动容器：按钮再多也不会被挤出屏幕 */
    private fun hScroll(row: View): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // 关键：容器自己不能抢焦点，否则焦点会停在滚动容器上，
            // 里面一排按钮一个都不高亮（真机表现为「焦点是灰的、看不出选中哪个」）
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(row)
        }

    /** 进界面时若没有任何元素获得焦点，主动聚焦第一个可聚焦控件。 */
    private fun focusFirstAvailable(container: View) {
        container.post {
            if (root.findFocus() != null) return@post
            firstFocusable(container)?.requestFocus()
        }
    }

    private fun firstFocusable(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.isFocusable && v !is ViewGroup) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                firstFocusable(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = Api(prefs.serverUrl.ifEmpty { BuildConfig.DEFAULT_URL })
        api.restoreCookie(prefs.cookie)
        imageLoader = ImageLoader(api)
        maybeCheckUpdate()

        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)
        hideSystemUi()

        if (prefs.serverUrl.isEmpty()) showSetup() else showBoot()
    }

    override fun onDestroy() {
        qrServer?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
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
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSetup()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * BACK 的层级回退：详情/片源/设置/直播 都退回上一层，
     * 只有最外层（推荐首页）才真的退出。以前任何界面按返回都直接杀掉应用。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (currentScreen) {
            "files" -> showSources()
            "qradd" -> { qrServer?.stop(); qrServer = null; showSources() }
            // 结果页回到发起搜索的那一页，而不是无条件回首页
            "results" -> backFromResults()
            "serversettings" -> if (prefs.serverUrl.isEmpty()) super.onBackPressed() else showSetup()
            "detail" -> backFromDetail()
            "live", "setup", "history" -> showRecommend()
            "sources" -> if (sourcesOrigin == "setup") showSetup() else showRecommend()
            "home" -> showRecommend()
            // 首页不再直接退出：卡片 → 顶部按钮 → 退出确认，逐级退
            "recommend" -> backFromRecommend()
            // 导入流程内的页面：逐级退回上一级，而不是退出 App
            "input" -> showAddSource()
            "manualadd" -> showAddSource()
            "addsource" -> showSources()
            "import" -> showSources()
            "homebar", "playsettings" -> showSetup()
            "boot" -> super.onBackPressed()
            else -> showRecommend()
        }
    }

    /**
     * 首页顶部按钮栏的左右键拦截。
     * 系统在 View 层就完成了焦点搜索，等到 onKeyDown 已经太晚，
     * 所以在派发之前判断：邻居不在同一个栏里，就直接吞掉按键。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val bar = homeBar
            val focused = currentFocus
            val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) View.FOCUS_RIGHT else View.FOCUS_LEFT
            val next = focused?.focusSearch(dir)
            if (bar != null && focused != null && focused.parent === bar) {
                if (next == null || next.parent !== bar) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    /* ------------------------------------------------------------ 通用小工具 */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setScreen(view: View, focusFirst: Boolean = true) {
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (focusFirst) focusFirstAvailable(view)
    }

    /* ------------------------------------------------------------ 品牌标识 */

    /**
     * DpadTV wordmark。刻意用文字 + 形状拼出来（不引图片资源）：
     * 任意分辨率都清晰，也不给 APK 增加体积。
     */
    private fun brandRow(showTagline: Boolean = true): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        // 方块标记：播放三角
        row.addView(
            TextView(this).apply {
                setText("▶")
                setTextSize(17f)
                setTextColor(INK)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(10).toFloat()
                }
            },
        )

        // 字标：Dpad 白 + TV 高亮
        row.addView(
            TextView(this).apply {
                val text = SpannableString("DpadTV")
                text.setSpan(ForegroundColorSpan(TEXT_COLOR), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(ACCENT), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setText(text)
                setTextSize(23f)
                setPadding(dp(10), 0, dp(12), 0)
                gravity = Gravity.CENTER_VERTICAL
            },
        )

        return row
    }

    private fun hintText(text: String): TextView =
        TextView(this).apply {
            setText(text)
            setTextSize(15f)
            setTextColor(Color.parseColor("#94a3b8"))
            setPadding(0, dp(6), 0, dp(10))
        }

    private fun field(hint: String, value: String = "", password: Boolean = false): EditText =
        NoScrollEditText(this).apply {
            // 我们的焦点框会替换掉系统输入框背景，连带丢掉它自带的内边距 ——
            // 不显式给高度和内边距的话，输入框会塌成一条细线（真机已复现）
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            minHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            setHint(hint)
            setTextColor(Color.parseColor("#e5e7eb"))
            setHintTextColor(Color.parseColor("#94a3b8"))
            setTextSize(18f)
            setSingleLine(true)
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            // 时序坑（真机与模拟器都复现过）：首次布局前控件宽度是 0，此时 setText 会让
            // TextView 按「文字比容器宽」算出横向滚动偏移；布局撑开宽度后这个偏移不归零，
            // 表现为开头 1~2 个字符被裁掉。post{} 无效（它在布局之前执行），
            // 所以改成**等第一次布局真正完成后再写入文字**。
            if (value.isNotEmpty()) {
                addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        v.removeOnLayoutChangeListener(this)
                        (v as EditText).setText(value)
                        v.setSelection(value.length)
                        v.scrollTo(0, 0)
                    }
                })
            }
            // 输入框不放大：放大后它会比父容器宽出 46px，实测会导致聚焦状态首字符渲染异常
            applyFocusStyle(
                view = this,
                normalBg = 0xFF111827.toInt(),
                focusedBg = 0xFF1E3A5F.toInt(),
                focusScale = 1f,
                onFocusChanged = { hasFocus ->
                    if (hasFocus) {
                        postDelayed({
                            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        }, 120)
                    }
                },
            )
        }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            setText(label)
            isAllCaps = false
            setTextSize(15f)
            isSingleLine = true
            setTextColor(if (primary) INK else TEXT_COLOR)
            // 按内容自适应宽度：固定 dp 宽度在低分辨率电视上会被挤出屏幕
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { rightMargin = dp(8) }
            minWidth = dp(72)
            minimumWidth = dp(72)
            // 替换背景后按钮自带的内边距会丢，显式补上，避免文字贴边
            setPadding(dp(18), dp(8), dp(18), dp(8))
            // 去掉 Material 默认的按下抬升阴影：全站是扁平深色，阴影反而显脏
            stateListAnimator = null
            setOnClickListener { onClick() }
            applyFocusStyle(
                this,
                if (primary) ACCENT else PANEL,
                this,
                if (primary) INK else TEXT_COLOR,
                if (primary) ACCENT else PANEL,
                focusScale = 1.03f,
                inset = 3,
                focusedOutline = if (primary) BRIGHT else ACCENT,
            )
        }

    /* ------------------------------------------------------------ 视觉组件 */

    /** 页面骨架：统一留白，省得每个页面各写一遍 setPadding */
    private fun screen(padH: Int = Ui.PAD_H, padV: Int = Ui.PAD_V): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        }

    /**
     * 二级页顶栏：返回 + 页面名 + 右侧计数。
     * 原先每个页面都在左上角挂一遍 logo —— 对用户没有信息量，还占掉一行高度。
     * 现在只有首页保留 logo，二级页统一成「返回 + 你在哪一页」。
     */
    private fun topBar(title: String, back: (() -> Unit)? = null, tail: String = ""): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        if (back != null) {
            row.addView(button("返回") { back() })
        } else {
            row.addView(brandRow(false))
        }
        row.addView(
            TextView(this).apply {
                text = title
                setTextSize(if (back == null) 16f else 19f)
                setTextColor(TEXT_COLOR)
                setPadding(dp(14), 0, 0, 0)
                isSingleLine = true
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (tail.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = tail
                setTextSize(13f)
                setTextColor(Ui.TEXT_FAINT)
            })
        }
        return row
    }

    /** 分组小标题：字号小、颜色弱，只用来分段，不跟内容抢注意力 */
    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        // 必须写 this.text：参数名 text 会遮蔽 TextView.text，
        // 直接写 text = text 会被解析成「给参数赋值」→ 'val' cannot be reassigned
        this.text = text
        setTextSize(13f)
        setTextColor(Ui.TEXT_FAINT)
        setPadding(0, dp(16), 0, dp(8))
    }

    /** 卡片容器：比页面底色亮一档 + 1dp 描边，把一组设置项包成一个整体 */
    /**
     * 分组卡片：圆角面板 + 1dp 描边，里面放若干 settingRow。
     *
     * 描边画在**前景层**（foreground），不画在背景里：行是铺满的矩形，画在背景上的描边
     * 会被行整段盖住，只从行与行之间的 1dp 缝里露出一个孤立竖点（就是之前那条「短白条」）。
     * 画在前景则永远压在子 View 之上，整圈描边稳定可见。
     *
     * 圆角交给 clipToOutline 裁，行本身用直角（radiusDp = 0）：否则行自己再画一遍圆弧，
     * 会和卡片圆弧错开 1dp，看着就是「转角虚化」。
     */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.bg(this@MainActivity, Ui.SURFACE, Ui.R_CARD)
        clipToOutline = true
        foreground = Ui.bg(this@MainActivity, android.graphics.Color.TRANSPARENT, Ui.R_CARD, 0, 1, Ui.STROKE)
    }

    /**
     * 卡片内的行间分隔线。
     *
     * 不要左右内缩：内缩后线两头会和卡片描边之间空出 20dp，那截被孤立的竖边
     * 在暗底上看着就像「两端各贴了一个短白条」。通到卡片边缘后是一条完整的线，
     * 和卡片边框接成 T 形，视觉上才连续。
     */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(Ui.STROKE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
        )
    }

    /**
     * 设置行：左标题、右当前值、最右箭头，整行可聚焦。
     * 取代原来「一列宽度参差的按钮」——那些按钮既看不出当前值，也看不出点进去会怎样。
     */
    private fun settingRow(
        title: String,
        value: String = "",
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onClick() }
        addView(
            TextView(this@MainActivity).apply {
                text = title
                setTextSize(16f)
                setTextColor(TEXT_COLOR)
                isSingleLine = true
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (value.isNotEmpty()) {
            addView(TextView(this@MainActivity).apply {
                text = value
                setTextSize(14f)
                setTextColor(Ui.TEXT_DIM)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(380)
                setPadding(dp(12), 0, dp(10), 0)
            })
        }
        addView(TextView(this@MainActivity).apply {
            text = "›"
            setTextSize(20f)
            setTextColor(Ui.TEXT_FAINT)
        })
        // 行的圆角必须和卡片一致（Ui.R_CARD）：不一样的话卡片弧和行弧错开，
        // 聚焦时外面一圈圆角、里面一圈直角，看着就是「焦点态把圆角弄没了」。
        applyFocusStyle(this, Ui.SURFACE, null, focusScale = 1f, inset = 0, radiusDp = Ui.R_CARD)
    }

    /** 分类页签：选中态是实心强调色，一眼能看出「现在看的是哪一类」 */
    private fun navTab(text: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(17f)
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(dp(26), 0, dp(26), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { rightMargin = dp(8) }
            setTextColor(if (active) INK else Ui.TEXT_DIM)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            // 打上标记：首页按返回时要把焦点还给「当前选中的那个分类」，
            // 而不是永远跳回第一个页签（正看剧集却回到电影会很懵）
            if (active) tag = TAG_ACTIVE_TAB
            applyFocusStyle(
                this,
                if (active) ACCENT else Color.TRANSPARENT,
                this,
                focusScale = 1.02f,
                inset = 2,
                focusedOutline = if (active) BRIGHT else ACCENT,
            )
        }

    /** 小胶囊：热门词、来源筛选这类「可选可不选」的次级操作 */
    private fun chip(text: String, active: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(14f)
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(8) }
            setTextColor(if (active) INK else Ui.TEXT_DIM)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            applyFocusStyle(
                this,
                if (active) ACCENT else Ui.SURFACE,
                this,
                focusScale = 1.02f,
                inset = 2,
                focusedOutline = if (active) BRIGHT else ACCENT,
            )
        }

    /** 海报卡的三个可变部分；用 tag 挂在根布局上，比 getChildAt 下标稳 */
    private class PosterHolder(
        val cell: LinearLayout,
        val img: ImageView,
        val badge: TextView,
        val title: TextView,
        val meta: TextView,
    )

    /**
     * 海报卡：圆角封面 + 评分角标 + 两行标题。
     * 首页、搜索结果、继续观看共用同一套，三处的观感才一致。
     * 圆角靠 clipToOutline 裁剪图片本身（给图片设背景圆角是没用的，图会盖住圆角）。
     */
    private fun buildPoster(): PosterHolder {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val frame = FrameLayout(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Ui.SURFACE_HI)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(
                        0, 0, view.width, view.height, dp(Ui.R_CARD).toFloat(),
                    )
                }
            }
        }
        frame.addView(img, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val badge = TextView(this).apply {
            setTextSize(11f)
            setTextColor(INK)
            setPadding(dp(7), dp(2), dp(7), dp(3))
            background = Ui.bg(this@MainActivity, ACCENT, 6)
            visibility = View.GONE
        }
        frame.addView(badge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = dp(6)
            rightMargin = dp(6)
        })
        cell.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(206)))
        val title = TextView(this).apply {
            setTextSize(13f)
            setTextColor(TEXT_COLOR)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, 0)
        }
        cell.addView(title)
        val meta = TextView(this).apply {
            setTextSize(12f)
            setTextColor(Ui.TEXT_FAINT)
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        cell.addView(meta)
        val holder = PosterHolder(cell, img, badge, title, meta)
        cell.tag = holder
        return holder
    }

    private fun bindPoster(
        holder: PosterHolder,
        cover: String,
        title: String,
        meta: String = "",
        rating: String = "",
    ) {
        holder.title.text = title
        holder.meta.text = meta
        holder.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        if (rating.isEmpty()) {
            holder.badge.visibility = View.GONE
        } else {
            holder.badge.text = "★ $rating"
            holder.badge.visibility = View.VISIBLE
        }
        // 复用时要校验 URL，否则快速滚动会把上一张图贴到新卡片上
        holder.img.tag = cover
        holder.img.setImageBitmap(null)
        imageLoader.load(cover, dp(200), dp(300)) { bmp ->
            if (holder.img.tag == cover) holder.img.setImageBitmap(bmp)
        }
    }

    private fun posterGrid(columns: Int): GridView = newGrid().apply {
        numColumns = columns
        horizontalSpacing = dp(12)
        verticalSpacing = dp(6)
        setPadding(0, dp(4), 0, 0)
    }

    /** 把任意 View 裁成圆角（图片必须裁 View 本身，只给背景设圆角会被图盖住） */
    private fun roundClip(v: View, radiusDp: Int = Ui.R_CARD) {
        v.clipToOutline = true
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
    }

    private fun progress(text: String = "加载中…") {
        dismissProgress()
        busyDialog = AlertDialog.Builder(this)
            .setMessage(text)
            // 网络挂住时不能把用户锁死在弹窗里（公共采集站经常超时）
            .setCancelable(true)
            .setOnCancelListener {
                job?.cancel()
                busyDialog = null
                toast("已取消")
            }
            .show()
    }

    private fun dismissProgress() {
        busyDialog?.dismiss()
        busyDialog = null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 电视列表/网格的统一工厂。两者策略不同：
     *
     * · 网格（海报）：让**子项**获得焦点（FOCUS_AFTER_DESCENDANTS），
     *   这样每张海报能画出描边 + 标题变色的焦点样式。
     * · 列表（结果/集数/频道）：让**列表本身**持有焦点，用 AdapterView 的 selector
     *   画选中高亮，配合 onItemClickListener 响应 OK 键。
     *   若让子行抢焦点，会出现「焦点停在列表上、看不出选中哪行」或「ENTER 无反应」。
     *
     * 背景都用深蓝填充 + 亮色描边：浅色文字在上面依然清晰，深色填充也不会瞎眼。
     */
    /**
     * 列表选中高亮用 **状态选择器**：只有列表自己处于聚焦状态时才画描边。
     * 之前靠「失焦时把 selector 换成透明」不生效（AdapterView 会继续沿用旧的 drawable），
     * 导致「焦点在按钮上，集数行还亮着」两个框同时出现。
     */
    private fun listSelector(): android.graphics.drawable.Drawable {
        val ring = InsetDrawable(
            GradientDrawable().apply {
                // 极淡的强调色填充 + 2dp 描边：比纯描边更容易看出「整行被选中」
                setColor(0x1A38BDF8)
                cornerRadius = dp(Ui.R_CARD).toFloat()
                setStroke(dp(2), ACCENT)
            },
            dp(2),
        )
        val empty = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(), empty)
        }
    }

    private fun newList(dividers: Boolean = true): ListView = ListView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        // 行间细分割线：纯文字列表（搜索历史、按钮排序）没有分隔会糊成一片。
        // 卡片式的列表（片源管理、添加片源）每行自带底色和描边，再加线就重复了，传 false。
        if (dividers) {
            divider = android.graphics.drawable.ColorDrawable(Ui.STROKE)
            dividerHeight = dp(1)
        } else {
            divider = null
            dividerHeight = 0
        }
        // 焦点环只靠 ListView 自己的 selector 状态绘制：AbsListView.shouldShowSelector()
        // 本身就要求 hasFocus()，失焦后不会再画，**不需要**在焦点变化时替换 selector。
        // 之前那版在 OnFocusChangeListener 里调 setSelector()，而 AbsListView.setSelector()
        // 会 requestLayout() —— 焦点一进列表就整页重排一次，观感上是列表「抖一下」（实测
        // 列表顶边 284→282→284 闪一帧）。这里保持同一个 selector 实例，只在重绘时切状态。
        val ring = listSelector()
        setSelector(ring)
        setOnFocusChangeListener { v, hasFocus ->
            // 只改 drawable 的 alpha，不触发 requestLayout（setSelector 才会重排）
            ring.alpha = if (hasFocus) 255 else 0
            v.invalidate()
        }
    }

    private fun bindList(listView: ListView, items: List<CharSequence>, onItem: (Int) -> Unit) {
        listView.adapter = simpleAdapter(items, onItem)
        listView.setOnItemClickListener { _, _, position, _ -> onItem(position) }
        // 有些遥控器/模拟器的 OK 键不走 onItemClick，这里显式兜底，保证按下去一定有反应
        listView.setOnKeyListener { v, keyCode, event ->
            val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (event.action == KeyEvent.ACTION_DOWN && isOk) {
                val pos = (v as ListView).selectedItemPosition
                if (pos >= 0) {
                    onItem(pos)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    /**
     * 网格自己持有焦点（和 ListView 一致）。
     * 之前让子项获得焦点，结果方向键走的是系统焦点搜索，而屏幕外的行在 GridView 里
     * 根本还没有相应的 View，焦点找不到下一行 —— 表现为「遥控器无法向下滚动」。
     * 交给 GridView 自己处理，它知道总共有多少项，才能正常换行和滚动。
     */
    private fun newGrid(): GridView = GridView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        setSelector(listSelector())
    }

    /** 统一切到后台线程调用 API，异常在 UI 线程上提示。 */
    private fun <T> runApi(loading: String? = "加载中…", block: () -> T, then: (T) -> Unit) {
        job?.cancel()
        if (loading != null) progress(loading)
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                dismissProgress()
                then(result)
            } catch (e: Exception) {
                dismissProgress()
                val msg = describe(e)
                // 以前失败只弹一个一秒就消失的 toast，网络抖动时用户会以为「点了没反应」
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("请求失败")
                    .setMessage(msg)
                    .setPositiveButton("重试") { _, _ -> runApi(loading, block, then) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpError -> {
            // 服务端的 error 文案比 "HTTP 403" 有用得多（例如防 SSRF 的说明）
            val serverMsg = try {
                org.json.JSONObject(e.body).optString("error", "")
            } catch (_: Exception) {
                ""
            }
            when {
                serverMsg.isNotEmpty() -> "$serverMsg（HTTP ${e.status}）"
                e.status == 401 -> "密码错误"
                e.status == 429 -> "尝试次数过多，请等 10 分钟"
                else -> "请求失败 HTTP ${e.status}"
            }
        }
        else -> "网络失败：${e.message ?: e.javaClass.simpleName}"
    }

    /* --------------------------------------------------------- 启动与服务器配置 */

    /** 启动页：先自动连服务器（同时拉取服务端下发的片源配置），再进首页。 */
    private fun showBoot() {
        currentScreen = "boot"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
        }
        col.addView(brandRow())
        col.addView(TextView(this).apply {
            text = "正在连接服务器…"
            setTextSize(18f)
            setTextColor(Ui.TEXT_DIM)
            setPadding(0, dp(18), 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = api.baseUrl
            setTextSize(13f)
            setTextColor(Ui.TEXT_FAINT)
        })
        // 转圈比一行静态文字更能说明「还在努力」，网络慢时用户不会以为卡死
        col.addView(
            android.widget.ProgressBar(this).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(20) },
        )
        col.addView(
            button("重新连接") { autoConnect() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
            ).apply { topMargin = dp(20) },
        )
        col.addView(button("手动设置服务器") { showSetup() })
        // 垂直水平居中，别让开机画面孤零零挂在左上角
        setScreen(FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        })
        autoConnect()
    }

    private fun autoConnect() {
        runApi("正在连接服务器…", {
            val first = api.status()
            val st = if (first.passwordRequired && !first.verified) {
                api.login(prefs.password)
            } else {
                first
            }
            // 服务端还可能下发 DEFAULT_SUBSCRIPTIONS（订阅链接），需要再解析一次；
            // 解析失败（例如内网地址被防 SSRF 拦掉）不影响其它通道
            val subSources = if (prefs.sources().isEmpty() && st.defaultSources.isEmpty()) {
                st.defaultSubscriptions.flatMap { url ->
                    runCatching { api.importSourceList(url) }.getOrDefault(emptyList())
                }
            } else {
                emptyList()
            }
            st to subSources
        }, { (st, subSources) ->
            seedFromServer(st, subSources)
            showRecommend()
        })
    }

    /**
     * 用服务端下发的配置填充本地：这就是「NAS 上配好了片源，客户端不用再配」的通道。
     * 只在本地为空时填充，不覆盖用户自己加过的源。
     */
    private fun seedFromServer(st: AppStatus, extraSources: List<SourceConfig> = emptyList()) {
        val serverSources = st.defaultSources + extraSources
        if (prefs.sources().isEmpty() && serverSources.isNotEmpty()) {
            prefs.saveSources(serverSources)
            toast("已从服务器同步 ${serverSources.size} 个片源")
        }
        if (prefs.liveUrls().isEmpty() && st.defaultLiveSources.isNotEmpty()) {
            prefs.saveLiveUrls(st.defaultLiveSources)
            prefs.activeLiveUrl = st.defaultLiveSources.first()
        }
    }

    /* --------------------------------------------------------- 源列表的导入 */

    /**
     * 所有导入入口最终都走这里：一份文本 → 片源（含网页版导出格式）→ 直播源。
     * 导入是「按 URL 去重追加」，重复导入不会产生重复项。
     */
    private fun importText(text: String) {
        val sources = api.parseSourceList(text)
        val lives = api.parseLiveSources(text)
        if (sources.isEmpty() && lives.isEmpty()) {
            toast("没解析出片源。支持：网页版导出的 LibreTV-Settings_*.json、SourceList JSON、TVBOX 配置")
            return
        }
        val added = prefs.addSources(sources)
        var liveAdded = 0
        if (lives.isNotEmpty()) {
            val current = prefs.liveSources()
            for (src in lives) {
                val idx = current.indexOfFirst { it.url == src.url }
                when {
                    idx < 0 -> {
                        current.add(src)
                        liveAdded++
                    }
                    current[idx].name.isEmpty() && src.name.isNotEmpty() -> current[idx] = src
                }
            }
            prefs.saveLiveSources(current)
            if (prefs.activeLiveUrl.isEmpty()) prefs.activeLiveUrl = current.first().url
        }
        val liveNote = if (liveAdded > 0) "，直播源新增 $liveAdded" else ""
        toast("片源 ${sources.size} 个（新增 $added）$liveNote")
        showSources()
    }

    /** 用系统文件选择器（SAF）读 JSON：不需要任何存储权限，U 盘/下载目录都能选。 */
    private fun pickJsonFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_JSON)
        } catch (e: Exception) {
            // 电视/投影仪常把 DocumentsUI 精简掉 —— 退回内置文件浏览器
            toast("系统没有文件选择器，改用内置浏览器")
            showFileBrowser()
        }
    }

    /* ------------------------------------------------- 内置文件浏览器（无 SAF 时） */

    private var browsePath = "/storage"

    /** 请求读存储权限（API 23+）。已授权则直接返回 true。 */
    private fun ensureReadPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) return true // 33+ 由 SAF 负责，这里不折腾
        val granted = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }
        return granted
    }

    @Deprecated("权限回调仍走 onRequestPermissionsResult")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showFileBrowser()
            } else {
                toast("没有存储权限，读不到 U 盘文件")
            }
        }
    }

    /**
     * 简易文件浏览器：只列目录与 .json，纯 D-pad 操作。
     * 起点 /storage（U 盘一般挂在 /storage/xxxx-xxxx 或 /mnt/media_rw）。
     */
    /** 判断是不是可导入的文本类文件 */
    private fun isImportableFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".json") || n.endsWith(".txt") ||
            n.endsWith(".m3u") || n.endsWith(".m3u8")
    }

    /**
     * Android 11+ 的分区存储下，直接 listFiles() 常常返回空。
     * 这里依次试几个常见位置，返回第一个「存在且能列出内容」的目录，
     * 让内置浏览器至少有个能用的起点（U 盘 / 下载目录 / 内置存储根）。
     */
    private fun findReadableStorageRoot(): String? {
        val candidates = mutableListOf(
            "/sdcard/Download",
            "/storage/emulated/0/Download",
            "/sdcard",
            "/storage/emulated/0",
            "/mnt/media_rw",
            "/storage",
        )
        runCatching {
            java.io.File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory) candidates.add(f.absolutePath)
            }
        }
        for (p in candidates) {
            val dir = java.io.File(p)
            if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) return p
        }
        return null
    }
    private fun showFileBrowser(path: String = browsePath) {
        currentScreen = "files"
        if (!ensureReadPermission()) return
        val dir = java.io.File(path)
        browsePath = dir.absolutePath

        val entries = ArrayList<Pair<String, java.io.File>>()
        dir.parentFile?.let { entries.add("⬆  上级目录" to it) }
        val children = dir.listFiles()?.sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() }),
        ).orEmpty()
        children.forEach { f ->
            val name = if (f.isDirectory) "📁  " + f.name else "📄  " + f.name
            entries.add(name to f)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), dp(16))
        }
        col.addView(topBar("选择文件", back = { showSources() }))
        col.addView(TextView(this).apply {
            text = browsePath
            setTextSize(13f)
            setTextColor(Ui.TEXT_FAINT)
            setPadding(0, 0, 0, dp(6))
        })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("/sdcard") { showFileBrowser("/sdcard") })
        bar.addView(button("/storage") { showFileBrowser("/storage") })
        bar.addView(button("自动查找") {
            val found = findReadableStorageRoot()
            if (found != null) {
                showFileBrowser(found)
            } else {
                toast("系统限制访问存储，请改用「粘贴文本」或「从网址下载」")
            }
        })
        col.addView(hScroll(bar))
        // 顶部栏是横向滚动容器：左右键若找不到可见邻居，就吞掉按键，避免焦点跑到屏幕外
        for (bi in 0 until bar.childCount) {
            val bv = bar.getChildAt(bi)
            bv.setOnKeyListener { view, code, ev ->
                if (ev.action != android.view.KeyEvent.ACTION_DOWN) {
                    false
                } else if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                    code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    val dir = if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                        View.FOCUS_LEFT
                    } else {
                        View.FOCUS_RIGHT
                    }
                    val next = view.focusSearch(dir)
                    // 没有邻居，或邻居不可见（在滚动区域外）时，消费掉按键
                    // 只允许在按钮栏内部移动：邻居跑到栏外（比如海报格子）就吞掉按键
                    next == null || next.parent !== bar
                } else {
                    false
                }
            }
        }

        if (entries.isEmpty()) {
            col.addView(hintText("这个目录为空或无权访问。试试 /storage 或 /sdcard。"))
        } else {
            val listView = newList()
            bindList(listView, entries.map { it.first }, onItem = { idx ->
                val f = entries[idx].second
                if (f.isDirectory) {
                    showFileBrowser(f.absolutePath)
                } else if (!isImportableFile(f.name)) {
                    toast("只支持 .json / .txt / .m3u 文件")
                } else {
                    runApi("读取文件…", { f.readText() }, { text -> importText(text) })
                }
            })
            col.addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setScreen(col)
    }

    @Deprecated("系统文件选择器仍走 startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_JSON || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runApi("读取文件…", {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }, { text -> importText(text) })
    }

    /* ------------------------------------------------------------- 设置界面 */

    /* ------------------------------------------------------------- 设置界面 */

    /** 设置页入口：已连接时只保留高频操作，连接设置收进「修改服务器设置」 */
    /**
     * 设置页入口。
     * @param expand 明确要求展开「服务器连接」区块（用户点了「修改服务器设置」）。
     * 其余情况（从菜单、返回键回来）自动收起 —— 之前这个标志一旦置真就永不复位，
     * 导致改过一次服务器之后，再进设置永远是展开态、页脚也被挤掉。
     */
    private fun showSetup() {
        currentScreen = "setup"
        val hasServer = prefs.serverUrl.isNotEmpty()
        if (prefs.serverUrl.isNotEmpty()) showSetupCompact() else showServerSettings()
    }

    /** 设置页底部的项目主页：开源后方便别人找到仓库，不抢眼但有存在感 */
    private fun githubFooter(): TextView = TextView(this).apply {
        val latest = prefs.latestTag
        val hasNew = UpdateChecker.isNewer(latest, BuildConfig.VERSION_NAME)
        val devTag = if (BuildConfig.TEST_BUILD) "（测试版）" else ""
        val head = "DpadTV v" + BuildConfig.VERSION_NAME + devTag + if (hasNew) "  •  有新版本 $latest" else ""
        val tail = "\n项目主页  github.com/AresCNZJ/DpadTV\n" +
            "免责声明：本应用仅为聚合客户端，不提供、不存储任何音视频内容，所有内容均来自用户自行配置的第三方接口。"
        val span = SpannableString(head + tail)
        if (hasNew) {
            // 版本号后面加红点 + 提示，用警示色强调
            val start = ("DpadTV v" + BuildConfig.VERSION_NAME).length
            span.setSpan(
                ForegroundColorSpan(0xFFEF4444.toInt()),
                start,
                head.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        setText(span)
        setTextColor(0xFF64748B.toInt())
        setTextSize(13f)
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, dp(18), 0, 0)
    }
    /**
     * 更新检测：每次启动最多检查一次，且 6 小时内不重复请求。
     * 请求只发一次（8 秒超时）；拿不到就放弃——失败也算作"已检查"，不会反复重试，
     * 同时保留已知的新版本标记，不因一次断网就把提示抹掉。
     */
    private fun maybeCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 6 * 3600_000L) return
        UpdateChecker.check { info ->
            prefs.lastUpdateCheck = now
            if (info == null) return@check
            if (UpdateChecker.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                val firstSeen = prefs.latestTag != info.tag
                prefs.latestTag = info.tag
                prefs.latestNotes = info.notes
                prefs.latestUrl = info.url
                if (firstSeen) toast("发现新版本 ${info.tag}，首页右上角可查看")
            } else {
                prefs.latestTag = ""
                prefs.latestNotes = ""
                prefs.latestUrl = ""
            }
        }
    }
    /* --------------------------------------------------------- 更新提示 */

    /** 首页右上角的更新胶囊：有新版本且开关打开时才出现 */
    private fun updateCapsule(): TextView? {
        if (!prefs.updateBannerEnabled) return null
        val tag = prefs.latestTag
        if (!UpdateChecker.isNewer(tag, BuildConfig.VERSION_NAME)) return null
        val unseen = prefs.updateBannerSeen != tag
        return button(if (unseen) "● 新版本 $tag" else "新版本 $tag") {
            prefs.updateBannerSeen = tag
            showUpdateDetail()
        }.apply {
            if (!unseen) setTextColor(0xFF94A3B8.toInt())
        }
    }

    private fun showUpdateDetail() {
        val notes = prefs.latestNotes.ifEmpty { "（这个版本没有提供更新说明）" }
        val url = prefs.latestUrl.ifEmpty { "https://github.com/AresCNZJ/DpadTV/releases" }

        // 左栏：更新说明，内容长时可滚动
        val notesView = TextView(this).apply {
            setText(notes)
            setTextColor(Color.parseColor("#cbd5e1"))
            setTextSize(14f)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, 0, dp(20), 0)
        }
        val notesScroll = android.widget.ScrollView(this).apply {
            addView(notesView)
            layoutParams = LinearLayout.LayoutParams(0, dp(300), 1f)
        }

        // 右栏：白色圆角卡片包住二维码，下面跟一行提示
        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
        }
        qrBitmap(url, dp(168))?.let { bmp ->
            qrCard.addView(ImageView(this).apply { setImageBitmap(bmp) })
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(qrCard, LinearLayout.LayoutParams(dp(196), dp(196)))
            addView(TextView(this@MainActivity).apply {
                setText("扫码下载")
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(13f)
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                setText(url)
                setTextColor(0xFF38BDF8.toInt())
                setTextSize(11f)
                setPadding(0, dp(6), 0, 0)
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(4))
            addView(notesScroll)
            addView(right)
        }

        AlertDialog.Builder(this)
            .setTitle("新版本 " + prefs.latestTag + "（当前 " + BuildConfig.VERSION_NAME + "）")
            .setView(row)
            .setPositiveButton("知道了") { _, _ -> showRecommend() }
            .show()
    }

    /** 把任意文本渲染成二维码位图（复用 ZXing） */
    private fun qrBitmap(text: String, sizePx: Int): android.graphics.Bitmap? = runCatching {
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx)
        android.graphics.Bitmap
            .createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            .also { b ->
                for (x in 0 until sizePx) {
                    for (y in 0 until sizePx) {
                        b.setPixel(
                            x, y,
                            if (matrix.get(x, y)) 0xFF0B101A.toInt() else 0xFFFFFFFF.toInt(),
                        )
                    }
                }
            }
    }.getOrNull()
    /* --------------------------------------------------------- 首页按钮排序 */

    /** 首页顶部按钮排序：上移 / 下移 / 恢复默认，改完立刻写回并重绘首页 */
    private fun showHomeBarSettings() {
        currentScreen = "homebar"
        val order = prefs.homeBarOrder().filter { HomeBar.DEFAULT.contains(it) }.toMutableList()
        for (id in HomeBar.DEFAULT) if (!order.contains(id)) order.add(id)
        val hidden = prefs.homeBarHidden().toMutableSet()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(18))
        }
        col.addView(topBar("首页按钮排序", back = { showSetup() }))
        col.addView(hintText("选中一条按 OK：上移 / 下移 / 隐藏 / 恢复默认"))

        val listView = newList()
        fun refresh() {
            prefs.saveHomeBarOrder(order)
            prefs.saveHomeBarHidden(hidden)
            bindList(listView, order.mapIndexed { i, id ->
                val mark = if (hidden.contains(id)) "   （已隐藏）" else ""
                "${i + 1}.  ${HomeBar.label(id)}$mark"
            }, onItem = { idx ->
                val id = order[idx]
                val isHidden = hidden.contains(id)
                AlertDialog.Builder(this)
                    .setTitle(HomeBar.label(id) + if (isHidden) "（已隐藏）" else "")
                    .setItems(
                        arrayOf("上移", "下移", if (isHidden) "显示" else "隐藏", "恢复默认顺序"),
                    ) { _, which ->
                        when (which) {
                            0 -> if (idx > 0) {
                                val tmp = order[idx]; order[idx] = order[idx - 1]; order[idx - 1] = tmp
                            } else {
                                toast("已经在最前")
                            }
                            1 -> if (idx < order.size - 1) {
                                val tmp = order[idx]; order[idx] = order[idx + 1]; order[idx + 1] = tmp
                            } else {
                                toast("已经在最后")
                            }
                            2 -> if (isHidden) {
                                hidden.remove(id)
                            } else if (order.size - hidden.size <= 1) {
                                toast("至少要保留一个按钮")
                            } else {
                                hidden.add(id)
                            }
                            else -> {
                                order.clear()
                                order.addAll(HomeBar.DEFAULT)
                                hidden.clear()
                            }
                        }
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
        }
        refresh()
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
    }
    /** 播放缓存（磁盘）：显示占用并清空 */
    private fun showClearPlaybackCache() {
        val bytes = PlaybackCache.sizeBytes(this)
        val mb = bytes / 1024.0 / 1024.0
        val text = String.format(java.util.Locale.US, "当前占用 %.1f MB\n上限 2 GB，超出后自动淘汰最久未用的片段。", mb)
        AlertDialog.Builder(this)
            .setTitle("播放缓存")
            .setMessage(text)
            .setPositiveButton("清空") { _, _ ->
                PlaybackCache.clear(this)
                toast("已清空播放缓存")
                showSetup()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /** 片源管理是从哪进来的：设置页或首页，返回时回到原处 */
    private var sourcesOrigin = "home"

    /** 首页顶部按钮栏；用来拦截左右键，避免焦点跑到栏外 */
    private var homeBar: LinearLayout? = null

    /** 首页当前选中的分类页签的 tag，按返回时据此还原焦点 */
    private val TAG_ACTIVE_TAB = "navTabActive"

    /** 正在测试中的源（列表右侧显示「测试中…」） */
    private val testingUrls = mutableSetOf<String>()

    /**
     * 片源列表的 adapter / 控件引用。
     *
     * 「全部测试」只靠 notifyDataSetChanged() 刷新行状态，**不再整页重建**：
     * 重建（setScreen）会丢掉 ListView 的选中位置和焦点，把所有按键都弹回顶部「返回」，
     * 而测试是逐源串行跑的 —— 73 个源就是 73 次重建，用户表现为「焦点死活离不开返回键」。
     */
    private var sourcesAdapter: BaseAdapter? = null
    private var sourcesList: ListView? = null

    /** 顶栏「共 / 启用 / 可用」统计文字。全部测试跑完后原地改文本，避免整页重建 */
    private var sourcesTail: TextView? = null

    /** 重建片源页后要落回去的行号。null = 按默认逻辑聚焦第一个控件（返回键） */
    private var pendingSourceFocus: Int? = null

    /* ------------------------------------------------------------- 清理缓存 */

    private fun showClearCache() {
        val imgs = if (::imageLoader.isInitialized) imageLoader.cacheSize() else 0
        val hist = prefs.history().size
        val search = prefs.searchHistory().size
        val progress = prefs.progressCount()
        val diskMb = PlaybackCache.sizeBytes(this) / 1024.0 / 1024.0
        val diskText = String.format(java.util.Locale.US, "%.1f MB", diskMb)
        val msg = "图片缓存：$imgs 张\n观看历史：$hist 条\n搜索记录：$search 条\n" +
            "播放进度：$progress 条\n播放缓存（磁盘）：$diskText\n\n片源配置、直播源和服务器设置不会被清除。"
        AlertDialog.Builder(this)
            .setTitle("清理缓存")
            .setMessage(msg)
            .setPositiveButton("清理") { _, _ ->
                val n = imageLoader.clearCache()
                prefs.clearHistory()
                prefs.clearSearchHistory()
                prefs.clearAllProgress()
                toast("已清理（图片 $n 张、历史 $hist 条、搜索 $search 条、进度 $progress 条）")
                showSetup()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /* ------------------------------------------------------------- 播放设置 */

    /** 单选弹窗（用全局对话框主题，和片源操作菜单同一套样式） */
    private fun chooseOption(title: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /** 播放设置：每项一行，右侧直接显示当前值，点开即选 */
    private fun showPlaySettings() {
        currentScreen = "playsettings"

        fun decodeLabel(): String = when (prefs.decodeMode) {
            1 -> "强制软解"
            2 -> "强制硬解"
            else -> "自动"
        }

        fun cacheLabel(): String = when (prefs.cacheLimitMb) {
            512 -> "512 MB"
            1024 -> "1 GB"
            4096 -> "4 GB"
            else -> "2 GB"
        }

        val col = screen(padH = 48, padV = 22)
        col.addView(topBar("播放设置", back = { showSetup() }))
        col.addView(hintText("选中一行按 OK 修改，改完立刻生效"))

        val c = card()
        c.addView(settingRow("快进 / 快退步长", "${prefs.seekStepSeconds} 秒") {
            chooseOption(
                "快进 / 快退步长",
                listOf("10 秒", "15 秒", "30 秒"),
                listOf(10, 15, 30).indexOf(prefs.seekStepSeconds).coerceAtLeast(0),
            ) { i ->
                prefs.seekStepSeconds = listOf(10, 15, 30)[i]
                showPlaySettings()
            }
        })
        c.addView(divider())
        c.addView(settingRow("自动连播下一集", if (prefs.autoNext) "开" else "关") {
            chooseOption("自动连播下一集", listOf("开", "关"), if (prefs.autoNext) 0 else 1) { i ->
                prefs.autoNext = (i == 0)
                showPlaySettings()
            }
        })
        c.addView(divider())
        c.addView(settingRow("记住播放进度", if (prefs.resumeEnabled) "开" else "关") {
            chooseOption("记住播放进度", listOf("开", "关"), if (prefs.resumeEnabled) 0 else 1) { i ->
                prefs.resumeEnabled = (i == 0)
                showPlaySettings()
            }
        })
        c.addView(divider())
        c.addView(settingRow("续播起始位置", "超过 ${prefs.resumeThresholdSeconds} 秒") {
            chooseOption(
                "续播起始位置",
                listOf("超过 5 秒", "超过 30 秒", "超过 60 秒"),
                listOf(5, 30, 60).indexOf(prefs.resumeThresholdSeconds).coerceAtLeast(0),
            ) { i ->
                prefs.resumeThresholdSeconds = listOf(5, 30, 60)[i]
                showPlaySettings()
            }
        })
        c.addView(divider())
        c.addView(settingRow("解码方式", decodeLabel()) {
            chooseOption(
                "解码方式（下次播放生效）",
                listOf("自动", "强制软解", "强制硬解"),
                prefs.decodeMode.coerceIn(0, 2),
            ) { i ->
                prefs.decodeMode = i
                showPlaySettings()
            }
        })
        c.addView(divider())
        c.addView(settingRow("播放缓存上限", cacheLabel()) {
            val options = listOf(512, 1024, 2048, 4096)
            chooseOption(
                "播放缓存上限",
                listOf("512 MB", "1 GB", "2 GB", "4 GB"),
                options.indexOf(prefs.cacheLimitMb).coerceAtLeast(2),
            ) { i ->
                prefs.cacheLimitMb = options[i]
                PlaybackCache.recreate(this)
                toast("已设为 ${listOf("512 MB", "1 GB", "2 GB", "4 GB")[i]}，下次播放生效")
                showPlaySettings()
            }
        })
        col.addView(c)

        col.addView(
            button("返回设置") { showSetup() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
            ).apply { topMargin = dp(20) },
        )
        setScreen(ScrollView(this).apply { addView(col) })
    }

    private fun showSetupCompact() {
        val sources = prefs.sources()
        val enabledCount = sources.count { it.enabled }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
        }
        col.addView(brandRow())
        col.addView(TextView(this).apply {
            setText("设置")
            setTextSize(24f)
            setTextColor(TEXT_COLOR)
            setPadding(0, dp(2), 0, 0)
        })
        col.addView(TextView(this).apply {
            setText(prefs.serverUrl)
            setTextSize(13f)
            setTextColor(Ui.TEXT_FAINT)
            setPadding(0, dp(4), 0, dp(2))
        })

        // 设置项按「内容 / 播放 / 界面 / 服务器」分组：
        // 原来 7 个按钮平铺，谁高频谁低频完全看不出来，也没有任何当前值。
        col.addView(sectionLabel("内容"))
        val content = card()
        content.addView(settingRow(
            "片源管理",
            "$enabledCount/${sources.size} 个启用",
        ) { showSources() })
        content.addView(divider())
        content.addView(settingRow("直播源", "${prefs.liveUrls().size} 个") {
            liveChannels = emptyList()
            showLive()
        })
        col.addView(content)

        col.addView(sectionLabel("播放"))
        col.addView(card().apply {
            addView(settingRow("播放设置", playSettingsSummary()) { showPlaySettings() })
        })

        col.addView(sectionLabel("界面"))
        val interfaceCard = card()
        interfaceCard.addView(settingRow("首页按钮排序") { showHomeBarSettings() })
        interfaceCard.addView(divider())
        interfaceCard.addView(settingRow(
            "首页更新提示",
            if (prefs.updateBannerEnabled) "开" else "关",
        ) {
            prefs.updateBannerEnabled = !prefs.updateBannerEnabled
            showSetup()
        })
        col.addView(interfaceCard)

        col.addView(sectionLabel("服务器"))
        val server = card()
        server.addView(settingRow("服务器地址", prefs.serverUrl) { showServerSettings() })
        server.addView(divider())
        server.addView(settingRow("清理缓存", cacheSummary()) { showClearCache() })
        col.addView(server)

        col.addView(
            button("返回首页", primary = true) { showRecommend() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
            ).apply { topMargin = dp(20) },
        )
        col.addView(githubFooter())
        setScreen(ScrollView(this).apply { addView(col) })
    }

    /** 设置页右侧的「当前值」摘要：一行字说清现在是什么状态，省得点进去才知道 */
    private fun playSettingsSummary(): String {
        val decode = when (prefs.decodeMode) {
            1 -> "软解"
            2 -> "硬解"
            else -> "自动解码"
        }
        return (if (prefs.autoNext) "连播" else "单集") + " · " + decode +
            " · " + prefs.seekStepSeconds + " 秒"
    }

    private fun cacheSummary(): String {
        val mb = PlaybackCache.sizeBytes(this) / 1024.0 / 1024.0
        return String.format(java.util.Locale.US, "%.0f MB", mb)
    }

    /** 服务器设置：只负责地址与密码，其他入口都在设置摘要页 */
    private fun showServerSettings() {
        currentScreen = "serversettings"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(22))
        }
        // 首次启动时没有「上一页」可回：直接退出应用，而不是原地重绘同一个页面
        val backFromServer: () -> Unit = {
            if (prefs.serverUrl.isEmpty()) finish() else showSetup()
        }
        col.addView(topBar("服务器设置", back = backFromServer))
        col.addView(hintText("填写 LibreTV 服务端的地址与访问密码"))

        // 输入框的 hint 依赖 BuildConfig.DEFAULT_URL，开源版里它是空的 ——
        // 所以字段名必须显式写在框上方，不能指望占位文字。
        col.addView(sectionLabel("服务器地址"))
        val urlField = field(
            "http://192.168.1.10:8899",
            prefs.serverUrl.ifEmpty { BuildConfig.DEFAULT_URL },
        )
        col.addView(urlField)
        col.addView(sectionLabel("访问密码"))
        val pwdField = field("服务端设置的访问密码", prefs.password, password = true)
        col.addView(pwdField)

        val statusLine = TextView(this).apply {
            setText(if (prefs.serverUrl.isEmpty()) "未连接" else "上次连接：" + prefs.serverUrl)
            setTextColor(Ui.TEXT_FAINT)
            setTextSize(13f)
            setPadding(0, dp(14), 0, dp(6))
        }
        col.addView(statusLine)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("连接并登录", primary = true) {
            val url = urlField.text.toString().trim()
            val pwd = pwdField.text.toString()
            if (url.isEmpty()) {
                toast("请填服务器地址")
                return@button
            }
            api = Api(url)
            api.restoreCookie(prefs.cookie)
            imageLoader = ImageLoader(api)
            runApi("正在连接…", {
                val st = api.status()
                val verified = if (st.passwordRequired && !st.verified) {
                    api.login(pwd).verified
                } else {
                    st.verified
                }
                Triple(st, verified, pwd)
            }, { (st, verified, pw) ->
                prefs.serverUrl = url
                prefs.password = pw
                api.currentCookie()?.let { prefs.cookie = it }
                toast(if (verified) "已连接 v${st.version}" else "已连接，但登录未生效")
                seedFromServer(st)
                showSetup()
            })
        })
        bar.addView(button("返回") {
            // 改了没提交也要存下来，避免白填
            if (urlField.text.toString().trim().isNotEmpty()) {
                prefs.serverUrl = urlField.text.toString().trim()
            }
            if (pwdField.text.toString().isNotEmpty()) {
                prefs.password = pwdField.text.toString()
            }
            showSetup()
        })
        col.addView(hScroll(bar))

        setScreen(ScrollView(this).apply { addView(col) })
        urlField.requestFocus()
    }

    /* ------------------------------------------------------------- 片源管理 */

    /* ------------------------------------------------------------- 片源管理 */

    /** 片源管理：列表为主，导入走独立页面；每条可编辑/停用/上移下移/测试/删除 */
    private fun showSources() {
        // 记录来源：从设置页进来的，返回时回设置；从首页进来的回首页。
        // 导入流程内部会多次回到这里（输入页 → 片源管理），这些情况要沿用原来的来源，
        // 否则「从设置进片源 → 导入 → 返回」会莫名其妙跳回首页。
        sourcesOrigin = when (currentScreen) {
            "setup" -> "setup"
            "sources", "addsource", "input", "manualadd", "files", "qradd" -> sourcesOrigin
            else -> "home"
        }
        currentScreen = "sources"
        val sources = prefs.sources()
        // 重建前先清掉旧引用，避免「全部测试」把状态刷到已经不在屏幕上的 adapter 上
        sourcesList = null
        sourcesAdapter = null
        sourcesTail = null

        val col = screen(padH = 40, padV = 20)
        // 返回目标取决于从哪进来的：设置页进来回设置，首页进来回首页
        val top = topBar(
            "片源管理",
            back = { if (sourcesOrigin == "setup") showSetup() else showRecommend() },
            tail = sourcesTailText(),
        )
        col.addView(top)
        // 统计文字要在「全部测试」结束后原地更新，所以把 tail 那个 TextView 记下来
        sourcesTail = top.getChildAt(top.childCount - 1) as? TextView

        // 只保留两个高频动作（添加 / 全部测试）；「全部启用 / 停用 / 清空」这类批量操作
        // 平时不用、误按代价大，收进「更多」菜单，避免顶部又变回一排等权按钮
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("添加片源", primary = true) { showAddSource() })
        bar.addView(button("全部测试") { runAllSourceTests() })
        bar.addView(button("更多") { sourceBulkActions() })
        col.addView(hScroll(bar))
        col.addView(hintText("OK 打开操作菜单 · 长按直接启用 / 停用"))

        if (sources.isEmpty()) {
            col.addView(emptySourcesCard())
            // 占位把下面的「直播源」推到底部，空状态才不会孤零零浮在屏幕中间
            col.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
            })
        } else {
            // 每行是带底色描边的卡片，不需要分割线
            val listView = newList(dividers = false)
            listView.adapter = object : BaseAdapter() {
                override fun getCount(): Int = sources.size

                override fun getItem(position: Int): Any = sources[position]

                override fun getItemId(position: Int): Long = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    // 外层留 3dp 缝：相邻卡片要能看出边界，焦点框也才有地方画
                    val wrap = (convertView as? LinearLayout)
                        ?: LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                        }
                    wrap.removeAllViews()
                    wrap.addView(sourceRow(sources[position], position))
                    return wrap
                }
            }
            listView.setOnItemClickListener { _, _, position, _ -> sourceActions(position) }
            listView.setOnItemLongClickListener { _, _, position, _ ->
                val list = prefs.sources()
                if (position in list.indices) {
                    list[position] = list[position].copy(enabled = !list[position].enabled)
                    prefs.saveSources(list)
                    toast(
                        if (list[position].enabled) "已启用「${list[position].name}」"
                        else "已停用「${list[position].name}」",
                    )
                    // 重建后把焦点还回这一行，否则焦点会跳到顶部「返回」，
                    // 想连续启用/停用几个源就得每按一次重新往下摸一遍
                    pendingSourceFocus = position
                    showSources()
                }
                true
            }
            col.addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            sourcesList = listView
            sourcesAdapter = listView.adapter as BaseAdapter
        }

        // 直播源是另一套数据（M3U 订阅），原先混在片源列表末尾，容易被当成「第 N 个片源」，
        // 而且片源一多就被挤到屏幕外。独立成一行，位置固定、语义也清楚。
        col.addView(card().apply {
            addView(settingRow("直播源", "${prefs.liveUrls().size} 个订阅") { showLive() })
        })

        val focusRow = pendingSourceFocus
        pendingSourceFocus = null
        // 有指定行时不要走「聚焦第一个控件」的兜底，否则会先落到「返回」再被我们抢回来
        setScreen(col, focusFirst = focusRow == null)
        val lv = sourcesList
        if (focusRow != null && lv != null) {
            lv.post {
                lv.requestFocus()
                if (focusRow in 0 until lv.count) lv.setSelection(focusRow)
            }
        }
    }

    /**
     * 片源列表的一行：序号 + 名称/地址 + 右侧状态。
     *
     * 序号不是装饰 —— 列表顺序就是搜索时逐个请求的顺序，用户上移/下移时需要看得见结果。
     *
     * 状态只说「可用 / 不可用」，不再带条数。`Api.testSource` 返回的第二个值是测试请求
     * 拿到的条目数，它既不是片库大小、也不等于实际能搜到多少（实测：某源搜「我」有 100 条，
     * 测试却返回 0），挂在状态后面只会让人误判。原文案还一度把它当毫秒显示成「可用 · 320ms」。
     */
    private fun sourceRow(s: SourceConfig, index: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = Ui.bg(this@MainActivity, Ui.SURFACE, Ui.R_CARD, 0, 1, Ui.STROKE)
        }
        row.addView(TextView(this).apply {
            text = "%02d".format(index + 1)
            setTextSize(13f)
            setTextColor(Ui.TEXT_FAINT)
            setPadding(0, 0, dp(14), 0)
        })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = s.name.ifEmpty { labelOf(s.url) }
            setTextSize(16f)
            setTextColor(if (s.enabled) TEXT_COLOR else Ui.TEXT_FAINT)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = s.url
            setTextSize(12f)
            setTextColor(Ui.TEXT_FAINT)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val r = prefs.sourceTest(s.url)
        val label = when {
            testingUrls.contains(s.url) -> "测试中…"
            !s.enabled -> "已停用"
            r == null -> "未测试"
            r.first -> "可用"
            else -> "不可用"
        }
        val color = when {
            testingUrls.contains(s.url) -> ACCENT
            !s.enabled -> Ui.TEXT_FAINT
            r == null -> Ui.TEXT_DIM
            r.first -> Ui.OK
            else -> Ui.DANGER
        }
        row.addView(TextView(this).apply {
            text = label
            setTextSize(13f)
            setTextColor(color)
            setPadding(dp(16), 0, 0, 0)
        })
        // 停用的整行压暗：比只在右侧写「已停用」更快扫出来
        if (!s.enabled) row.alpha = 0.55f
        return row
    }

    /** 空状态：与其给一张空白列表，不如把「下一步点哪」直接摆出来 */
    /**
     * 空状态：只解释「片源是什么」。
     *
     * 不再放「从服务器同步 / 手动添加」两个按钮 —— 服务端不一定配了 DEFAULT_SOURCES，
     * 把人往那条路上引经常扑空；真正的入口是顶栏那个「添加片源」，六种方式都在里面。
     */
    private fun emptySourcesCard(): LinearLayout = card().apply {
        setPadding(dp(24), dp(20), dp(24), dp(20))
        addView(TextView(this@MainActivity).apply {
            text = "还没有片源"
            setTextColor(TEXT_COLOR)
            setTextSize(19f)
        })
        addView(TextView(this@MainActivity).apply {
            text = "片源就是提供影片数据的采集站。"
            setTextColor(Ui.TEXT_DIM)
            setTextSize(14f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })
    }

    /** 批量操作：平时用不到，但不给入口就只能一个个点 */
    private fun sourceBulkActions() {
        val onlyAvail = prefs.searchOnlyAvailable
        val actions = arrayOf(
            if (onlyAvail) "搜索仅用可用源（当前：开）" else "搜索仅用可用源（当前：关）",
            "全部启用",
            "全部停用",
            "从服务器配置同步",
            "删除全部片源",
        )
        AlertDialog.Builder(this)
            .setTitle("片源批量操作")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        prefs.searchOnlyAvailable = !onlyAvail
                        toast(
                            if (onlyAvail) "已关闭：搜索时用全部启用的源"
                            else "已开启：搜索时只用「测试可用且有结果」的源",
                        )
                        // 重建以刷新顶部的 共/启用/可用 统计
                        showSources()
                    }
                    1, 2 -> {
                        val list = prefs.sources()
                        if (list.isEmpty()) {
                            toast("还没有片源")
                            return@setItems
                        }
                        val on = which == 1
                        prefs.saveSources(list.map { it.copy(enabled = on) })
                        toast(if (on) "已启用全部 ${list.size} 个" else "已停用全部 ${list.size} 个")
                        showSources()
                    }
                    3 -> importServerConfig()
                    4 -> confirmDeleteAllSources()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「真实可用」的判定：启用中 + 最近一次连通性测试成功。
     *
     * 不再要求测试条数 > 0：那个条数是服务端探一次拿回的样本量，和「这个源能不能出货」
     * 不是一回事（实测某源搜「我」能出 100 条，测试却返回 0）。用条数做闸门会把好源误杀，
     * 也让列表里「可用」的行和顶栏「可用 N」对不上。
     */
    private fun isUsableSource(s: SourceConfig): Boolean {
        if (!s.enabled) return false
        val t = prefs.sourceTest(s.url) ?: return false
        return t.first
    }

    /** 清空全部片源：不可撤销的操作，必须二次确认并说清楚影响范围 */
    private fun confirmDeleteAllSources() {
        val n = prefs.sources().size
        if (n == 0) {
            toast("还没有片源")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除全部 $n 个片源？")
            .setMessage("只清空本机列表，服务器上的配置不受影响，之后可以重新同步。")
            .setPositiveButton("删除") { _, _ ->
                prefs.saveSources(emptyList())
                toast("已清空片源")
                showSources()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 全部测试：逐个探测启用中的源，结果写回 Prefs 供列表展示。
     *
     * 关键约束：**测试过程中绝不能整页重建**。早先每测完一个源就调一次 showSources()，
     * 而 setScreen() 会丢掉ListView 的选中行与焦点、并把焦点落到顶部「返回」，
     * 于是每 1~2 秒焦点就被弹回去一次，用户按方向键等于没按（表现为「焦点死锁在返回键上」）。
     * 现在只 notifyDataSetChanged() 刷行状态，选中行与焦点都留在原地。
     */
    private fun runAllSourceTests() {
        val list = prefs.sources().filter { it.enabled }
        if (list.isEmpty()) {
            toast("没有启用的片源")
            return
        }
        toast("正在测试 ${list.size} 个片源…")
        testingUrls.clear()
        list.forEach { testingUrls.add(it.url) }
        refreshSourceRows()
        scope.launch {
            var failed = 0
            for (s in list) {
                val r = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { api.testSource(s.url) }.getOrNull()
                }
                if (r != null) {
                    prefs.saveSourceTest(s.url, r.first, r.second.toLong())
                    if (!r.first) failed++
                }
                testingUrls.remove(s.url)
                withContext(kotlinx.coroutines.Dispatchers.Main) { refreshSourceRows() }
            }
            testingUrls.clear()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                refreshSourceRows()
                toast(if (failed > 0) "测试完成：$failed 个不可用" else "测试完成：全部可用")
            }
        }
    }

    /**
     * 只刷新片源行的状态文字，不重建页面。
     * AbsListView 在 notifyDataSetChanged() 后会保留选中位置和焦点，这正是我们要的。
     * 顶部统计也一起原地更新 —— 测试结果落库后「可用」数会变。
     */
    private fun refreshSourceRows() {
        if (currentScreen != "sources") return
        sourcesAdapter?.notifyDataSetChanged()
        sourcesTail?.text = sourcesTailText()
    }

    /** 片源页顶栏统计：总数 / 启用数 / 真实可用数（+ 开关状态） */
    private fun sourcesTailText(): String {
        val sources = prefs.sources()
        if (sources.isEmpty()) return "还没有片源"
        val enabled = sources.count { it.enabled }
        val usable = sources.count { isUsableSource(it) }
        return buildString {
            append("共 ${sources.size} · 启用 $enabled · 可用 $usable")
            if (prefs.searchOnlyAvailable) append(" · 搜索仅用可用")
        }
    }

    private fun sourceActions(index: Int) {
        val s = prefs.sources().getOrNull(index) ?: return
        val actions = arrayOf(
            if (s.enabled) "停用（搜索时跳过）" else "启用",
            "编辑名称",
            "编辑地址",
            "上移",
            "下移",
            "测试连通性",
            "删除",
        )
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(actions) { _, which ->
                val cur = prefs.sources()
                if (index !in cur.indices) return@setItems
                when (which) {
                    0 -> {
                        cur[index] = cur[index].copy(enabled = !cur[index].enabled)
                        prefs.saveSources(cur)
                        pendingSourceFocus = index
                        showSources()
                    }
                    1 -> editSourceField(index, name = true)
                    2 -> editSourceField(index, name = false)
                    3 -> swapSources(index, index - 1)
                    4 -> swapSources(index, index + 1)
                    5 -> testSource(cur[index])
                    6 -> {
                        prefs.saveSources(cur.filterIndexed { i, _ -> i != index })
                        toast("已删除")
                        // 删除后焦点落到原位置（删的是最后一个就落到新的最后一行）
                        pendingSourceFocus = index.coerceAtMost(cur.size - 2).coerceAtLeast(0)
                        showSources()
                    }
                }
            }
            .show()
    }

    private fun swapSources(a: Int, b: Int) {
        val list = prefs.sources()
        if (a !in list.indices || b !in list.indices) {
            toast("已经到边了")
            return
        }
        val tmp = list[a]
        list[a] = list[b]
        list[b] = tmp
        prefs.saveSources(list)
        // 焦点跟着这一条走，才能连按上移/下移把源推到目标位置
        pendingSourceFocus = b
        showSources()
    }

    private fun editSourceField(index: Int, name: Boolean) {
        val item = prefs.sources().getOrNull(index) ?: return
        val input = field(
            if (name) "名称" else "https://…/api.php/provide/vod",
            if (name) item.name else item.url,
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(if (name) "片源名称" else "采集站地址")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    val list = prefs.sources()
                    if (index in list.indices) {
                        list[index] =
                            if (name) list[index].copy(name = v) else list[index].copy(url = v)
                        prefs.saveSources(list)
                    }
                    pendingSourceFocus = index
                    showSources()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testSource(s: SourceConfig) {
        runApi("正在测试「${s.name}」…", { api.testSource(s.url) }, { r ->
            toast(
                if (r.first) "「${s.name}」可用"
                else "「${s.name}」不可用（HTTP ${r.second}）",
            )
        })
    }
    /* --------------------------------------------------------------- 添加片源 */

    /* --------------------------------------------------------------- 添加片源 */

    /** 统一样式的输入页：替代 AlertDialog，风格与全站一致（白框 + 按钮） */
    private fun inputPage(
        title: String,
        hint: String,
        value: String = "",
        multiline: Boolean = false,
        backTo: () -> Unit = { showAddSource() },
        onOk: (String) -> Unit,
    ) {
        currentScreen = "input"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(18))
        }
        col.addView(topBar(title, back = backTo))
        val input = field(hint, value)
        if (multiline) {
            input.setSingleLine(false)
            input.minLines = 8
            input.maxLines = 14
            input.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
        }
        col.addView(input)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("确定", primary = true) { onOk(input.text.toString().trim()) })
        col.addView(hScroll(bar))
        setScreen(col)
        input.requestFocus()
    }

    /** 添加片源列表里的一项：标题 + 一句说明 + 可选角标 */
    private class AddOption(
        val title: String,
        val desc: String,
        val badge: String? = null,
        val key: String? = null,
        val run: () -> Unit,
    )

    /** 服务器是否配了默认片源：null=还没问过 / ready / empty / error */
    private var serverSourceState: String? = null

    /** 「从服务器配置同步」那一行的引用：检测回来后原地更新，不重建页面 */
    private var serverSyncRow: View? = null
    private var serverSyncBadge: TextView? = null

    /**
     * 进页面时问一次服务端有没有配默认片源。
     *
     * 多数人不会去 LibreTV 服务端配 DEFAULT_SOURCES，那一项点进去只会拿到「没有片源」。
     * 检测为空就把它置灰并写明「服务器未配置源」，省得白点一次；
     * 有配置才恢复成可点。结果只更新那一行的角标与透明度，不重建页面（否则焦点会被弹回「返回」）。
     */
    private fun detectServerSources() {
        scope.launch {
            val state = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { api.status() }.fold(
                    { st ->
                        if (st.defaultSources.isNotEmpty() || st.defaultSubscriptions.isNotEmpty()) "ready" else "empty"
                    },
                    { "error" },
                )
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                serverSourceState = state
                applyServerSourceState()
            }
        }
    }

    /** 按 serverSourceState 刷新「从服务器配置同步」这一行 */
    private fun applyServerSourceState() {
        val row = serverSyncRow ?: return
        val badge = serverSyncBadge ?: return
        val usable = serverSourceState == "ready"
        badge.text = when (serverSourceState) {
            "ready" -> "最省事"
            "empty" -> "服务器未配置源"
            "error" -> "检测失败"
            else -> "检测中…"
        }
        // 不可用时角标也跟着走灰底，否则亮蓝色标签贴在暗行上会更刺眼
        if (usable) {
            badge.background = Ui.bg(this, Ui.ACCENT, 6)
            badge.setTextColor(INK)
        } else {
            badge.background = Ui.bg(this, Ui.SURFACE_HI, 6, 0, 1, Ui.STROKE)
            badge.setTextColor(Ui.TEXT_FAINT)
        }
        row.alpha = if (usable) 1f else 0.45f
        row.isEnabled = usable
    }

    /** 添加片源：应用内列表（不是弹窗） */
    private fun showAddSource() {
        currentScreen = "addsource"

        // 按「最省事 → 最麻烦」排序，每项一句用途说明。
        // 「从服务器配置同步」提到第一：多数人服务端早就配好了，这一步不用碰电视遥控器打字
        // 扫码放第一位：这是唯一不用在电视上用遥控器敲字、也不用插 U 盘的方式，
        // 手机扫一下就能把订阅文件传上电视。服务端同步退到第二，它只适用于服务端确实
        // 配过 DEFAULT_SOURCES 的人，大部分人第一次进来这里其实是空的。
        val options = listOf(
            AddOption("扫码添加", "手机扫码后在网页里选文件或粘贴", badge = "推荐") { showQrAdd() },
            AddOption("从服务器配置同步", "读取服务端 DEFAULT_SOURCES / 订阅链接", badge = "最省事", key = "server") {
                importServerConfig()
            },
            AddOption("粘贴文本", "从电脑复制 sources.json 内容，粘到这里") {
                inputPage("粘贴 sources.json", "把 sources.json 全文粘到这里", multiline = true) { v ->
                    if (v.isEmpty()) toast("内容为空") else importText(v)
                }
            },
            AddOption("从设备文件选择", "读取 U 盘 / 下载目录里的 .json / .m3u") { pickJsonFile() },
            AddOption("从网址导入", "sources.json、订阅链接或 M3U") {
                inputPage("从网址导入", "https://example.com/sources.json") { u ->
                    if (u.isEmpty()) {
                        toast("请填地址")
                    } else {
                        // 先让服务端代拉并解析：订阅地址（返回源列表 JSON）走这条路最稳，
                        // 电视自己拉 gist / GitHub 经常连不上。拉不动再退回本地下载原文解析。
                        runApi("下载中…", {
                            val parsed = runCatching { api.importSourceList(u) }.getOrDefault(emptyList())
                            parsed to (if (parsed.isEmpty()) api.fetchText(u) else "")
                        }, { (parsed, raw) ->
                            if (parsed.isNotEmpty()) {
                                val added = prefs.addSources(parsed)
                                toast("导入 ${parsed.size} 个片源（新增 $added）")
                                showSources()
                            } else {
                                importText(raw)
                            }
                        })
                    }
                }
            },
            AddOption("手动添加采集站", "只添加一个 CMS 接口地址") { showManualAdd() },
        )

        serverSyncRow = null
        serverSyncBadge = null

        val col = screen(padH = 48, padV = 22)
        col.addView(topBar("添加片源", back = { showSources() }))
        col.addView(hintText("越靠上越省事 · 导入按地址去重追加，重复导入不会产生重复项"))
        // 先问一次服务端有没有配默认片源，结果回来只刷新那一行，不重建页面
        detectServerSources()

        val listView = newList(dividers = false)
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = options.size

            override fun getItem(position: Int): Any = options[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val wrap = (convertView as? LinearLayout)
                    ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                    }
                wrap.removeAllViews()
                val opt = options[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(12), dp(16), dp(12))
                    background = Ui.bg(this@MainActivity, Ui.SURFACE, Ui.R_CARD, 0, 1, Ui.STROKE)
                }
                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val titleRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(TextView(this@MainActivity).apply {
                    text = opt.title
                    setTextColor(TEXT_COLOR)
                    setTextSize(17f)
                })
                opt.badge?.let { badge ->
                    val badgeView = TextView(this@MainActivity).apply {
                        text = badge
                        setTextColor(INK)
                        setTextSize(11f)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                        background = Ui.bg(this@MainActivity, Ui.ACCENT, 6)
                    }
                    titleRow.addView(badgeView, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(10) })
                    if (opt.key == "server") {
                        serverSyncRow = row
                        serverSyncBadge = badgeView
                        applyServerSourceState()
                    }
                }
                texts.addView(titleRow)
                texts.addView(TextView(this@MainActivity).apply {
                    text = opt.desc
                    setTextColor(Ui.TEXT_FAINT)
                    setTextSize(13f)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(texts, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this@MainActivity).apply {
                    text = "›"
                    setTextSize(20f)
                    setTextColor(Ui.TEXT_FAINT)
                    setPadding(dp(12), 0, 0, 0)
                })
                wrap.addView(row)
                return wrap
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val opt = options[position]
            if (opt.key == "server" && serverSourceState != "ready") {
                toast(
                    when (serverSourceState) {
                        null -> "正在检测服务器配置…"
                        "error" -> "连不上服务器，用扫码或手动添加吧"
                        else -> "服务器上没有配置默认片源，用扫码或手动添加吧"
                    },
                )
                return@setOnItemClickListener
            }
            opt.run()
        }
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
    }

    /** 手动添加：两个输入框的应用内页面 */
    private fun showManualAdd() {
        currentScreen = "manualadd"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(18))
        }
        col.addView(topBar("手动添加采集站", back = { showAddSource() }))
        col.addView(hintText("地址形如 https://xxx/api.php/provide/vod（只支持 Apple CMS JSON 接口）"))
        col.addView(sectionLabel("名称"))
        val nameField = field("可留空，自动取域名")
        col.addView(nameField)
        col.addView(sectionLabel("采集站地址"))
        val urlField = field("https://xxx/api.php/provide/vod")
        col.addView(urlField)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("保存", primary = true) {
            val url = urlField.text.toString().trim().trimEnd('/')
            when {
                url.isEmpty() -> {
                    toast("地址不能为空")
                    urlField.requestFocus()
                }
                // 少了协议头服务端会直接 400，用户看到的只是「添加成功但搜不到」，
                // 不如在保存前拦住
                !url.startsWith("http://") && !url.startsWith("https://") -> {
                    toast("地址要以 http:// 或 https:// 开头")
                    urlField.requestFocus()
                }
                prefs.sources().any { it.url.trimEnd('/') == url } -> {
                    toast("这个地址已经在列表里了")
                }
                else -> {
                    val name = nameField.text.toString().trim().ifEmpty {
                        url.substringAfter("//").substringBefore('/')
                    }
                    prefs.addSources(listOf(SourceConfig(name = name, url = url)))
                    toast("已添加「$name」")
                    showSources()
                }
            }
        })
        col.addView(hScroll(bar))
        setScreen(col)
        nameField.requestFocus()
    }


    /* --------------------------------------------------------------- 扫码添加 */

    private var qrServer: QrAddServer? = null

    /** 扫码添加：App 起一个局域网小网页，二维码里就是这个地址，手机扫码后粘贴 sources.json */
    private fun showQrAdd() {
        currentScreen = "qradd"
        qrServer?.stop()
        val server = QrAddServer(this, 8765) { text ->
            val list = api.parseSourceList(text)
            if (list.isEmpty()) throw IllegalArgumentException("no sources")
            prefs.addSources(list)
            val live = api.parseLiveFromExport(text)
            if (live.isNotEmpty()) {
                val cur = prefs.liveUrls()
                live.forEach { if (!cur.contains(it)) cur.add(it) }
                prefs.saveLiveUrls(cur)
                if (prefs.activeLiveUrl.isEmpty()) prefs.activeLiveUrl = cur.first()
            }
            list.size
        }
        qrServer = if (server.start()) server else null
        val url = server.url

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(18))
        }
        // 返回时一定要把局域网服务停掉，否则端口一直被占着，下次进来会启动失败
        col.addView(topBar("扫码添加片源", back = {
            qrServer?.stop()
            qrServer = null
            showSources()
        }))
        col.addView(hintText(
            if (qrServer == null) {
                "无法在 8765 端口启动接收服务（可能被占用）"
            } else {
                "手机连着同一个 WiFi，扫码后把 sources.json 粘贴进网页，点提交即可导入。"
            },
        ))

        if (qrServer != null) {
            val sizePx = dp(240)
            val qr = ImageView(this).apply {
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                roundClip(this, 10)
            }
            val bmp = runCatching {
                val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                    url, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx,
                )
                android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                    for (x in 0 until sizePx) {
                        for (y in 0 until sizePx) {
                            b.setPixel(x, y, if (matrix.get(x, y)) 0xFF04121B.toInt() else 0xFFFFFFFF.toInt())
                        }
                    }
                }
            }.getOrNull()
            if (bmp != null) qr.setImageBitmap(bmp)
            // 二维码居中：原来贴着左边，看着像没排好版
            val qrBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            qrBox.addView(qr, LinearLayout.LayoutParams(sizePx + dp(20), sizePx + dp(20)))
            qrBox.addView(TextView(this).apply {
                setText(url)
                setTextColor(BRIGHTER_TEXT)
                setTextSize(16f)
                setPadding(0, dp(14), 0, 0)
            })
            col.addView(qrBox)
        }

        setScreen(col)
    }
    private fun importServerConfig() {
        runApi("读取服务器配置…", {
            val st = api.status()
            // 订阅里通常同时带片源和直播源，一起取回来
            val pairs = st.defaultSubscriptions.map { u ->
                runCatching { api.importSubscription(u) }
                    .getOrDefault(emptyList<SourceConfig>() to emptyList())
            }
            val subs = pairs.flatMap { it.first }
            // 直播源有两个来源：服务端直接配的 DEFAULT_LIVE_SOURCES + 订阅文件里的 liveSources
            val subLives = pairs.flatMap { it.second }
            val serverLives = st.defaultLiveSources.map { LiveSource("", it) }
            Triple(st, subs, (serverLives + subLives).distinctBy { it.url })
        }, { (st, subs, lives) ->
            val all = st.defaultSources + subs
            val added = if (all.isEmpty()) 0 else prefs.addSources(all)

            var liveAdded = 0
            if (lives.isNotEmpty()) {
                val cur = prefs.liveSources()
                lives.forEach { src ->
                    val idx = cur.indexOfFirst { it.url == src.url }
                    when {
                        idx < 0 -> {
                            cur.add(src)
                            liveAdded++
                        }
                        // 老数据只存了地址：这次拿到了名字就补上，不然界面上永远是一串链接
                        cur[idx].name.isEmpty() && src.name.isNotEmpty() -> cur[idx] = src
                    }
                }
                prefs.saveLiveSources(cur)
                if (prefs.activeLiveUrl.isEmpty()) prefs.activeLiveUrl = cur.firstOrNull()?.url.orEmpty()
            }

            if (all.isEmpty() && lives.isEmpty()) {
                toast("服务器没配 DEFAULT_SOURCES / DEFAULT_SUBSCRIPTIONS / DEFAULT_LIVE_SOURCES")
                return@runApi
            }
            val parts = mutableListOf<String>()
            if (all.isNotEmpty()) parts.add("片源 ${all.size} 个（新增 $added）")
            if (lives.isNotEmpty()) parts.add("直播源 ${lives.size} 个（新增 $liveAdded）")
            toast(parts.joinToString("，"))
            liveChannels = emptyList()
            liveChannelSource = ""
            showSources()
        })
    }

    /** 直播源删除：列出已添加的 M3U 订阅，选一个确认后删掉 */
    private fun removeLiveSourceDialog() {
        val sources = prefs.liveSources()
        if (sources.isEmpty()) {
            toast("还没有直播源")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除直播源")
            .setItems(sources.map { it.label }.toTypedArray()) { _, which ->
                confirmRemoveLiveSource(sources[which].url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRemoveLiveSource(url: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除这个直播源？")
            .setMessage(url)
            .setPositiveButton("删除") { _, _ ->
                val left = prefs.liveUrls().filter { it != url }
                prefs.saveLiveUrls(left)
                if (prefs.activeLiveUrl == url) prefs.activeLiveUrl = left.firstOrNull().orEmpty()
                liveChannels = emptyList()
                liveChannelSource = ""
                toast(if (left.isEmpty()) "已删除，没有直播源了" else "已删除")
                if (left.isEmpty()) addLiveDialog() else showLive()
            }
            .setNegativeButton("取消", null)
            .create()
        // 遥控器上 AlertDialog 的按钮默认没有焦点，不主动 requestFocus 的话按 OK 毫无反应，
        // 必须先按一次方向键才能选中 —— 删除这种确认框尤其容易被当成「删不掉」
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.show()
    }
    /* ------------------------------------------------- 搜索结果（卡片网格） */

    private var resultGroups: List<Pair<String, List<SearchItem>>> = emptyList()

    /**
     * 搜索结果：和首页同一套海报网格。
     * 同一片名收成一张卡片（多来源在详情页「换源」里切）。
     */
    private fun showResults() {
        currentScreen = "results"
        val visible = if (sourceFilter.isEmpty()) {
            lastResults
        } else {
            lastResults.filter { it.sourceName == sourceFilter }
        }
        resultGroups = visible.groupBy { it.name }.map { (title, items) -> title to items }

        val col = screen()
        col.addView(topBar(
            "搜索结果",
            back = { backFromResults() },
            tail = "${resultGroups.size} 部 · ${visible.size} 条",
        ))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(chip(
            if (sourceFilter.isEmpty()) "全部来源" else "来源：$sourceFilter",
            active = sourceFilter.isNotEmpty(),
        ) { pickSourceFilter() })
        bar.addView(button("重新搜索") { showHome() })
        col.addView(hScroll(bar))

        if (resultGroups.isEmpty()) {
            col.addView(hintText("没有匹配的结果，换个片名或增加片源试试"))
            setScreen(col)
            return
        }

        val grid = posterGrid(6).apply { setPadding(0, dp(12), 0, 0) }
        grid.adapter = resultsAdapter()
        grid.setOnItemClickListener { _, _, pos, _ ->
            resultGroups.getOrNull(pos)?.let { openDetail(it.second.first()) }
        }
        col.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        grid.requestFocus()
    }

    /** 来源筛选：多源搜同一部片时，先把结果收窄到一个采集站 */
    private fun pickSourceFilter() {
        val names = listOf("全部来源") + lastResults.map { it.sourceName }.distinct().sorted()
        AlertDialog.Builder(this)
            .setTitle("只看某个采集站的结果")
            .setItems(names.toTypedArray()) { _, which ->
                sourceFilter = if (which == 0) "" else names[which]
                showResults()
            }
            .show()
    }

    private fun resultsAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = resultGroups.size
        override fun getItem(position: Int): Any = resultGroups[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? PosterHolder) ?: buildPoster()
            val group = resultGroups[position]
            val title = group.first
            val items = group.second
            val first = items.first()
            val srcs = items.map { it.sourceName }.distinct()
            // 角标显示「几个来源找得到」，比把来源名塞进标题更省空间
            bindPoster(
                holder,
                first.pic,
                title,
                meta = if (srcs.size > 1) "${srcs.size} 个来源" else srcs.firstOrNull().orEmpty(),
            )
            holder.cell.setOnClickListener { openDetail(first) }
            return holder.cell
        }
    }

    /* --------------------------------------------------------------- 搜索页 */

    /* --------------------------------------------------------------- 热门搜索 */

    /** 搜索页热门搜索：按类型缓存，取首页排行榜前 10 条 */
    private val hotSearchTitles = mutableMapOf<String, List<String>>()

    private fun ensureHotSearch() {
        if (hotSearchTitles.size >= 3) return
        scope.launch {
            val specs = listOf("movie" to "热门", "tv" to "热门", "show" to "综艺")
            for ((type, tag) in specs) {
                if (hotSearchTitles.containsKey(type)) continue
                val items = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { api.douban(type, tag, 0, 10) }.getOrDefault(emptyList())
                }
                hotSearchTitles[type] = items.map { it.title }.filter { it.isNotBlank() }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (currentScreen == "home") showHome()
            }
        }
    }
    /**
     * 搜索页：只负责「输入」和「挑一个最近搜过的」。
     *
     * 原先把搜索结果也塞在这一页（一个列表），于是同一个数据有两种长相 ——
     * 搜索页是列表、点进去又是海报网格，用户不知道自己在哪。
     * 现在搜索页只做输入，结果统一交给 showResults()。
     */
    private fun showHome() {
        currentScreen = "home"
        val col = screen()

        // 顶栏：品牌 + 片源状态胶囊（点一下直接进片源管理）。
        // 原先这里是 logo 下面再加一行巨大的「搜索」标题 —— logo 已经说明这是哪，
        // 标题纯占地方；换成片源状态，顺手把「为什么搜不到」的常见原因摆到眼前
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(brandRow(false), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enabledSources = prefs.sources().count { it.enabled }
        header.addView(chip(
            if (enabledSources == 0) "片源未配置" else "片源 $enabledSources 个",
        ) { showSources() })
        col.addView(header)

        val keywordField = field("输入影片名称…", searchDraft)
        // 热门搜索是异步加载的，加载完会重建这一页；不保留草稿的话，
        // 用户正好在打字时输入会被清空（真机上很难受，也很难复现）
        keywordField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchDraft = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        keywordField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 输入框占满剩余宽度：原先写死 580dp，低分辨率电视上会被搜索按钮挤出去
        searchRow.addView(keywordField, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(
            button("搜索", primary = true) { doSearch(keywordField.text.toString()) },
            // 和输入框同为 52dp：原先按钮 44dp，两个控件顶边错位，看着像没对齐
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52),
            ).apply { leftMargin = dp(10) },
        )
        col.addView(searchRow)

        // 热门搜索：遥控器打字太累，能直接点的就别让人输入。
        // 但原来「电影/剧集/综艺」各占一行标题 + 一行词（共 30 个），一个人一次只看得到
        // 前四五个，剩下全靠横向滚动，等于占掉半个屏幕还没什么用。
        // 现在合并成一行：三类轮流各取 4 个、去重，够点就行。
        ensureHotSearch()
        val hot = listOf("movie", "tv", "show")
            .flatMap { type -> hotSearchTitles[type].orEmpty().take(4) }
            .distinct()
            .take(12)
        if (hot.isNotEmpty()) {
            col.addView(sectionLabel("热门"))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            hot.forEach { title -> row.addView(chip(title) { doSearch(title) }) }
            col.addView(hScroll(row))
        }

        col.addView(sectionLabel("最近搜索"))
        val history = prefs.searchHistory()
        if (history.isEmpty()) {
            col.addView(hintText("还没有搜索记录。输入片名按搜索，或点上面的热门词。"))
        } else {
            // 清空历史是低频且不可撤销的操作，放在列表最后一行，
            // 不跟「搜索」抢主操作位（原先是搜索框旁边的一个按钮）
            val listView = newList()
            bindList(listView, history.map { it } + "清空搜索记录", onItem = { idx ->
                if (idx == history.size) {
                    confirmClearSearchHistory()
                } else {
                    doSearch(history[idx])
                }
            })
            col.addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        keywordField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(keywordField.text.toString())
                true
            } else {
                false
            }
        }

        setScreen(col)
        keywordField.requestFocus()
    }

    /** 清空搜索记录：误按一下就要重新攒，先问一句 */
    private fun confirmClearSearchHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空搜索记录？")
            .setMessage("只会清掉这一页的「最近搜索」，观看记录不受影响。")
            .setPositiveButton("清空") { _, _ ->
                prefs.clearSearchHistory()
                showHome()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 当前这一批搜索结果是「从哪进来的」，决定返回键回到搜索页还是首页 */
    private var resultsFrom = "home"

    private fun doSearch(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) { toast("请输入片名"); return }
        // 在搜索页发起 = 返回时回搜索页；在首页点海报发起 = 返回时回首页
        resultsFrom = if (currentScreen == "home") "home" else "recommend"
        var sources = prefs.sources().filter { it.enabled }
        if (sources.isEmpty()) { toast("没有启用的片源，去「片源管理」开启或添加"); showSources(); return }
        // 「搜索仅用可用源」开关：只搜测试可用且真出过结果的源。
        // 一个可用源都没有时（比如都还没测试过）不硬卡住，退回全部启用源并提示，
        // 否则新装用户把开关一开就再也搜不出任何东西
        if (prefs.searchOnlyAvailable) {
            val usable = sources.filter { isUsableSource(it) }
            if (usable.isNotEmpty()) {
                sources = usable
            } else {
                toast("暂无「可用且有结果」的源，本次仍搜全部 ${sources.size} 个启用源")
            }
        }
        runApi("正在搜索「$kw」（${sources.size} 个源，可能需要几秒）…", {
            val first = api.search(kw, sources)
            // 兜底：豆瓣条目常带「（2023）」「：副标题」这类噪声，采集站匹配不到时再试一次干净片名
            if (first.items.isNotEmpty()) {
                first
            } else {
                val cleaned = cleanKeyword(kw)
                if (cleaned.isNotEmpty() && cleaned != kw) api.search(cleaned, sources) else first
            }
        }, { result ->
            prefs.addSearchHistory(kw)
            lastResults = result.items
            val failedNote = if (result.failures > 0) "（${result.failures} 个源无响应）" else ""
            if (result.items.isEmpty()) {
                // 没搜到就把关键词留在框里，方便改一个字重搜
                toast("没有找到结果$failedNote，换个片名或别的源试试")
                showHome()
            } else {
                searchDraft = ""
                toast("找到 ${result.items.size} 个结果$failedNote")
                showResults()
            }
        })
    }

    /** 采集站的简介常带 <p>/<br>/&nbsp; 这类标记，直接显示会很脏。 */
    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]{0,40}>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 去掉括号注记、年份与标点，得到更可能被采集站命中的片名。 */
    private fun cleanKeyword(keyword: String): String =
        keyword
            .replace(Regex("[（(\\[【][^）)\\]】]*[）)\\]】]"), "")
            .replace(Regex("[·:：!！?？,，.。\\-—_~、|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /* --------------------------------------------------------------- 详情页 */

    /** 详情页状态：detail 还在路上时先画骨架，拿到数据再原地补全 */
    private var detailState = ""            // "" 已就绪 / "loading" / "error"
    private var detailError = ""
    /** 当前详情页对应的搜索结果，用于「换源 / 重试」原地重绘时比对请求是否已经过期 */
    private var openItem: SearchItem? = null

    private fun openDetail(item: SearchItem) {
        openItem = item
        detailError = ""
        detailState = "loading"
        // 先出骨架：点一下立刻有反应。原来是先弹一个「正在获取详情…」的遮罩，
        // 网络慢时要盯着黑屏等一秒多，电视上体感很差。
        showDetail(item, null)

        val source = prefs.sources().firstOrNull { it.url == item.sourceUrl }
            ?: SourceConfig(item.sourceName, item.sourceUrl)
        job?.cancel()
        job = scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { api.detail(item.vodId, source) }
                // 用户可能已经按返回或换了来源（data class 的相等性刚好够用）
                if (currentScreen != "detail" || openItem != item) return@launch
                if (detail.episodes.isEmpty()) {
                    detailState = "error"
                    detailError = "这个来源没有可播放地址"
                    showDetail(item, null)
                } else {
                    detailState = ""
                    showDetail(item, detail)
                }
            } catch (e: Exception) {
                if (currentScreen != "detail" || openItem != item) return@launch
                detailState = "error"
                detailError = describe(e)
                showDetail(item, null)
            }
        }
    }

    private fun showDetail(item: SearchItem, detail: Detail?) {
        currentScreen = "detail"
        val episodes = detail?.episodes.orEmpty()
        val title = detail?.info?.title?.takeIf { it.isNotEmpty() } ?: item.name
        val coverUrl = detail?.info?.cover?.takeIf { it.isNotEmpty() } ?: item.pic

        val col = screen(padH = 48, padV = 18)
        col.addView(topBar(
            "详情",
            back = { backFromDetail() },
            tail = when {
                episodes.isNotEmpty() -> "共 ${episodes.size} 集"
                detailState == "loading" -> "加载中…"
                detailState == "error" -> "加载失败"
                else -> ""
            },
        ))

        // 封面 + 文字信息做成头部：电视上纯文字太干，有封面能一眼确认是不是要找的那部片
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        // 很多电视盒子只有 540dp 高，头部每多占一行，选集网格就少一行。
        // 矮屏幕上简介收成 2 行、封面收窄，优先保证集数可见。
        val compact = resources.displayMetrics.heightPixels / resources.displayMetrics.density < 620f
        // 封面从 140×200 收到 108×154：窄一点才能把纵向空间让给选集网格
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Ui.SURFACE_HI)
            roundClip(this)
        }
        header.addView(
            cover,
            LinearLayout.LayoutParams(dp(108), dp(if (compact) 132 else 154)),
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, 0, 0)
        }
        info.addView(TextView(this).apply {
            text = title
            setTextSize(25f)
            setTextColor(TEXT_COLOR)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // 元信息收成一行：年份 · 地区 · 状态 · 来源。集数在右上角，这里不重复
        info.addView(TextView(this).apply {
            text = listOfNotNull(
                (detail?.info?.year ?: item.year).takeIf { it.isNotEmpty() },
                detail?.info?.area?.takeIf { it.isNotEmpty() },
                (detail?.info?.remarks ?: item.remarks).takeIf { it.isNotEmpty() },
                item.sourceName.takeIf { it.isNotEmpty() },
            ).joinToString("  ·  ").ifEmpty { " " }
            setTextSize(14f)
            setTextColor(Ui.TEXT_DIM)
            setPadding(0, dp(8), 0, 0)
        })
        val actor = detail?.info?.actor.orEmpty()
        val desc = stripHtml(detail?.info?.desc.orEmpty())
        if (actor.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = "主演  $actor"
                setTextSize(13f)
                setTextColor(Ui.TEXT_FAINT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            })
        }
        if (desc.isNotEmpty()) {
            info.addView(TextView(this).apply {
                setText(desc)
                setTextColor(Ui.TEXT_DIM)
                setTextSize(14f)
                setLineSpacing(dp(5).toFloat(), 1f)
                // 简介固定 2–3 行，全文走「简介」按钮的弹窗 —— 展开会顶掉选集网格
                maxLines = if (compact) 2 else 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            })
        }
        header.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(header)

        if (coverUrl.isNotEmpty()) {
            imageLoader.load(coverUrl, dp(240), dp(350)) { bmp -> bmp?.let { cover.setImageBitmap(it) } }
        }

        // 进度：以观看历史的时间戳为准，而不是「集数最大的那一集」——
        // 用户可能回去重看某一集，那一次才是真正的「上次看到哪」。
        // 只认「有进度且没播完」的记录：播完时 progress 会被清零，历史条目还在。
        val lastWatched = prefs.history().firstOrNull {
            episodes.contains(it.playUrl) && prefs.progressOf(it.playUrl) > 0
        }
        val resumeIndex = lastWatched?.let { episodes.indexOf(it.playUrl) } ?: -1
        val watchedCount = episodes.count { prefs.progressOf(it) > 0 }

        /* ---- 操作行：继续观看 / 从头播放 / 简介 / 换源，一行放不下就横向滚动 ---- */
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        var primaryAction: View? = null

        if (episodes.isNotEmpty()) {
            val watchedAt = formatTime(lastWatched?.positionMs ?: 0L)
            val label = when {
                resumeIndex >= 0 && episodes.size > 1 ->
                    "▶   继续观看 · 第 ${resumeIndex + 1} 集 · $watchedAt"
                resumeIndex >= 0 -> "▶   继续观看 · $watchedAt"
                episodes.size == 1 -> "▶   播放"
                else -> "▶   从第 1 集播放"
            }
            val playBtn = button(label, primary = true) {
                playFromList(episodes, resumeIndex.coerceAtLeast(0), title, coverUrl)
            }
            primaryAction = playBtn
            actionRow.addView(playBtn)
            // 有进度时给一个「从头开始」的出口，否则用户只能续播，没法重看
            if (resumeIndex >= 0) {
                actionRow.addView(button("从头播放") {
                    playFromList(episodes, 0, title, coverUrl, fromStart = true)
                })
            }
        }

        if (desc.length > 90) {
            actionRow.addView(button("简介") { showDescDialog(title, desc) })
        }
        if (actionRow.childCount > 0) col.addView(hScroll(actionRow))

        // 换源单独一行：这是这个 App 的核心操作（一个源挂了立刻换下一个），
        // 混在操作行里会被挤到屏幕外，用户根本发现不了。
        val alternatives = lastResults
            .filter { it.name == item.name }
            .distinctBy { it.sourceUrl }
        if (alternatives.size > 1) {
            val sourceRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            sourceRow.addView(TextView(this).apply {
                text = "来源"
                setTextSize(13f)
                setTextColor(Ui.TEXT_FAINT)
                setPadding(0, 0, dp(12), 0)
            })
            alternatives.take(8).forEach { alt ->
                val current = alt.sourceUrl == item.sourceUrl
                sourceRow.addView(chip(
                    (if (current) "● " else "") + alt.sourceName.ifEmpty { "来源" },
                    active = current,
                ) {
                    if (!current) openDetail(alt)
                })
            }
            col.addView(hScroll(sourceRow))
        }

        // 集数还没到（加载中 / 失败）：给状态和重试，不占选集网格的位置
        if (episodes.isEmpty()) {
            col.addView(TextView(this).apply {
                text = when (detailState) {
                    "loading" -> "正在获取选集…"
                    "error" -> "获取失败：$detailError"
                    else -> ""
                }
                setTextSize(15f)
                setTextColor(if (detailState == "error") Ui.DANGER else Ui.TEXT_DIM)
                setPadding(0, dp(18), 0, 0)
            })
            if (detailState == "error") {
                val retry = button("重试", primary = true) { openDetail(item) }
                col.addView(retry, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(10) })
                if (primaryAction == null) primaryAction = retry
            }
            setScreen(col, focusFirst = false)
            col.post {
                // 没有主按钮时退回到第一个可聚焦控件（返回 / 来源），
                // 否则整页没有焦点，遥控器按下去毫无反应
                (primaryAction ?: firstFocusable(col))?.requestFocus()
            }
            return
        }

        col.addView(sectionLabel(buildString {
            append("选集  ·  共 ${episodes.size} 集")
            if (watchedCount > 0) append("  ·  已看 $watchedCount")
        }))

        val epGrid = newGrid().apply {
            numColumns = 8
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
        }
        epGrid.adapter = object : BaseAdapter() {
            override fun getCount() = episodes.size
            override fun getItem(position: Int): Any = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                // 外层留 3dp 边距，选中描边才有地方画（描边画在 item 边界，被不透明背景盖住就看不见了）
                val box = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    addView(TextView(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        setTextSize(15f)
                        setPadding(dp(6), dp(11), dp(6), dp(11))
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                // 只写序号：8 列时「第 12 集」太宽，标题里已经写明这是选集
                val tv = box.getChildAt(0) as TextView
                val watched = prefs.progressOf(episodes[position]) > 0
                tv.text = "${position + 1}"
                tv.setTextColor(if (watched) Ui.TEXT_FAINT else TEXT_COLOR)
                tv.background = Ui.bg(
                    this@MainActivity,
                    if (watched) 0xFF10161F.toInt() else Ui.SURFACE,
                    Ui.R_BTN,
                )
                return box
            }
        }
        epGrid.setOnItemClickListener { _, _, position, _ ->
            playFromList(episodes, position, title, coverUrl)
        }
        col.addView(epGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col, focusFirst = false)
        val target = primaryAction
        col.post {
            // 默认焦点落在「继续观看」上，而不是左上角的返回键 ——
            // 否则一进详情页按 OK 就直接退回去了
            target?.requestFocus()
            if (resumeIndex > 0) epGrid.setSelection(resumeIndex)
        }
    }

    /** 完整简介：详情页只留 3 行，全文放弹窗里，避免挤掉选集网格 */
    private fun showDescDialog(title: String, text: String) {
        val box = ScrollView(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(this@MainActivity).apply {
                setText(text)
                setTextSize(15f)
                setTextColor(Ui.TEXT_DIM)
                setLineSpacing(dp(6).toFloat(), 1f)
            })
        }
        // 只接受 setView(View) / setView(Int) 两个重载；带四个内边距那个重载在新 SDK 里已不可见，
        // 边距统一由上面 ScrollView 自己的 padding 负责
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 详情页的返回目标：有结果就回结果页，直接从首页点进来则回首页 */
    private fun backFromDetail() {
        if (lastResults.isNotEmpty()) showResults() else showRecommend()
    }

    /** 结果页的返回目标：从搜索页搜的回搜索页，从首页海报搜的直接回首页 */
    private fun backFromResults() {
        if (resultsFrom == "home") showHome() else showRecommend()
    }

    /** 从 m3u8 路径里取一个可读标签，例如 .../HD国语版/index.m3u8 → HD国语版 */
    private fun labelOf(url: String): String {
        val parts = url.substringBefore('?').split('/')
        return when {
            parts.size >= 2 -> parts[parts.size - 2]
            else -> ""
        }
    }

    /** 单集播放（直播频道、观看历史都用这个） */
    private fun play(url: String, title: String) = playFromList(listOf(url), 0, title)

    /** 整季播放：把整个集数列表交给播放器，一集放完自动进下一集 */
    private fun playFromList(
        urls: List<String>,
        index: Int,
        title: String,
        cover: String = "",
        fromStart: Boolean = false,
    ) {
        if (urls.isEmpty()) return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
            putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, urls.size - 1))
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_COVER, cover)
            // 「从头播放」要忽略续播记录，否则点它会接着上次的位置放
            putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
        })
    }

    /* ------------------------------------------------------------ 简易适配器 */

    /* --------------------------------------------------------------- 直播页 */

    private fun showLive() {
        currentScreen = "live"
        val sources = prefs.liveSources()
        if (sources.isEmpty() && !liveShowFavorites) {
            addLiveDialog()
            return
        }
        val active = prefs.activeLiveUrl.ifEmpty { sources.firstOrNull()?.url.orEmpty() }
        val activeLabel = sources.firstOrNull { it.url == active }?.label ?: "未命名"

        // 收藏夹：不分直播源，直接铺收藏列表
        if (liveShowFavorites) {
            val favs = favoritesAsChannels()
            renderLivePage(
                shown = favs,
                title = "收藏夹",
                tail = if (favs.isEmpty()) "还没有收藏" else "${favs.size} 个频道 · 不分直播源",
                activeLabel = activeLabel,
            )
            return
        }

        if (liveChannelSource != active || liveChannels.isEmpty()) {
            runApi("正在加载频道…", { api.liveChannels(active) }, { list ->
                liveChannels = list
                liveChannelSource = active
                liveProbeResult.clear()
                if (list.isEmpty()) toast("没解析到频道，换个直播源试试") else showLive()
            })
            return
        }

        // 有的 m3u 没有 group-title（iptv-org 的 streams/cn.m3u 就是），服务端只能全给「其他」，
        // 这种时候按频道名兜底归类，总比 500 行挤在一起强
        val channels = if (hasUsableGroups()) liveChannels else liveChannels.map { c ->
            c.copy(group = inferLiveGroup(c.name))
        }
        var shown = channels
        if (liveGroupFilter.isNotEmpty()) shown = shown.filter { it.group == liveGroupFilter }
        if (liveOnlyAlive) shown = shown.filter { liveProbeResult[it.url] == true }

        val dead = liveProbeResult.count { !it.value }
        val tail = buildString {
            append("${shown.size}/${channels.size} 个频道")
            if (liveProbeResult.isNotEmpty()) append(" · 死链 $dead")
        }
        renderLivePage(shown, "直播", tail, activeLabel)
    }

    /** 直播页统一的渲染：频道列表 + 顶部操作条，两种模式（当前源 / 收藏夹）共用 */
    private fun renderLivePage(shown: List<Channel>, title: String, tail: String, activeLabel: String) {
        liveShown = shown
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), dp(16))
        }
        col.addView(topBar(title, back = { showRecommend() }, tail = tail))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (liveShowFavorites) {
            bar.addView(button("返回频道") { liveShowFavorites = false; showLive() })
        } else {
            bar.addView(button("源：" + activeLabel.take(12)) { pickLiveSource() })
            bar.addView(button("添加直播源") { addLiveDialog() })
            bar.addView(button("删除") { removeLiveSourceDialog() })
            bar.addView(
                button(if (liveGroupFilter.isEmpty()) "分组" else "分组：" + liveGroupFilter.take(10)) {
                    val groups = listOf("全部分组") + liveShown.map { it.group }.distinct().sorted()
                    AlertDialog.Builder(this)
                        .setTitle("按分组筛选")
                        .setItems(groups.toTypedArray()) { _, which ->
                            liveGroupFilter = if (which == 0) "" else groups[which]
                            showLive()
                        }
                        .show()
                },
            )
        }
        bar.addView(button(if (isSelectedFavorite()) "取消收藏" else "收藏") { toggleFavoriteSelected() })
        bar.addView(button(if (liveShowFavorites) "收藏夹中" else "收藏夹") {
            if (!liveShowFavorites) liveShowFavorites = true
            showLive()
        })
        bar.addView(button("测活") { probeLiveChannels(liveShown) })
        if (!liveShowFavorites) {
            bar.addView(button(if (liveOnlyAlive) "只看可用 ✓" else "只看可用") {
                liveOnlyAlive = !liveOnlyAlive
                showLive()
            })
        }
        col.addView(hScroll(bar))

        val sorted = shown.sortedWith(compareBy({ it.group }, { it.name }))
        val listView = newList()
        liveListView = listView
        bindList(listView, sorted.map { liveRowLabel(it) },
            onItem = { idx -> play(sorted[idx].url, sorted[idx].name) })
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        listView.requestFocus()
    }

    /** 行首色点：绿=测活通过，红=连不上，没有点=还没测；收藏的再加一颗星 */
    private fun liveRowLabel(c: Channel): CharSequence {
        val fav = prefs.isFavorite(c.url)
        val color = when (liveProbeResult[c.url]) {
            true -> 0xFF22C55E.toInt()
            false -> 0xFFEF4444.toInt()
            null -> 0
        }
        val text = "${c.group}  ·  ${c.name}"
        if (color == 0 && !fav) return text
        val prefix = StringBuilder()
        if (fav) prefix.append("★ ")
        val dotIndex = if (color != 0) { prefix.append("● "); prefix.length - 2 } else -1
        val sp = SpannableString(prefix.toString() + text)
        if (dotIndex >= 0) {
            sp.setSpan(ForegroundColorSpan(color), dotIndex, dotIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (fav) {
            sp.setSpan(ForegroundColorSpan(0xFFFBBF24.toInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun favoritesAsChannels(): List<Channel> =
        prefs.favorites().map { Channel(it.name, it.url, it.group.ifEmpty { "收藏" }, "") }

    private fun selectedChannel(): Channel? {
        val lv = liveListView ?: return null
        val pos = lv.selectedItemPosition
        val sorted = liveShown.sortedWith(compareBy({ it.group }, { it.name }))
        return sorted.getOrNull(pos)
    }

    private fun isSelectedFavorite(): Boolean = selectedChannel()?.let { prefs.isFavorite(it.url) } ?: false

    private fun toggleFavoriteSelected() {
        val c = selectedChannel()
        if (c == null) {
            toast("先用遥控器选中一个频道")
            return
        }
        val now = prefs.toggleFavorite(FavLive(c.name, c.url, c.group))
        toast(if (now) "已收藏：${c.name}" else "已取消收藏：${c.name}")
        showLive()
    }

    /** 当前源的分组信息是否可用：全都空或全是一个「其他」就说明 m3u 里没带分组 */
    private fun hasUsableGroups(): Boolean =
        liveChannels.map { it.group.trim() }.filter { it.isNotEmpty() && it != "其他" }.distinct().size > 1

    /** 没有分组的源，按频道名粗分类 —— 只是让列表能看，不追求精确 */
    private fun inferLiveGroup(name: String): String = when {
        name.contains("CCTV", true) || name.contains("央视", true) -> "央视"
        name.contains("卫视") -> "卫视"
        name.contains("春晚") -> "春晚"
        name.contains("4K", true) || name.contains("8K", true) -> "4K/8K"
        name.contains("电影") || name.contains("影视") || name.contains("剧场") -> "影视"
        name.contains("少儿") || name.contains("动画") || name.contains("卡通") -> "少儿"
        name.contains("纪录") || name.contains("纪实") -> "纪录"
        name.contains("体育") || name.contains("足球") || name.contains("篮球") -> "体育"
        name.contains("新闻") -> "新闻"
        name.contains("音乐") || name.contains("MV", true) -> "音乐"
        name.contains("地方") || name.contains("都市") -> "地方"
        else -> {
            // 关键词都没命中时：中文名归「其他」，英文名按首字母分桶
            // （iptv-org 的 cn.m3u 全是英文台名，不这么做就是 500 多行挤在一个「其他」里）
            val c = name.firstOrNull()
            if (c != null && c.code < 128 && c.isLetter()) c.uppercaseChar().toString() else "其他"
        }
    }

    /**
     * 给当前列表逐个探活（并发 8 路，单个 4 秒超时）。
     * 一次最多测 80 个：500 多个频道全测要等太久，先把当前分组测明白更实用。
     */
    private fun probeLiveChannels(list: List<Channel>) {
        if (list.isEmpty()) {
            toast("没有可测的频道")
            return
        }
        val target = list.take(80)
        scope.launch {
            progress("正在测活 ${target.size} 个频道…")
            val results = withContext(Dispatchers.IO) {
                val out = mutableListOf<Pair<Channel, Boolean>>()
                for (chunk in target.chunked(8)) {
                    out += chunk.map { ch ->
                        async(Dispatchers.IO) { ch to runCatching { api.probe(ch.url) }.getOrDefault(false) }
                    }.awaitAll()
                }
                out
            }
            dismissProgress()
            results.forEach { (ch, ok) -> liveProbeResult[ch.url] = ok }
            val ok = results.count { it.second }
            toast("测活完成：$ok/${results.size} 个能连上")
            showLive()
        }
    }

    private fun pickLiveSource() {
        val sources = prefs.liveSources()
        if (sources.isEmpty()) {
            addLiveDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择直播源")
            .setItems(sources.map { it.label }.toTypedArray()) { _, which ->
                prefs.activeLiveUrl = sources[which].url
                liveChannels = emptyList()
                liveChannelSource = ""
                liveProbeResult.clear()
                showLive()
            }
            .setPositiveButton("添加新的", { _, _ -> addLiveDialog() })
            .show()
    }

    private fun addLiveDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(hintText("填 M3U 播放列表地址。服务端会去抓这个地址，所以必须是公网可访问的（内网地址会被拒）。"))
        val nameField = field("名称（可留空）")
        box.addView(nameField)
        val urlField = field("https://iptv-org.github.io/iptv/countries/cn.m3u")
        box.addView(urlField)
        AlertDialog.Builder(this)
            .setTitle("添加直播源")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val u = urlField.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                val name = nameField.text.toString().trim()
                val list = prefs.liveSources()
                if (list.none { it.url == u }) list.add(LiveSource(name, u))
                prefs.saveLiveSources(list)
                prefs.activeLiveUrl = u
                liveChannels = emptyList()
                liveChannelSource = ""
                liveProbeResult.clear()
                showLive()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ------------------------------------------------------------ 继续观看 */

    /** 继续观看：卡片形式（封面 + 片名 + 进度） */
    private fun showHistoryCards() {
        // 历史里缺封面的记录：用片名反查一次，拿到海报就写回（最多补 5 条）
        prefs.history().filter { it.cover.isEmpty() }.take(5).forEach { h ->
            scope.launch {
                val items = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        api.search(h.title, prefs.sources().filter { it.enabled }).items
                    }.getOrDefault(emptyList())
                }
                val cover = items.firstOrNull { it.name == h.title }?.pic
                    ?: items.firstOrNull()?.pic.orEmpty()
                if (cover.isNotEmpty()) {
                    prefs.updateHistoryCover(h.playUrl, cover)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (currentScreen == "history") showHistoryCards()
                    }
                }
            }
        }
        currentScreen = "history"
        val items2 = prefs.history()
        val col = screen()
        col.addView(topBar(
            "继续观看",
            back = { showRecommend() },
            tail = "${items2.size} 条",
        ))

        if (items2.isEmpty()) {
            col.addView(hintText("还没有观看记录。播过的片子会出现在这里，方便接着看。"))
            setScreen(col)
            return
        }

        col.addView(sectionLabel("选中即从上次位置继续播放 · 长按删除"))
        val grid = posterGrid(6)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = items2.size
            override fun getItem(position: Int): Any = items2[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder = (convertView?.tag as? PosterHolder) ?: buildPoster()
                val h = items2[position]
                bindPoster(
                    holder,
                    h.cover,
                    h.title,
                    meta = formatTime(h.positionMs) + " / " + formatTime(h.durationMs),
                )
                holder.cell.setOnClickListener { play(h.playUrl, h.title) }
                return holder.cell
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            items2.getOrNull(pos)?.let { play(it.playUrl, it.title) }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            prefs.removeHistory(items2[pos].playUrl)
            toast("已删除该条")
            showHistoryCards()
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(
            button("清空观看历史") {
                prefs.clearHistory()
                toast("已清空")
                showHistoryCards()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
            ).apply { topMargin = dp(10) },
        )

        setScreen(col)
        col.post { col.findFocus() ?: col.requestFocus() }
    }
    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /* ------------------------------------------------------------ 推荐首页 */

    /**
     * 首页的返回层级：卡片 → 顶部按钮栏 → 退出确认。
     *
     * 原来是直接 super.onBackPressed()，焦点停在卡片上时误按返回就把应用杀了；
     * 遥控器的返回键和方向键挨得很近，这个代价太大。所以按 Android TV 的通行做法分两级退。
     */
    private fun backFromRecommend() {
        val bar = homeBar
        val focused = currentFocus
        if (bar != null && (focused == null || !isInBar(focused, bar))) {
            // 焦点还在海报网格（或页面某处）上：先退回顶部按钮，不退出
            val target = bar.findViewWithTag<View>(TAG_ACTIVE_TAB) ?: firstFocusable(bar)
            if (target != null) {
                target.requestFocus()
                return
            }
        }
        confirmExit()
    }

    /** 焦点是否落在顶部按钮栏内。按钮在 HorizontalScrollView 里，只比 parent 不够 */
    private fun isInBar(view: View, bar: ViewGroup): Boolean {
        var p: android.view.ViewParent? = view.parent
        while (p != null) {
            if (p === bar) return true
            p = p.parent
        }
        return false
    }

    /** 退出确认：多一层确认，避免遥控器误触直接结束应用 */
    private fun confirmExit() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("退出 DpadTV？")
            .setMessage("播放进度已经保存，下次打开可以接着看。")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .create()
        // 默认焦点给「取消」：这个弹窗存在的意义就是防误触，
        // 默认停在「退出」上，误触者再按一下 OK 就真退了
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.show()
    }

    private fun showRecommend() {
        currentScreen = "recommend"
        if (recommendItems.isEmpty()) {
            loadRecommend()
            return
        }
        val col = screen()
        val brandBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandBar.addView(
            brandRow(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        updateCapsule()?.let { brandBar.addView(it) }
        col.addView(brandBar)

        // 顶部按「分类」和「工具」分两组：
        // 分类用实心页签表示当前是哪一类，工具用按钮。原来 8 个按钮长得一模一样，
        // 既看不出选中了哪个分类，也分不清主次。
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hiddenIds = prefs.homeBarHidden()
        // 老用户存的排序里没有后来新增的页签（比如动漫）：
        // 按保存的顺序排，缺的默认项补到末尾，保证新页签一定出现
        val order = prefs.homeBarOrder().filter { HomeBar.DEFAULT.contains(it) }.toMutableList()
        for (id in HomeBar.DEFAULT) if (!order.contains(id)) order.add(id)
        var toolsDividerAdded = false
        for (id in order) {
            if (hiddenIds.contains(id)) continue
            val isCategory = id == "movie" || id == "tv" || id == "anime" || id == "show"
            // 分类组和工具组之间加一根竖线，视觉上分成两段
            if (!isCategory && !toolsDividerAdded && bar.childCount > 0) {
                bar.addView(View(this).apply {
                    setBackgroundColor(Ui.STROKE)
                    layoutParams = LinearLayout.LayoutParams(
                        dp(1), dp(20),
                    ).apply {
                        leftMargin = dp(6)
                        rightMargin = dp(14)
                    }
                })
                toolsDividerAdded = true
            }
            when (id) {
                "movie" -> bar.addView(navTab("电影", mediaType == "movie") {
                    switchCategory("movie", "热门")
                })
                "tv" -> bar.addView(navTab("剧集", mediaType == "tv") {
                    switchCategory("tv", "热门")
                })
                "anime" -> bar.addView(navTab("动漫", mediaType == "anime") {
                    switchCategory("anime", "动漫")
                })
                "show" -> bar.addView(navTab("综艺", mediaType == "show") {
                    switchCategory("show", "综艺")
                })
                "search" -> bar.addView(button("搜索") { showHome() })
                "live" -> bar.addView(button("直播") {
                    liveChannels = emptyList()
                    showLive()
                })
                "history" -> bar.addView(button("继续") { showHistoryCards() })
                "sources" -> bar.addView(button("片源") { showSources() })
                "setup" -> bar.addView(button("设置") { showSetup() })
            }
        }
        col.addView(hScroll(bar))
        homeBar = bar

        // 只保留「这一屏是什么」，去掉原来的「第 N 批 / 共 M 条」——那是调试信息
        val sectionName = when {
            mediaType == "show" -> "豆瓣 · 综艺"
            mediaType == "anime" -> "豆瓣 · 动漫"
            mediaType == "tv" -> "豆瓣 · $recommendTag · 剧集"
            else -> "豆瓣 · $recommendTag · 电影"
        }
        col.addView(sectionLabel("$sectionName  ·  ${recommendItems.size} 部"))

        val grid = posterGrid(6).apply { setPadding(0, dp(8), 0, 0) }
        grid.adapter = recommendAdapter()
        if (pendingGridSelection >= 0) {
            grid.setSelection(pendingGridSelection)
            pendingGridSelection = -1
            suppressAutoLoad = true
        }
        grid.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) = Unit
            override fun onScroll(
                view: android.widget.AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                lastVisibleIndex = firstVisibleItem
                if (suppressAutoLoad) {
                    suppressAutoLoad = false
                    return
                }
                // 滚到末尾前两行时自动加载下一批
                if (!isLoadingMore && !noMoreRecommend &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - 2
                ) {
                    loadRecommend(true)
                }
            }
        })
        // 顶部栏在 HorizontalScrollView 内，焦点搜索找不到下方网格，这里直接接管
        for (bi in 0 until bar.childCount) {
            bar.getChildAt(bi).setOnKeyListener { _, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                    ev.action == android.view.KeyEvent.ACTION_DOWN
                ) {
                    grid.requestFocus()
                    true
                } else {
                    false
                }
            }
        }
        // 网格已在第一行时，上方向键回到顶部按钮
        grid.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                ev.action == android.view.KeyEvent.ACTION_DOWN &&
                grid.selectedItemPosition < grid.numColumns
            ) {
                // 用 firstFocusable 而不是 childAt(0)：第一个子项可能是那根分隔线
                firstFocusable(bar)?.requestFocus()
                true
            } else {
                false
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            recommendItems.getOrNull(pos)?.let { doSearch(it.title) }
        }
        col.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        grid.requestFocus()
    }

    /** 切换首页分类：抽出来是因为三个页签的点击逻辑原本各写了一遍 */
    private fun switchCategory(type: String, tag: String) {
        mediaType = type
        recommendTag = tag
        recommendItems = emptyList()
        recommendPage = 0
        noMoreRecommend = false
        isLoadingMore = false
        showRecommend()
    }

    private fun loadRecommend(more: Boolean = false) {
        if (more && (isLoadingMore || noMoreRecommend)) return
        isLoadingMore = true
        val page = if (more) recommendPage + 1 else 0
        runApi(if (more) "加载更多…" else "正在加载推荐…", {
            api.douban(mediaType, recommendTag, page * 48, 48)
        }, { items ->
            isLoadingMore = false
            if (items.isEmpty()) {
                if (more) {
                    noMoreRecommend = true
                    toast("已经到底了")
                } else {
                    toast("没取到推荐，直接用搜索吧")
                    showHome()
                }
            } else {
                recommendItems = if (more) recommendItems + items else items
                recommendPage = page
                // 追加时记住当前滚动位置，重建网格后回到原处，避免"跳回顶部"又触发加载
                if (more) pendingGridSelection = lastVisibleIndex
                showRecommend()
            }
        })
    }

    /** 首页海报：与搜索结果、继续观看共用 buildPoster，三处观感一致 */
    private fun recommendAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = recommendItems.size
        override fun getItem(position: Int): Any = recommendItems[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? PosterHolder) ?: buildPoster()
            val item = recommendItems[position]
            // 评分从标题里挪到封面角标：标题只放片名，扫读时不再被 ★8.7 打断
            bindPoster(holder, item.cover, item.title, rating = item.rating)
            holder.cell.setOnClickListener { doSearch(item.title) }
            return holder.cell
        }
    }

    private fun simpleAdapter(items: List<CharSequence>, onItem: (Int) -> Unit): BaseAdapter =
        object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    setTextSize(17f)
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                tv.text = items[position]
                tv.setOnClickListener { onItem(position) }
                applyFocusStyle(tv, Color.TRANSPARENT, tv)
                return tv
            }
        }
}
