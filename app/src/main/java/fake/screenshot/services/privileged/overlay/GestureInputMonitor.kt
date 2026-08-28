package fake.screenshot.services.privileged.overlay

import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import java.io.FileDescriptor

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
 * ==================== fd 生命周期（单点关闭） ====================
 *
 * 旧实现存在 fd 双重 close 竞态（既有问题）：stop() 关闭 pfd 唤醒阻塞
 * 读后，readLoop 嵌套 use{} 的 FileInputStream / FileOutputStream 又各
 * close 一次同一 fd 号——窗口期内 fd 号若被 binder/media 复用，会误关
 * 无关 fd（症状为随机 binder 死亡 / 媒体异常）。现行方案：
 *
 * - start() 经 ParcelFileDescriptor.detachFd() 取出 raw fd（返回 Int，
 *   原始 pfd 自此成为空壳，close 无副作用），随即 adoptFd(int) 重新
 *   包装为新的 ParcelFileDescriptor——它是该 fd 的唯一所有者，close
 *   仅由读线程收尾执行一次；
 * - stop() 以 Os.shutdown(SHUT_RDWR) 唤醒阻塞的 read（断开 socket 使
 *   read 立即返回 EOF，但不释放 fd 号——对复用中的 fd 无任何影响）；
 * - shutdown（stop 线程）与 close（读线程）在 fdLock 上互斥：读线程
 *   close 前先在同一锁内置空 ownerPfd，stop 线程拿到的引用必然尚未
 *   关闭；close 在全路径（正常退出 / 唤醒退出 / 异常退出）恰好执行一次。
 *
 * ==================== 无痕对照 ====================
 *
 * | 暴露面                     | 状态                                          |
 * |---------------------------|-----------------------------------------------|
 * | 下层应用触摸遮挡标记        | spy window 不计入遮挡判定（trusted）           |
 * | 窗口列表 / 无障碍枚举      | 无窗口（纯 monitor + 纯 Surface layer）       |
 * | SYSTEM_ALERT_WINDOW appops | 不使用                                        |
 * | monitor / 线程名           | 随机字符串（SecureRandom，见 OverlayHiddenApi）|
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

    /**
     * channel fd 的唯一所有者是读线程（start 时经 detachFd + adoptFd
     * 重新包装取得）。本字段仅供 stop() 做 shutdown 唤醒协调，配合
     * [fdLock] 串行化 "shutdown（stop 线程）" 与 "close（读线程）"。
     */
    @Volatile
    private var ownerPfd: ParcelFileDescriptor? = null

    private val fdLock = Any()

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

        // fd 所有权移交读线程：detachFd 返回 raw fd 号（Int）且 pfdCopy
        // 自此成为空壳（close 无副作用）；adoptFd 重新包装为新的 pfd，
        // 它是该 fd 的唯一所有者，仅由 readLoop 收尾 close 一次（见类
        // 文档 "fd 生命周期"一节）
        val pfd = ParcelFileDescriptor.adoptFd(pfdCopy.detachFd())

        monitor = mon
        synchronized(fdLock) { ownerPfd = pfd }
        running = true
        reader = Thread {
            readLoop(pfd)
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
        // 唤醒阻塞的 read：shutdown 使 socket 断开、read 立即返回 EOF，
        // 读线程随后自行退出并独占关闭 fd。不 close（close 权在读线程）。
        // 持锁执行保证 fd 尚未被读线程关闭（读线程 close 前先在同锁内
        // 置空本字段）；shutdown 不释放 fd 号，故无论时序如何都不会
        // 触及复用中的 fd。
        synchronized(fdLock) {
            ownerPfd?.let { pfd ->
                runCatching {
                    android.system.Os.shutdown(
                        pfd.fileDescriptor,
                        android.system.OsConstants.SHUT_RDWR
                    )
                }
            }
        }
        reader = null
        OverlayHiddenApi.callMonitor(monitor, "dispose")
        monitor = null
    }

    /** 命中悬浮窗（DOWN 时判定）即抢占指针流，下层收不到后续事件。 */
    fun pilferPointers() {
        OverlayHiddenApi.callMonitor(monitor, "pilferPointers")
    }

    // ==================== 读取线程 ====================

    /**
     * 读取循环（[pfd] 为该 fd 的唯一所有者）。
     *
     * InputChannel socket 出厂为 O_NONBLOCK。这里改为阻塞模式：read()
     * 无数据时挂起（零 CPU），stop() 的 shutdown 唤醒。SOCK_SEQPACKET
     * 保证一次 read 返回一条完整 InputMessage。
     *
     * 读写直接经 Os.read / Os.write（作用于 pfd.fileDescriptor）：不经
     * FileInputStream / FileOutputStream 包装（二者的 close 均会关闭底层
     * fd，与收尾的独占关闭叠加构成双重 close——旧实现的既有缺陷之一）。
     */
    private fun readLoop(pfd: ParcelFileDescriptor) {
        val fd: FileDescriptor = pfd.fileDescriptor
        // 切换为阻塞模式（清除 O_NONBLOCK），read 挂起等待而非抛 EAGAIN
        runCatching {
            val flags = android.system.Os.fcntlInt(
                fd, android.system.OsConstants.F_GETFL, 0
            )
            android.system.Os.fcntlInt(
                fd, android.system.OsConstants.F_SETFL,
                flags and android.system.OsConstants.O_NONBLOCK.inv()
            )
        }

        // 单条 MOTION 最大尺寸：header(168) + 32 指针 × 144B ≈ 4.8KB，
        // 8KB 覆盖全版本布局（含 Gen4 扩展）与未来余量
        val buf = ByteArray(8192)
        try {
            while (running) {
                val n = try {
                    android.system.Os.read(fd, buf, 0, buf.size)
                } catch (e: android.system.ErrnoException) {
                    when (e.errno) {
                        // 阻塞模式下不应出现 EAGAIN（fcntl 失败仍为非阻塞
                        // 的兜底，短 sleep 避免 busy loop）；EINTR 为信号
                        // 打断，重试即可
                        android.system.OsConstants.EAGAIN -> {
                            runCatching { Thread.sleep(10) }
                            continue
                        }

                        android.system.OsConstants.EINTR -> continue

                        else -> -1 // 不可恢复错误：等同通道死亡
                    }
                }
                // 注意语义差异：Os.read 对端关闭返回 0（EOF），
                // 非 FileInputStream 的 -1
                if (n <= 0) break
                if (n < 8) continue

                val type = InputMessageCodec.typeOf(buf, n)

                // 先 ACK 再派发：防止手势处理阻塞触发派发超时 ANR
                runCatching {
                    InputMessageCodec.encodeFinishedAck(buf, n)?.let { ack ->
                        android.system.Os.write(fd, ack, 0, ack.size)
                    }
                }

                if (type != 1) continue // 只关心 MOTION

                val ev = runCatching { InputMessageCodec.parseMotion(buf, n) }.getOrNull()
                    ?: continue
                runCatching { onEvent(ev) }
                ev.recycle()
            }
        } finally {
            // fd 独占关闭（pfd.close，全路径唯一 close 点），与 stop()
            // 的 shutdown 同锁互斥。仅当字段仍指向本 pfd 时才置空——本
            // 实例停止后若复用本类（start 写入新 pfd），不得误清后续
            // 实例的引用
            synchronized(fdLock) {
                if (ownerPfd === pfd) ownerPfd = null
                runCatching { pfd.close() }
            }
        }
    }

}
