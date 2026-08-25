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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity(), LSPosedServiceManager.ServiceStateListener {
    private var mService: XposedService? = null
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
        runBlocking {
            val enableFlagSecure =
                ConfigManager.getDataOnce(applicationContext, "enable_flag_secure", true)
            val hideFromRecent =
                ConfigManager.getDataOnce(applicationContext, "hide_from_recent", false)
            if (enableFlagSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            getSystemService(ActivityManager::class.java)
                .appTasks.forEach { it.setExcludeFromRecents(hideFromRecent) }
        }
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
                    Auxiliary.getRandomString((20..30).random())
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
                    (1000..4999).random()
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
                    Auxiliary.getRandomString((31..36).random())
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
                    (5000..9999).random()
                )
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.statusBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
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
    }

    override fun onServiceStateChanged(service: XposedService?) {
        mService = service
        isModuleActivated = mService != null
    }

    override fun onStart() {
        super.onStart()
        LSPosedServiceManager.addServiceStateListener(this, true)
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
