package fake.screenshot.wrappers

import android.content.Context
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat.getString
import fake.screenshot.Auxiliary
import fake.screenshot.R
import fake.screenshot.services.ControlOverlayService
import fake.screenshot.services.DisplayOverlayService
import fake.screenshot.services.privileged.overlay.RootOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object OverlayServiceManager {
    private val _isDisplayRunning = MutableStateFlow(false)
    private val _isControlRunning = MutableStateFlow(false)
    private val _mediaList = MutableStateFlow<List<Uri>>(emptyList())
    val isDisplayRunning: StateFlow<Boolean> = _isDisplayRunning.asStateFlow()
    val isControlRunning: StateFlow<Boolean> = _isControlRunning.asStateFlow()
    val mediaList: StateFlow<List<Uri>> = _mediaList.asStateFlow()

    // ==================== 通知渠道/通知 ID 随机化（隐蔽性） ====================

    /**
     * 渠道 ID / 通知 ID 随机值持久化（明文 prefs，与 ConfigManager.data_ref
     * 同文件同策略）。
     *
     * 为什么不入加密 DataStore：
     * 1. 胁迫销毁会清空 DataStore——渠道 ID 丢失后下次启动会以新 ID 创建
     *    新渠道，旧渠道永久残留系统设置（系统从不自动删除），渠道数随每次
     *    销毁 +1，恰好构成"销毁过"侧信道（data_ref 明文随机化要消除的
     *    正是同类侧信道）。明文存储下 ID 跨销毁稳定，渠道数恒定。
     * 2. 明文键名/值均为无语义随机串，取证读到也只是随机值，"从未销毁"
     *    与"销毁后"不可区分。
     * 3. SharedPreferences 同步轻量，服务 onCreate 主线程直接读取无
     *    DataStore 加密首读（磁盘 IO + Keystore 解密）的阻塞/ANR 风险
     *    （本类 configScope 文档明确禁止主线程 runBlocking 配置 IO）。
     *
     * 键名刻意中性（s_a..s_d）：明文 prefs 的键名本身是取证可见信息，
     * 不得出现 "overlay"/"channel" 等语义。语义映射见下方调用处。
     * 配合各服务 onDestroy 的 deleteNotificationChannel：静止态零渠道残留。
     */
    private const val OVERLAY_PREFS_NAME = "sync_preferences"
    private const val KEY_CHANNEL_ID_DISPLAY = "s_a"
    private const val KEY_CHANNEL_ID_CONTROL = "s_b"
    private const val KEY_NOTIF_ID_DISPLAY = "s_c"
    private const val KEY_NOTIF_ID_CONTROL = "s_d"

    /** Display 悬浮窗通知渠道 ID：首读缺失就地产出随机值落盘（幂等、跨销毁稳定）。 */
    fun displayChannelId(context: Context): String =
        randomizedString(context, KEY_CHANNEL_ID_DISPLAY, 20..30)

    /** Control 悬浮窗通知渠道 ID（ID 长度与 Display 错开，风格无关联）。 */
    fun controlChannelId(context: Context): String =
        randomizedString(context, KEY_CHANNEL_ID_CONTROL, 31..36)

    /**
     * Display 悬浮窗前台通知 ID：随机区间 1000..4999，与 Control 的
     * 5000..9999 互斥——两个 FGS 通知共存时随机取值也不会互相覆盖。
     */
    fun displayNotificationId(context: Context): Int =
        randomizedInt(context, KEY_NOTIF_ID_DISPLAY, 1000..4999)

    /** Control 悬浮窗前台通知 ID（随机区间与 Display 互斥，见上）。 */
    fun controlNotificationId(context: Context): Int =
        randomizedInt(context, KEY_NOTIF_ID_CONTROL, 5000..9999)

    private fun randomizedString(context: Context, key: String, lengthRange: IntRange): String {
        val prefs = context.applicationContext
            .getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { return it }
        val random = Auxiliary.getRandomString(Auxiliary.getSecureRandomInt(lengthRange))
        // commit（同步落盘）：渠道创建前确保持久化，进程死亡不产生第二个值
        prefs.edit().putString(key, random).commit()
        return random
    }

    private fun randomizedInt(context: Context, key: String, range: IntRange): Int {
        val prefs = context.applicationContext
            .getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getInt(key, -1)
        if (existing != -1) return existing
        val random = Auxiliary.getSecureRandomInt(range)
        prefs.edit().putInt(key, random).commit()
        return random
    }

    // ==================== 路由：ROOT 无痕路线优先，普通路线兜底 ====================

    /** true = ROOT 无痕悬浮窗路线（uid=0 可信触摸层）；false = 普通悬浮窗路线。 */
    @Volatile
    private var rootRoute = false

    /** ROOT 路线下 root 端窗口已挂载（连接成功后一次性，防 Listener 重入）。 */
    @Volatile
    private var rootAttached = false

    /** ROOT 路线下控制窗口是否应挂载（对齐普通路线 isControlRunning 语义）。 */
    @Volatile
    private var rootControlDesired = false

    @Volatile
    private var rootMediaIndex = 0

    @Volatile
    private var appContext: Context? = null

    // ==================== 参数代理：为两种悬浮窗统一提供外观/音频参数 ====================

    /**
     * 配置读写专用作用域：DataStore（加密）首读含磁盘 IO 与 Tink 密钥
     * 解析，写入同理——绝不在主线程 runBlocking（旧实现卡顿/ANR 风险）。
     */
    private val configScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * start 后用户是否已显式调整过外观参数：异步读取晚到时不得覆盖
     * 用户的显式选择（竞态窗口内 UI 可能已下发 setDisplayAlpha/setMuted）。
     */
    @Volatile
    private var paramsTouched = false

    @Volatile
    private var displayAlphaValue = 1.0f

    @Volatile
    private var videoMutedValue = false

    /** 后端连接通知：成功挂 root 窗口并显示首媒体；失败/死亡回落普通路线。 */
    private val rootListener = RootOverlayService.Listener { active ->
        if (!rootRoute) return@Listener
        if (active) {
            if (!rootAttached) onRootConnected()
        } else {
            fallbackToNormalRoute()
        }
    }

    /** root 端手势判定"切换媒体"（主线程回调）：推进索引并投递目标媒体。 */
    private val mediaSwitchHandler = RootOverlayService.MediaSwitchHandler { delta ->
        if (!rootRoute) return@MediaSwitchHandler
        val newIndex = rootMediaIndex + delta
        if (newIndex in _mediaList.value.indices) {
            rootMediaIndex = newIndex
            showRootMedia()
        }
    }

    fun start(context: Context) {
        if (_isDisplayRunning.value) return
        val ctx = context.applicationContext
        appContext = ctx
        paramsTouched = false
        stealthToastShown = false
        // 回落过渡守卫：root 失败回落普通路线的窗口内（运行标志复位 →
        // 普通服务 onCreate 之间），不加守卫的用户开关会重走 root 路线
        // ——回落本身刚因 root 失败触发，立即重试只会再弹一次 root 授权
        // 框/再等一轮 8s 超时。过渡期内直接续走普通路线（与回落目标一致）
        if (fallbackTransition) {
            startNormalRoute(context, withControl = true)
            return
        }
        // 外观参数与普通路线 DisplayOverlayService.onCreate 同源（DataStore）。
        // 异步读取（IO 作用域，见 configScope 文档）：root 路线的首个消费者
        // onRootConnected 经连接回调异步到达，读取完成晚于连接时补投；
        // 普通路线由 DisplayOverlayService 自行读取同源配置，不受影响
        configScope.launch {
            val alpha = runCatching {
                ConfigManager.getDataOnce(ctx, "overlay_display_alpha", 1.0f)
            }.getOrDefault(1.0f)
            val muted = runCatching {
                ConfigManager.getDataOnce(ctx, "overlay_video_muted", false)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (paramsTouched) return@withContext
                displayAlphaValue = alpha
                videoMutedValue = muted
                // 读取晚于连接建立：补投给已挂载的 root 端
                if (rootRoute && RootOverlayService.isActive) {
                    RootOverlayService.setAlpha(alpha)
                    RootOverlayService.setMuted(muted)
                }
            }
        }
        if (startRootRoute(ctx)) return
        startNormalRoute(context, withControl = true)
    }

    fun stop(context: Context) {
        // 回落过渡解除：用户主动停止 = 过程终结（再开启是全新意图，
        // 允许重试 root 路线）
        fallbackTransition = false
        if (rootRoute) {
            stopRootRoute()
        } else {
            DisplayOverlayService.stop(context)
            ControlOverlayService.stop(context)
        }
    }

    fun startControl(context: Context) {
        if (rootRoute) {
            rootControlDesired = true
            if (RootOverlayService.isActive) RootOverlayService.attachControl()
            _isControlRunning.value = true
        } else {
            ControlOverlayService.start(context)
        }
    }

    fun stopControl(context: Context) {
        if (rootRoute) {
            rootControlDesired = false
            RootOverlayService.detachControl()
            _isControlRunning.value = false
        } else {
            ControlOverlayService.stop(context)
        }
    }

    fun setDisplayRunning(running: Boolean) {
        _isDisplayRunning.value = running
        // 普通路线服务就绪 = 回落过渡完成（见 fallbackTransition 注释）
        if (running) fallbackTransition = false
    }

    fun setControlRunning(running: Boolean) {
        _isControlRunning.value = running
    }

    fun setMediaList(list: List<Uri>) {
        _mediaList.value = list
        // 普通路线由 UI 调用 DisplayOverlayService.reloadMediaList() 驱动重载；
        // ROOT 路线在此直接驱动（等价 reloadMediaList：重置索引并显示首项）
        if (rootRoute && RootOverlayService.isActive) {
            rootMediaIndex = 0
            showRootMedia()
        }
    }

    // ==================== 参数代理入口（两条路线统一路由） ====================

    /**
     * 显示窗口透明度：ROOT 路线转发 root 端；普通路线转发本地服务。
     * 持久化由 UI 完成（与现有行为一致）。
     */
    fun setDisplayAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0.0f, 1.0f)
        displayAlphaValue = clamped
        paramsTouched = true
        if (rootRoute) {
            RootOverlayService.setAlpha(clamped)
        } else {
            DisplayOverlayService.setDisplayAlpha(clamped)
        }
    }

    fun getDisplayAlpha(): Float = displayAlphaValue

    /**
     * 视频静音：ROOT 路线转发 root 端并由本类异步持久化（见
     * configScope 文档，禁止主线程阻塞写）；普通路线转发本地服务
     * （其内部自行持久化）。
     */
    fun setMuted(muted: Boolean) {
        videoMutedValue = muted
        paramsTouched = true
        if (rootRoute) {
            RootOverlayService.setMuted(muted)
            appContext?.let { ctx ->
                configScope.launch {
                    runCatching { ConfigManager.saveData(ctx, "overlay_video_muted", muted) }
                }
            }
        } else {
            DisplayOverlayService.setMuted(muted)
        }
    }

    fun isMuted(): Boolean = videoMutedValue

    // ==================== 路由内部实现 ====================

    /**
     * 绑定 ROOT 托管服务（双后端：Shizuku UserService 优先 / su 直连兜底）。
     * 返回 false 表示立即判定不可行（无 root 且无 su）：同步走普通路线；
     * 返回 true 表示已绑定或正在建立（su 异步路径上限 8 秒），结果经
     * [rootListener] 通知，失败时自动回落普通路线，悬浮窗不中断。
     */
    private fun startRootRoute(ctx: Context): Boolean {
        RootOverlayService.setMediaSwitchHandler(mediaSwitchHandler)
        RootOverlayService.addListener(rootListener)
        if (!RootOverlayService.bind(ctx)) {
            RootOverlayService.setMediaSwitchHandler(null)
            RootOverlayService.removeListener(rootListener)
            return false
        }
        rootRoute = true
        rootAttached = false
        rootControlDesired = true // 与普通路线 start() 语义一致：显示+控制同时启动
        rootMediaIndex = 0
        _isDisplayRunning.value = true
        _isControlRunning.value = true
        if (RootOverlayService.isActive) onRootConnected()
        return true
    }

    private fun startNormalRoute(context: Context, withControl: Boolean) {
        // fallback 触发时机常在 app 已退后台（su 8s 超时/Shizuku 死亡回调），
        // Android 12+ 后台 FGS 启动限制会抛 ForegroundServiceStartNotAllowedException
        // ——不捕获则主线程崩溃。启动失败仅置状态（用户下次前台操作再拉起）
        runCatching { DisplayOverlayService.start(context) }
        if (withControl) runCatching { ControlOverlayService.start(context) }
    }

    /** root 端连接到达：挂窗口并显示首个媒体（初始几何与普通路线一致）。 */
    private fun onRootConnected() {
        if (!rootRoute || rootAttached) return
        rootAttached = true
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val initWidth = (bounds.width() * 0.6f).toInt().coerceIn(300, 1000)
        val initHeight = (bounds.height() * 0.4f).toInt().coerceIn(200, 800)
        RootOverlayService.attach(100, 200, initWidth, initHeight)
        RootOverlayService.setAlpha(displayAlphaValue)
        RootOverlayService.setMuted(videoMutedValue)
        if (rootControlDesired) RootOverlayService.attachControl()
        rootMediaIndex = 0
        showRootMedia()
    }

    /**
     * 投递当前索引媒体到 root 端（主线程调用，天然串行）。
     * fd 跨进程传递时内核已 dup，代理层负责关闭本地副本。
     */
    private fun showRootMedia() {
        if (!rootRoute || !RootOverlayService.isActive) return
        val ctx = appContext ?: return
        val list = _mediaList.value
        if (list.isEmpty()) {
            RootOverlayService.clearMedia()
            return
        }
        if (rootMediaIndex !in list.indices) rootMediaIndex = 0
        val uri = list[rootMediaIndex]
        runCatching {
            val mime = ctx.contentResolver.getType(uri)
            // pfd 生命周期由本侧独占（try/finally 关闭）：转发目标（Shizuku
            // binder transact / su socket SCM_RIGHTS）在同步调用返回时内核
            // 已完成 dup，本地关闭不影响对端；后端死亡（api 为 null）或
            // 转发抛异常时 pfd 不再泄漏（fd 属稀缺资源，长期泄漏耗尽后
            // 媒体/网络/存储全部失效）
            when {
                mime?.startsWith("image/") == true ->
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                        try {
                            RootOverlayService.showImage(pfd)
                        } finally {
                            runCatching { pfd.close() }
                        }
                    }

                mime?.startsWith("video/") == true ->
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                        try {
                            RootOverlayService.showVideo(pfd)
                        } finally {
                            runCatching { pfd.close() }
                        }
                    }
            }
        }
    }

    private fun stopRootRoute() {
        rootRoute = false
        rootAttached = false
        rootControlDesired = false
        RootOverlayService.setMediaSwitchHandler(null)
        RootOverlayService.removeListener(rootListener)
        RootOverlayService.unbind() // root 进程销毁，窗口经 binder 死亡通知自动摘除
        _isDisplayRunning.value = false
        _isControlRunning.value = false
    }

    /**
     * ROOT 后端失败/断连（su 建立失败、Shizuku 死亡、root 端窗口挂载失败）：
     * 清理 ROOT 路线并回落普通悬浮窗路线，悬浮窗不中断。
     * 隐藏性降级：root 路线承诺截图排除，普通路线仅尽力排除——
     * Toast 告知用户当前会话截图可能包含悬浮窗（功能不受影响），并携带
     * 具体失败原因（RootOverlayService 逐环节诊断，便于无 adb 定位）。
     */
    private fun fallbackToNormalRoute() {
        val ctx = appContext ?: return
        val reason = RootOverlayService.lastFailureReason
        val controlDesired = rootControlDesired
        rootRoute = false
        rootAttached = false
        rootControlDesired = false
        RootOverlayService.setMediaSwitchHandler(null)
        RootOverlayService.removeListener(rootListener)
        RootOverlayService.unbind()
        _isDisplayRunning.value = false
        _isControlRunning.value = false
        // 过渡窗口开启（普通服务 onCreate 上报运行态时解除；窗口内
        // start() 续走普通路线，不重试 root——见 start() 守卫注释）
        fallbackTransition = true
        startNormalRoute(ctx, withControl = controlDesired)
        notifyStealthDegraded(ctx, reason)
    }

    /**
     * 隐藏性降级统一提示。同一会话只提示一次：root 失败回落 +
     * 普通路线排除失败都指向同一事实（截图可能包含悬浮窗），
     * 重复弹出让用户烦躁。与会话级失效对齐（非持久化）。
     * 公开方法：DisplayOverlayService 排除重试终态失败时同样接入
     * （reason 为空——普通路线无 root 侧诊断链）。
     */
    fun notifyStealthDegradedPublic(ctx: Context, reason: String? = null) {
        if (stealthToastShown) return
        stealthToastShown = true
        runCatching {
            // 屏显只给固定提示：reason 携带 root 侧完整诊断（su 命令串/
            // 入口类名/进程名等可归因信息），Toast 是肩窥与取证的可见
            // 面——完整诊断仅保留在 RootOverlayService.lastFailureReason
            //（内存态，随进程消失），不上屏
            Toast.makeText(ctx, getString(ctx, R.string.overlay_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun notifyStealthDegraded(ctx: Context, reason: String? = null) =
        notifyStealthDegradedPublic(ctx, reason)

    /** [notifyStealthDegraded] 的会话级去重（悬浮窗停止时复位）。 */
    @Volatile
    private var stealthToastShown = false

    /**
     * root→普通路线回落过渡标志：true = 过渡窗口内（运行标志已复位、
     * 普通服务尚未 onCreate）。窗口内 start() 续走普通路线而非重试
     * root（回落刚因 root 失败触发，立即重试 = 再弹一次授权框）；
     * 普通服务上报运行态或用户主动 stop 时解除。若回落时 FGS 启动
     * 失败（后台限制），标志保持——用户下次开关仍走普通路线重试
     * （与 startNormalRoute 的"下次前台操作再拉起"语义一致）
     */
    @Volatile
    private var fallbackTransition = false
}
