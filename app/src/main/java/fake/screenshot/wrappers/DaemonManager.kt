package fake.screenshot.wrappers

import android.content.Context
import android.os.Environment
import fake.screenshot.Auxiliary
import fake.screenshot.defense.KeyVault
import fake.screenshot.defense.SensitiveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.milliseconds

object DaemonManager {
    private const val VERSION = "4.1"
    private lateinit var appContext: Context
    private val mutex = Mutex()

    /** sh 安全引用：单引号包裹，内部单引号转义为 '\''（与 ScreenShareManager 一致） */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // 缓存密钥（DK 由 KeyVault 经 Keystore 包裹管理，进程内复用）。
    // @Volatile：胁迫销毁序列（DefenseProtocol 步骤 5，IO 线程）清空缓存
    // 与其他线程 getKey() 之间需要 happens-before——否则旧信道密钥可能
    // 跨线程可见残留（同文件 lastRenewAtMillis 已加，此处此前遗漏）
    @Volatile
    private var cachedKey: SecretKeySpec? = null

    // daemon 续期节流：touch 高频调用（10s 心跳），socket 往返约 1 次/分钟足够
    @Volatile
    private var lastRenewAtMillis = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private suspend fun getPort(): Int {
        return ConfigManager.getDataOnce(
            appContext,
            "daemon_socket_port",
            1234
        )
    }

    /** 可空：DK 拆分激活且本会话未解锁组装时无密钥可用（fail-closed） */
    private fun getKey(): SecretKeySpec? =
        cachedKey ?: KeyVault.getDaemonKeyOrNull()?.also { cachedKey = it }

    /** 胁迫销毁后清信道密钥缓存：后续操作走重新生成的 DK，不复用已销毁密钥 */
    fun clearCachedKey() {
        cachedKey = null
    }

    suspend fun startDaemon(): Boolean = mutex.withLock {
        if (isDaemonRunning()) {
            // 已运行的实例也补发一次 config：上次启动若 2s 探测超时，
            // syncConfig 从未送达——daemon 侧看门狗的死线引爆以
            // config_synced 为前提（陈旧锚点宽限），config 补发即时
            // 重置其锚点死线。幂等无害（daemon 侧 config 处理可重入）
            syncConfig()
            return true
        }

        val port = getPort()

        // 检查端口是否被其他进程占用（仅连接测试，不发送数据）
        try {
            Socket("127.0.0.1", port).use {
                // 能连接说明端口被占用，且不是我们的守护进程（因为 isDaemonRunning 已返回 false）
                return false
            }
        } catch (_: Exception) {
            // 连接失败，端口空闲，继续启动
        }

        withContext(Dispatchers.IO) {
            // 密钥经 stdin 递交（不经 argv，避免 cmdline 泄露），
            // 命令行仅含二进制路径与端口。
            // DK 拆分激活且未组装 → 无密钥 → 启动失败（fail-closed：
            // 解锁一次即可恢复，绝不在无密钥状态下给出半可用语义）
            val key = getKey() ?: return@withContext false
            // 库名中性化（隐蔽性）：daemon 就地运行时路径进入 cmdline（ps 可见）
            val daemonPath = "${appContext.applicationInfo.nativeLibraryDir}/libnetsvc.so"
            val (exitCode, _) = Auxiliary.execWithStdin("$daemonPath $port", key.encoded)
            if (exitCode != 0) {
                return@withContext false
            }
        }

        // 等待守护进程启动，最多重试20次（每次间隔100ms，共2秒）
        repeat(20) {
            if (isDaemonRunning()) {
                syncConfig()
                return true
            }
            delay(100.milliseconds)
        }
        return false
    }

    /**
     * daemon 兜底探测/杀进程模式（不依赖加密信道，不依赖端口）。
     * daemon 启动后会把端口从 argv 内存擦除（memset argv[1]，防 cmdline
     * 泄露），因此模式不能含端口：
     * - 自拷贝体：cmdline = "/data/local/tmp/.<rand20-35>"（端口已擦空），
     *   锚定隐藏随机名的形状（'.' 前缀 + 20..35 位字母数字，random_hidden_tmp_name 的长度域）
     * - 就地运行回落态：cmdline 以 /data/ 路径开头且以 "libnetsvc.so" 收尾
     *   （exec 形态：argv[0] 即二进制路径；cat/ls 等以 "cat " 开头的命令行不命中）
     * 两个模式均不匹配 Relay/app_process（cmdline 以类名收尾）与
     * .w_ 守护脚本（以 "sh " 开头，锚定 ^ 不命中）
     */
    private val daemonFallbackPattern1 = "^/data/local/tmp/\\.[A-Za-z0-9]{20,35}( |$)"
    private val daemonFallbackPattern2 = "^/data/.*/libnetsvc\\.so( |$)"

