package fake.screenshot.services.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import fake.screenshot.Auxiliary
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用进程侧对 RootDisplayService（root 进程）的连接管理，双后端自动选择：
 *
 * - 后端 1（优先）：Shizuku UserService——Sui 或以 root 启动的 Shizuku。
 *   成熟稳定，bindUserService 即用。
 * - 后端 2（兜底）：su 直连（[SuDisplayConnection]）——仅凭 root 管理器
 *   （Magisk/KernelSU 等）对本应用的授权即可拉起 root 宿主进程，
 *   不要求设备上存在 Shizuku/Sui。有 root 而无 Shizuku 时同样获得
 *   绝对无痕窗口（uid=0 的 TRUSTED_OVERLAY）。
 *
 * 两个后端对外统一呈现 IRootDisplay 与 [Listener]：上层
 * （DisplayOverlayService / ControlOverlayService）完全不感知后端差异，
 * 连接失败/进程死亡一律经 onConnectionChanged(false) 回落本地窗口。
 *
 * - Shizuku 路径：bindUserService 同步创建、onServiceConnected 经 Shizuku
 *   主线程派发，binder 会"稍后"到达。
 * - su 路径：异步建立（含 root 管理器授权弹窗等待，上限 8 秒），
 *   结果经 handleSuResult 转主线程；generation 序号使 unbind/rebind
 *   能作废仍在途的连接请求，避免孤儿 root 进程。
 */
object RootDisplayConnection {

    private const val APPLICATION_ID = "fake.screenshot"

    // version 用于让 Shizuku 服务端区分实现版本：修改 RootDisplayService 的
    // 行为/结构后必须递增，否则服务端可能沿用旧版本缓存的类。
    // v2：新增控制窗口（attachControl/detachControl）与 registerCallback
    // （su 直连路径不经过 Shizuku，与该版本号无关）
    private val args = Shizuku.UserServiceArgs(
        ComponentName(APPLICATION_ID, RootDisplayService::class.java.name)
    )
        .processNameSuffix("display")
        .version(2)

    @Volatile
    private var service: IRootDisplay? = null

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    private var suBackend: SuDisplayConnection? = null

    @Volatile
    private var pendingSu = false

    /** unbind/rebind 时递增：作废仍在途的 su 连接请求 */
    private val generation = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())

    fun interface Listener {
        /** 回调发生在主线程。 */
        fun onConnectionChanged(active: Boolean)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (suBackend != null) {
                // 当前实际走 su 后端（Shizuku 晚到的回调）：忽略，防止覆盖
                return
            }
            service = IRootDisplay.Stub.asInterface(binder)
            isActive = service != null
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (suBackend != null) return
            service = null
            isActive = false
            notifyChanged()
        }
    }

    /** 当前可用的 root 端接口；连接不可用时返回 null（调用方走本地路径）。 */
    fun get(): IRootDisplay? = if (isActive) service else null

    /**
     * 绑定 root 托管服务（双后端）。返回 false 表示立即判定不可行
     * （Shizuku 非 root 且无 su 二进制），调用方应同步回退本地窗口；
     * 返回 true 表示已绑定或正在建立（su 异步路径），
     * 结果经 [Listener] 通知。
     */
    fun bind(context: Context): Boolean {
        if (isActive || pendingSu) return true

        // 后端 1：Sui / 以 root 运行的 Shizuku
        if (isShizukuRoot()) {
            try {
                Shizuku.bindUserService(args, connection)
                return true
            } catch (_: Throwable) {
                // binder 竞态/未授权/版本不支持：落到 su 兜底
            }
        }

        // 后端 2：root 管理器直接授权（su），无需 Shizuku/Sui 存在
        if (suBackend != null) return true
        val gen = generation.incrementAndGet()
        val pending = SuDisplayConnection.connectAsync(context) { conn ->
            mainHandler.post { handleSuResult(conn, gen) }
        }
        pendingSu = pending
        return pending
    }

    /** 解除绑定并销毁 root 端服务（双后端各自清理）。 */
    fun unbind() {
        generation.incrementAndGet() // 作废在途的 su 连接请求
        pendingSu = false
        suBackend?.let {
            suBackend = null
            it.shutdown()
        }
        runCatching {
            Shizuku.unbindUserService(args, connection, true)
        }
        service = null
        isActive = false
    }

    private fun isShizukuRoot(): Boolean = runCatching {
        Shizuku.getBinder() != null &&
                Auxiliary.isShellActivated &&
                Shizuku.getUid() == 0
    }.getOrDefault(false)

    /**
     * su 后端结果（成功连接 / 失败 / 进程死亡）统一入口，主线程执行。
     * gen 不匹配说明该请求已被 unbind/rebind 作废：直接销毁连接。
     */
    private fun handleSuResult(conn: SuDisplayConnection?, gen: Int) {
        if (gen != generation.get()) {
            conn?.shutdown()
            return
        }
        pendingSu = false
        if (conn != null) {
            suBackend = conn
            service = conn.rootDisplay
            isActive = true
            notifyChanged()
        } else {
            // 建立失败（无授权/超时）或进程死亡：走 su 后端失败通知。
            // su 是唯一在途后端（Shizuku 可用就不会走到 su），置空安全。
            suBackend = null
            service = null
            isActive = false
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        val active = isActive
        listeners.forEach { listener ->
            runCatching { listener.onConnectionChanged(active) }
        }
    }
}
