package fake.screenshot.defense

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * L1 密钥保管客户端：DK 唯一持有者是独立 vault 进程（libsyncsvc.so），
 * 本类只做帧编解码与 RPC 门面——DK/CK 永不进入 Java 层（结构隔离，
 * 见 vault.cpp 头注释与 defense/README 威胁表 #23）。
 *
 * 帧协议（socketpair，[4B BE 长度][payload]，payload[0]=op）与
 * vault.cpp 的 Op 枚举一一对应；编解码集中在本文件，改动两侧同步。
 *
 * 生命周期：懒 spawn（首个 RPC 触发）——无门禁（LIVE_WK）状态在
 * spawn 后递交 WK（Keystore 包裹存 sync_wrap.bin，经 Java 瞬时转交，
 * 已声明边界）；主进程死亡 = socketpair EOF = vault 清密钥退出。
 * vault 死亡由下一次 RPC 的 EOF 感知并懒重启（会话回锁定态）。
 *
 * 已知边界（声明于 README）：被 hook 的主进程可在解锁会话内冒调
 * open/compose 逐条取明文（RPC oracle）——偷不到密钥，无法离线/
 * 锁后解密，与"用户主动查看凭据"同级（T3）。
 */
object VaultClient {
    // ---- op 常量（与 vault.cpp Op 对齐）----
    private const val OP_PING = 0x01
    private const val OP_UNLOCK = 0x02
    private const val OP_LOCK = 0x03
    private const val OP_SEAL = 0x04
    private const val OP_OPEN = 0x05
    private const val OP_ENABLE_GATE = 0x06
    private const val OP_MIGRATE = 0x07
    private const val OP_REMOVE_GATE = 0x08
    private const val OP_SETWK = 0x09
    private const val OP_GETCK = 0x0A
    private const val OP_OPENCH = 0x0B
    private const val OP_COMPOSE = 0x0C
    private const val OP_FSEAL_INIT = 0x0D
    private const val OP_FSEAL_UPDATE = 0x0E
    private const val OP_FSEAL_FINAL = 0x0F
    // 0x10-0x12（FOPEN 流式解密）已随死代码删除；OP_DESTROY 固定 0x13
    private const val OP_DESTROY = 0x13

    // ---- UNLOCK 结果（vault.cpp UnlockResult）----
    private const val UR_SECURITY = 0
    private const val UR_COERCION = 1
    private const val UR_BAD = 2
    private const val UR_RATE = 3

    // ---- MIGRATE/REMOVE_GATE 结果（vault.cpp VerifyResult）----
    private const val VR_OK = 0
    private const val VR_BAD_CURRENT = 1
    private const val VR_ERROR = 2

    // ---- 磁盘态 mode（vault.cpp DiskState 低 7 位）----
    private const val MODE_NOTHING = 0
    private const val MODE_LIVE_PW = 1
    private const val MODE_LIVE_WK = 2
    private const val MODE_DEAD_PW = 3
    private const val MODE_CORRUPT = 4

    // ---- 文件名（vault 拥有 sync_key.bin；本类拥有 sync_wrap.bin）----
    private const val WRAP_FILE = "sync_wrap.bin"
    private const val KEY_FILE = "sync_key.bin"
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 128
    private const val WK_LENGTH = 32
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val HARDWARE_ALIAS = "hardware_encryption_key"

    private lateinit var appContext: Context
    private val mutex = Mutex()
    private var libReady = false

    // 状态镜像（RPC 后更新；vault 死亡/重启时回退）
    @Volatile
    var gateEnabled: Boolean = false
        private set

    @Volatile
    var sessionUnlocked: Boolean = false
        private set

