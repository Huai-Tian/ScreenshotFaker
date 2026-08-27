package fake.screenshot.services.privileged

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import fake.screenshot.Auxiliary
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

/**
 * 应用进程侧的 su 直连后端：经 root 管理器（Magisk/KernelSU 等）的 su
 * 直接拉起 root 宿主进程（见 [SuLauncher]），不依赖 Shizuku/Sui 存在。
 *
 * 建连流程（异步）：
 * 1. 生成不可猜测的抽象 socket 名，`su -c "CLASSPATH=<apk> app_process …"`
 *    启动 SuLauncher（stdout/stderr 重定向 /dev/null，无任何输出特征）；
 * 2. 轮询连接该 socket（上限 8 秒，覆盖 root 管理器授权弹窗的等待）；
 * 3. 成功后 [rootDisplay]（IRootDisplay 的 socket 代理）交给
 *    RootDisplayConnection 对外暴露；读线程循环接收 root->app 帧。
 *
 * 生命周期：
 * - 连接成功/死亡/失败均经 onResult 回调（SuDisplayConnection 或 null）；
 * - shutdown()：发送 destroy 帧后关闭——主动关闭不触发死亡回调
 *   （区别于意外断连：后者必须通知上层回落本地窗口）。
 */
internal class SuDisplayConnection private constructor(
    val proxy: SuRootDisplayProxy,
    private val transport: SuTransport,
    private val process: Process,
    private val onDead: () -> Unit
) {

    val rootDisplay: IRootDisplay get() = proxy

    @Volatile private var closed = false
    @Volatile private var started = false

    private val reader = Thread {
        while (!closed) {
            val frame = runCatching { transport.readFrame() }.getOrNull() ?: break
            if (frame.code == SuProto.CODE_ON_SWITCH_MEDIA && frame.payload.size >= 4) {
                val delta = DataInputStream(
                    ByteArrayInputStream(frame.payload)
                ).readInt()
                proxy.callback?.let { runCatching { it.onSwitchMedia(delta) } }
            }
            // 未知帧忽略（向前兼容）
        }
        // root 进程死亡 / su 被杀：与 Shizuku 服务死亡同样处理
        if (started && !closed) onDead()
    }

    fun start() {
        started = true
        reader.apply { name = "SuDisplayReader" }.start()
    }

    /** 主动断开（应用侧销毁悬浮窗）：通知 root 端退出并清理，不触发死亡回调。 */
    fun shutdown() {
        closed = true
        runCatching { transport.sendFrame(SuProto.CODE_DESTROY) }
        runCatching { transport.close() }
        runCatching { process.destroy() }
        // reader 因 EOF 退出；closed 已置位故不再回调 onDead
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val CONNECT_POLL_MS = 100L
        private const val PROBE_MS = 300L

        /**
         * 异步建立 su 直连。返回 false 表示立即判定不可行（无 su 二进制/
         * su 进程被立即拒绝），此时调用方应同步回退本地窗口；返回 true
         * 表示正在建立（含等待 root 授权弹窗），最终结果经 [onResult]，
         * 回调线程为内部工作线程（由 RootDisplayConnection 转主线程）。
         */
        fun connectAsync(
            context: Context,
            onResult: (SuDisplayConnection?) -> Unit
        ): Boolean {
            if (!Auxiliary.hasSuBinary()) return false
            val name = "sf.r.${Auxiliary.getRandomString(24)}"
            val apkPath = context.applicationInfo.sourceDir

            Thread {
                val proc = startRootProcess(apkPath, name)
                if (proc == null) {
                    onResult(null)
                    return@Thread
                }
                val transport = awaitTransport(name, proc)
                if (transport == null) {
                    runCatching { proc.destroy() }
                    onResult(null)
                    return@Thread
                }
                val proxy = SuRootDisplayProxy(transport)
                val conn = SuDisplayConnection(proxy, transport, proc) { onResult(null) }
                conn.start()
                onResult(conn)
            }.apply {
                isDaemon = true
                this.name = "SuDisplayConnect"
            }.start()
            return true
        }

        /**
         * 启动 root 宿主进程。先试 PATH 中的 su，再试常见绝对路径；
         * 短暂探测存活以过滤"无授权被直接拒绝"（拒绝时 su 立即退出；
         * 授权弹窗期间进程存活，继续等待）。全失败返回 null。
         */
        private fun startRootProcess(apkPath: String, socketName: String): Process? {
            val cmd = "CLASSPATH='$apkPath' exec /system/bin/app_process / " +
                    "${SuLauncher::class.java.name} $socketName"
            val candidates = listOf("su") + Auxiliary.suBinaryPaths
            for (bin in candidates) {
                val proc = runCatching {
                    ProcessBuilder(bin, "-c", cmd)
                        // 静默运行：无输出、不写任何可检测痕迹
                        .redirectOutput(File("/dev/null"))
                        .redirectError(File("/dev/null"))
                        .start()
                }.getOrNull() ?: continue
                runCatching { Thread.sleep(PROBE_MS) }
                if (proc.isAlive) return proc
                runCatching { proc.destroy() }
            }
            return null
        }

        private fun awaitTransport(name: String, proc: Process): SuTransport? {
            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                SuTransport.connect(name, 1500)?.let { return it }
                // su client 随宿主存活；退出即失败（拒绝/崩溃）
                if (!proc.isAlive) return null
                runCatching { Thread.sleep(CONNECT_POLL_MS) }
            }
            return null
        }
    }
}

