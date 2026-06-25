package fake.screenshot

import android.content.Context
import androidx.core.text.isDigitsOnly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DaemonManager {
    private lateinit var appContext: Context
    private val _abstractName = MutableStateFlow("fake.screenshot.daemon")

    // 初始化：保存 Context 并加载配置
    fun init(context: Context) {
        appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            refreshConfig() // 首次加载
        }
    }

    suspend fun refreshConfig() {
            val name = ConfigManager.getDataOnce(
                appContext,
                "daemon_abstract_name",
                "fake.screenshot.daemon"
            )
            _abstractName.value = name
    }
    suspend fun startDaemon(): Boolean {
        refreshConfig()
        withContext(Dispatchers.IO) {

        }
        //假设判断了
        return true
    }
    suspend fun stopDaemon(): Boolean {
        withContext(Dispatchers.IO) {
            // 发送 stop 命令或 kill 进程
        }
        //假设判断了
        return true
    }
    suspend fun isDaemonRunning(abstractNamespace:String): Boolean {
        withContext(Dispatchers.IO){

        }
        //假设判断了
        return abstractNamespace.isDigitsOnly()
    }
}