    private fun daemonProcessAlive(): Boolean {
        return Auxiliary.exec("pgrep -f '$daemonFallbackPattern1'").first == 0 ||
                Auxiliary.exec("pgrep -f '$daemonFallbackPattern2'").first == 0
    }

    /**
     * 停止守护进程。
     * @param purge true = 同时清扫 app 侧共享（磁贴/页面启动的 relay 与守护 sh）。
     * 胁迫销毁序列使用——app 侧 stopScreenShare 依赖 shell 特权，Shizuku
     * 断连时清不掉，由持特权的 daemon 兜底。普通停止保持原语义（不牵连
     * 用户正在运行的 app 侧共享）
     */
    suspend fun stopDaemon(purge: Boolean = false): Boolean = mutex.withLock {
        val stopCmd = if (purge) "purge" else "stop"
        if (sendCommand(stopCmd) == null) {
            // 信道不可用（如进程重启后 DK 未组装、daemon 无响应）：
            // 按进程特征兜底杀——否则销毁序列会带着内存中的旧 DK
            // 放任 daemon 存活到死线（root 可 dump 其内存解密历史产物）。
            // daemon 的 SIGTERM handler 负责锚点/自拷贝/密钥清理
            Auxiliary.exec(
                "pkill -TERM -f '$daemonFallbackPattern1'; " +
                        "pkill -TERM -f '$daemonFallbackPattern2'"
            )
            if (purge) {
                // purge 语义兜底：本路径（信道不可用）与"特权不可用"
                // （Shizuku 断连，步骤 1 的 app 侧 stopScreenShare 杀不掉
                // shell uid 的 relay）高度伴生，而上方两个 daemon pattern
                // 刻意不匹配 relay 与 .w_ 守护脚本——不补杀则销毁序列
                // 完成后守护循环 1s 续命、推流继续。与 stopScreenShare
                // 名称丢失分支同款通配清理；特权可用时生效（exec 内部
                // 按当前 shell/root 状态路由），无特权时尽力而为
                Auxiliary.exec(
                    "pkill -f /data/local/tmp/.w_ ; pkill -INT -f vendor.entry.Main; " +
                            "sleep 1; pkill -KILL -f vendor.entry.Main; " +
                            "rm -f /data/local/tmp/.s_* /data/local/tmp/.w_*.sh"
                )
            }
            repeat(20) {
                if (!daemonProcessAlive()) return true
                delay(100.milliseconds)
            }
            // SIGTERM 无效（卡在不可中断睡眠等）则 SIGKILL 兜底；
            // SIGKILL 跳过 handler 清理，残留锚点/自拷贝文件由下次
            // startDaemon 的锚点重初始化与 detonate 路径覆盖
            Auxiliary.exec(
                "pkill -KILL -f '$daemonFallbackPattern1'; " +
                        "pkill -KILL -f '$daemonFallbackPattern2'"
            )
            repeat(10) {
                if (!daemonProcessAlive()) return true
                delay(100.milliseconds)
            }
            return !daemonProcessAlive()
        }
        // 发送成功，等待进程真正退出。daemon 收尾最长可达 10s（录屏 SIGINT
        // 后等加密回写）+ 共享 supervisor 隧道拆除，旧 2s 窗口会在收尾仍在
        // 进行时提前判活/误判失败：端口变更流程（依赖本返回值决定是否拉起
        // 第二实例）会在旧实例仍占端口时就继续。对齐为 15s。
        // 探测用单次 status（retries=1，不重试）：stop 已被接受后命令线程被
        // 收尾阻塞（3s 超时 → 无响应）或进程已退出（连接拒绝），无响应即
        // 视为停止中/已停止——不必与正常运行的慢响应区分
        repeat(150) {
            if (sendCommand("status", retries = 1)?.startsWith("Working") != true) return true
            delay(100.milliseconds)
        }
        return false
    }

