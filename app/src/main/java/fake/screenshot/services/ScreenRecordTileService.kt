package fake.screenshot.services

import android.os.Environment
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fake.screenshot.Auxiliary
import fake.screenshot.defense.DefenseProtocol
import fake.screenshot.defense.KeyVault
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.R
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class ScreenRecordTileService : TileService() {
    private val tempPath = "/data/local/tmp/"

    private var isRecording = false
    private var recordPid: Int? = null
    private var clicked = false
    private var showingNoPermission = false

    private var lastEncryptOutputs = false
    private var lastSavePath = ""
    private var lastFileName = ""
    private var lastTempName = ""

    @Volatile
    private var isEncrypting = false

    // startRecording 启动占位（主线程读写，见 onStopListening 注释）：
    // 在 isRecording 置位前（execGetPid 等待期）充当"已有启动在途"的
    // 原子标志，关闭双启动竞态
    private var isStarting = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 回调串行化 + exec 离开主线程（与 ScreenshotTileService 同语义）：
    // refreshShellState / isProcessAlive 含同步 exec，Shizuku binder 假死
    // 时无超时——主线程直接执行是 ANR 面。串行锁保持快速连击的处理顺序
    private val handlerMutex = Mutex()

    companion object {
        /** 收起面板→开始录制的意图时效窗（理由见 ScreenshotTileService） */
        private const val COLLAPSE_INTENT_FRESH_MS = 2000L
    }

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴可能先于 MainActivity 运行（如重启后直接点磁贴），确保 DK 上下文就绪
        DefenseProtocol.init(applicationContext)
        serviceScope.launch {
            handlerMutex.withLock {
                withContext(Dispatchers.Main) {
                    checkAndResetIfProcessDead()
                    updateTileUI()
                }
            }
        }
    }

    override fun onClick() {
        super.onClick()
        // clicked 同步翻转（非录制时；截图磁贴同理由——onStopListening 的
        // 意图快照依赖此刻已写入，异步翻转会丢"点击后立即收起"的意图）。
        // 与原实现的已知差异（方向均为 fail-safe，接受）：
        // 1) 原实现先跑 checkAndResetIfProcessDead（含同步 exec，ANR 面）
        //    再判定录制态，"录制已自行结束 + 点击"会武装新录制；本实现
        //    同步读到 isRecording=true 不翻转，异步复核后不武装
        // 2) "停止在途 + 快速第二次点击想重新武装"：点击读到的 isRecording
        //    仍是 true（停止在异步 handleClicked 内完成）→ 不武装，意图
        //    静默丢失需重点一次。窗口 = 点击块排队+refresh（~R）。宁漏启
        //    动不误启动——补救需在本延迟链里二次翻转，会重新引入
        //    "翻转晚于收起快照"的丢意图竞态（P4），权衡后接受
        if (!isRecording) {
            clicked = !clicked
        }
        updateTileUI()
        // 仅慢速部分（同步 exec / kill 复核）离主线程
        serviceScope.launch {
            handlerMutex.withLock {
                Auxiliary.refreshShellState()
                withContext(Dispatchers.Main) {
                    handleClicked()
                }
            }
        }
    }

    /** onClick 慢速收尾（主线程、串行锁内执行；见 handlerMutex 注释） */
    private suspend fun handleClicked() {
        if (!Auxiliary.isShellActivated && !Auxiliary.isRootActivated) {
            clicked = false
            showingNoPermission = !showingNoPermission
            if (showingNoPermission) {
                if (isRecording) {
                    showingNoPermission = false
                    return
                }
            }
            updateTileUI()
            return
        }

        showingNoPermission = false
        checkAndResetIfProcessDead()

        if (isRecording) {
            stopRecording()
            return
        }
        // clicked 已在 onClick 同步翻转，此处仅刷新显示
        updateTileUI()
    }

    override fun onStopListening() {
        super.onStopListening()
        // 决策输入同步快照 + 意图时效窗（理由见 ScreenshotTileService 的
        // onStopListening 注释：延迟块读字段会被重开面板的回调改写；
        // 被拖延后开始的录制会录到任意时刻起的屏幕）
        val wasClicked = clicked
        val atEr = SystemClock.elapsedRealtime()
        // 同步清零（与截图磁贴对齐，此前遗漏）：不清零时窗口期内重开
        // 面板显示过期的"收起后开始录制"，且快速点击取消（onClick 翻回
        // false）后被本延迟块以快照 wasClicked=true 照样启动——取消
        // 意图被吞、违背用户意图开始录制
        clicked = false
        showingNoPermission = false
        // wasClicked=true 时，点击块（必先于本块持锁执行）刚刷新过权限态，
        // 毫秒级新鲜；本块再刷一次纯属冗余——且排队串行下决策时刻被
        // 推迟一个完整 refresh 周期，冷启动（R~1-1.5s）时 2s 时效窗被
        // 提前耗尽，健康设备首次录制被静默放弃。删冗余刷新
        serviceScope.launch {
            handlerMutex.withLock {
                withContext(Dispatchers.Main) {
                    checkAndResetIfProcessDead()
                    val fresh =
                        SystemClock.elapsedRealtime() - atEr <= COLLAPSE_INTENT_FRESH_MS
                    if (wasClicked && fresh &&
                        (Auxiliary.isShellActivated || Auxiliary.isRootActivated)
                    ) {
                        // isStarting 原子占位（主线程锁内）：关闭双启动竞态——
                        // startRecording 脱离 handlerMutex 且 isRecording 在其
                        // 尾部才置位，快速"收起→重开→点击→收起"两次武装时
                        // 第二个 startRecording 可读过期 isRecording=false 并发
                        // 启动两个 screenrecord，recordPid/lastTempName 被后者
                        // 覆盖，前者无人收尾：不加密、不删除，明文录制文件
                        // 永久残留 /data/local/tmp（shell/adb 可读，隐私泄露）。
                        // 占位覆盖 startRecording 全程（含其开头等待上一次
                        // 加密收尾的自旋，最长 5s）——等待期占位拒绝新启动
                        // 是正确 fail-safe：上一次会话尚未收尾时本就不该有
                        // 第二个启动。onDestroy 取消协程令占位残留时实例
                        // 已销毁（重建后字段全新），无跨实例影响
                        if (!isStarting) {
                            isStarting = true
                            serviceScope.launch {
                                try {
                                    startRecording()
                                } finally {
                                    withContext(Dispatchers.Main) {
                                        isStarting = false
                                    }
                                }
                            }
                        }
                    }
                    updateTileUI()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun startRecording() {
        if (isRecording) {
            return
        }
        var waitCount = 0
        while (isEncrypting && waitCount < 10) {
            delay(500.milliseconds)
            waitCount++
        }
        if (isEncrypting) {
            return
        }

        val savePath = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_save_path",
            defaultValue = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).path
        )
        val duration = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_duration",
            defaultValue = "180"
        ).toIntOrNull() ?: 180
        val prefix = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_prefix",
            defaultValue = ""
        )
        val suffix = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_suffix",
            defaultValue = ".mp4"
        )
        val displayID = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_display_id",
            defaultValue = ""
        ).let {
            // 校验（防元字符进 shell，与 daemon 侧处理对齐）：非法值按未配置处理
            if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "--display-id $it"
        }
        val bitrate = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_bitrate",
            defaultValue = ""
        ).let {
            if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "--bit-rate $it"
        }
        val resolution = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_resolution",
            defaultValue = ""
        ).let {
            if (it.isEmpty() || !Auxiliary.isConfigValid(it)) "" else "--size $it"
        }
        val customPrefix = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_custom_prefix",
            defaultValue = false
        )
        val fullRandom = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_full_random",
            defaultValue = false
        )
        val bugreport = ConfigManager.getDataOnce(
            context = this,
            key = "screenRecord_bugreport",
            defaultValue = false
        ).let { if (it) "--bugreport" else "" }
        val encryptOutputs = ConfigManager.getDataOnce(
            context = this,
            key = "encrypt_outputs",
            defaultValue = false
        )
        // DK 拆分激活且本会话未解锁组装：直接放弃（fail-closed）。
        // screenrecord 全程向 tmp 写明文，未就绪时启动 = 明文全程暴露
        if (encryptOutputs && !KeyVault.isDaemonKeyReady()) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    this@ScreenRecordTileService,
                    getString(R.string.unlock_app_first),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                updateTileUI()
            }
            return
        }

        File(savePath).apply { if (!exists()) mkdirs() }
        val fileName = when {
            fullRandom -> Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(16..24))
            customPrefix -> "${prefix}_${Auxiliary.getRandomString(4)}$suffix"
            else -> "${Auxiliary.getCurrentDateString()}_${Auxiliary.getRandomString(4)}$suffix"
        }
        val tempName = Auxiliary.getRandomStringEx(Auxiliary.getSecureRandomInt(20..35))
        // 输出路径（唯一含用户可控元字符的段）安全引用：与 daemon 侧
        // shell_quote 对齐——保存路径可含空格/引号/元字符，裸拼会拆分
        // 参数或构成用户自伤型命令注入（root 模式放大）。其余段经校验
        val outputPath = if (encryptOutputs) Auxiliary.shellQuote(tempPath + tempName)
        else Auxiliary.shellQuote("$savePath/$fileName")

        lastEncryptOutputs = encryptOutputs
        lastSavePath = savePath
        lastFileName = fileName
        lastTempName = tempName

        val args = listOf(
            "/system/bin/screenrecord",
            "--time-limit", duration.toString(),
            displayID,
            bitrate,
            resolution,
            bugreport,
            outputPath
        ).filter { it.isNotEmpty() }

        // stdout/stderr 重定向 /dev/null（与 ScreenShareManager 拉起 server 的
        // 既有约定同源）：POSIX 后台任务继承 sh 的 stdout 管道写端，screenrecord
        // 全程持有该 fd（AOSP 源码不 close/dup2），读端 readText() 要等 EOF
        // 即等录制结束——默认 180s > exec 120s 超时：execGetPid 恒 null，
        // isRecording 永不置位，磁贴状态机全灭且明文临时文件无人收尾
        // （加密模式：明文录制文件永久残留 /data/local/tmp，shell/adb 可读）。
        // 重定向后 sh 写完 pid 即退出，EOF 毫秒级到达；screenrecord 本就
        // 只写文件不依赖 stdout，行为不变。cmdline 可见性与原先相同（命令
        // 体在 sh -c / stdin 中，ps 所见仍是 screenrecord 及其参数）
        val cmd = args.joinToString(" ") + " >/dev/null 2>&1 & echo $!"

        val pid = Auxiliary.execGetPid(cmd)
        if (pid == null) {
            withContext(Dispatchers.Main) {
                updateTileUI()
            }
            return
        }

        withContext(Dispatchers.Main) {
            isRecording = true
            recordPid = pid
            updateTileUI()
        }
    }

    private suspend fun stopRecording() {
        if (!isRecording) {
            return
        }
        val pid = recordPid
        if (pid != null) {
            // 身份复核：screenrecord 早期崩溃后 PID 被复用时，裸 kill -2
            // 会误杀无关进程（与 daemon 侧 PID 复用防护同一语义）。
            // exec 挪 IO（调用方在主线程上下文，见 handlerMutex 注释）
            withContext(Dispatchers.IO) {
                Auxiliary.killProcessIfCmdlineMatches(pid, "screenrecord")
            }
        }
        isRecording = false
        recordPid = null
        clicked = false
        updateTileUI()

        if (lastEncryptOutputs) {
            val temp = lastTempName
            val save = lastSavePath
            val file = lastFileName
            // 分离 scope（对齐 ScreenshotTileService.screenshot 的模式）：
            // 加密收尾含明文删除（rm -f），挂 serviceScope 时 onDestroy
            // 在 launch 与协程启动间的取消会令 chmod/加密/rm 全不执行，
            // 明文录制文件永久残留 /data/local/tmp——明文清理必须跨
            // 服务销毁存活，不可被取消
            CoroutineScope(Dispatchers.IO).launch {
                if (isEncrypting) return@launch
                isEncrypting = true
                try {
                    var waited = 0
                    while (waited < 5) {
                        if (pid == null || !isProcessAlive(pid)) break
                        delay(500.milliseconds)
                        waited++
                    }
                    val tempFile = File(tempPath + temp)
                    if (tempFile.exists()) {
                        // 444 维持（勿改 400，理由见 ScreenshotTileService：
                        // shell/root 写 + app 读的跨 uid 链路）
                        Auxiliary.exec("chmod 444 '$tempPath$temp'")
                        val encrypted = File("$save/$file")
                        KeyVault.encryptFileByKeystore(tempFile, encrypted)
                    }
                } catch (_: Exception) {
                } finally {
                    Auxiliary.exec("rm -f ${tempPath + temp}")
                    isEncrypting = false
                }
                applyDefinedTimestamp("$save/$file")
            }
        } else {
            val save = lastSavePath
            val file = lastFileName
            serviceScope.launch {
                var waited = 0
                while (waited < 5) {
                    if (pid == null || !isProcessAlive(pid)) break
                    delay(500.milliseconds)
                    waited++
                }
                applyDefinedTimestamp("$save/$file")
            }
        }
    }

    private suspend fun applyDefinedTimestamp(path: String) {
        val definedTimestamp = ConfigManager.getDataOnce(
            context = this@ScreenRecordTileService,
            key = "defined_timestamp",
            defaultValue = ""
        )
        Auxiliary.applyDefinedTimestamp(definedTimestamp, path)
    }

    private suspend fun checkAndResetIfProcessDead() {
        val pid = recordPid
        if (isRecording && pid != null && !isProcessAlive(pid)) {
            isRecording = false
            recordPid = null
            if (lastEncryptOutputs && File(tempPath + lastTempName).exists()) {
                val temp = lastTempName
                val save = lastSavePath
                val file = lastFileName
                // 分离 scope（与 stopRecording 对称，此前遗漏）：本路径是
                // 录制自然结束（时长到/screenrecord 崩溃）后的明文收尾，
                // 含最多 2.5s 的 pid 等待 delay——恰是系统解绑销毁磁贴的
                // 高发窗口。挂 serviceScope 时 onDestroy 取消会令
                // chmod/加密/rm 全不执行，明文录制文件永久残留
                // /data/local/tmp（shell/adb 可读）。明文清理必须跨服务
                // 销毁存活，不可被取消
                CoroutineScope(Dispatchers.IO).launch {
                    if (isEncrypting) return@launch
                    isEncrypting = true
                    try {
                        // 444 维持（勿改 400，理由见 ScreenshotTileService：
                        // shell/root 写 + app 读的跨 uid 链路）
                        Auxiliary.exec("chmod 444 '$tempPath$temp'")
                        File(tempPath + temp).apply {
                            val encrypted = File("$save/$file")
                            KeyVault.encryptFileByKeystore(this, encrypted)
                        }
                    } catch (_: Exception) {
                    } finally {
                        Auxiliary.exec("rm -f ${tempPath + temp}")
                        isEncrypting = false
                    }
                    applyDefinedTimestamp("$save/$file")
                }
            } else if (!lastEncryptOutputs) {
                val save = lastSavePath
                val file = lastFileName
                serviceScope.launch {
                    applyDefinedTimestamp("$save/$file")
                }
            }
            updateTileUI()
        }
    }

    private suspend fun isProcessAlive(pid: Int): Boolean =
        withContext(Dispatchers.IO) {
            val (exitCode, _) = Auxiliary.exec("kill -0 $pid 2>/dev/null")
            exitCode == 0
        }

    private fun updateTileUI() {
        qsTile?.apply {
            state = when {
                isRecording -> Tile.STATE_ACTIVE
                clicked -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            label = when {
                showingNoPermission -> getString(R.string.no_permission)
                isRecording -> getString(R.string.recording)
                clicked -> getString(R.string.collapse_to_start)
                else -> getString(R.string.stealth_screen_recording)
            }
            updateTile()
        }
    }
}