package fake.screenshot.services.privileged

import android.net.LocalServerSocket
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * su 直连模式的 root 宿主进程入口。
 *
 * 由应用进程执行：
 *   su -c "CLASSPATH='<apk路径>' exec /system/bin/app_process / \
 *          fake.screenshot.services.privileged.SuLauncher <socket名>"
 *
 * 以 CLASSPATH=本应用 APK 启动的 app_process 会用 PathClassLoader 加载
 * 全部应用类（与 Shizuku UserService 从同一 APK 反射加载完全同源），
 * 因此 RootDisplayService 无需任何改动即可在本进程实例化：它自建
 * HandlerThread（ViewRootImpl 的 Looper 要求）、反射 ActivityThread.
 * systemMain() 获取 system context，addView 时以 uid=0 通过
 * INTERNAL_SYSTEM_WINDOW 校验使 TRUSTED_OVERLAY 真实生效。
 *
 * 进程生命周期：
 * - 启动后在抽象命名空间监听（名字由应用进程随机生成传入，不可猜测）；
 * - 看门狗：15 秒内未等到连接则退出（应用进程侧 8 秒已放弃，防止孤儿
 *   root 进程常驻）；
 * - 连接后进入帧派发循环，直接调用 RootDisplayService 方法（其方法与
 *   binder 线程进入时一样内部 post 到窗口线程，线程安全）；
 * - 收到 destroy 帧、对端关闭（应用进程死亡）或协议错误即退出——进程
 * 死亡时 WMS 经 binder 死亡通知自动摘除本进程窗口，不会泄漏。
 */
object SuLauncher {

    /** 无人连接时 root 进程最长存活时间（毫秒）。 */
    private const val ACCEPT_WATCHDOG_MS = 15_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val name = args.getOrNull(0) ?: return

        val server = runCatching { LocalServerSocket(name) }.getOrNull() ?: return

        val connected = AtomicBoolean(false)
        Thread {
            runCatching { Thread.sleep(ACCEPT_WATCHDOG_MS) }
            if (!connected.get()) {
                runCatching { server.close() }
                // 未等到应用连接：直接退出，不留任何进程痕迹
                Runtime.getRuntime().exit(0)
            }
        }.apply {
            isDaemon = true
            this.name = "SuAcceptWatchdog"
        }.start()

        val socket = runCatching { server.accept() }.getOrNull()
        connected.set(true)
        if (socket == null) {
            Runtime.getRuntime().exit(0)
            return
        }

        // 窗口宿主：与 Shizuku UserService 共用同一实现
        val service = RootDisplayService()

        val transport = SuTransport.accepted(socket)

        // 反向回调（root -> app）：手势判定"切换媒体"时经 socket 帧通知。
        // binder 对象无法跨 socket 序列化，故以发送代理对象就地注入
        // （与 Shizuku 路径的 registerCallback binder 代理等效）。
        service.registerCallback(SuCallbackSender(transport))

        // 帧派发循环：本线程即"协议线程"，等价于 Shizuku 路径的 binder 线程
        while (true) {
            val frame = runCatching { transport.readFrame() }.getOrNull() ?: break
            if (frame.code == SuProto.CODE_DESTROY) break
            val ok = runCatching { dispatch(service, frame) }.getOrDefault(false)
            if (!ok) break
        }

        // 进程退出即清理：WMS 摘除窗口、MediaPlayer 释放（进程级资源回收）
        runCatching { transport.close() }
        Runtime.getRuntime().exit(0)
    }

    /** 参数与 SuRootDisplayProxy 各方法的写入顺序一一对应；false 表示协议错误。 */
    private fun dispatch(service: RootDisplayService, frame: SuTransport.Frame): Boolean {
        val di = DataInputStream(ByteArrayInputStream(frame.payload))
        fun i(): Int = di.readInt()
        fun f(): Float = di.readFloat()
        fun b(): Boolean = di.readBoolean()

        when (frame.code) {
            1 -> service.attach(i(), i(), i(), i())
            2 -> service.detach()
            3 -> service.setGeometry(i(), i(), i(), i())
            4 -> service.setAlpha(f())
            5 -> {
                // SCM_RIGHTS 收到的 fd 归本进程所有：dup 成 ParcelFileDescriptor
                // 后关闭原始副本（dup 与 binder 传递语义一致）
                val fd = frame.fds.firstOrNull() ?: return false
                val pfd = ParcelFileDescriptor.dup(fd)
                android.system.Os.close(fd)
                service.showImage(pfd)
            }
            6 -> {
                val fd = frame.fds.firstOrNull() ?: return false
                val pfd = ParcelFileDescriptor.dup(fd)
                android.system.Os.close(fd)
                service.showVideo(pfd)
            }
            7 -> service.clearMedia()
            8 -> service.scaleImage(f())
            9 -> service.panImage(f(), f())
            10 -> service.togglePlayPause()
            11 -> service.seekBy(i())
            12 -> service.setMuted(b())
            13 -> service.attachControl(i(), i(), i(), i())
            14 -> service.detachControl()
            else -> {
                // 未知帧：忽略（向前兼容），不算协议错误
            }
        }
        return true
    }

    private class SuCallbackSender(
        private val transport: SuTransport
    ) : IRootDisplayCallback.Stub() {
        override fun onSwitchMedia(delta: Int) {
            runCatching {
                transport.sendFrame(SuProto.CODE_ON_SWITCH_MEDIA) { it.writeInt(delta) }
            }
        }
    }
}
