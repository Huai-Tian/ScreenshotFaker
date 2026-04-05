package fake.screenshot

import android.content.Context
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
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fake.screenshot.pages.ApplicationCompose
import fake.screenshot.pages.ExtensionCompose
import fake.screenshot.pages.GalleryCompose
import fake.screenshot.pages.HomeCompose
import fake.screenshot.pages.SettingsCompose

class MainActivity : ComponentActivity() {
    companion object {
        fun isModuleActivated() = false
        fun isShellActivated() = true
        fun isRootActivated() = false
        fun getVersionName(context: Context): String {
            return try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: context.getString(R.string.unknown)
            } catch (_: Exception) {
                context.getString(R.string.unknown)
            }
        }

        fun getVersionCode(context: Context): Long {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.longVersionCode
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryFlow.collectAsState(initial = null)
            val currentDestination = navBackStackEntry?.destination?.route?.let { route ->
                AppDestinations.entries.find { it.route == route } ?: AppDestinations.HOME
            } ?: AppDestinations.HOME
            val visibleDestinations = AppDestinations.entries.filter { destination ->
                when (destination) {
                    AppDestinations.GALLERY, AppDestinations.APPLICATION -> isModuleActivated()
                    AppDestinations.EXTENSION -> isShellActivated() || isRootActivated()
                    else -> true
                }
            }

            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    visibleDestinations.forEach { destination ->
                        item(
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = {
                                if (currentDestination == destination) {
                                    Text(
                                        when (destination.label) {
                                            "Home" -> getString(R.string.home)
                                            "Settings" -> getString(R.string.settings)
                                            "Gallery" -> getString(R.string.gallery)
                                            "Application" -> getString(R.string.application)
                                            "Extension" -> getString(R.string.extension)
                                            else -> getString(R.string.unknown)
                                        }
                                    )
                                }
                            },
                            selected = currentDestination == destination,
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
            ) {
                NavHost(
                    navController = navController,
                    startDestination = AppDestinations.HOME.route
                ) {
                    composable(AppDestinations.HOME.route) { HomeCompose() }
                    composable(AppDestinations.SETTINGS.route) { SettingsCompose() }
                    composable(AppDestinations.GALLERY.route) { GalleryCompose() }
                    composable(AppDestinations.APPLICATION.route) { ApplicationCompose() }
                    composable(AppDestinations.EXTENSION.route) { ExtensionCompose() }

                }
            }
        }
    }
}

enum class AppDestinations(val label: String, val icon: ImageVector, val route: String) {
    HOME("Home", Icons.Default.Home, "home"),
    APPLICATION("Application", Icons.Default.Apps, "application"),
    GALLERY("Gallery", Icons.Default.Photo, "gallery"),
    EXTENSION("Extension", Icons.Default.Extension, "extension"),
    SETTINGS("Settings", Icons.Default.Settings, "settings")
}
