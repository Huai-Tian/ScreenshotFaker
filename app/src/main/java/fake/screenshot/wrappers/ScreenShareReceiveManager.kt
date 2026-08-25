package fake.screenshot.wrappers

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕共享接收端代理管理器（每个实例对应一部发送设备）。
 *
 * 在本机 [localPort] 上开启监听，把接入的每条 TCP 连接转发到发送端：
 * - 直连模式：手动中继，转发到 [address]:[targetPort]；
 * - SSH 模式：登录 SSH 服务器（[address]:[sshPort]）建立本地端口转发
 *   （localhost:localPort → SSH 服务器 127.0.0.1:targetPort），
 *   适用于发送端通过远程转发把共享端口暴露在 SSH 服务器上的场景。
 *
 * 设计为普通类而非单例：可同时创建多个实例分别对接多部发送设备，
 * 各实例的监听端口、SSH 会话与转发连接完全独立。
 *
 * 监听地址固定为回环（127.0.0.1）：转发的目的端口可能带有共享密码认证，
 * 仅供本机客户端（内置查看器或 adb 转发的外部 scrcpy 客户端）接入，
 * 不对局域网暴露。
 */
class ScreenShareReceiveManager(
    private val address: String,
    private val localPort: Int,
    private val targetPort: Int,
    private val useSSH: Boolean,
    private val sshPort: Int?,
    private val name: String?,
    private val password: ByteArray?
) {
    private val isRunning = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var sshSession: Session? = null

    /** 当前会话的全部活跃 socket，stop 时统一关闭以中断阻塞读 */
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        if (useSSH) {
            require(sshPort != null) { "sshPort must be provided when useSSH is true" }
            require(name != null) { "name must be provided when useSSH is true" }
            require(password != null) { "password must be provided when useSSH is true" }
        }
    }

    /**
     * 启动本地代理。
     * @return 已在运行返回 true；启动成功返回 true；失败返回 false
     */
    fun startProxy(): Boolean {
        if (!isRunning.compareAndSet(false, true)) return true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        if (useSSH) {
            // init 块已保证 useSSH 时这三个参数非空
            val session = runCatching {
                JSch().getSession(name!!, address, sshPort!!).apply {
                    setPassword(password!!)
                    setConfig("StrictHostKeyChecking", "no")
                    connect(SSH_TIMEOUT_MS)
                }
            }.getOrElse {
                release()
                return false
            }
            // JSch 直接在本机建立 localPort 的监听，无需手动中继
            val ok = runCatching {
                session.setPortForwardingL(localPort, "127.0.0.1", targetPort)
            }.isSuccess
            if (!ok) {
                runCatching { session.disconnect() }
                release()
                return false
            }
            sshSession = session
            return true
        }

        // 直连模式：本地监听 + 手动双向中继
        val server = runCatching {
            ServerSocket(localPort, 50, InetAddress.getLoopbackAddress())
        }.getOrElse {
            release()
            return false
        }
        serverSocket = server
        scope?.launch {
            while (isActive && isRunning.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(client)
                launch { relayConnection(client) }
            }
        }
        return true
    }

    /** 停止代理：关闭监听、全部转发连接与 SSH 会话 */
    fun stopProxy() {
        if (!isRunning.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { sshSession?.disconnect() }
        sshSession = null
        release()
    }

    private fun release() {
        scope?.cancel()
        scope = null
        serverSocket = null
    }

    /**
     * 直连模式下中继一条客户端连接：连接目标并双向转发。
     * 任一方向结束即关闭两个 socket，解除另一方向的阻塞读。
     */
    private suspend fun relayConnection(client: Socket) = coroutineScope {
        val target = runCatching {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(address, targetPort), CONNECT_TIMEOUT_MS)
            }
        }.getOrNull() ?: run {
            runCatching { client.close() }
            sockets.remove(client)
            return@coroutineScope
        }
        sockets.add(target)

        // coroutineScope 会等待两个方向都结束后才返回
        launch { relay(client.getInputStream(), target.getOutputStream(), client, target) }
        launch { relay(target.getInputStream(), client.getOutputStream(), client, target) }

        sockets.remove(client)
        sockets.remove(target)
    }

    private fun relay(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            var len: Int
            while (input.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
                output.flush()
            }
        } catch (_: IOException) {
        } finally {
            // 关闭两个 socket，让另一方向退出
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private companion object {
        const val SSH_TIMEOUT_MS = 8000
        const val CONNECT_TIMEOUT_MS = 8000
        const val BUFFER_SIZE = 64 * 1024
    }
}