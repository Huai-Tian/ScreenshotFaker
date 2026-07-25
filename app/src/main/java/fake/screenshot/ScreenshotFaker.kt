package fake.screenshot

import android.util.Log
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.Arrays


private const val TAG = "ScreenshotFaker"

class ScreenshotFaker : XposedModule() {
    private lateinit var module: XposedModule
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        module = this
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        when (param.packageName) {
            "com.android.systemui" -> module.log(Log.INFO, TAG, "UI")
            "android" -> module.log(Log.INFO, TAG, "ANDROID")
            else -> module.log(Log.INFO, TAG, param.packageName)
        }
    }

    private fun hookMethods(clazz: Class<*>, hooker: Hooker, vararg names: String?) {
        val list = listOf(*names)
        Arrays.stream(clazz.getDeclaredMethods())
            .filter { method: Method? -> list.contains(method!!.name) }
            .forEach { method: Method? -> hook(method!!).intercept(hooker) }
    }
}