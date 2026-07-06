package fake.screenshot

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import fake.screenshot.pages.SettingsCompose
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
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
        setContent {
            val navController = rememberNavController()
            val currentDestination by navController.currentBackStackEntryAsState()
            val currentRoute = currentDestination?.destination?.route ?: ""

            // 动态过滤需要显示在底部导航栏的目标
            val visibleDestinations = AppDestinations.entries.filter { destination ->
                when (destination) {
                    AppDestinations.GALLERY, AppDestinations.APPLICATION -> isModuleActivated()
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
                ) {
                    composable(AppDestinations.HOME.route) { HomeCompose() }
                    composable(AppDestinations.SETTINGS.route) { SettingsCompose(navController) }
                    composable(AppDestinations.GALLERY.route) { GalleryCompose() }
                    composable(AppDestinations.APPLICATION.route) { ApplicationCompose() }
                    composable(AppDestinations.EXTENSION.route) { ExtensionCompose() }
                    composable("daemon_status") { DaemonStatusCompose() }
                    composable("about") { AboutCompose() }
                    composable("receive_screen_sharing") { ReceiveScreenSharingCompose() }
                }
            }
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

enum class AppDestinations(val label: String, val icon: ImageVector, val route: String) {
    HOME("Home", Icons.Default.Home, "home"),
    APPLICATION("Application", Icons.Default.Apps, "application"),
    GALLERY("Gallery", Icons.Default.Photo, "gallery"),
    EXTENSION("Extension", Icons.Default.Extension, "extension"),
    SETTINGS("Settings", Icons.Default.Settings, "settings")
}
