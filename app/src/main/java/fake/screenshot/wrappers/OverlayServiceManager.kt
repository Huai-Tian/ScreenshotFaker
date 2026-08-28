package fake.screenshot.wrappers

import android.content.Context
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat.getString
import fake.screenshot.R
import fake.screenshot.services.ControlOverlayService
import fake.screenshot.services.DisplayOverlayService
import fake.screenshot.services.privileged.RootOverlayService
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
        DisplayOverlayService.start(context)
        if (withControl) ControlOverlayService.start(context)
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
            when {
                mime?.startsWith("image/") == true ->
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                        RootOverlayService.showImage(pfd)
                    }

                mime?.startsWith("video/") == true ->
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                        RootOverlayService.showVideo(pfd)
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
     * Toast 告知用户当前会话截图可能包含悬浮窗（功能不受影响）。
     */
    private fun fallbackToNormalRoute() {
        val ctx = appContext ?: return
        val controlDesired = rootControlDesired
        rootRoute = false
        rootAttached = false
        rootControlDesired = false
        RootOverlayService.setMediaSwitchHandler(null)
        RootOverlayService.removeListener(rootListener)
        RootOverlayService.unbind()
        _isDisplayRunning.value = false
        _isControlRunning.value = false
        startNormalRoute(ctx, withControl = controlDesired)
        notifyStealthDegraded(ctx)
    }

    /**
     * 隐藏性降级统一提示。同一会话只提示一次：root 失败回落 +
     * 普通路线排除失败都指向同一事实（截图可能包含悬浮窗），
     * 重复弹出让用户烦躁。与会话级失效对齐（非持久化）。
     * 公开方法：DisplayOverlayService 排除重试终态失败时同样接入。
     */
    fun notifyStealthDegradedPublic(ctx: Context) {
        if (stealthToastShown) return
        stealthToastShown = true
        runCatching {
            Toast.makeText(
                ctx,
                getString(ctx, R.string.overlay_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun notifyStealthDegraded(ctx: Context) = notifyStealthDegradedPublic(ctx)

    /** [notifyStealthDegraded] 的会话级去重（悬浮窗停止时复位）。 */
    @Volatile
    private var stealthToastShown = false
}