    // spawn 后 WK 是否已递交（每代 vault 进程一次）
    @Volatile
    private var wkDelivered = false

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!libReady) {
            runCatching { System.loadLibrary("memsys") }.onSuccess { libReady = true }
        }
        // 冷启动权威门禁判定：任何 RPC 发生前 isGateEnabled 必须就绪
        //（MainActivity 门禁页路由依赖同步值；vault 懒 spawn，不能用 RPC）
        refreshGateStateFromDisk()
    }

    /**
     * 文件级门禁判定（与 vault.cpp loadState 的 gateOn 语义严格对齐）：
     * 仅"文件存在且可解析为 LIVE_WK"是门禁关；魔数/版本/状态字节任何
     * 不符、截断、读失败 = CORRUPT/LIVE_PW/DEAD_PW 同类（vault 对三者
     * 均上报 gateOn——不可解析按疑似篡改处理，fail-closed）。
     * 崩溃窗口（tmp+rename 原子写）不产生中间态。
     */
    fun refreshGateStateFromDisk() {
        if (!::appContext.isInitialized) return
        gateEnabled = runCatching {
            val f = File(appContext.filesDir, KEY_FILE)
            if (!f.exists()) return@runCatching false
            val head = f.inputStream().use { s ->
                val b = ByteArray(3)
                var got = 0
                while (got < 3) {
                    val r = s.read(b, got, 3 - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < 3) return@runCatching true  // 截断 = 不可解析
                b
            }
            !(head[0] == 'K'.code.toByte() && head[1] == 2.toByte() &&
                    head[2] == MODE_LIVE_WK.toByte())
        }.getOrDefault(true)  // 读异常 = 不可解析（fail-closed）
    }

    enum class UnlockResult { SECURITY, COERCION, BAD, RATE_LIMITED }

    /** COMPOSE 槽位：DK 解密的敏感密文（_sec blob = nonce+ct）或明文段 */
    sealed class Part {
        class Literal(val bytes: ByteArray) : Part()
        class Slot(val blob: ByteArray) : Part()
    }

    // ===================== 帧编解码 =====================

    private class FrameBuilder {
        val out = java.io.ByteArrayOutputStream()

        fun u8(v: Int) = apply { out.write(v and 0xFF) }

        fun u16(v: Int) = apply {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun u32(v: Int) = apply {
            out.write((v shr 24) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun bytes(b: ByteArray) = apply { out.write(b) }

        fun str(s: String) = apply {
            val b = s.toByteArray(Charsets.UTF_8)
            u16(b.size)
            bytes(b)
        }

        fun build(): ByteArray = out.toByteArray()
    }

    private class RespParser(val data: ByteArray) {
        var pos = 1  // [0] = status
        fun u16(): Int {
            val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }
    }

    // ===================== RPC 核心 ======================================

    /**
     * 单次 RPC：确保 vault 存活（懒 spawn + WK 递交）→ 发帧 → 解响应。
     * 任何失败返回 null（fail-closed 由调用方按各自语义处理）。
     * vault 死亡（EOF）时标记会话锁定，下次调用重启 vault。
     */
    private suspend fun request(req: ByteArray): ByteArray? = mutex.withLock {
        if (!libReady || !::appContext.isInitialized) return null
        withContext(Dispatchers.IO) {
            if (!nativeAlive() || !wkDelivered) {
                if (!respawnLocked()) return@withContext null
            }
            val resp = nativeRequest(req)
            if (resp == null) {
                if (!nativeAlive()) {
                    // vault 已死：标记锁定（DK 已随进程消失），下次懒重启
                    sessionUnlocked = false
                    wkDelivered = false
                }
                return@withContext null
            }
            resp
        }
    }

    /** 已持 mutex：重启 vault 并递交 WK（mode=LIVE_WK 时）。失败置死态。 */
    private fun respawnLocked(): Boolean {
        nativeClose()
        wkDelivered = false
        sessionUnlocked = false
        val bin = File(appContext.applicationInfo.nativeLibraryDir, "libsyncsvc.so").absolutePath
        if (!nativeStart(bin, appContext.filesDir.absolutePath)) return false
        return runCatching { deliverWkLocked() }.getOrDefault(false)
    }

    /**
     * WK 递交（vault 冷启动路径）：
     * - LIVE_WK：sync_wrap.bin 经 Keystore 解包 → SETWK（解开 DK）
     * - NOTHING（首装/无门禁销毁后）：生成新 WK → 确认包裹落盘成功 →
     *   SETWK（vault 就地创建新 DK）——落盘先于 SETWK，崩溃无空窗且
     *   不产生"vault 建了 DK 而 app 侧无包裹副本"的分裂态
     * - 有门禁/CORRUPT：无需 WK
     * wkDelivered 仅成功后置位：任何瞬态失败令下次 request() 重新
     * respawn + 重递（不产生"已递交"假象锁死 LIVE_WK 用户的 DK）。
     */
    private fun deliverWkLocked(): Boolean {
        val st = nativeRequest(FrameBuilder().u8(OP_PING).build()) ?: return false
        if (st.size != 3) return false
        val mode = st[1].toInt() and 0x7F
        gateEnabled = (st[1].toInt() and 0x80) != 0
        sessionUnlocked = st[2].toInt() == 1
        if (mode != MODE_LIVE_WK && mode != MODE_NOTHING) {
            wkDelivered = true  // 有门禁/CORRUPT：无需 WK
            return true
        }
        val wk: ByteArray? = if (mode == MODE_LIVE_WK) {
            readWrappedWk()  // 包裹文件损坏 = DK 不可达（fail-closed）
        } else {
            ByteArray(WK_LENGTH).also { SecureRandom().nextBytes(it) }
                .takeIf { writeWrappedWk(it) }
        }
        if (wk == null) return false
        val resp = nativeRequest(
            FrameBuilder().u8(OP_SETWK).bytes(wk).build()
        )
        wk.fill(0)
        if (resp == null || resp.isEmpty() || resp[0].toInt() != 0) return false
        wkDelivered = true
        sessionUnlocked = true
        return true
    }

    // ===================== 公开 API =====================

    /** DK 是否可用（解锁会话内 / 无门禁模式）——磁贴与敏感功能 fail-closed 判定 */
    suspend fun isKeyReady(): Boolean {
        val st = status() ?: return false
        return st.dkReady
    }

    data class VaultStatus(val mode: Int, val gateOn: Boolean, val dkReady: Boolean)

    suspend fun status(): VaultStatus? {
        val r = request(FrameBuilder().u8(OP_PING).build()) ?: return null
        if (r.size != 3) return null
        return VaultStatus(r[1].toInt() and 0x7F, (r[1].toInt() and 0x80) != 0, r[2].toInt() == 1)
    }

    /** 门禁是否启用（同步缓存——RPC 后刷新；进程启动后首次访问可能为 false） */
    fun isGateEnabledSync(): Boolean = gateEnabled

    /**
     * 解锁（验证 + DK 组装，一次 Argon2id）：
     * SECURITY = DK 就绪；COERCION = vault 已就地重生（旧 DK 孤儿化，
     * 会话以新随机 DK 全功能就绪——演出：该密码正常解锁），调用方执行
     * 完整销毁序列（keepVaultSession，勿再 OP_DESTROY）后照常进入；
     * BAD/RATE = 失败。
     */
    suspend fun unlock(password: String): UnlockResult {
        val r = request(FrameBuilder().u8(OP_UNLOCK).str(password).build())
            ?: return UnlockResult.BAD
        if (r.isEmpty()) return UnlockResult.BAD
        sessionUnlocked = (r[0].toInt() == UR_SECURITY || r[0].toInt() == UR_COERCION)
        return when (r[0].toInt()) {
            UR_SECURITY -> UnlockResult.SECURITY
            UR_COERCION -> UnlockResult.COERCION
            UR_RATE -> UnlockResult.RATE_LIMITED
            else -> UnlockResult.BAD
        }
    }

    /** 锁定：vault 清 DK（仅门禁模式有效——无门禁 DK 常驻为已声明语义） */
    suspend fun lock() {
        runCatching { request(FrameBuilder().u8(OP_LOCK).build()) }
        sessionUnlocked = false
    }

    /** DK 加密（敏感字段写入；返回 nonce+ct blob） */
    suspend fun seal(plain: ByteArray): ByteArray? {
        if (plain.size > 65535) return null
        val r = request(FrameBuilder().u8(OP_SEAL).u16(plain.size).bytes(plain).build())
            ?: return null
        if (r.isEmpty() || r[0].toInt() != 0) return null
        return r.copyOfRange(1, r.size)
    }

    /** DK 解密（blob = nonce+ct；失败 null——锁定态/密文损坏/篡改同语义） */
    suspend fun open(blob: ByteArray): ByteArray? {
        if (blob.size < NONCE_LENGTH + 16) return null
        val r = request(FrameBuilder().u8(OP_OPEN).bytes(blob).build()) ?: return null
        if (r.size < 3 || r[0].toInt() != 0) return null
        val p = RespParser(r)
        val n = p.u16()
        if (r.size < 3 + n) return null
        return r.copyOfRange(3, 3 + n)
    }

    /** 首次设门禁（无门禁 → 有门禁；DK 保持） */
    suspend fun enableGate(security: String, coercion: String): Boolean {
        val b = FrameBuilder().u8(OP_ENABLE_GATE).str(security)
        if (coercion.isEmpty()) b.u16(0) else b.str(coercion)
        val r = request(b.build()) ?: return false
        val ok = r.isNotEmpty() && r[0].toInt() == 0
        if (ok) gateEnabled = true
        return ok
    }

    enum class GateChangeResult { OK, BAD_CURRENT, ERROR }

    /** 改密（cur 错 = BAD_CURRENT；胁迫密码 = 静默孤儿化返回 OK） */
    suspend fun migrate(current: String, security: String, coercion: String): GateChangeResult {
        val b = FrameBuilder().u8(OP_MIGRATE).str(current).str(security)
        if (coercion.isEmpty()) b.u16(0) else b.str(coercion)
        val r = request(b.build()) ?: return GateChangeResult.ERROR
        if (r.isEmpty()) return GateChangeResult.ERROR
        return when (r[0].toInt()) {
            VR_OK -> GateChangeResult.OK
            VR_BAD_CURRENT -> GateChangeResult.BAD_CURRENT
            else -> GateChangeResult.ERROR
        }
    }

    /**
     * 移除门禁：成功后新 WK 已由 vault 返回并就地包裹落盘
     * （sync_wrap.bin）；DK 保持（密文连续）。BAD_CURRENT = 当前密码错。
     */
    suspend fun removeGate(current: String): GateChangeResult {
        val r = request(FrameBuilder().u8(OP_REMOVE_GATE).str(current).build())
            ?: return GateChangeResult.ERROR
        if (r.isEmpty()) return GateChangeResult.ERROR
        return when (r[0].toInt()) {
            VR_OK -> {
                if (r.size != 1 + WK_LENGTH) return GateChangeResult.ERROR
                val wk = r.copyOfRange(1, 1 + WK_LENGTH)
                val written = writeWrappedWk(wk)
                wk.fill(0)
                if (!written) {
                    // vault 已转 LIVE_WK 但包裹落盘失败：下次会话 DK 不可达。
                    // 返回 ERROR 促用户立即重新启用门禁（enableGate 在
                    // LIVE_WK + DK 内存就绪下可恢复，无数据损失）
                    return GateChangeResult.ERROR
                }
                gateEnabled = false
                sessionUnlocked = true
                GateChangeResult.OK
            }
            VR_BAD_CURRENT -> GateChangeResult.BAD_CURRENT
            else -> GateChangeResult.ERROR
        }
    }

    /** daemon 信道密钥（确定性派生自 DK；瞬时过 Java 递 stdin——已声明边界） */
    suspend fun getChannelKey(): ByteArray? {
        val r = request(FrameBuilder().u8(OP_GETCK).build()) ?: return null
        if (r.size != 1 + WK_LENGTH || r[0].toInt() != 0) return null
        return r.copyOfRange(1, 1 + WK_LENGTH)
    }

    /** daemon 信道响应解密 */
    suspend fun openChannel(blob: ByteArray): String? {
        if (blob.size < NONCE_LENGTH + 16) return null
        val r = request(FrameBuilder().u8(OP_OPENCH).bytes(blob).build()) ?: return null
        if (r.size < 3 || r[0].toInt() != 0) return null
        val p = RespParser(r)
        val n = p.u16()
        if (r.size < 3 + n) return null
        return String(r, 3, n, Charsets.UTF_8)
    }

    /**
     * 组装 daemon 信道帧（凭据明文不进 Java）：
     * parts 交错拼接（Slot 由 vault 用 DK 解密）→ vault 追加时间戳 →
     * CK 加密 → 返回 nonce+ct（socket 写出格式与旧实现一致）。
     */
    suspend fun composeChannel(parts: List<Part>): ByteArray? {
        val b = FrameBuilder().u8(OP_COMPOSE).u32(parts.size)
        for (part in parts) {
            when (part) {
                is Part.Literal -> b.u8(0).u32(part.bytes.size).bytes(part.bytes)
                is Part.Slot -> b.u8(1).u32(part.blob.size).bytes(part.blob)
            }
        }
        val r = request(b.build()) ?: return null
        if (r.size < 1 + NONCE_LENGTH + 16 || r[0].toInt() != 0) return null
        return r.copyOfRange(1, r.size)
    }

    /**
     * 流式文件加密（encrypt_outputs；格式 [12B nonce][ct][16B tag] 与旧
     * CipherOutputStream 产物同构）。分块 60KB；任一步失败（协议失败
     * 返回 false 或异常）删输出文件——截断的 [nonce][ct][无tag] 产物
     * 无法解密且看似正常。
     */
    suspend fun sealFile(input: File, output: File): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val init = request(FrameBuilder().u8(OP_FSEAL_INIT).build())
                ?.takeIf { it.size == 1 + NONCE_LENGTH && it[0].toInt() == 0 }
                ?: return@runCatching false
            output.outputStream().use { out ->
                out.write(init, 1, NONCE_LENGTH)
                val buf = ByteArray(60000)
                input.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        val ct = request(
                            FrameBuilder().u8(OP_FSEAL_UPDATE).u32(n).bytes(
                                if (n == buf.size) buf else buf.copyOf(n)
                            ).build()
                        ) ?: return@runCatching false
                        if (ct.size != 1 + 4 + n || ct[0].toInt() != 0) {
                            return@runCatching false
                        }
                        out.write(ct, 5, n)
                    }
                }
                val fin = request(FrameBuilder().u8(OP_FSEAL_FINAL).build())
                    ?: return@runCatching false
                if (fin.size != 1 + 16 || fin[0].toInt() != 0) return@runCatching false
                out.write(fin, 1, 16)
            }
            true
        }.getOrDefault(false)
        if (!ok) runCatching { output.delete() }
        ok
    }

    /**
     * vault 层销毁（销毁序列步骤）：清 DK、改写/删除 sync_key.bin
     * （验证项保留 = 门禁行为一致）+ 删本类拥有的 sync_wrap.bin。
     * vault 不在则直接删文件（进程不在 = 内存无密钥，文件级等效）。
     */
    suspend fun destroy() {
        runCatching { request(FrameBuilder().u8(OP_DESTROY).build()) }
        sessionUnlocked = false
        wkDelivered = false
        runCatching {
            File(appContext.filesDir, "sync_wrap.bin").delete()
            File(appContext.filesDir, "sync_wrap.bin.tmp").delete()
        }
    }

    /**
     * 胁迫重生路径的 app 侧清扫（销毁序列步骤 2.5 的演出变体，由
     * DefenseProtocol.keepVaultSession 调用）：vault 已在解锁命中时就地
     * 重生（新 DK 会话），OP_DESTROY 会杀死重生会话（回到"销毁后功能
     * 全废"的穿帮态）——仅删本类拥有的 sync_wrap.bin（WK 包裹；门禁态
     * 不应存在，防早期无门禁时代的残留），不动 vault 进程与状态镜像。
     */
    fun destroyWrapFileOnly() {
        if (!::appContext.isInitialized) return
        runCatching {
            File(appContext.filesDir, "$WRAP_FILE.tmp").delete()
            File(appContext.filesDir, WRAP_FILE).delete()
        }
    }

    // ===================== WK 的 Keystore 包裹（app 侧拥有）=====================

    private fun wrapFile(): File = File(appContext.filesDir, WRAP_FILE)

    private fun readWrappedWk(): ByteArray? {
        val blob = runCatching { wrapFile().readBytes() }.getOrNull() ?: return null
        if (blob.size <= NONCE_LENGTH) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, getOrCreateHardwareKey(),
                GCMParameterSpec(TAG_LENGTH, blob.copyOfRange(0, NONCE_LENGTH))
            )
            cipher.doFinal(blob.copyOfRange(NONCE_LENGTH, blob.size))
                .takeIf { it.size == WK_LENGTH }
        }.getOrNull()
    }

    /**
     * WK 包裹落盘（tmp + fsync + rename 原子写）。
     * 失败返回 false——调用方不得在失败后递交 SETWK（vault 建了 DK 而
     * app 侧无包裹副本 = 状态分裂，见 deliverWkLocked）。
     */
    private fun writeWrappedWk(wk: ByteArray): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, getOrCreateHardwareKey()) }
        val blob = cipher.iv + cipher.doFinal(wk)
        val tmp = File(appContext.filesDir, "$WRAP_FILE.tmp")
        java.io.FileOutputStream(tmp).use {
            it.write(blob)
            it.fd.sync()  // 密钥承载文件：rename 前强制落盘
        }
        if (!tmp.renameTo(wrapFile())) {
            // 同目录 rename 失败属异常环境：按失败处理（不退化为非原子
            // copy——半截包裹 = 下次 readWrappedWk 解包失败 = DK 不可达）
            tmp.delete()
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    private fun getOrCreateHardwareKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(HARDWARE_ALIAS, null) as? SecretKey ?: run {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            val spec = KeyGenParameterSpec.Builder(
                HARDWARE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    // ===================== JNI（vault_client.cpp）=====================

    private external fun nativeStart(binPath: String, filesDir: String): Boolean
    private external fun nativeRequest(req: ByteArray): ByteArray?
    private external fun nativeAlive(): Boolean
    private external fun nativeClose()
}
