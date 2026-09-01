package fake.screenshot.wrappers

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import fake.screenshot.Auxiliary
import fake.screenshot.R
import fake.screenshot.defense.DefenseProtocol
import fake.screenshot.defense.SensitiveStore
import fake.screenshot.services.ScreenShareTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

object ScreenShareManager {
    private const val VERSION = "4.1"
    private lateinit var relayName: String
    private lateinit var relayJob: Job
    private var sshSession: Session? = null
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    var relayRunning = false
        private set

    // toggle 互斥（磁贴/页面统一入口串行化）：双击落在 initializeInternal
    // 的 SSH 连接窗口（最长 8s）内时，两次并发 init 各生成 relayName 后
    // 覆盖共享字段（r1→r2），首个 relay 协程仍以 r1 写守护脚本并拉起
    // server——停止只清理 r2，.w_r1 守护循环的 STOP 标记（.s_r1）永不
    // 存在，1s 无限续命："停止"后 server 复活并持续推流。串行化后第二次
    // toggle 等待首次完成，见到 relayRunning=true 走停止分支，清理对象
    // 与拉起对象一致。stopScreenShare 供 DefenseProtocol 销毁序列直调，
    // 不经此锁（withLock 非重入，锁内调用会死锁）；其"先清 relayRunning
    // 再清理文件"的顺序保证与本协程 setup 段的交错安全（见拉起前复查）
    private val toggleMutex = Mutex()

    // 胁迫销毁闩锁（进程生命周期内不复位）：销毁序列开始（任何步骤之前）
    // 置位。封堵销毁↔磁贴启动 TOCTOU：relay 协程在挂起点间已快照完
    // 旧凭据/配置，销毁若落在"拉起前复查已通过→exec 拉起"之后或
    // stopScreenShare 的 pkill/rm 因 Shizuku 断连失效时，协程会以
    // 销毁前的密码拉起 server 继续推流（隐私在"已销毁"后持续泄露）。
    // 闩锁在 relay 协程的拉起前复查点强制短路（先于闩锁置位通过复查、
    // 其后 exec 与销毁并发的微小残余窗口由销毁步骤 1/2 的清理兜底）。
    // 不复位是刻意语义：销毁已清空全部凭据与配置，此后本进程内的共享
    // 启动一律 fail-closed（重启 app 后自然恢复）
    @Volatile
    private var coercionDestroyed = false

    /** DefenseProtocol 销毁序列第一动作：置闩锁（先于停共享/擦密钥） */
    fun markCoercionDestroyed() {
        coercionDestroyed = true
    }

    @Volatile
    var lastError: String? = null
        private set

    private val tileListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addTileListener(listener: () -> Unit) {
        tileListeners.add(listener)
        listener()
    }

    fun removeTileListener(listener: () -> Unit) {
        tileListeners.remove(listener)
    }

    private fun notifyStateChanged() {
        tileListeners.forEach { runCatching(it) }
        if (::appContext.isInitialized) {
            runCatching {
                TileService.requestListeningState(
                    appContext,
                    ComponentName(appContext, ScreenShareTileService::class.java)
                )
            }
        }
    }

    private sealed interface InitResult {
        data object Ok : InitResult
        data class SshFailed(val reason: String) : InitResult
        data class CopyFailed(val reason: String) : InitResult
    }


