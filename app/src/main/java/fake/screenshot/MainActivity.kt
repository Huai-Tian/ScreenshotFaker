package fake.screenshot

import android.app.Application
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fake.screenshot.Auxiliary
import fake.screenshot.Auxiliary.isModuleActivated
import fake.screenshot.Auxiliary.isShellActivated
import fake.screenshot.pages.AboutCompose
import fake.screenshot.pages.ApplicationCompose
import fake.screenshot.pages.DaemonStatusCompose
import fake.screenshot.pages.ExtensionCompose
import fake.screenshot.pages.GalleryCompose
import fake.screenshot.pages.GateCompose
import fake.screenshot.pages.HomeCompose
import fake.screenshot.pages.ReceiveScreenSharingCompose
import fake.screenshot.pages.ScreenShareViewerCompose
import fake.screenshot.pages.SettingsCompose
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.crypto.tink.aead.AeadConfig
import fake.screenshot.defense.DefenseProtocol
import fake.screenshot.defense.GateManager
import fake.screenshot.defense.GuardManager
import fake.screenshot.defense.IdleWatchdog
import fake.screenshot.defense.SensitiveStore
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.DaemonManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity(), LSPosedServiceManager.ServiceStateListener {
    private var mService: XposedService? = null
    private var heartbeatJob: Job? = null

    // ---- 会话自动锁定（DK 驻留窗口收窄）----
    // SIGSTOP 先手 dump 的可利用窗口 = DK 在内存的时间。锁定触发：
    // 息屏立即（进程级注册，见 LSPosedServiceManager）/ 后台 30s 宽限 /
    // 前台无操作 5min。锁定后磁贴与敏感功能经 isDaemonKeyReady/
    // isSensitiveConfigured fail-closed，超时计时不受影响
    companion object {
        private const val LOCK_FOREGROUND_IDLE_MS = 5 * 60_000L
        private const val LOCK_BACKGROUND_MS = 30_000L
    }

    private var lockJob: Job? = null
    private var lastInteractionEr = 0L

    /** 本实例生命周期内解锁过（锁定后回前台重建门禁页的判定依据） */
    private var everUnlocked = false
    val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResults ->
        if (requestCode == 1 && grantResults == PackageManager.PERMISSION_GRANTED) {
            isShellActivated = true
        }
    }
    val deadListener = Shizuku.OnBinderDeadListener {
        isShellActivated = false
    }
    val receivedListener = Shizuku.OnBinderReceivedListener {
        isShellActivated = true
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyOverlayProtection()
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addBinderReceivedListener(receivedListener)
        DaemonManager.init(applicationContext)
        DefenseProtocol.init(applicationContext)
        // 注入雷管：命中即完整销毁（Application 层已查则幂等无害；
        // 同步等待确保销毁完成后再判定超时/加载配置）
        if (GuardManager.checkNow()) {
            runBlocking { DefenseProtocol.destroyForCoercion() }
        }
        // 超时销毁冷启动判定（雷管/超期检测 + 可能的销毁），一切配置加载之前；
        // 销毁同步等待完成，防止主界面读到半销毁状态
        runBlocking { IdleWatchdog.checkIdleExpired() }
        // 息屏锁定已上移至 LSPosedServiceManager（进程级注册）：
        // Activity 级注册在用户按返回键 finish 后注销，此后息屏不再
        // 触发锁定——DK 与信道密钥缓存随进程存活无限驻留
        // 无操作计时的基准：以启动时刻初始化（否则默认 0 会让解锁后
        // 首个心跳 tick 误判"已无操作超时"立即锁定）
        lastInteractionEr = SystemClock.elapsedRealtime()
        // 无门禁：判定通过即有效使用，touch 锚点；
        // 有门禁：验证前不 touch（打开对计时器透明，防反复打开续命）
        val gateRequired = GateManager.isGateEnabled() && !GateManager.sessionUnlocked
        if (gateRequired) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            runBlocking { IdleWatchdog.touchIdle() }
            runBlocking { applyWindowSecurityConfig() }
        }
        // 10s 心跳：仅无门禁直接进入时启动；有门禁在验证通过后启动
        if (!gateRequired) startHeartbeat()
        WindowCompat.getInsetsController(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.statusBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            var unlocked by remember { mutableStateOf(!gateRequired) }
            if (!unlocked) {
                GateCompose(
                    onUnlocked = {
                        GateManager.markUnlocked()
                        unlocked = true
                        everUnlocked = true
                        // 解锁动作重置无操作基准（门禁页停留时间不得计入）
                        lastInteractionEr = SystemClock.elapsedRealtime()
                        startHeartbeat()
                        // 解锁后：touch 超时锚点（验证通过 = 有效使用），
                        // 再加载配置（安全密码读真实配置；胁迫路径此时已恢复默认值）
                        lifecycleScope.launch {
                            IdleWatchdog.touchIdle()
                            applyWindowSecurityConfig()
                        }
                    }
                )
            } else {
                MainContent()
            }
        }
    }

    /**
     * 10s 心跳：仅 RESUMED（前台可见）时续期，防"停留超档位"误毁。
     * 挂 repeatOnLifecycle(RESUMED)：onStop 即暂停——旧实现挂裸
     * lifecycleScope，Activity 退后台（未销毁）仍每 10s 续期，叠加前台服务
     * 保活进程后超时自毁永不触发。
     * 心跳顺带执行前台无操作锁定（与续期同循环：锁定后续期自然停止）。
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(10_000.milliseconds)
                    IdleWatchdog.touchIdle()
                    // 前台无操作超时：清 DK 重建门禁（DK 仅在活跃使用期间驻留）。
                    // 仅对有门禁用户生效——无门禁时 sessionUnlocked 恒 false，
                    // 锁定无意义（单段 DK 本就可从文件读出），不打扰
                    if (GateManager.isGateEnabled() &&
                        SystemClock.elapsedRealtime() - lastInteractionEr >
                        LOCK_FOREGROUND_IDLE_MS
                    ) {
                        GateManager.lockSession()
                        recreate()
                        break
                    }
                }
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionEr = SystemClock.elapsedRealtime()
    }

    /**
     * 通知栏遮盖防护（防 tapjacking）：令本窗口之上不允许任何非系统悬浮窗
     * ——恶意 app 无法用 overlay 盖在门禁密码框/设置页密码框上偷窥输入或
     * 劫持点击。需 manifest 声明 HIDE_OVERLAY_WINDOWS（normal 级）。
     *
     * API 31+：公开 Window.setHideOverlayWindows。
     * API 30（minSdk）：flag 本身自 Android 10 起存在但 @hide（31 才转正），
     * 经 HiddenApiBypass 反射读取字段值设置——字段读取失败则静默跳过
     * （不硬编码数值，避免版本间 flag 位变动风险）。
     */
    private fun applyOverlayProtection() {
        if (Build.VERSION.SDK_INT >= 31) {
            window.setHideOverlayWindows(true)
        } else {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/view/")
                val flag = HiddenApiBypass.getStaticFields(WindowManager.LayoutParams::class.java)
                    .firstOrNull { it.name == "FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS" }
                    ?.getInt(null) ?: return
                window.addFlags(flag)
            }
        }
    }

    /** 应用窗口安全配置（防截屏 / 最近任务隐藏），随配置变化调用 */
    private suspend fun applyWindowSecurityConfig() {
        val enableFlagSecure =
            ConfigManager.getDataOnce(applicationContext, "enable_flag_secure", true)
        // 默认 true（隐蔽性是本 app 初衷：胁迫者翻最近任务不应看到本 app
        // 刚被使用过——那是最快的归因路径）。用户显式设置过则以设置为准
        val hideFromRecent =
            ConfigManager.getDataOnce(applicationContext, "hide_from_recent", true)
        if (enableFlagSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        getSystemService(ActivityManager::class.java)
            .appTasks.forEach { it.setExcludeFromRecents(hideFromRecent) }
    }

    override fun onServiceStateChanged(service: XposedService?) {
        mService = service
        isModuleActivated = mService != null
    }

    override fun onStart() {
        super.onStart()
        LSPosedServiceManager.addServiceStateListener(this, true)
        // 回前台 = 恢复使用：先检查后续期（旧实现只 touch 不检查——Activity
        // 长驻后台未销毁时，重回前台的这次检查是唯一的机会）
        lockJob?.cancel()
        lifecycleScope.launch {
            if (!IdleWatchdog.checkIdleExpired()) IdleWatchdog.touchIdle()
        }
        // 锁定一致性：本实例解锁过但会话已锁（后台宽限到点/息屏/冻结恢复），
        // 重建到门禁页——锁定动作可能在后台或冻结期间已静默完成
        if (everUnlocked && GateManager.isGateEnabled() && !GateManager.sessionUnlocked) {
            recreate()
            return
        }
    }

    override fun onStop() {
        LSPosedServiceManager.removeServiceStateListener(this)
        super.onStop()
        // 后台宽限锁定：进程存活到点即清 DK（进程被冻结时任务暂停，
        // 恢复后补执行；进程被杀则重启本就回到门禁）。短暂跳转（选图/
        // 分享/权限页）在宽限内返回不锁
        lockJob?.cancel()
        lockJob = lifecycleScope.launch {
            delay(LOCK_BACKGROUND_MS)
            GateManager.lockSession()
        }
    }

    override fun onResume() {
        super.onResume()
        isShellActivated = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(listener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeBinderReceivedListener(receivedListener)
    }
}

/** 主界面：底部导航 + 各页面路由（门禁通过后组合） */
@Composable
fun MainContent() {
    val navController = rememberNavController()
    val currentDestination by navController.currentBackStackEntryAsState()
    val currentRoute = currentDestination?.destination?.route ?: ""

    // 动态过滤需要显示在底部导航栏的目标
    val visibleDestinations = AppDestinations.entries.filter { destination ->
        when (destination) {
            AppDestinations.GALLERY, AppDestinations.APPLICATION -> isModuleActivated
            else -> true
        }
    }
    val visibleBottomBarRoutes = visibleDestinations.map { it.route }.toSet()

    Scaffold(
        bottomBar = {
            if (currentRoute in visibleBottomBarRoutes) {
                NavigationBar {
                    visibleDestinations.forEach { destination ->
                        NavigationBarItem(
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = {
                                Text(
                                    when (destination.label) {
                                        "Home" -> stringResource(R.string.home)
                                        "Settings" -> stringResource(R.string.settings)
                                        "Gallery" -> stringResource(R.string.gallery)
                                        "Application" -> stringResource(R.string.application)
                                        "Extension" -> stringResource(R.string.extension)
                                        else -> stringResource(R.string.unknown)
                                    }
                                )
                            },
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = AppDestinations.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = it.calculateBottomPadding())
        ) {
            composable(AppDestinations.HOME.route) { HomeCompose() }
            composable(AppDestinations.SETTINGS.route) { SettingsCompose(navController) }
            composable(AppDestinations.GALLERY.route) { GalleryCompose() }
            composable(AppDestinations.APPLICATION.route) { ApplicationCompose() }
            composable(AppDestinations.EXTENSION.route) { ExtensionCompose() }
            composable("daemon_status") { DaemonStatusCompose() }
            composable("about") { AboutCompose() }
            composable("receive_screen_sharing") { ReceiveScreenSharingCompose(navController) }
            composable("receive_viewer/{configId}") { entry ->
                ScreenShareViewerCompose(
                    entry.arguments?.getString("configId")?.toIntOrNull() ?: -1
                )
            }
        }
    }
}

class LSPosedServiceManager : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        @Volatile
        var mService: XposedService? = null
            private set
        private val serviceStateListeners = CopyOnWriteArraySet<ServiceStateListener>()

        private fun dispatchServiceState(
            listener: ServiceStateListener,
            service: XposedService?
        ) {
            if (serviceStateListeners.contains(listener)) {
                listener.onServiceStateChanged(service)
            }
        }

        fun addServiceStateListener(
            listener: ServiceStateListener,
            notifyImmediately: Boolean
        ) {
            serviceStateListeners.add(listener)
            if (notifyImmediately) {
                dispatchServiceState(listener, mService)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            serviceStateListeners.remove(listener)
        }
    }

    private fun notifyServiceStateChanged(service: XposedService?) {
        for (listener in serviceStateListeners) {
            dispatchServiceState(listener, service)
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
        AeadConfig.register()
        // 首装共享密码生成：默认配置下共享认证关闭（无 auth_password 即
        // 局域网任意设备可观看/控制/读写剪贴板）——首装即生成随机高强度
        // 密码（DK 加密存 SensitiveStore），用户可显式清空回到无密码
        // （允许无密码是产品决策，但默认值必须是"有密码"）。IO 协程执行
        // （DataStore 加密读写，见 SensitiveStore）；幂等：已存在 _sec
        // 密文（含显式清空后的空值语义——isConfigured 仅在从未写入时为
        // false）不覆盖
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (!SensitiveStore.isSensitiveConfigured(
                        this@LSPosedServiceManager, "screenShare_password"
                    )
                ) {
                    val pwd = Auxiliary.getStrongPassword(
                        Auxiliary.getSecureRandomInt(14..18)
                    )
                    SensitiveStore.putSensitive(
                        this@LSPosedServiceManager, "screenShare_password", pwd
                    )
                }
            }
        }
        // 息屏锁定（进程级注册）：原实现注册在 MainActivity，用户解锁后
        // 按返回键 finish Activity（进程因 FGS/缓存存活）即注销——此后
        // 息屏不再锁定，DK 与信道密钥缓存随进程无限驻留，击穿防线 #10
        // 声称的"DK 驻留窗口收窄"。Application 级注册随进程存活全程有效
        //（lockSession 幂等：未解锁态下无操作，重复触发无害）
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        GateManager.lockSession()
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 通知渠道随机化在两个悬浮窗服务的 createNotification 内联完成
        //（明文 prefs 同步读，缺失键就地落盘随机值）——渠道只在服务创建
        // 通知时建立、随服务销毁删除，任何入口（含服务先于 Activity 的
        // 冷启动）都不落默认渠道名，无需在此预随机化
        // 注入检测雷管：进程任何入口（服务/磁贴/BootReceiver 先于 Activity 启动）
        // 均经过此处。命中 → 完整销毁序列（含 Keystore 条目与 daemon 停止；
        // 各步骤独立容错，未初始化的 manager 抛异常被吸收）。native 自主
        // 线程同时启动——即使本协程被注入代码拦截，native 兜底引爆仍在跑
        GuardManager.init(this)
        if (GuardManager.checkNow()) {
            DaemonManager.init(this)
            DefenseProtocol.init(this)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { DefenseProtocol.destroyForCoercion() }
            }
        }
    }

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onServiceBind(service: XposedService) {
        mService = service
        notifyServiceStateChanged(mService)
    }

    override fun onServiceDied(service: XposedService) {
        mService = null
        notifyServiceStateChanged(mService)
    }
}

enum class AppDestinations(val label: String, val icon: ImageVector, val route: String) {
    HOME("Home", Icons.Default.Home, "home"),
    APPLICATION("Application", Icons.Default.Apps, "application"),
    GALLERY("Gallery", Icons.Default.Photo, "gallery"),
    EXTENSION("Extension", Icons.Default.Extension, "extension"),
    SETTINGS("Settings", Icons.Default.Settings, "settings")
}
