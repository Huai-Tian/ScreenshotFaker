package fake.screenshot

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "ScreenshotFaker"

class ScreenshotFaker : IXposedHookLoadPackage {
    override fun handleLoadPackage(p0: XC_LoadPackage.LoadPackageParam) {
        when (p0.packageName) {
            "com.android.systemui" -> hookSystemUI()
            "android" -> hookAndroid()
            this.javaClass.`package`?.name -> {
                try {
                    XposedHelpers.findAndHookMethod(
                        MainActivity.Companion::class.java.name,
                        p0.classLoader,
                        "isModuleActivated",
                        XC_MethodReplacement.returnConstant(true)
                    )
                } catch (e: Exception) {
                    XposedBridge.log("$TAG: Error in hooking ${MainActivity::class.java.name}")
                    XposedBridge.log(e)
                }
            }

            else -> XposedBridge.log("$TAG: 模块入口已执行！当前包名: ${p0.packageName}")
        }
    }

    fun hookSystemUI() {
        XposedBridge.log("$TAG: 成功注入SystemUI进程！！！")
    }

    fun hookAndroid() {
        XposedBridge.log("$TAG: 成功注入Android进程！！！")
    }
}