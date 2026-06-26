package fake.screenshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

object DaemonManager {
    private lateinit var appContext: Context
    private val mutex = Mutex()

    // 初始化：保存 Context
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun startDaemon(): Boolean = mutex.withLock {
        if (isDaemonRunning()) return true
        val port = ConfigManager.getDataOnce(
            appContext,
            "daemon_socket_port",
            1234
        )
        val password = ConfigManager.getDataOnce(
            appContext,
            "daemon_socket_port",
            1234
        )
        try {
            Socket("127.0.0.1", port).use {
                return false
            }
        } catch (_: Exception) {
            // 连接失败，端口空闲，继续启动
        }
        withContext(Dispatchers.IO) {
            Auxiliary.exec("${appContext.applicationInfo.nativeLibraryDir}/daemon.so $port $password")
        }

        // 等待守护进程启动，最多重试20次（每次间隔100ms，共2秒）
        repeat(20) {
            if (isDaemonRunning()) return true
            delay(100.milliseconds)  // 单位毫秒，可直接写数字
        }
        return false
    }

    suspend fun stopDaemon(): Boolean = mutex.withLock {
        sendCommand("stop")  // sendCommand 已经是挂起函数，内部处理IO
        // 等待守护进程退出，最多重试20次
        repeat(20) {
            if (!isDaemonRunning()) return true
            delay(100.milliseconds)
        }
        return false
    }

    suspend fun isDaemonRunning() = sendCommand("status")?.startsWith("Working") ?: false

    suspend fun sendCommand(command: String): String? {//TODO发出的消息需要通过password加密
        return withContext(Dispatchers.IO) {
            try {
                Socket(
                    "127.0.0.1", ConfigManager.getDataOnce(
                        appContext,
                        "daemon_socket_port",
                        1234
                    )
                ).use { socket ->
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    writer.println(command)
                    writer.flush()
                    reader.readLine()
                }
            } catch (e: Exception) {
                Log.e("DaemonManager", "sendCommand error", e)
                null
            }
        }
    }
}