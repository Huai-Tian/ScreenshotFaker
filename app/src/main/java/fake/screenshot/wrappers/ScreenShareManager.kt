package fake.screenshot.wrappers

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
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
            try {
                val jsch = JSch()
                val session = jsch.getSession(name, address, port)
                session.setPassword(password.toByteArray(Charsets.UTF_8))
                session.setConfig("StrictHostKeyChecking", "no")
                session.connect(8000)
                // 旧连接先行断开：cp 失败等路径下 initialized 保持 false，
                // 用户重试会再次进入本分支——直接覆盖 sshSession 会让旧
                // JSch 连接（无 GC 收尾保证）驻留 TCP；连接失败分支同理
                sshSession?.disconnect()
                sshSession = session
            } catch (e: Exception) {
                sshSession?.disconnect()
                sshSession = null
                return InitResult.SshFailed(
                    e.message ?: e.javaClass.simpleName
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
            val authPassword =
                SensitiveStore.getSensitive(appContext, "screenShare_password", "")
                    .let { if (it.isEmpty()) "" else "auth_password=${shellQuote(it)}" }
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
                // 远程转发指向本机端口，server 重启后重新监听同一端口，转发持续有效
                runCatching { session.setPortForwardingR(remotePort, "127.0.0.1", localPort) }
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
            // heredoc 单引号定界：内容原样写入脚本文件，不做变量展开
            Auxiliary.exec("cat > $watchPath <<'RL_EOF'\n$script\nRL_EOF")

            // stop 交错兜底：stopScreenShare 先清 relayRunning 再清理文件；
            // 本协程的写脚本→拉起是非挂起 exec 段，取消点只能落在其后的
            // 挂起点上——若停止恰好落在该段内，此处复查可见 false，放弃
            // 拉起并清除刚写入的脚本。不复查则守护循环在 STOP 标记已被
            // 删除的状态下执行 while 循环，无限续命（"停止"后推流复活）
            if (!relayRunning) {
                Auxiliary.exec("rm -f $watchPath")
                return@launch
            }

            // 阻塞运行守护循环：用户停止或连续快速退出时返回
            Auxiliary.exec("sh $watchPath")
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
        Auxiliary.refreshShellState()
        scope.launch {
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
                lastError = if (!Auxiliary.isShellActivated && !Auxiliary.isRootActivated) {
                    context.getString(R.string.no_permission)
                } else {
                    when (initializeInternal()) {
                        is InitResult.SshFailed -> "ssh_connect_failed"
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