    private suspend fun initializeInternal(): InitResult {
        if (initialized) return InitResult.Ok
        if (ConfigManager.getDataOnce(appContext, "ssh_tunnel_enabled", false)) {
            // SSH 凭据经 DK 第二层加密存储（防 root-as-uid 读 DataStore 提取）。
            // DK 拆分激活且未解锁（磁贴直接触发共享）→ 凭据不可得 → SSH 连接
            // 失败返回 SshFailed（fail-closed：解锁一次即恢复；直连模式不受影响）
            val address =
                SensitiveStore.getSensitive(appContext, "ssh_tunnel_server_address", "127.0.0.1")
            val port = ConfigManager.getDataOnce(appContext, "ssh_tunnel_server_port", 22)
            val name = SensitiveStore.getSensitive(
                appContext, "ssh_tunnel_user_name",
                "ScreenshotFaker"
            )
            val password = SensitiveStore.getSensitive(
                appContext, "ssh_tunnel_user_password",
                "ScreenshotFaker"
            )
            // —— 主机密钥 TOFU（Trust On First Use）——
            // 旧实现 StrictHostKeyChecking=no 对任意主机密钥放行：局域网/
            // 网络路径上的 MITM 可伪装 SSH 服务器，截获凭据并把远程转发
            // 指向自己 → 屏幕流实时泄露。TOFU：首次连接记录主机密钥指纹
            //（SHA-256，SensitiveStore DK 加密，按 host:port 隔离），此后
            // 指纹变化即拒绝连接（服务器换钥需在设置页显式重置）
            val hostKeyStoreKey = SensitiveStore.sshHostKeyStoreKey(address, port)
            // fail-closed：指纹已固定（_sec 密文存在）但本会话不可解（锁定态）
            // → 拒绝连接。若放行：解不出已固定指纹 → 退化为"首次连接"→
            // 采纳 MITM 密钥。凭据在锁定态本就取不到（错误默认值），正常
            // 服务器认证必败，唯一能"连上"的是来者不拒的 MITM——恰是要防的
            if (SensitiveStore.isSensitiveConfigured(appContext, hostKeyStoreKey) &&
                SensitiveStore.getSensitive(appContext, hostKeyStoreKey, "").isEmpty()
            ) {
                return InitResult.SshFailed("locked_no_credentials")
            }
            val storedFingerprint =
                SensitiveStore.getSensitive(appContext, hostKeyStoreKey, "")
            // check() 在 JSch 连接线程上同步回调（非挂起上下文）：已固定指纹
            // 预读到局部量，连接成功后再回写采纳的指纹（putSensitive 是挂起函数）
            var observedFingerprint: String? = null
            val hostKeyRepository = object : HostKeyRepository {
                override fun check(host: String, key: ByteArray): Int {
                    val fingerprint = Auxiliary.sha256Hex(key)
                    // 摘要失败 = 无法判定 = 拒绝（fail-closed，不盲信）
                    if (fingerprint.isEmpty()) return HostKeyRepository.CHANGED
                    observedFingerprint = fingerprint
                    return if (storedFingerprint.isEmpty() ||
                        storedFingerprint == fingerprint
                    ) HostKeyRepository.OK else HostKeyRepository.CHANGED
                }

                // 采纳/持久化由本函数在连接成功后统一处理（回调在非挂起线程）
                override fun add(hostkey: HostKey?, userinfo: UserInfo?) {}
                override fun remove(host: String?, type: String?) {}
                override fun remove(host: String?, type: String?, key: ByteArray?) {}
                override fun getKnownHostsRepositoryID(): String = "tofu"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> =
                    emptyArray()
            }
            try {
                val jsch = JSch()
                val session = jsch.getSession(name, address, port)
                session.setPassword(password.toByteArray(Charsets.UTF_8))
                session.setHostKeyRepository(hostKeyRepository)
                // "yes"：check 返回 OK 才放行；CHANGED（指纹变化 = MITM/换钥）
                // 抛异常终止连接。绝不可用 "no"——其语义对 CHANGED 也自动
                // 接受并覆盖，TOFU 校验形同虚设
                session.setConfig("StrictHostKeyChecking", "yes")
                session.connect(8000)
                // 首次连接（TOFU 采纳）或指纹不变：持久化当前指纹。
                // mismatch 时 connect 必已抛异常，不会走到这里
                observedFingerprint?.let { fingerprint ->
                    if (fingerprint != storedFingerprint) {
                        SensitiveStore.putSensitive(appContext, hostKeyStoreKey, fingerprint)
                    }
                }
                // 旧连接先行断开：cp 失败等路径下 initialized 保持 false，
                // 用户重试会再次进入本分支——直接覆盖 sshSession 会让旧
                // JSch 连接（无 GC 收尾保证）驻留 TCP；连接失败分支同理
                sshSession?.disconnect()
                sshSession = session
            } catch (e: Exception) {
                sshSession?.disconnect()
                sshSession = null
                return InitResult.SshFailed(
                    // 已有固定指纹且本轮观测不同 = 主机密钥变化（MITM/服务器
                    // 换钥），与普通连接失败区分供磁贴副标题反馈
                    if (storedFingerprint.isNotEmpty() &&
                        observedFingerprint != null &&
                        observedFingerprint != storedFingerprint
                    ) "ssh_hostkey_changed" else (e.message ?: e.javaClass.simpleName)
                )
            }
        }
        relayName = Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(20..35))
        val src = "${appContext.applicationInfo.nativeLibraryDir}/libextsvr.so"
        val (exitCode, output) = Auxiliary.exec("cp $src /data/local/tmp/$relayName")
        if (exitCode != 0) {
            return InitResult.CopyFailed(output.take(80))
        }
        initialized = true
        return InitResult.Ok
    }

    private fun startScreenShareInternal(): Boolean {
        // 闩锁双保险（toggle 入口已拦，防未来新增直调路径绕过）
        if (coercionDestroyed) return false
        if (!(initialized && (Auxiliary.isShellActivated || Auxiliary.isRootActivated))) return false
        if (relayRunning) return true
        // 先立标志再启动协程：协程体拉起前的 relayRunning 复查依赖标志
        // 已就位（launch 后置存在体先跑的理论窗口）
        relayRunning = true
        relayJob = scope.launch {
            // 会话名快照：本协程的守护脚本/停止标记与自身会话绑定——
            // 共享 relayName 被后续会话覆盖的极端交错下，本会话自引用
            // 仍一致，停止清理不脱钩
            val sessionName = relayName
            // 新会话清除上一会话的错误残留（server_exited_repeatedly 等
            // 不应显示到下一次启动失败为止）
            lastError = null
            val localPort = ConfigManager.getDataOnce(appContext, "screenShare_port", 2345)
            val enableControl = ConfigManager.getDataOnce(appContext, "screenShare_control", true)
                .let { "control=$it" }
            val syncClipboard =
                ConfigManager.getDataOnce(appContext, "screenShare_sync_clipboard", true)
                    .let { "clipboard_autosync=$it" }
            val enableVideo =
                ConfigManager.getDataOnce(appContext, "screenShare_video", true).let { "video=$it" }
            val videoDisplay =
                ConfigManager.getDataOnce(appContext, "screenShare_video_display", true)
                    .let { if (it) "video_source=display" else "" }
            // display_id/camera_id/camera_zoom 为用户自由文本，最终经 sh 执行
            //（守护脚本/daemon 侧 sh -c）——校验防元字符，非法值按未配置处理
            //（与磁贴 displayID/bitrate/resolution 的校验同语义）
            val videoDisplayID =
                ConfigManager.getDataOnce(appContext, "screenShare_video_display_id", "")
                    .let {
                        if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "display_id=$it"
                    }
            val videoCamera =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera", false)
                    .let { if (it) "video_source=camera" else "" }
            val videoCameraID =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera_id", "0")
                    .let { if (Auxiliary.isConfigValid(it)) "camera_id=$it" else "" }
            val videoCameraZoom =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera_zoom", "")
                    .let {
                        if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "camera_zoom=$it"
                    }
            val videoCameraTorch =
                ConfigManager.getDataOnce(appContext, "screenShare_video_camera_torch", false)
                    .let { "camera_torch=$it" }
            // 限制分辨率/帧率，降低编码与传输延迟（0 表示不限制）
            val maxSize = ConfigManager.getDataOnce(appContext, "screenShare_max_size", 1280)
                .let { if (it > 0) "max_size=$it" else "" }
            val maxFps = ConfigManager.getDataOnce(appContext, "screenShare_max_fps", 60)
                .let { if (it > 0) "max_fps=$it" else "" }
            // 视频比特率：过高会加大编码与传输延迟，0 表示使用 server 默认值（8Mbps）
            val videoBitRate = ConfigManager.getDataOnce(appContext, "screenShare_video_bit_rate", 4000000)
                .let { if (it > 0) "video_bit_rate=$it" else "" }
            val enableAudio =
                ConfigManager.getDataOnce(appContext, "screenShare_audio", true).let { "audio=$it" }
            // 音频源必须用 playback（AudioPolicy 播放捕获）而非 output（REMOTE_SUBMIX）：
            // - output：音频被路由进 submix，发送端设备静音；且注册 submix 时系统
            //   重配音频路由，部分设备（OPPO 等）会把媒体音量重置为固定值；
            //   audio_dup 参数对该模式完全不生效
            // - playback + audio_dup=true：ROUTE_FLAG_LOOP_BACK_RENDER，设备继续
            //   外放的同时复制一份音频流到捕获，音量不受影响（双端都有声音）
            val audioDup = "audio_dup=true"
            // 音频编码用 raw（PCM 直传）而非 opus：实测部分接收设备的 Opus 解码器
            // 组件启动后立即进入错误态（queueInputBuffer 抛 IllegalStateException），
            // raw 无需解码，接收端 PCM 直接写 AudioTrack，彻底绕开解码器兼容性问题。
            // 代价是带宽 ~1.5Mbps（48kHz 立体声 16bit），局域网/SSH 隧道均可承受。
            val audioCodec = "audio_codec=raw"
            val audioOutput =
                ConfigManager.getDataOnce(appContext, "screenShare_audio_output", true)
                    .let { if (it) "audio_source=playback" else "" }
            val audioMic = ConfigManager.getDataOnce(appContext, "screenShare_audio_mic", false)
                .let { if (it) "audio_source=mic" else "" }
            val tcpLocalOnly =
                if (sshSession != null) "tcp_local_only=true" else ""
            // 共享密码经环境变量递交（server 侧 auth_password_env=VAR）：
            // 不进 argv/cmdline（对 shell/root 可见）、不进守护脚本明文
            // （脚本仅含变量名引用，由启动时刻的 env 注入值）
            val authPassword =
                SensitiveStore.getSensitive(appContext, "screenShare_password", "")
                    .let { if (it.isEmpty()) "" else "auth_password_env=SF_SHARE_PWD" }
            // fail-closed：共享密码已配置（_sec 密文存在）但本会话不可解
            // （锁定态，DK 未组装）→ 中止启动。静默降级为无认证共享等于
            // 把认证控制 fail-open 给任何接收者。此时 server 尚未拉起，
            // 复位状态并通知磁贴即可
            if (authPassword.isEmpty() &&
                SensitiveStore.isSensitiveConfigured(appContext, "screenShare_password")
            ) {
                relayRunning = false
                initialized = false
                sshSession?.disconnect()
                sshSession = null
                lastError = "locked_no_credentials"
                notifyStateChanged()
                return@launch
            }
            // 入口类用中性名 vendor.entry.Main：ps/pgrep 的进程 cmdline
            // 不暴露 app 身份（fake.screenshot）与功能提示，内部实现包名亦不出现
            val base =
                "CLASSPATH=/data/local/tmp/$sessionName app_process / vendor.entry.Main $VERSION tunnel_forward=true tcp_port=$localPort"
            val args = listOf(
                base,
                enableControl,
                syncClipboard,
                enableVideo,
                videoDisplay,
                videoDisplayID,
                videoCamera,
                videoCameraID,
                videoCameraZoom,
                videoCameraTorch,
                maxSize,
                maxFps,
                videoBitRate,
                enableAudio,
                audioDup,
                audioCodec,
                audioOutput,
                audioMic,
                tcpLocalOnly,
                authPassword
            ).filter { it.isNotEmpty() }

            sshSession?.let { session ->
                val configuredRemotePort =
                    ConfigManager.getDataOnce(appContext, "ssh_tunnel_remote_port", 0)
                val remotePort =
                    if (configuredRemotePort in 1024..65535) configuredRemotePort else localPort
                // 远程转发指向本机端口，server 重启后重新监听同一端口，转发持续有效。
                // 失败（端口被占/服务器拒绝）时 server 照常启动但共享不可达——
                // 记入 lastError 供磁贴副标题反馈，不再静默
                runCatching { session.setPortForwardingR(remotePort, "127.0.0.1", localPort) }
                    .onFailure { lastError = "ssh_forward_failed" }
            }

            // 清理残留：app 进程被杀后重启时，上一会话的守护循环与 server
            // 仍在系统里运行（守护循环独立于 app 进程），必须先清理再启动，
            // 否则新 server 会因端口被占用而启动失败
            Auxiliary.exec(
                "pkill -f /data/local/tmp/.w_ ; pkill -INT -f vendor.entry.Main; sleep 1; " +
                        "pkill -KILL -f vendor.entry.Main; " +
                        "rm -f /data/local/tmp/.s_* /data/local/tmp/.w_*.sh"
            )

            // 守护循环：接收端断开（锁屏/切后台/网络波动）会使 server 进程退出，
            // 由 sh 循环在 1s 后自动重新拉起。放在独立 shell 进程中运行而不是
            // Kotlin 协程：发送端 app 退到后台被冻结/被杀时重启依然继续工作。
            // 连续 3 次快速退出（<30s）说明 server 无法正常启动（端口占用等），放弃。
            // server 输出重定向 /dev/null：防止 stdout 管道写满阻塞 server。
            val serverCmd = args.joinToString(" ")
            // 标记/脚本文件名带随机后缀且以 . 开头（ls 默认不可见），
            // 不含任何工具特征字样
            val stopFlag = "/data/local/tmp/.s_$sessionName"
            val watchPath = "/data/local/tmp/.w_$sessionName.sh"
            // 密码值：经 env 注入守护 sh，再传给 server 进程（auth_password_env
            // 消费）。脚本内容只含变量名引用，明文不落盘、不进 argv/cmdline
            val passwordValue =
                if (authPassword.isEmpty()) ""
                else SensitiveStore.getSensitive(appContext, "screenShare_password", "")
            // $$ 前缀（multi-dollar interpolation，Kotlin 2.2+ 默认启用）：
            // 前缀字符串内单 $ 为字面量、$$ 才触发 Kotlin 插值。
            // 仅 $标识符 形式（$STOP/$d/$n）需要前缀；$ 后跟标点
            // （$(date)、$((e - s))）在普通字符串中本就是字面量，无需前缀
            val script = listOf(
                "STOP=$stopFlag",
                $$"rm -f \"$STOP\"",
                "n=0",
                $$"while [ ! -f \"$STOP\" ]; do",
                "  s=$(date +%s)",
                "  $serverCmd >/dev/null 2>&1",
                "  e=$(date +%s)",
                "  d=$((e - s))",
                $$"  if [ $d -ge 30 ]; then n=0; else n=$((n + 1)); fi",
                $$"  if [ $n -ge 3 ]; then break; fi",
                "  sleep 1",
                "done",
                $$"rm -f \"$STOP\" \"$$watchPath\" 2>/dev/null"
            ).joinToString("\n")
            // heredoc 单引号定界：内容原样写入脚本文件，不做变量展开；
            // 随即 chmod 600 收紧（cat 创建默认 644，脚本虽不含明文密码，
            // 仍无理由对同 uid 其他进程可读）
            Auxiliary.exec(
                "cat > $watchPath <<'RL_EOF'\n$script\nRL_EOF\nchmod 600 $watchPath"
            )

            // stop 交错兜底：stopScreenShare 先清 relayRunning 再清理文件；
            // 本协程的写脚本→拉起是非挂起 exec 段，取消点只能落在其后的
            // 挂起点上——若停止恰好落在该段内，此处复查可见 false，放弃
            // 拉起并清除刚写入的脚本。不复查则守护循环在 STOP 标记已被
            // 删除的状态下执行 while 循环，无限续命（"停止"后推流复活）。
            // coercionDestroyed：销毁序列已启动（哪怕步骤 1 的 pkill 因
            // 特权断连失效）——本协程持的是销毁前快照的旧凭据，拉起 =
            // "已销毁"后旧密码继续推流，必须放弃
            if (!relayRunning || coercionDestroyed) {
                Auxiliary.exec("rm -f $watchPath")
                return@launch
            }

            // 阻塞运行守护循环：用户停止或连续快速退出时返回。
            // 密码经 env（SF_SHARE_PWD）注入守护 sh，脚本内 serverCmd 展开
            // 时传递给 app_process（cmdline 只含变量名，env 不进 cmdline）
            val runCmd = if (passwordValue.isEmpty()) "sh $watchPath"
            else "SF_SHARE_PWD=${shellQuote(passwordValue)} sh $watchPath"
            val (execExitCode, _) = Auxiliary.exec(runCmd)
            if (execExitCode == -1) {
                // exec 超时放弃等待（守护脚本孤儿化独立存活是刻意设计）：
                // 共享实际仍在进行。保持 relayRunning 与 initialized 不变
                // （会话仍活着），磁贴维持激活态。若误置 false + 报错：
                // 磁贴熄灭显示"server_exited_repeatedly"，用户合理推断
                // 共享已停止而屏幕流实际仍在传输——恰是本项目全链路
                // 贯彻的"杜绝虚假安全感"要消灭的状态。状态在下一次
                // toggle 时由 isServerActuallyRunning() 收敛（在跑 → 停止
                // 分支；已死 → 启动分支）；胁迫销毁路径不受影响（闩锁 +
                // stopScreenShare 的 pkill/rm 与本协程是否在等待无关）
                notifyStateChanged()
                return@launch
            }
            // 守护脚本正常退出（STOP 标记 / 连续 3 次快速退出）
            if (relayRunning) {
                lastError = "server_exited_repeatedly"
            }
            relayRunning = false
            initialized = false
            notifyStateChanged()
        }
        return true
    }

    /**
     * 磁贴/页面统一入口：异步初始化并启动/停止共享。
     * 可安全地在主线程调用；失败原因写入 [lastError] 并刷新磁贴副标题。
     *
     * 停止判定不能只依赖 [relayRunning]：发送端 app 退到后台被系统冻结/杀死后
     * 进程重启，标志位归零，但 scrcpy server 与守护循环是独立 shell 进程仍在运行。
     * 此时第一次点击会走"启动"分支（表现为重新拉起共享、磁贴无反应），
     * 第二次才真正停止。因此标志位为 false 时先用 pgrep 探测实际进程状态。
     */
    fun toggleScreenShare(context: Context) {
        appContext = context.applicationContext
        // 磁贴可能是冷启动进程的第一个入口（重启后未打开过 app 即点磁贴）：
        // KeyVault/DaemonManager 的 context 是 lateinit，未初始化时
        // SensitiveStore→KeyVault 路径直接抛 UninitializedPropertyAccessException。
        // 与 Screenshot/ScreenRecord 磁贴的初始化保持一致
        DaemonManager.init(context)
        DefenseProtocol.init(context)
        scope.launch {
            // refreshShellState 调 exec("command -v su")（binder 阻塞时可感知
            // 卡顿）——挪进 IO 协程避免主线程 ANR（之前在 launch 之外同步执行）
            Auxiliary.refreshShellState()
            toggleMutex.withLock {
                if (relayRunning || isServerActuallyRunning()) {
                    // daemon 管理的共享（日志触发启动）经加密信道停止：app 侧
                    // pkill 杀不掉 supervisor 守护的 server（1s 内重启），不通知
                    // 则推流继续而用户以为已停止（隐私持续泄露）。daemon 不在线
                    // 时秒级失败，继续常规清理，无害
                    runCatching { DaemonManager.stopDaemonManagedShare() }
                    stopScreenShare()
                    notifyStateChanged()
                    return@launch
                }
                // 销毁闩锁：本进程已执行过胁迫销毁（凭据/配置已全部擦除）
                // → 拒绝新会话（fail-closed，见闩锁注释；重启 app 后恢复）
                lastError = if (coercionDestroyed) {
                    "destroyed"
                } else if (!Auxiliary.isShellActivated && !Auxiliary.isRootActivated) {
                    context.getString(R.string.no_permission)
                } else {
                    when (val result = initializeInternal()) {
                        is InitResult.SshFailed -> when (result.reason) {
                            // 主机密钥变化（MITM/服务器换钥）：提示用户到 SSH 设置
                            // 核对指纹，确认换钥后显式重置再重连——绝不自动采纳
                            "ssh_hostkey_changed" -> "ssh_hostkey_changed"
                            // 锁定态无凭据/无已固定指纹（DK 未组装，fail-closed）
                            "locked_no_credentials" -> "locked_no_credentials"
                            else -> "ssh_connect_failed"
                        }
                        is InitResult.CopyFailed -> "copy_server_failed"
                        InitResult.Ok -> {
                            if (startScreenShareInternal()) null
                            else context.getString(R.string.initialize_failed)
                        }
                    }
                }
                notifyStateChanged()
            }
        }
    }

    /** server 进程是否实际在运行（app 进程重启后标志位丢失时以此为准） */
    private fun isServerActuallyRunning(): Boolean =
        Auxiliary.exec("pgrep -f vendor.entry.Main").first == 0

    fun stopScreenShare() {
        // 先清标志再杀进程，确保守护循环不会在杀进程的间隙重新拉起 server
        relayRunning = false
        if (::relayName.isInitialized) {
            val stopFlag = "/data/local/tmp/.s_$relayName"
            val watchPath = "/data/local/tmp/.w_$relayName.sh"
            // 1) 写停止标记：守护循环醒来后退出，不再重启 server
            Auxiliary.exec("touch $stopFlag")
            // 2) 杀 server 进程。注意：CLASSPATH 是环境变量，不会出现在进程 cmdline 中，
            //    必须按 app_process 的实际命令行（含入口类名）匹配。
            //    先 SIGINT 让 server 走 CleanUp 正常收尾，1s 后仍存活则 SIGKILL 兜底
            Auxiliary.exec(
                "pkill -INT -f vendor.entry.Main; sleep 1; pkill -KILL -f vendor.entry.Main"
            )
            // 3) 兜底杀守护 sh（停止标记因异常未生效时），并清理脚本与标记文件
            Auxiliary.exec("pkill -f $watchPath; rm -f $stopFlag $watchPath")
        } else {
            // app 进程被杀重启后名称已丢失：按通配模式清理所有守护脚本与 server。
            // 守护循环用固定 $STOP 文件名判断退出，脚本被杀即不再拉起，标记文件可删
            Auxiliary.exec(
                "pkill -f /data/local/tmp/.w_ ; pkill -INT -f vendor.entry.Main; sleep 1; " +
                        "pkill -KILL -f vendor.entry.Main; " +
                        "rm -f /data/local/tmp/.s_* /data/local/tmp/.w_*.sh"
            )
        }
        if (::relayJob.isInitialized) {
            relayJob.cancel()
        }
        sshSession?.disconnect()
        sshSession = null
        initialized = false
    }

    /** sh 安全引用：单引号包裹，内部单引号转义为 '\'' */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}