    /**
     * 停止 daemon 管理的屏幕共享（不停止 daemon 本身）。
     * app 侧的 pkill 杀不掉 supervisor 守护的 server（1s 内自动重启），
     * 必须经加密信道通知 daemon——否则用户点"停止"后推流继续，
     * 隐私持续泄露且无感知。daemon 不在线（返回 false）= 无 daemon
     * 管理的共享可停，调用方继续常规 pkill 清理即可
     */
    suspend fun stopDaemonManagedShare(): Boolean =
        runCatching { sendCommand("shareoff") }.getOrNull() == "fine"

    suspend fun detachDaemon(): Boolean = mutex.withLock {
        sendCommand("detach") ?: return !isDaemonRunning()
        // 发送成功，等待进程退出
        repeat(20) {
            if (!isDaemonRunning()) return true
            delay(100.milliseconds)
        }
        return false
    }

    suspend fun isDaemonRunning() = sendCommand("status")?.startsWith("Working") ?: false

    suspend fun sendCommand(command: String, retries: Int = 3): String? {
        var attempt = 0
        while (attempt < retries) {
            val result = withContext(Dispatchers.IO) context@{
                try {
                    val port = getPort()
                    // DK 拆分激活且未组装 → 无密钥 → 信道不可用（fail-closed）
                    val key = getKey() ?: return@context null
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 3000
                        // 1. 构造并发送加密命令
                        val timestamp = Auxiliary.getCurrentTimestampSeconds()
                        val plaintext = "$command\u001C$timestamp"
                        val (nonce, ciphertext) = EncryptManager.encryptByPassword(key, plaintext)

                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeInt(ciphertext.size + nonce.size)
                        out.write(nonce)
                        out.write(ciphertext)
                        out.flush()

                        // 2. 读取响应
                        val `in` = DataInputStream(socket.getInputStream())
                        val respLen = `in`.readInt()
                        // 与 daemon 侧 recv_encrypted 的 65536 上限对等：daemon
                        // 死亡后本地恶意进程可抢占端口回发巨型长度——
                        // ByteArray(huge) 抛 OutOfMemoryError（Error 不被
                        // catch(Exception) 捕获）直接崩溃 app（合法响应最长
                        // 为 detail 命令输出，数千字节量级）
                        if (respLen <= 0 || respLen > 65536) return@context null
                        val respData = ByteArray(respLen)
                        `in`.readFully(respData)

                        // 3. 解密响应
                        val respNonce = respData.sliceArray(0 until 12)
                        val respCiphertext = respData.sliceArray(12 until respData.size)
                        val plainResponse =
                            EncryptManager.decryptByPassword(key, respNonce, respCiphertext)

                        // 4. 如果是错误响应，返回 null 以便重试
                        if (plainResponse == "Decryption failed") {
                            return@context null
                        }

                        // 5. 验证格式和时间戳
                        val parts = plainResponse.split('\u001C')
                        if (parts.size != 2) return@context null
                        val responseCommand = parts[0]
                        val responseTimestamp = parts[1].toLongOrNull()
                        if (responseTimestamp == null || !Auxiliary.isTimestampValid(
                                responseTimestamp
                            )
                        ) {
                            return@context null
                        }
                        return@context responseCommand
                    }
                } catch (_: Exception) {
                    return@context null
                }
            }

            if (result != null) {
                return result
            }
            attempt++
            if (attempt < retries) {
                delay(200.milliseconds) // 增加延迟到 200ms
            }
        }
        return null
    }

    suspend fun syncConfig(): Boolean {
        if (!isDaemonRunning()) return false
        // fail-closed（与 ScreenShareManager 启动前检查同语义）：共享密码
        // 已配置（_sec 密文存在）但本会话不可解（锁定态 DK 未组装，或单段
        // DK 轮换后密文孤儿化）→ 中止整个 config 下发。静默发送无
        // auth_password 的配置会让 daemon 侧共享在"用户以为有密码"的状态下
        // 无认证运行（两侧行为分裂）。此时 daemon 保留旧配置（含密码），
        // 解锁后下次 syncConfig 即恢复
        if (SensitiveStore.isSensitiveConfigured(appContext, "screenShare_password") &&
            SensitiveStore.getSensitive(appContext, "screenShare_password", "").isEmpty()
        ) {
            return false
        }
        val separator = ConfigManager.getDataOnce(appContext, "daemon_config_separator", "#")
        val screenshot =
            ConfigManager.getDataOnce(appContext, "daemon_screenshot_config", "").split(separator)
                .joinToString("\u001F")
        val screenRecord =
            ConfigManager.getDataOnce(appContext, "daemon_screenRecord_config", "").split(separator)
                .joinToString("\u001F")
        val screenShare =
            ConfigManager.getDataOnce(appContext, "daemon_screenshare_config", "").split(separator)
                .joinToString("\u001F")
        val screenshotCommand = suspend {
            val savePath = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenshot_save_path",
                defaultValue = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_SCREENSHOTS
                ).path
            )
            val suffix = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenshot_suffix",
                defaultValue = ".png"
            )
            // displayID 校验（防元字符进 shell）：daemon 侧 execute_command 对
            // 非末段原样拼接进 sh -c（其注释明言依赖本侧 isConfigValid），
            // 非法值按未配置处理——与磁贴路径对齐，消除"同一配置两种执行结果"
            val displayID = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenshot_display_id",
                defaultValue = ""
            ).let { if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "-d $it" }
            listOf(
                "screencap",
                "-p",
                displayID,
                savePath,
                suffix
            ).filter { it.isNotEmpty() }.joinToString("\u001F")
        }
        val screenRecordCommand = suspend {
            val savePath = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_save_path",
                defaultValue = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).path
            )
            val duration = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_duration",
                defaultValue = "180"
            )
            val suffix = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_suffix",
                defaultValue = ".mp4"
            )
            // 同 screenshot_display_id：daemon 侧原样拼接进 sh -c，须先校验
            val displayID = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_display_id",
                defaultValue = ""
            ).let { if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "--display-id $it" }
            val bitrate = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_bitrate",
                defaultValue = ""
            ).let { if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "--bit-rate $it" }
            val resolution = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_resolution",
                defaultValue = ""
            ).let { if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "--size $it" }
            val bugreport = ConfigManager.getDataOnce(
                context = appContext,
                key = "screenRecord_bugreport",
                defaultValue = false
            ).let { if (it) "--bugreport" else "" }
            listOf(
                "screenrecord",
                "--time-limit", duration,
                displayID,
                bitrate,
                resolution,
                bugreport,
                savePath,
                suffix
            ).filter { it.isNotEmpty() }.joinToString("\u001F")
        }
        val screenShareCommand = suspend {
            val localPort = ConfigManager.getDataOnce(appContext, "screenShare_port", 2345)
            val sshEnabled = ConfigManager.getDataOnce(appContext, "ssh_tunnel_enabled", false)
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
            // 共享参数校验（与 ScreenShareManager.startScreenShareInternal 同语义）：
            // daemon 侧 spawn_shell_command 经 sh -c 执行该命令串，自由文本
            // 参数须防元字符，非法值按未配置处理
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
            // 视频比特率：过高会加大编码与传输延迟，0 表示使用 server 默认值
            val videoBitRate =
                ConfigManager.getDataOnce(appContext, "screenShare_video_bit_rate", 4000000)
                    .let { if (it > 0) "video_bit_rate=$it" else "" }
            val enableAudio =
                ConfigManager.getDataOnce(appContext, "screenShare_audio", true).let { "audio=$it" }
            // 与 ScreenShareManager 保持一致：
            // - playback + audio_dup=true：设备继续外放的同时复制一份音频流到捕获，音量不受影响
            // - raw（PCM 直传）：绕开部分接收设备 Opus 解码器的兼容性问题
            val audioDup = "audio_dup=true"
            val audioCodec = "audio_codec=raw"
            val audioOutput =
                ConfigManager.getDataOnce(appContext, "screenShare_audio_output", true)
                    .let { if (it) "audio_source=playback" else "" }
            val audioMic = ConfigManager.getDataOnce(appContext, "screenShare_audio_mic", false)
                .let { if (it) "audio_source=mic" else "" }
            // SSH 隧道模式下 server 只监听回环，防止局域网直连绕过隧道
            val tcpLocalOnly = if (sshEnabled) "tcp_local_only=true" else ""
            // 共享认证密码：DK 第二层加密存储（防 root-as-uid 提取）
            val authPassword =
                SensitiveStore.getSensitive(appContext, "screenShare_password", "")
                    .let { if (it.isEmpty()) "" else "auth_password=${shellQuote(it)}" }
            val base =
                "CLASSPATH=/data/local/tmp/FullRandomName app_process / vendor.entry.Main $VERSION tunnel_forward=true tcp_port=$localPort"

            listOf(
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
            ).filter { it.isNotEmpty() }.joinToString("\u001F")
        }
        val sshOptions = suspend {
            val enabled = ConfigManager.getDataOnce(
                appContext,
                "ssh_tunnel_enabled",
                false
            )
            // 敏感凭据经 DK 第二层加密存储（防 root-as-uid 读 DataStore 提取）；
            // syncConfig 只在 DK 可用后可达（startDaemon 依赖 DK），恒解密成功
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
            val remotePort = ConfigManager.getDataOnce(appContext, "ssh_tunnel_remote_port", 0)
            listOf(enabled, address, port, name, password, remotePort).joinToString("\u001F")
        }
        val otherOptions = suspend {
            val relayPath =
                "${appContext.applicationInfo.nativeLibraryDir}/libextsvr.so"
            val autoEncrypt =
                "${ConfigManager.getDataOnce(appContext, "encrypt_outputs", false)}"
            val definedTimestamp =
                ConfigManager.getDataOnce(appContext, "defined_timestamp", "").trim()
            // —— 超时看门狗信任链（daemon 侧独立计时，不依赖 app 存活）——
            // deadline 为绝对时刻（秒），由 app 侧 idle_ts 锚点 + limit 推导；
            // daemon 自身另持 uptime/wall 双锚点防冻结与回拨（详见 daemon.cpp）
            val idleLimit = runCatching {
                ConfigManager.getDataOnce(appContext, "idle_limit", 0L)
            }.getOrDefault(0L)
            val idleDeadline = runCatching {
                val ts = ConfigManager.getDataOnce(appContext, "idle_ts", "")
                // 锚点末段恒为墙钟毫秒（三段式 boot,er,wc 与旧两段式 er,wc 均如此）
                val wc0 = ts.split(",").lastOrNull()?.toLongOrNull()
                if (idleLimit <= 0) 0L
                else (wc0 ?: System.currentTimeMillis()) + idleLimit * 60_000L
            }.getOrDefault(0L) / 1000L
            // root 模式下 daemon 过期时的可达擦除范围（shell 模式不可达，仅尽力）
            val appDataDir = appContext.applicationInfo.dataDir ?: ""
            val appUid = appContext.applicationInfo.uid
            listOf(
                relayPath,
                autoEncrypt,
                definedTimestamp,
                idleLimit.toString(),
                idleDeadline.toString(),
                appDataDir,
                appUid.toString()
            ).joinToString("\u001F")
        }
        val command =
            "config$screenshot\u001E$screenRecord\u001E$screenShare\u001D${screenshotCommand()}\u001E${screenRecordCommand()}\u001E${screenShareCommand()}\u001D${sshOptions()}\u001D${otherOptions()}"
        return sendCommand(command) == "fine"
    }

    /**
     * 向守护进程续期超时死线（touchIdle 捎带调用，daemon 不在线则静默跳过）。
     * 节流至约 1 次/分钟：心跳 10s 触发但 socket 往返无必要如此频繁。
     */
    suspend fun renewIdleDeadline(limitMinutes: Long) {
        if (limitMinutes <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastRenewAtMillis < 55_000L) return
        lastRenewAtMillis = now
        val deadlineSec = (now + limitMinutes * 60_000L) / 1000L
        val renewed = runCatching { sendCommand("renew:$deadlineSec") }.getOrNull()
        if (renewed == null) {
            // 失败立即解除节流：不解除则 55s 内的后续 touch 全部跳过，
            // daemon 侧死线持续陈旧——若 daemon 存活而信道瞬断（单条
            // 命令超时/瞬时洪泛），下一次 touch 立即重试即恢复；
            // daemon 已死则连接秒拒，重试代价可忽略
            lastRenewAtMillis = 0L
        }
    }
}