/**
 * IRootDisplay 的 socket 代理（应用进程侧）。
 *
 * 方法与 SuLauncher.dispatch 的参数顺序一一对应；
 * showImage/showVideo 的 fd 经 SCM_RIGHTS 传递（内核 dup，与 binder
 * 语义一致——调用方继续拥有并负责关闭自己的副本）；
 * registerCallback 不走协议：回调仅保存在本地，供读线程派发
 * root->app 帧时使用（root 端启动时已就地注入发送代理）。
 */
internal class SuRootDisplayProxy(
    private val transport: SuTransport
) : IRootDisplay {

    @Volatile
    var callback: IRootDisplayCallback? = null

    override fun attach(x: Int, y: Int, width: Int, height: Int) =
        transport.sendFrame(1) {
            it.writeInt(x); it.writeInt(y); it.writeInt(width); it.writeInt(height)
        }

    override fun detach() = transport.sendFrame(2)

    override fun setGeometry(x: Int, y: Int, width: Int, height: Int) =
        transport.sendFrame(3) {
            it.writeInt(x); it.writeInt(y); it.writeInt(width); it.writeInt(height)
        }

    override fun setAlpha(alpha: Float) =
        transport.sendFrame(4) { it.writeFloat(alpha) }

    override fun showImage(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        transport.sendFrame(5, fds = arrayOf(fd.fileDescriptor))
    }

    override fun showVideo(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        transport.sendFrame(6, fds = arrayOf(fd.fileDescriptor))
    }

    override fun clearMedia() = transport.sendFrame(7)

    override fun scaleImage(factor: Float) =
        transport.sendFrame(8) { it.writeFloat(factor) }

    override fun panImage(dx: Float, dy: Float) =
        transport.sendFrame(9) { it.writeFloat(dx); it.writeFloat(dy) }

    override fun togglePlayPause() = transport.sendFrame(10)

    override fun seekBy(deltaMs: Int) =
        transport.sendFrame(11) { it.writeInt(deltaMs) }

    override fun setMuted(muted: Boolean) =
        transport.sendFrame(12) { it.writeBoolean(muted) }

    override fun attachControl(x: Int, y: Int, width: Int, height: Int) =
        transport.sendFrame(13) {
            it.writeInt(x); it.writeInt(y); it.writeInt(width); it.writeInt(height)
        }

    override fun detachControl() = transport.sendFrame(14)

    override fun registerCallback(cb: IRootDisplayCallback?) {
        callback = cb
    }

    override fun asBinder(): IBinder? = null
}
