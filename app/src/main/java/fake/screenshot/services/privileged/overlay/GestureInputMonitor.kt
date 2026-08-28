package fake.screenshot.services.privileged.overlay

import android.os.ParcelFileDescriptor
import android.view.MotionEvent

/**
 * 手势输入监视器：纯 Surface 方案的输入接收端（路线 B）。
 *
 * ==================== 机制 ====================
 *
 * 经 IInputManager.monitorGestureInput 在 InputDispatcher 侧创建一个
 * gesture monitor（spy window，frame 覆盖整个 display）：
 *
 * - spy window 在 dispatcher 侧 inputConfig 含 SPY 且被视作 trusted——
 *   其存在不会让下层应用收到 FLAG_WINDOW_IS_OBSCURED 系列遮挡标记，
 *   setFilterTouchesWhenObscured / block_untrusted_touches 均无从感知；
 * - 我们持有返回的 InputChannel（dup 出的 fd 副本），专用线程
 *   SOCK_SEQPACKET 读取 InputMessage 二进制并自解析（见 [InputMessageCodec]）；
 * - 每条 KEY/MOTION 立即回发 FINISHED ACK——这是路线 B 唯一无法回避的
 *   协议义务（不 ACK 触发系统级输入 ANR，属严重暴露面）；
 * - 命中悬浮窗时调用 pilferPointers：InputDispatcher 立即截断该指针流
 *   向其他窗口的后续派发（下层最多收到一个孤立 DOWN，无 UP 不构成
 *   click，且事件本身不带任何遮挡标记）——等效旧方案的可触摸控制窗口，
 *   但完全不经 WMS / 不产生任何窗口记录。
 *
 * ==================== 无痕对照 ====================
 *
 * | 暴露面                     | 状态                                          |
 * |---------------------------|-----------------------------------------------|
 * | 下层应用触摸遮挡标记        | spy window 不计入遮挡判定（trusted）           |
 * | 窗口列表 / 无障碍枚举      | 无窗口（纯 monitor + 纯 Surface layer）       |
 * | SYSTEM_ALERT_WINDOW appops | 不使用                                        |
 * | monitor 名称               | 随机字符串（Auxiliary.getRandomString）        |
 * | ANR / input 日志           | 即时 ACK，无派发超时                          |
 *
 * @param onEvent 已转好的 MotionEvent（调用线程为读线程，使用方须自行切线程）
 * @param onFatal monitor 建立失败 / 通道死亡（必须触发回落）
 */
internal class GestureInputMonitor(
    private val displayId: Int,
    private val onEvent: (MotionEvent) -> Unit,
    private val onFatal: (Throwable) -> Unit
) {

    private var monitor: Any? = null

    /** channel 的 dup 副本（读取线程持有；原 fd 归 InputMonitor 所有） */
    private var pfd: ParcelFileDescriptor? = null

    @Volatile
    private var running = false

    private var reader: Thread? = null

    // ==================== 生命周期 ====================

    /**
     * 建立手势监视。失败（服务缺失/反射异常/权限异常）返回 false，
     * 由调用方决定上报回落。
     */
    fun start(): Boolean {
        if (running) return true
        val mon = OverlayHiddenApi.createGestureMonitor(displayId)
            ?: return false.also { onFatal(IllegalStateException("monitorGestureInput reflection failed")) }
        val channel = OverlayHiddenApi.callMonitor(mon, "getInputChannel")
            ?: return false.also {
                OverlayHiddenApi.callMonitor(mon, "dispose")
                onFatal(IllegalStateException("InputMonitor.getInputChannel unavailable"))
            }
        val pfdCopy = OverlayHiddenApi.channelPfd(channel)
            ?: return false.also {
                OverlayHiddenApi.callMonitor(mon, "dispose")
                onFatal(IllegalStateException("InputChannel fd unavailable"))
            }

        monitor = mon
        pfd = pfdCopy
        running = true
        reader = Thread {
            readLoop(pfdCopy)
            if (running) {
                // 对端关闭（system 重启 / monitor 被销毁）：等同后端死亡
                running = false
                onFatal(IllegalStateException("input channel closed"))
            }
        }.apply {
            name = OverlayHiddenApi.randomName() // 线程名也随机化
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        reader?.interrupt()
        reader = null
        pfd?.let { runCatching { it.close() } }
        pfd = null
        OverlayHiddenApi.callMonitor(monitor, "dispose")
        monitor = null
    }

    /** 命中悬浮窗（DOWN 时判定）即抢占指针流，下层收不到后续事件。 */
    fun pilferPointers() {
        OverlayHiddenApi.callMonitor(monitor, "pilferPointers")
    }

    // ==================== 读取线程 ====================

    /**
     * 读取循环。
     *
     * InputChannel socket 出厂为 O_NONBLOCK。这里改为阻塞模式：
     * read() 无数据时挂起（零 CPU），stop() 关闭 fd 唤醒线程退出。
     * SOCK_SEQPACKET 保证一次 read 返回一条完整 InputMessage。
     */
    private fun readLoop(fd: ParcelFileDescriptor) {
        val rawFd = fd.fileDescriptor

        // 切换为阻塞模式（清除 O_NONBLOCK），read 挂起等待而非抛 EAGAIN
        runCatching {
            val flags = android.system.Os.fcntlInt(
                rawFd, android.system.OsConstants.F_GETFL, 0
            )
            android.system.Os.fcntlInt(
                rawFd, android.system.OsConstants.F_SETFL,
                flags and android.system.OsConstants.O_NONBLOCK.inv()
            )
        }

        java.io.FileInputStream(rawFd).use { input ->
            java.io.FileOutputStream(rawFd).use { output ->
                val buf = ByteArray(4096)
                while (running) {
                    val n = runCatching { input.read(buf) }.getOrElse { e ->
                        // 阻塞模式下不应出现 EAGAIN；若 fcntl 失败仍为非阻塞，
                        // 用短 sleep 避免 busy loop
                        if (e.message?.contains("again", ignoreCase = true) == true) {
                            runCatching { Thread.sleep(10) }
                            0
                        } else {
                            -1
                        }
                    }
                    if (n < 0) break
                    if (n == 0) continue
                    if (n < 8) continue

                    val type = InputMessageCodec.typeOf(buf, n)

                    // 先 ACK 再派发：防止手势处理阻塞触发派发超时 ANR
                    runCatching {
                        InputMessageCodec.encodeFinishedAck(buf, n)?.let { output.write(it) }
                    }

                    if (type != 1) continue // 只关心 MOTION

                    val ev = runCatching { InputMessageCodec.parseMotion(buf, n) }.getOrNull()
                        ?: continue
                    runCatching { onEvent(ev) }
                    ev.recycle()
                }
                if (running) {
                    running = false
                    onFatal(IllegalStateException("input channel closed"))
                }
            }
        }
    }

}
