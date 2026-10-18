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
    private var busyDialog: AlertDialog? = null
    /** 当前界面名，用于实现遥控器 BACK 的层级回退（否则任何界面按返回都会直接退出应用）。 */
    private var currentScreen = "setup"
    /** 设置页是否展开「服务器连接」区块。已连接时默认收起（低频操作不占首屏） */
    private var setupShowFields = false
    private val REQ_PICK_JSON = 1001
    private val REQ_STORAGE = 1002

    /* ---------------------------------------------------------- 焦点视觉 */

    // 电视上最致命的问题就是「看不出焦点在哪」。统一用高对比度方案：
    // 聚焦 = 亮蓝填充 + 深色文字 + 4dp 亮色描边 + 轻微放大。
    private val ACCENT = 0xFF38BDF8.toInt()
    private val BRIGHT = 0xFFE0F2FE.toInt()
    private val INK = 0xFF04121B.toInt()
    private val TEXT_COLOR = 0xFFE5E7EB.toInt()
    private val PANEL = 0xFF1F2937.toInt()
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
        focusScale: Float = 1.04f,
    ) {
        val normal = InsetDrawable(
            GradientDrawable().apply {
                setColor(normalBg)
                cornerRadius = dp(8).toFloat()
            },
            dp(3),
        )
        // 焦点态 = 同样的填充 + 亮色描边。描边放进背景而不是 foreground：
        // 实测 Button 上 foreground 不渲染（放大截图里 5 个按钮完全一样），
        // 画进背景则一定能画出来，卡片和按钮也就能共用同一套样式。
        val focused = InsetDrawable(
            GradientDrawable().apply {
                setColor(normalBg)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(5), BRIGHT)
            },
            dp(3),
        )
        val ring = InsetDrawable(
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(4), BRIGHT)
            },
            dp(2),
        )

        // View.setBackground() 会用背景 drawable 的 padding 覆盖控件自身的 padding！
        // InsetDrawable 会报告 3dp 的 padding，于是我们设的 16dp 内边距被无声吃掉，
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
        root.setBackgroundColor(Color.parseColor("#0b101a"))
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
            "results" -> showRecommend()
            "serversettings" -> showSetup()
            "detail", "live", "setup", "history" -> showRecommend()
            "sources" -> if (sourcesOrigin == "setup") showSetup() else showRecommend()
            "home", "recommend" -> super.onBackPressed()
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

    private fun titleText(text: String, size: Float = 26f): TextView =
        TextView(this).apply {
            setText(text)
            setTextSize(size)
            setTextColor(Color.parseColor("#e5e7eb"))
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
            setTextSize(16f)
            isSingleLine = true
            setTextColor(if (primary) INK else TEXT_COLOR)
            // 按内容自适应宽度：固定 dp 宽度在低分辨率电视上会被挤出屏幕
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
            minWidth = dp(76)
            minimumWidth = dp(76)
            // 替换背景后按钮自带的内边距会丢，显式补上，避免文字贴边
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { onClick() }
            applyFocusStyle(
                this,
                if (primary) ACCENT else PANEL,
                this,
                if (primary) INK else TEXT_COLOR,
                if (primary) 0xFF7DD3FC.toInt() else ACCENT,
            )
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
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(5), BRIGHT)
            },
            dp(3),
        )
        val empty = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(), empty)
        }
    }

    private fun newList(): ListView = ListView(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        setSelector(listSelector())
        // AdapterView 会把选中高亮一直留着，焦点移走后就变成「永远是聚焦状态」。
        // 失焦时把 selector 换成透明，重新聚焦再装回来。
        setOnFocusChangeListener { v, hasFocus ->
            (v as ListView).setSelector(
                if (hasFocus) listSelector()
                else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
            )
            v.invalidate()
        }
    }

    private fun bindList(listView: ListView, items: List<String>, onItem: (Int) -> Unit) {
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
            setPadding(dp(56), dp(36), dp(56), dp(36))
        }
        col.addView(titleText("正在连接服务器…", 22f))
        col.addView(hintText(api.baseUrl))
        col.addView(button("手动设置服务器", primary = true) { showSetup() })
        col.addView(button("重新连接") { autoConnect() })
        setScreen(col)
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
        val lives = api.parseLiveFromExport(text)
        if (sources.isEmpty() && lives.isEmpty()) {
            toast("没解析出片源。支持：网页版导出的 LibreTV-Settings_*.json、SourceList JSON、TVBOX 配置")
            return
        }
        val added = prefs.addSources(sources)
        var liveAdded = 0
        if (lives.isNotEmpty()) {
            val current = prefs.liveUrls()
            for (u in lives) {
                if (!current.contains(u)) {
                    current.add(u)
                    liveAdded++
                }
            }
            prefs.saveLiveUrls(current)
            if (prefs.activeLiveUrl.isEmpty()) prefs.activeLiveUrl = current.first()
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
            setPadding(dp(40), dp(24), dp(40), dp(20))
        }
        col.addView(brandRow(false))
        col.addView(titleText("选择 sources.json · 当前：" + browsePath, 15f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回片源管理") { showSources() })
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
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        col.addView(brandRow(false))
        col.addView(titleText("首页按钮排序", 20f))
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

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回设置") { showSetup() })
        col.addView(hScroll(bar))
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

    /** 正在测试中的源（列表右侧显示「…」） */
    private val testingUrls = mutableSetOf<String>()

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
    /** 播放设置：每项点开菜单选择，和全站弹窗风格一致 */
    private fun showPlaySettings() {
        currentScreen = "playsettings"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        col.addView(brandRow(false))
        col.addView(titleText("播放设置", 20f))
        col.addView(hintText("选中一条按 OK，在弹出的菜单里选择"))

        fun rows(): List<String> = listOf(
            "快进 / 快退步长    ${prefs.seekStepSeconds} 秒",
            "自动连播下一集    ${if (prefs.autoNext) "开" else "关"}",
            "记住播放进度      ${if (prefs.resumeEnabled) "开" else "关"}",
            "续播起始位置      超过 ${prefs.resumeThresholdSeconds} 秒",
            "解码方式          " + when (prefs.decodeMode) { 1 -> "强制软解"; 2 -> "强制硬解"; else -> "自动" },
            "播放缓存上限      " + when (prefs.cacheLimitMb) {
                512 -> "512 MB"
                1024 -> "1 GB"
                4096 -> "4 GB"
                else -> "2 GB"
            },
        )

        val listView = newList()
        fun refresh() {
            bindList(listView, rows(), onItem = { idx ->
                when (idx) {
                    0 -> chooseOption("快进 / 快退步长", listOf("10 秒", "15 秒", "30 秒"),
                        listOf(10, 15, 30).indexOf(prefs.seekStepSeconds).coerceAtLeast(0)
                    ) { i -> prefs.seekStepSeconds = listOf(10, 15, 30)[i]; refresh() }
                    1 -> chooseOption("自动连播下一集", listOf("开", "关"),
                        if (prefs.autoNext) 0 else 1
                    ) { i -> prefs.autoNext = (i == 0); refresh() }
                    2 -> chooseOption("记住播放进度", listOf("开", "关"),
                        if (prefs.resumeEnabled) 0 else 1
                    ) { i -> prefs.resumeEnabled = (i == 0); refresh() }
                    3 -> chooseOption("续播起始位置", listOf("超过 5 秒", "超过 30 秒", "超过 60 秒"),
                        listOf(5, 30, 60).indexOf(prefs.resumeThresholdSeconds).coerceAtLeast(0)
                    ) { i -> prefs.resumeThresholdSeconds = listOf(5, 30, 60)[i]; refresh() }
                    4 -> chooseOption("解码方式（下次播放生效）", listOf("自动", "强制软解", "强制硬解"),
                        prefs.decodeMode.coerceIn(0, 2)
                    ) { i -> prefs.decodeMode = i; refresh() }
                    5 -> chooseOption(
                        "播放缓存上限",
                        listOf("512 MB", "1 GB", "2 GB", "4 GB"),
                        listOf(512, 1024, 2048, 4096).indexOf(prefs.cacheLimitMb).coerceAtLeast(2),
                    ) { i ->
                        prefs.cacheLimitMb = listOf(512, 1024, 2048, 4096)[i]
                        PlaybackCache.recreate(this)
                        toast("已设为 ${listOf("512 MB", "1 GB", "2 GB", "4 GB")[i]}，下次播放生效")
                        refresh()
                    }
                }
            })
        }
        refresh()
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回设置") { showSetup() })
        col.addView(hScroll(bar))
        setScreen(col)
    }
    private fun showSetupCompact() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
        }
        col.addView(brandRow())
        col.addView(hintText("服务器与片源  ·  已配置 ${prefs.sources().size} 个片源"))
        col.addView(TextView(this).apply {
            setText("已连接：" + prefs.serverUrl)
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(15f)
            setPadding(0, dp(12), 0, dp(22))
        })
        col.addView(button("片源管理", primary = true) { showSources() })
        col.addView(button("播放设置") { showPlaySettings() })
        col.addView(button("清理缓存") { showClearCache() })
        col.addView(button("首页按钮排序") { showHomeBarSettings() })
        col.addView(button("首页更新提示：" + if (prefs.updateBannerEnabled) "开" else "关") {
            prefs.updateBannerEnabled = !prefs.updateBannerEnabled
            showSetup()
        })
        col.addView(button("修改服务器设置") { showServerSettings() })
        col.addView(button("返回") { showRecommend() })
        col.addView(githubFooter())
        setScreen(ScrollView(this).apply { addView(col) })
    }

    /** 服务器设置：只负责地址与密码，其他入口都在设置摘要页 */
    private fun showServerSettings() {
        currentScreen = "serversettings"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
        }
        col.addView(brandRow(false))
        col.addView(titleText("服务器设置", 20f))
        col.addView(hintText("填写 LibreTV 服务端的地址与访问密码"))

        val urlField = field(BuildConfig.DEFAULT_URL, prefs.serverUrl.ifEmpty { BuildConfig.DEFAULT_URL })
        col.addView(urlField)
        val pwdField = field("访问密码", prefs.password, password = true)
        col.addView(pwdField)

        val statusLine = TextView(this).apply {
            setText(if (prefs.serverUrl.isEmpty()) "未连接" else "上次连接：" + prefs.serverUrl)
            setTextColor(Color.parseColor("#94a3b8"))
            setTextSize(15f)
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
        // 记录来源：从设置页进来的，返回时回设置；从首页进来的回首页
        sourcesOrigin = if (currentScreen == "setup") "setup" else "home"
        currentScreen = "sources"
        val sources = prefs.sources()
        val enabledCount = sources.count { it.enabled }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), dp(16))
        }
        col.addView(brandRow(false))
        col.addView(titleText("片源管理 · ${sources.size} 个（启用 $enabledCount）", 18f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("添加片源", primary = true) { showAddSource() })
        bar.addView(button("全部测试") { runAllSourceTests() })
        bar.addView(button("完成，回首页") { showRecommend() })
        col.addView(hScroll(bar))
        col.addView(hintText("OK 打开操作菜单 · 长按直接启用/停用"))

        val liveCount = prefs.liveUrls().size
        val rows = sources
        val listView = newList()
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size + 1

            override fun getItem(position: Int): Any = position

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(14), dp(8), dp(14))
                }
                row.removeAllViews()
                if (position >= rows.size) {
                    // 末尾一行：直播源入口
                    row.addView(TextView(this@MainActivity).apply {
                        text = "  ▸  直播源 $liveCount 个 · 管理"
                        setTextColor(ACCENT)
                        setTextSize(16f)
                    })
                    return row
                }
                val s = rows[position]
                val dot = View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (s.enabled) ACCENT else Color.parseColor("#64748b"))
                    }
                }
                val name = TextView(this@MainActivity).apply {
                    text = s.name
                    setTextColor(TEXT_COLOR)
                    setTextSize(16f)
                    isSingleLine = true
                    maxWidth = dp(360)
                }
                val domain = TextView(this@MainActivity).apply {
                    text = s.url
                    setTextColor(Color.parseColor("#64748b"))
                    setTextSize(13f)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(dp(20), 0, dp(12), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val status = TextView(this@MainActivity).apply {
                    val r = prefs.sourceTest(s.url)
                    text = when {
                        testingUrls.contains(s.url) -> "…"
                        !s.enabled -> "已停用"
                        r == null -> "未测试"
                        r.first -> "可用 · ${r.second}ms"
                        else -> "上次失败"
                    }
                    setTextColor(
                        when {
                            !s.enabled -> Color.parseColor("#64748b")
                            r == null -> Color.parseColor("#94a3b8")
                            r.first -> Color.parseColor("#4ade80")
                            else -> Color.parseColor("#f87171")
                        },
                    )
                    setTextSize(13f)
                }
                row.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(14) })
                row.addView(name)
                row.addView(domain)
                row.addView(status)
                return row
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position >= rows.size) showLive() else sourceActions(position)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val list = prefs.sources()
            if (position in list.indices) {
                list[position] = list[position].copy(enabled = !list[position].enabled)
                prefs.saveSources(list)
                toast(if (list[position].enabled) "已启用「${list[position].name}」" else "已停用「${list[position].name}」")
                showSources()
            }
            true
        }
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setScreen(col)
    }

    /** 全部测试：并发探测启用中的源，结果写回 Prefs 供列表展示 */
    private fun runAllSourceTests() {
        val list = prefs.sources().filter { it.enabled }
        if (list.isEmpty()) {
            toast("没有启用的片源")
            return
        }
        toast("正在测试 ${list.size} 个片源…")
        testingUrls.clear()
        list.forEach { testingUrls.add(it.url) }
        showSources()
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
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (currentScreen == "sources") showSources()
                }
            }
            testingUrls.clear()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (currentScreen == "sources") showSources()
                toast(if (failed > 0) "测试完成：$failed 个不可用" else "测试完成：全部可用")
            }
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
                    showSources()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testSource(s: SourceConfig) {
        runApi("正在测试「${s.name}」…", { api.testSource(s.url) }, { r ->
            toast(
                if (r.first) "「${s.name}」可用，返回 ${r.second} 个结果"
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
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        col.addView(brandRow(false))
        col.addView(titleText(title, 20f))
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
        bar.addView(button("返回") { backTo() })
        col.addView(hScroll(bar))
        setScreen(col)
        input.requestFocus()
    }

    /** 添加片源：应用内列表（不是弹窗） */
    private fun showAddSource() {
        currentScreen = "addsource"

        // 按「最省事 → 最麻烦」排序，每项给一句用途说明
        val options = listOf(
            Triple("扫码添加", "手机扫码后在网页里选文件或粘贴", { showQrAdd() }),
            Triple("粘贴文本", "从电脑复制 sources.json 内容，粘到这里", {
                inputPage("粘贴 sources.json", "把 sources.json 全文粘到这里", multiline = true) { v ->
                    if (v.isEmpty()) toast("内容为空") else importText(v)
                }
            }),
            Triple("从设备文件选择", "读取 U 盘 / 下载目录里的 .json / .m3u", { pickJsonFile() }),
            Triple("手动添加采集站", "只添加一个 CMS 接口地址", { showManualAdd() }),
            Triple("从网址导入", "填 sources.json 或订阅链接", {
                inputPage("从网址导入", "https://example.com/sources.json") { u ->
                    if (u.isEmpty()) {
                        toast("请填地址")
                    } else {
                        runApi("下载中…", { api.fetchText(u) }, { text -> importText(text) })
                    }
                }
            }),
            Triple("服务器配置导入", "从 LibreTV 的 DEFAULT_SOURCES 拉取", { importServerConfig() }),
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        col.addView(brandRow(false))
        col.addView(titleText("添加片源", 20f))
        col.addView(hintText("选一种方式，越靠上越省事"))

        val listView = newList()
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = options.size

            override fun getItem(position: Int): Any = options[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val box = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                }
                box.removeAllViews()
                val (title, desc, _) = options[position]
                box.addView(TextView(this@MainActivity).apply {
                    text = if (position == 0) "$title   推荐" else title
                    setTextColor(if (position == 0) ACCENT else TEXT_COLOR)
                    setTextSize(17f)
                })
                box.addView(TextView(this@MainActivity).apply {
                    text = desc
                    setTextColor(Color.parseColor("#64748b"))
                    setTextSize(13f)
                    setPadding(0, dp(3), 0, 0)
                })
                return box
            }
        }
        listView.setOnItemClickListener { _, _, position, _ -> options[position].third() }
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回片源管理") { showSources() })
        col.addView(hScroll(bar))
        setScreen(col)
    }

    /** 手动添加：两个输入框的应用内页面 */
    private fun showManualAdd() {
        currentScreen = "manualadd"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        col.addView(brandRow(false))
        col.addView(titleText("手动添加采集站", 20f))
        col.addView(hintText("地址形如 https://xxx/api.php/provide/vod（只支持 Apple CMS JSON 接口）"))
        val nameField = field("名称（可留空，自动取域名）")
        val urlField = field("https://xxx/api.php/provide/vod")
        col.addView(nameField)
        col.addView(urlField)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("保存", primary = true) {
            val url = urlField.text.toString().trim()
            if (url.isEmpty()) {
                toast("地址不能为空")
            } else {
                val name = nameField.text.toString().trim().ifEmpty {
                    url.substringAfter("//").substringBefore('/')
                }
                val added = prefs.addSources(listOf(SourceConfig(name = name, url = url)))
                toast(if (added > 0) "已添加「$name」" else "该地址已存在")
                showSources()
            }
        })
        bar.addView(button("返回") { showAddSource() })
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
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        col.addView(brandRow(false))
        col.addView(titleText("扫码添加片源", 20f))
        col.addView(hintText(
            if (qrServer == null) {
                "无法在 8765 端口启动接收服务（可能被占用）"
            } else {
                "手机连着同一个 WiFi，扫码后把 sources.json 粘贴进网页，点提交即可导入。"
            },
        ))

        if (qrServer != null) {
            val sizePx = dp(260)
            val qr = ImageView(this).apply {
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
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
            col.addView(qr, LinearLayout.LayoutParams(sizePx + dp(20), sizePx + dp(20)))
            col.addView(TextView(this).apply {
                setText(url)
                setTextColor(BRIGHTER_TEXT)
                setTextSize(16f)
                setPadding(0, dp(14), 0, 0)
            })
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("完成，回片源管理") { qrServer?.stop(); qrServer = null; showSources() })
        bar.addView(button("取消") { qrServer?.stop(); qrServer = null; showSources() })
        col.addView(hScroll(bar))

        setScreen(col)
    }
    private fun sourceJsonMenu() {
        AlertDialog.Builder(this)
            .setTitle("sources.json 从哪里来")
            .setItems(
                arrayOf("粘贴 JSON 文本", "从设备文件选择（U 盘 / 下载目录）", "从网址下载（可用内网地址）"),
            ) { _, which ->
                when (which) {
                    0 -> pasteJsonDialog()
                    1 -> pickJsonFile()
                    2 -> urlImportDialog()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun dialogWithInput(title: String, hint: String, value: String, multiline: Boolean, onOk: (String) -> Unit) {
        val input = field(hint, value)
        if (multiline) {
            input.setSingleLine(false)
            input.minLines = 6
            input.maxLines = 10
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pasteJsonDialog() {
        dialogWithInput("粘贴 sources.json", "把 sources.json 全文粘到这里", "", true) { v ->
            if (v.isEmpty()) toast("内容为空") else importText(v)
        }
    }

    private fun urlImportDialog() {
        dialogWithInput("从网址导入", "http://<你的NAS>:5000/sources.json", "", false) { u ->
            if (u.isEmpty()) {
                toast("请填地址")
            } else {
                runApi("下载中…", { api.fetchText(u) }, { text -> importText(text) })
            }
        }
    }

    private fun subscriptionDialog() {
        dialogWithInput("从订阅地址导入", "https://…/sources.json（必须公网）", "", false) { u ->
            if (u.isEmpty()) {
                toast("请填地址")
            } else {
                runApi("导入中…", { api.importSourceList(u) }, { list ->
                    if (list.isEmpty()) {
                        toast("没有导入到点播源（订阅里可能只有直播源）")
                    } else {
                        val added = prefs.addSources(list)
                        toast("订阅共 ${list.size} 个，新增 $added")
                        showSources()
                    }
                })
            }
        }
    }

    private fun importServerConfig() {
        runApi("读取服务器配置…", {
            val st = api.status()
            val subs = st.defaultSubscriptions.flatMap { u ->
                runCatching { api.importSourceList(u) }.getOrDefault(emptyList())
            }
            st to subs
        }, { (st, subs) ->
            val all = st.defaultSources + subs
            if (all.isEmpty()) {
                toast("服务器没配 DEFAULT_SOURCES / DEFAULT_SUBSCRIPTIONS")
            } else {
                val added = prefs.addSources(all)
                toast("服务器共 ${all.size} 个，新增 $added")
                showSources()
            }
        })
    }
    private fun showImport() {
        currentScreen = "import"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), dp(32))
        }
        col.addView(titleText("片源管理"))
        col.addView(hintText("订阅地址：一份 LibreTV-SourceList JSON 的公开链接（也可以填 TVBOX 配置地址）。"))

        val subField = field("https://…/sources.json")
        col.addView(subField)
        col.addView(button("从文件导入（U 盘 / 下载目录）") { pickJsonFile() })
        col.addView(button("浏览设备文件（无文件选择器时用）") { showFileBrowser() })

        // App 直连，不受服务端防 SSRF 限制 —— 源列表放 NAS 共享或任何内网静态服务都能直接拉
        val lanField = field("http://<你的NAS>:5000/sources.json")
        col.addView(lanField)
        col.addView(button("从该地址导入（App 直连）") {
            val u = lanField.text.toString().trim()
            if (u.isEmpty()) { toast("请填地址"); return@button }
            runApi("下载中…", { api.fetchText(u) }, { text -> importText(text) })
        })

        col.addView(button("从服务器配置导入", primary = true) {
            runApi("读取服务器配置…", {
                val st = api.status()
                val subs = st.defaultSubscriptions.flatMap { u ->
                    runCatching { api.importSourceList(u) }.getOrDefault(emptyList())
                }
                st to subs
            }, { (st, subs) ->
                val all = st.defaultSources + subs
                if (all.isEmpty()) {
                    toast("服务器没配 DEFAULT_SOURCES / DEFAULT_SUBSCRIPTIONS（网页版的片源存在浏览器本地存储里，客户端读不到）")
                } else {
                    val added = prefs.addSources(all)
                    if (prefs.liveUrls().isEmpty() && st.defaultLiveSources.isNotEmpty()) {
                        prefs.saveLiveUrls(st.defaultLiveSources)
                        prefs.activeLiveUrl = st.defaultLiveSources.first()
                    }
                    toast("服务器共 ${all.size} 个片源，新增 $added")
                    showSources()
                }
            })
        })
        col.addView(button("从订阅导入（必须公网地址）") {
            val sub = subField.text.toString().trim()
            if (sub.isEmpty()) { toast("请填订阅地址"); return@button }
            runApi("导入中…", { api.importSourceList(sub) }, { list ->
                if (list.isEmpty()) {
                    toast("没有导入到点播源（订阅里可能只有直播源或被跳过）")
                } else {
                    val added = prefs.addSources(list)
                    toast("导入 ${list.size} 个，新增 $added")
                    showSources()
                }
            })
        })
        col.addView(button("手动添加采集站") { addSourceDialog() })

        // 订阅地址会被服务端做防 SSRF 校验，内网地址一律 403；局域网里的 sources.json
        // 只能靠下面这条「本地解析」路径（不过服务器，直接在这里解析）。
        val jsonField = field("或粘贴 sources.json 全文（本地解析，不进服务器）")
        jsonField.setSingleLine(false)
        jsonField.minLines = 2
        jsonField.maxLines = 4
        col.addView(jsonField)
        col.addView(button("从文本导入（本地解析）") {
            val text = jsonField.text.toString().trim()
            if (text.isEmpty()) { toast("请先粘贴 JSON 内容"); return@button }
            importText(text)
        })
        col.addView(button("删除全部片源") {
            prefs.saveSources(emptyList())
            toast("已清空")
            showSources()
        })
        col.addView(button("返回片源管理") { showSources() })

        val listView = newList()
        val sources = prefs.sources()
        bindList(listView, 
            sources.map { s -> "${s.name}\n${s.url}" },
            onItem = { idx ->
                AlertDialog.Builder(this)
                    .setTitle(sources[idx].name)
                    .setItems(arrayOf("删除该片源")) { _, _ ->
                        val left = prefs.sources().filterIndexed { i, _ -> i != idx }
                        prefs.saveSources(left)
                        showSources()
                    }
                    .show()
            },
        )
        // 控件已经多到会超出 1080p 屏幕，所以控件区和片源列表各占一半、各自可滚动
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(
            ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.6f),
        )
        wrapper.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.4f),
        )
        setScreen(wrapper)
    }

    private fun addSourceDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val nameField = field("名称（可留空）")
        val urlField = field("https://xxx/api.php/provide/vod")
        box.addView(nameField)
        box.addView(urlField)
        AlertDialog.Builder(this)
            .setTitle("添加采集站")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val url = urlField.text.toString().trim()
                if (url.isEmpty()) { toast("地址不能为空"); return@setPositiveButton }
                val added = prefs.addSources(listOf(SourceConfig(
                    name = nameField.text.toString().trim().ifEmpty {
                        url.substringAfter("//").substringBefore('/')
                    },
                    url = url,
                )))
                toast(if (added > 0) "已添加" else "该地址已存在")
                showSources()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ------------------------------------------------- 搜索结果（卡片网格） */

    private var resultGroups: List<Pair<String, List<SearchItem>>> = emptyList()

    /**
     * 搜索结果按网页版的样子展示：封面 + 标题 + 类型/年份/备注 + 来源数。
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

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), dp(16))
        }
        col.addView(brandRow(false))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(titleText("${resultGroups.size} 部 · ${visible.size} 条结果", 18f))
        bar.addView(button("来源筛选") {
            val names = listOf("全部来源") + lastResults.map { it.sourceName }.distinct().sorted()
            AlertDialog.Builder(this)
                .setTitle("只看某个采集站的结果")
                .setItems(names.toTypedArray()) { _, which ->
                    sourceFilter = if (which == 0) "" else names[which]
                    showResults()
                }
                .show()
        })
        bar.addView(button("返回推荐") { showRecommend() })
        bar.addView(button("重新搜索") { showHome() })
        col.addView(hScroll(bar))

        if (sourceFilter.isNotEmpty()) {
            col.addView(hintText("当前只看：$sourceFilter（$(${resultGroups.size}) 部）"))
        }

        val grid = newGrid().apply {
            numColumns = 3
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            setPadding(0, dp(8), 0, 0)
        }
        grid.adapter = resultsAdapter()
        grid.setOnItemClickListener { _, _, pos, _ ->
            resultGroups.getOrNull(pos)?.let { openDetail(it.second.first()) }
        }
        col.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        grid.requestFocus()
    }

    private fun resultsAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = resultGroups.size
        override fun getItem(position: Int): Any = resultGroups[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(
                    ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.parseColor("#1a2233"))
                    },
                    LinearLayout.LayoutParams(dp(86), dp(120)),
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        addView(TextView(this@MainActivity).apply {
                            setTextSize(15f)
                            setTextColor(TEXT_COLOR)
                            maxLines = 2
                        })
                        addView(TextView(this@MainActivity).apply {
                            setTextSize(12f)
                            setTextColor(0xFF94A3B8.toInt())
                            maxLines = 2
                            setPadding(0, dp(4), 0, 0)
                        })
                        addView(TextView(this@MainActivity).apply {
                            setTextSize(11f)
                            setTextColor(0xFF64748B.toInt())
                            maxLines = 2
                            setPadding(0, dp(4), 0, 0)
                        })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }

            val (title, items) = resultGroups[position]
            val first = items.first()
            val poster = cell.getChildAt(0) as ImageView
            val info = cell.getChildAt(1) as LinearLayout
            (info.getChildAt(0) as TextView).text = title
            (info.getChildAt(1) as TextView).text = listOfNotNull(
                first.typeName.takeIf { it.isNotEmpty() },
                first.year.takeIf { it.isNotEmpty() },
                first.remarks.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            val srcs = items.map { it.sourceName }.distinct()
            (info.getChildAt(2) as TextView).text =
                if (srcs.size > 1) "${srcs.size} 个来源 · ${srcs.joinToString(" / ")}"
                else srcs.firstOrNull().orEmpty()

            poster.tag = first.pic
            poster.setImageBitmap(null)
            imageLoader.load(first.pic, dp(120), dp(170)) { bmp ->
                if (poster.tag == first.pic) poster.setImageBitmap(bmp)
            }
            cell.setOnClickListener { openDetail(first) }
            return cell
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
    private fun showHome() {
        currentScreen = "home"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(28))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(brandRow(false))
        val keywordField = field("输入影片名称…")
        keywordField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        header.addView(keywordField, LinearLayout.LayoutParams(dp(580), dp(48)))
        header.addView(button("搜索", primary = true) { doSearch(keywordField.text.toString()) })
        header.addView(button("清空历史") { prefs.clearSearchHistory(); showHome() })
        col.addView(hScroll(header))

        // 热门搜索：电影 / 剧集 / 综艺 各取排行榜前 10，点一下直接搜
        ensureHotSearch()
        for ((type, label) in listOf(
            "movie" to "热门电影",
            "tv" to "热门剧集",
            "show" to "热门综艺",
        )) {
            val titles = hotSearchTitles[type].orEmpty()
            if (titles.isEmpty()) continue
            col.addView(hintText(label))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            titles.forEach { title -> row.addView(button(title) { doSearch(title) }) }
            col.addView(hScroll(row))
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(
            TextView(this).apply {
                setText(
                    "片源 ${prefs.sources().size} 个 · 服务器 ${api.baseUrl}" +
                        if (sourceFilter.isEmpty()) "" else " · 只看：$sourceFilter",
                )
                setTextColor(Color.parseColor("#64748b"))
                setTextSize(13f)
                setPadding(0, dp(6), dp(12), dp(8))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (lastResults.isNotEmpty()) {
            statusRow.addView(
                button(if (sourceFilter.isEmpty()) "来源筛选" else "来源：" + sourceFilter.take(8)) {
                    val names = listOf("全部来源") + lastResults.map { it.sourceName }.distinct().sorted()
                    AlertDialog.Builder(this)
                        .setTitle("只看某个采集站的结果")
                        .setItems(names.toTypedArray()) { _, which ->
                            sourceFilter = if (which == 0) "" else names[which]
                            showHome()
                        }
                        .show()
                },
            )
        }
        col.addView(statusRow)

        // 没有结果时把「搜索历史」放在这里：电视遥控器打字太难，
        // 重搜一部之前看过的片子应该是一次点击的事。
        val history = prefs.searchHistory()
        val rows: List<String>
        val onRow: (Int) -> Unit
        if (lastResults.isEmpty()) {
            if (history.isEmpty()) {
                rows = listOf("（还没有搜索记录，输入片名开始）")
                onRow = { }
            } else {
                rows = history.map { "最近搜索 · $it" }
                onRow = { idx ->
                    if (idx == history.size) {
                        prefs.clearSearchHistory()
                        showHome()
                    } else {
                        doSearch(history[idx])
                    }
                }
            }
        } else {
            // 同一部片在多个采集站都有结果，平铺会刷出一堆重复行（「流浪地球」7 条里 5 条是同一部片）。
            // 按片名收成一行，来源并列显示；点进去后详情页的「换源」可以切换。
            val visible = if (sourceFilter.isEmpty()) {
                lastResults
            } else {
                lastResults.filter { it.sourceName == sourceFilter }
            }
            val groups = visible.groupBy { it.name }
            val titles = groups.keys.toList()
            rows = titles.map { title ->
                val group = groups.getValue(title)
                val srcs = group.map { it.sourceName }.distinct()
                val remark = group.firstOrNull { it.remarks.isNotEmpty() }?.remarks.orEmpty()
                buildString {
                    append(title)
                    append("    ")
                    append(srcs.joinToString(" / "))
                    if (remark.isNotEmpty()) {
                        append("  ·  ")
                        append(remark)
                    }
                }
            }
            onRow = { idx -> openDetail(groups.getValue(titles[idx]).first()) }
        }

        val listView = newList()
        bindList(listView, rows, onItem = onRow)
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        keywordField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(keywordField.text.toString())
                true
            } else false
        }

        setScreen(col)
        if (lastResults.isEmpty()) keywordField.requestFocus() else listView.requestFocus()
    }

    private fun doSearch(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) { toast("请输入片名"); return }
        val sources = prefs.sources().filter { it.enabled }
        if (sources.isEmpty()) { toast("没有启用的片源，去「片源管理」开启或添加"); showSources(); return }
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
                toast("没有找到结果$failedNote，换个片名或别的源试试")
                showHome()
            } else {
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

    private fun openDetail(item: SearchItem) {
        val source = prefs.sources().firstOrNull { it.url == item.sourceUrl }
            ?: SourceConfig(item.sourceName, item.sourceUrl)
        runApi("正在获取详情…", { api.detail(item.vodId, source) }, { detail ->
            if (detail.episodes.isEmpty()) {
                toast("这个源没有可播放地址")
                return@runApi
            }
            showDetail(item, detail)
        })
    }

    private fun showDetail(item: SearchItem, detail: Detail) {
        currentScreen = "detail"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(28), dp(56), dp(28))
        }
        // 封面 + 文字信息做成头部：电视上纯文字太干，有封面能一眼确认是不是要找的那部片
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1a2233"))
        }
        header.addView(cover, LinearLayout.LayoutParams(dp(150), dp(220)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        info.addView(titleText(detail.info.title.ifEmpty { item.name }, 24f))
        info.addView(hintText(listOfNotNull(
            detail.info.year.takeIf { it.isNotEmpty() },
            detail.info.area.takeIf { it.isNotEmpty() },
            detail.info.remarks.takeIf { it.isNotEmpty() },
            item.sourceName,
            "共 ${detail.episodes.size} 集",
        ).joinToString(" · ")))
        if (detail.info.desc.isNotEmpty()) {
            info.addView(TextView(this).apply {
                setText(stripHtml(detail.info.desc))
                setTextColor(Color.parseColor("#94a3b8"))
                setTextSize(14f)
                maxLines = 5
            })
        }
        header.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(header)

        val coverUrl = detail.info.cover.ifEmpty { item.pic }
        if (coverUrl.isNotEmpty()) {
            imageLoader.load(coverUrl, dp(300), dp(440)) { bmp -> bmp?.let { cover.setImageBitmap(it) } }
        }

        col.addView(hintText("选择集数（共 ${detail.episodes.size} 集）："))

        // 换源：同一个片名在其他采集站的结果，一键切换（按来源名去重，避免同一站出现两次）
        val alternatives = lastResults
            .filter { it.name == item.name }
            .distinctBy { it.sourceUrl }
        if (alternatives.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(hintText("来源："), LinearLayout.LayoutParams(dp(70), dp(44)))
            alternatives.take(6).forEach { alt ->
                row.addView(
                    button((if (alt.sourceUrl == item.sourceUrl) "● " else "") + alt.sourceName.ifEmpty { "来源" }) { if (alt.sourceUrl != item.sourceUrl) openDetail(alt) },
                )
            }
            col.addView(hScroll(row))
        }

        val epGrid = newGrid().apply {
            numColumns = 8
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
        }
        epGrid.adapter = object : BaseAdapter() {
            override fun getCount() = detail.episodes.size
            override fun getItem(position: Int): Any = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER
                    setTextSize(14f)
                    setTextColor(TEXT_COLOR)
                    setPadding(dp(6), dp(14), dp(6), dp(14))
                }
                // CMS 的集数标题常常带一段哈希（第1集$4ad62e），这里只保留序号
                tv.text = "第 ${position + 1} 集"
                return tv
            }
        }
        epGrid.setOnItemClickListener { _, _, position, _ ->
            playFromList(
                detail.episodes,
                position,
                detail.info.title.ifEmpty { item.name },
                detail.info.cover.ifEmpty { item.pic },
            )
        }
        col.addView(epGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
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
    private fun playFromList(urls: List<String>, index: Int, title: String, cover: String = "") {
        if (urls.isEmpty()) return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
            putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, urls.size - 1))
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_COVER, cover)
        })
    }

    /* ------------------------------------------------------------ 简易适配器 */

    /* --------------------------------------------------------------- 直播页 */

    private fun showLive() {
        currentScreen = "live"
        val urls = prefs.liveUrls()
        if (urls.isEmpty()) {
            addLiveDialog()
            return
        }
        val active = prefs.activeLiveUrl.ifEmpty { urls.first() }
        if (liveChannelSource != active || liveChannels.isEmpty()) {
            runApi("正在加载频道…", { api.liveChannels(active) }, { list ->
                liveChannels = list
                liveChannelSource = active
                if (list.isEmpty()) toast("没解析到频道，换个直播源试试") else showLive()
            })
            return
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        val shown = if (liveGroupFilter.isEmpty()) {
            liveChannels
        } else {
            liveChannels.filter { it.group == liveGroupFilter }
        }
        col.addView(titleText("直播  ·  ${shown.size}/${liveChannels.size} 个频道", 20f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("换源") { pickLiveSource() })
        bar.addView(button("添加直播源") { addLiveDialog() })
        bar.addView(
            button(if (liveGroupFilter.isEmpty()) "分组" else "分组：" + liveGroupFilter.take(10)) {
                val groups = listOf("全部分组") + liveChannels.map { it.group }.distinct().sorted()
                AlertDialog.Builder(this)
                    .setTitle("按分组筛选")
                    .setItems(groups.toTypedArray()) { _, which ->
                        liveGroupFilter = if (which == 0) "" else groups[which]
                        showLive()
                    }
                    .show()
            },
        )
        bar.addView(button("返回") { showRecommend() })
        col.addView(hScroll(bar))

        val sorted = shown.sortedWith(compareBy({ it.group }, { it.name }))
        val listView = newList()
        bindList(listView, 
            sorted.map { "${it.group}  ·  ${it.name}" },
            onItem = { idx -> play(sorted[idx].url, sorted[idx].name) },
        )
        col.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setScreen(col)
        listView.requestFocus()
    }

    private fun pickLiveSource() {
        val urls = prefs.liveUrls()
        if (urls.isEmpty()) {
            addLiveDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择直播源")
            .setItems(urls.toTypedArray()) { _, which ->
                prefs.activeLiveUrl = urls[which]
                liveChannels = emptyList()
                liveChannelSource = ""
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
        val urlField = field("https://iptv-org.github.io/iptv/countries/cn.m3u")
        box.addView(urlField)
        AlertDialog.Builder(this)
            .setTitle("添加直播源")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val u = urlField.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                val list = prefs.liveUrls()
                if (!list.contains(u)) list.add(u)
                prefs.saveLiveUrls(list)
                prefs.activeLiveUrl = u
                liveChannels = emptyList()
                liveChannelSource = ""
                showLive()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ------------------------------------------------------------ 继续观看 */

    private fun showHistory() {
        currentScreen = "history"
        val items = prefs.history()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
        col.addView(titleText("继续观看  ·  ${items.size} 条", 20f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("返回") { showRecommend() })
        bar.addView(
            button("清空历史") {
                prefs.clearHistory()
                toast("已清空观看历史")
                showHistory()
            },
        )
        col.addView(hScroll(bar))

        if (items.isEmpty()) {
            col.addView(hintText("还没有观看记录。播过的片子会出现在这里，方便接着看。"))
        } else {
            col.addView(hintText("选中即从上次位置继续播放；长按（或按菜单键）可删除单条。"))
            val listView = newList()
            bindList(listView, 
                items.map { "${it.title}    ${formatTime(it.positionMs)} / ${formatTime(it.durationMs)}" },
                onItem = { idx ->
                    val h = items[idx]
                    play(h.playUrl, h.title)
                },
            )
            listView.setOnItemLongClickListener { _, _, pos, _ ->
                prefs.removeHistory(items[pos].playUrl)
                toast("已删除该条")
                showHistory()
                true
            }
            col.addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setScreen(col)
    }

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
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(20), dp(40), dp(16))
        }
        col.addView(brandRow(false))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(titleText("继续观看 · ${items2.size} 条", 18f))
        bar.addView(button("返回推荐") { showRecommend() })
        if (items2.isNotEmpty()) {
            bar.addView(button("清空历史") { prefs.clearHistory(); toast("已清空"); showHistoryCards() })
        }
        col.addView(hScroll(bar))

        if (items2.isEmpty()) {
            col.addView(hintText("还没有观看记录。播过的片子会出现在这里，方便接着看。"))
        } else {
            col.addView(hintText("选中即从上次位置继续播放"))
            val grid = newGrid().apply {
                numColumns = 3
                horizontalSpacing = dp(12)
                verticalSpacing = dp(12)
            }
            grid.adapter = object : BaseAdapter() {
                override fun getCount() = items2.size
                override fun getItem(position: Int): Any = items2[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(
                            ImageView(this@MainActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setBackgroundColor(Color.parseColor("#1a2233"))
                            },
                            LinearLayout.LayoutParams(dp(86), dp(120)),
                        )
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(12), 0, 0, 0)
                                addView(TextView(this@MainActivity).apply {
                                    setTextSize(15f); setTextColor(TEXT_COLOR); maxLines = 2
                                })
                                addView(TextView(this@MainActivity).apply {
                                    setTextSize(12f); setTextColor(0xFF94A3B8.toInt())
                                    maxLines = 1; setPadding(0, dp(4), 0, 0)
                                })
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                    val h = items2[position]
                    val poster = cell.getChildAt(0) as ImageView
                    val info = cell.getChildAt(1) as LinearLayout
                    (info.getChildAt(0) as TextView).text = h.title
                    (info.getChildAt(1) as TextView).text =
                        formatTime(h.positionMs) + " / " + formatTime(h.durationMs)
                    poster.tag = h.cover
                    poster.setImageBitmap(null)
                    imageLoader.load(h.cover, dp(120), dp(170)) { bmp ->
                        if (poster.tag == h.cover) poster.setImageBitmap(bmp)
                    }
                    cell.setOnClickListener { play(h.playUrl, h.title) }
                    return cell
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
        }
        setScreen(col)
        col.post { col.findFocus() ?: col.requestFocus() }
    }
    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /* ------------------------------------------------------------ 推荐首页 */

    private fun showRecommend() {
        currentScreen = "recommend"
        if (recommendItems.isEmpty()) {
            loadRecommend()
            return
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(20))
        }
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
        col.addView(titleText(
            (when {
                recommendTag == "综艺" -> "豆瓣 · 综艺"
                mediaType == "tv" -> "豆瓣 · " + recommendTag + " · 剧集"
                else -> "豆瓣 · " + recommendTag + " · 电影"
            }) +
                "  ·  第 ${recommendPage + 1} 批，共 ${recommendItems.size} 条",
            17f,
        ))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hiddenIds = prefs.homeBarHidden()
        for (id in prefs.homeBarOrder()) {
            if (hiddenIds.contains(id)) continue
            when (id) {
                "movie" -> bar.addView(button("电影") { mediaType = "movie"; recommendTag = "热门"; recommendItems = emptyList(); recommendPage = 0; noMoreRecommend = false; isLoadingMore = false; showRecommend() })
                "tv" -> bar.addView(button("剧集") { mediaType = "tv"; recommendTag = "热门"; recommendItems = emptyList(); recommendPage = 0; noMoreRecommend = false; isLoadingMore = false; showRecommend() })
                "show" -> bar.addView(button("综艺") { mediaType = "show"; recommendTag = "综艺"; recommendItems = emptyList(); recommendPage = 0; noMoreRecommend = false; isLoadingMore = false; showRecommend() })
                "search" -> bar.addView(button("搜索") { showHome() })
                "live" -> bar.addView(button("直播") { liveChannels = emptyList(); showLive() })
                "history" -> bar.addView(button("继续") { showHistoryCards() })
                "sources" -> bar.addView(button("片源") { showSources() })
                "setup" -> bar.addView(button("设置") { showSetup() })
            }
        }
        col.addView(hScroll(bar))
        homeBar = bar

        val grid = newGrid().apply {
            numColumns = 6
            horizontalSpacing = dp(14)
            verticalSpacing = dp(14)
            setPadding(0, dp(12), 0, 0)
        }
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
                    if (bar.childCount > 0) bar.getChildAt(0).requestFocus()
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

    /** 海报网格：ImageView 复用时要校验 URL，否则快速滚动会串图。 */
    private fun recommendAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = recommendItems.size
        override fun getItem(position: Int): Any = recommendItems[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(
                    ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(210))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.parseColor("#1a2233"))
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        setTextSize(13f)
                        setTextColor(Color.parseColor("#e5e7eb"))
                        maxLines = 2
                        setPadding(0, dp(6), 0, 0)
                    },
                )
            }
            val item = recommendItems[position]
            val img = cell.getChildAt(0) as ImageView
            val label = cell.getChildAt(1) as TextView
            label.text = if (item.rating.isEmpty()) item.title else "${item.title}  ★${item.rating}"
            img.tag = item.cover
            img.setImageBitmap(null)
            imageLoader.load(item.cover, dp(180), dp(260)) { bmp ->
                if (img.tag == item.cover) img.setImageBitmap(bmp)
            }
            cell.setOnClickListener { doSearch(item.title) }
            return cell
        }
    }

    private fun simpleAdapter(items: List<String>, onItem: (Int) -> Unit): BaseAdapter =
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
