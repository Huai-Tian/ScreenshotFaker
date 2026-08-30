package fake.screenshot

import android.app.Application
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
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
import androidx.lifecycle.lifecycleScope
import com.google.crypto.tink.aead.AeadConfig
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.DaemonManager
import fake.screenshot.wrappers.EncryptManager
import fake.screenshot.wrappers.GateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity(), LSPosedServiceManager.ServiceStateListener {
    private var mService: XposedService? = null
    private var heartbeatJob: Job? = null
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
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addBinderReceivedListener(receivedListener)
        DaemonManager.init(applicationContext)
        EncryptManager.init(applicationContext)
        GateManager.init(applicationContext)
        // 超时销毁冷启动判定（雷管/超期检测 + 可能的销毁），一切配置加载之前；
        // 销毁同步等待完成，防止主界面读到半销毁状态
        runBlocking { GateManager.checkIdleExpired() }
        // 无门禁：判定通过即有效使用，touch 锚点；
        // 有门禁：验证前不 touch（打开对计时器透明，防反复打开续命）
        val gateRequired = GateManager.isGateEnabled() && !GateManager.sessionUnlocked
        if (gateRequired) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            runBlocking { GateManager.touchIdle() }
            runBlocking { applyWindowSecurityConfig() }
            randomizeChannelNames()
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
                        startHeartbeat()
                        // 解锁后：touch 超时锚点（验证通过 = 有效使用），
                        // 再加载配置（安全密码读真实配置；胁迫路径此时已恢复默认值）
                        lifecycleScope.launch {
                            GateManager.touchIdle()
                            applyWindowSecurityConfig()
                        }
                        randomizeChannelNames()
                    }
                )
            } else {
                MainContent()
            }
        }
    }

    /** 10s 心跳：已解锁会话内持续续期，防"停留超档位"误毁 */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = lifecycleScope.launch {
            while (true) {
                delay(10_000.milliseconds)
                GateManager.touchIdle()
            }
        }
    }

    /** 应用窗口安全配置（防截屏 / 最近任务隐藏），随配置变化调用 */
    private suspend fun applyWindowSecurityConfig() {
        val enableFlagSecure =
            ConfigManager.getDataOnce(applicationContext, "enable_flag_secure", true)
        val hideFromRecent =
            ConfigManager.getDataOnce(applicationContext, "hide_from_recent", false)
        if (enableFlagSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        getSystemService(ActivityManager::class.java)
            .appTasks.forEach { it.setExcludeFromRecents(hideFromRecent) }
    }

    /** 通知渠道名/ID 随机化（默认值检测到才改写） */
    private fun randomizeChannelNames() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (ConfigManager.getDataOnce(
                    applicationContext,
                    "overlay_service_display_channel_name",
                    "Display"
                ) == "Display"
            ) {
                ConfigManager.saveData(
                    applicationContext,
                    "overlay_service_display_channel_name",
                    Auxiliary.getRandomString(Auxiliary.getSecureRandomInt(20..30))
                )
            }
            if (ConfigManager.getDataOnce(
                    applicationContext,
                    "overlay_service_display_channel_id",
                    1001
                ) == 1001
            ) {
                ConfigManager.saveData(
                    applicationContext,
                    "overlay_service_display_channel_id",
                    Auxiliary.getSecureRandomInt(1000..4999)
                )
            }
            if (ConfigManager.getDataOnce(
                    applicationContext,
                    "overlay_service_control_channel_name",
                    "Control"
                ) == "Control"
            ) {
                ConfigManager.saveData(
                    applicationContext,
                    "overlay_service_control_channel_name",
                    Auxiliary.getRandomString(Auxiliary.getSecureRandomInt(31..36))
                )
            }
            if (ConfigManager.getDataOnce(
                    applicationContext,
                    "overlay_service_control_channel_id",
                    1002
                ) == 1002
            ) {
                ConfigManager.saveData(
                    applicationContext,
                    "overlay_service_control_channel_id",
                    Auxiliary.getSecureRandomInt(5000..9999)
                )
            }
        }
    }

    override fun onServiceStateChanged(service: XposedService?) {
        mService = service
        isModuleActivated = mService != null
    }

    override fun onStart() {
        super.onStart()
        LSPosedServiceManager.addServiceStateListener(this, true)
        // 回前台 = 恢复使用：touch 锚点（已解锁会话内；未解锁的门禁页不 touch）
        lifecycleScope.launch { GateManager.touchIdle() }
    }

    override fun onStop() {
        LSPosedServiceManager.removeServiceStateListener(this)
        super.onStop()
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
