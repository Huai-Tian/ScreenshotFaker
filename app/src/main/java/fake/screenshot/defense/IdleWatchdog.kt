package fake.screenshot.defense

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import java.io.IOException
import fake.screenshot.wrappers.ConfigManager
import fake.screenshot.wrappers.DaemonManager

/**
 * L2 未使用自动销毁（TG 账号超时销毁式，独立于门禁验证）。
 *
 * 状态存储：
 * - armed 哨兵：明文 prefs "sync_preferences"（与验证器同文件，中性命名），
 *   永不清除。由 [GateManager.setPasswords]（随验证器同一次 commit——
 *   验证器存在而 armed 消失 = 定向篡改 = 自毁）与本类 [setIdleTimeout]
 *   共写；键名 "armed" 是冻结不变量，两处定义必须一致
 * - idle_limit / idle_ts：密文 DataStore（Tink 保护）
 *
 * 计时锚点（反回拨）：三段式 "boot,elapsedRealtime,currentTimeMillis"。
 * boot 取 Settings.Global.BOOT_COUNT（system_server 维护，用户态不可
 * 回拨）：同开机走 er 单调 + 双锚点交叉校验；跨开机走墙钟判定；
 * boot 减小 = 篡改。旧版两段式锚点按旧规则评估一次后迁移。
 *
 * 销毁执行不在本类：判定命中后委托 [DefenseProtocol]（共享同一把
 * 检查/销毁互斥锁，read-judge-destroy 整体串行）。
 *
 * 到期复查闹钟：每次检查通过（未到期）即按当前锚点剩余时间布防
 * [AlarmReceiver]——封堵"重启后检查通过、app 永不再打开、不再重启"
 * 场景下无第二个触发点的缺口。链条自续（闹钟到点复查→未到期再布防），
 * 不跨重启（重启由 BootCompletedReceiver 接管）。
 */
object IdleWatchdog {
    // prefs 文件名是隐蔽性设计（与验证器/KeyVault 同文件），冻结不变量
    private const val PREFS_NAME = "sync_preferences"

    // armed 哨兵：GateManager.setPasswords 与本类共写（见类注释），勿改键名
    private const val KEY_ARMED = "armed"
    private const val CONFIG_KEY_IDLE_LIMIT = "idle_limit"
    private const val CONFIG_KEY_IDLE_TS = "idle_ts"

    // 超时销毁默认档：6 个月（分钟）。首次启用/销毁后复位都用此值
    private const val DEFAULT_IDLE_LIMIT_MINUTES = 259200L

    // 双锚点自洽容差：同一开机内 (er-er0) 与 (wc-wc0) 偏差超过此值 = 非法
    private const val ANCHOR_DRIFT_TOLERANCE_MS = 10 * 60 * 1000L

    // 跨重启墙钟回拨容差（与 daemon.cpp ANCHOR_FREEZE_TOLERANCE_SEC=600s 对齐）：
    // NTP 小幅向后校正（秒级）是正常设备行为，硬性 wc<wc0 即引爆会把
    // "重启 + 时钟校正"的普通用户误杀（销毁不可逆，误报代价极高）。
    // 容差内的回拨最多把死线推迟同等时长（一次性，锚点不随回拨刷新），
    // 大幅回拨（>10min）仍按篡改引爆。与漂移容差共用 10min：RTC 纽扣
    // 电池老化（重启后时钟大幅回到过去）是稳定用户无过错可触发的硬件
    // 事件（误爆 = 双侧销毁），10min 容差下冻结/回拨攻击须持续维持
    // 异常状态才能获益，检测延迟不构成实质逃逸窗口
    private const val ROLLBACK_TOLERANCE_MS = 10 * 60 * 1000L

    // wc0 合理性区间下限（2020-01-01），防垃圾值
    private const val WC0_MIN = 1_577_836_800_000L

    // 错钟推迟路径的复查闹钟间隔（24h）：RTC 掉电用户的时钟恢复是
    // 分钟级；root 攻击者有 force-stop（杀闹钟，已声明边界）这条更
    // 便宜的路径——本闹钟防"低扰冻结取证"（无进程操作），对其 24h
    // 检出绰绰有余，取更小值只会打扰 Doze/耗电
    private const val RECHECK_ALARM_DELAY_MS = 24 * 60 * 60 * 1000L

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 可选档位（分钟）：5分钟 ~ 12个月，无禁用项 */
    val idleTimeoutOptions: List<Long> = listOf(
        5L, 30L, 60L, 360L, 1440L, 10080L,
        43200L, 129600L, 259200L, 525600L
    )

    fun isIdleArmed(): Boolean = prefs().getBoolean(KEY_ARMED, false)

    /**
     * idle 密文状态快照。
     * - readable=true：正常读取
     * - readable=false 且 retryable=false：DataStore/Tink 解密失败（密钥已死
     *   或密文损坏），调用方按"已销毁/被篡改"处理
     * - readable=false 且 retryable=true：DataStore **基础设施**异常
     *   （IllegalStateException——并发构造冲突/scope 状态错误等），绝非密文
     *   损坏。原实现的 catch-all 把它折叠为 readable=false 会让无头检查
     *   （Boot/Alarm）对完全健康的数据执行 8 步销毁。此类异常按"本轮无法
     *   判定"放行（不引爆、不 touch），判定推迟到下一个触发点——
     *   推迟销毁的代价远低于误毁
     * 任何情况下异常都不得逃逸导致启动崩溃循环。
     */
    private data class IdleState(
        val readable: Boolean, val retryable: Boolean = false,
        val limit: Long = 0L, val ts: String = ""
    )

    private suspend fun readIdleState(): IdleState = try {
        IdleState(
            true, false,
            ConfigManager.getDataOnce(appContext, CONFIG_KEY_IDLE_LIMIT, 0L),
            ConfigManager.getDataOnce(appContext, CONFIG_KEY_IDLE_TS, "")
        )
    } catch (e: IllegalStateException) {
        // 基础设施状态错误（构造竞态/scope 冲突）：与密文内容无关
        IdleState(false, retryable = true)
    } catch (_: IOException) {
        // DataStore IO 层异常（磁盘错误/读取中断/暂时性故障）：与密文
        // 内容无关。折叠为"密文不可读"会让一次暂时性读失败触发全量
        // 销毁——误毁代价远高于推迟判定，按"本轮无法判定"放行。
        // 真正的密钥死亡/密文损坏在 Tink 层抛 GeneralSecurityException，
        // 走下方分支照常引爆
        IdleState(false, retryable = true)
    } catch (_: Exception) {
        // Tink 解密失败（GeneralSecurityException）等：密钥已死/密文损坏
        IdleState(false, false, 0L, "")
    }

    /**
     * 读当前开机次数（跨重启单调递增，由 system_server 维护，用户态不可回拨）。
     * 读取失败返回 -1（个别设备不支持），调用方回退到双锚点启发式。
     */
    private fun readBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    /**
     * 写入三段式锚点：boot,elapsedRealtime,currentTimeMillis。
     * boot 显式区分开机周期——er 跨开机比较无意义，禁止用 er 大小猜测是否重启。
     *
     * 错误墙钟守卫：RTC 掉电/无网/关闭自动时间的设备墙钟可能停在
     * WC0_MIN 之前——垃圾 wc0 一旦写入，下一次检查即引爆（wc0<WC0_MIN
     * 判篡改）。拒绝写入（锚点保持旧值：旧锚点与本机 er 同开机时漂移
     * 校验照常，跨开机时墙钟判定继续用旧 wc0——时钟恢复后自愈）。
     * 时钟恢复（NTP/手动）后的前向跳变由 ANCHOR_DRIFT_TOLERANCE 吸收
     * 不了的情形同样依赖此守卫：错钟期间没写过锚点，就不会有
     * "错钟 wc0 + 校正后 wall"的超漂移误判。
     *
     * @return false = 未落盘（错钟拒绝 / IO 失败）。调用方据此避免
     * 制造 "(armed, limit>0, ts 空)" 的引爆态（见 setIdleTimeout）
     */
    private suspend fun writeAnchor(): Boolean {
        val wc = System.currentTimeMillis()
        if (wc < WC0_MIN) return false
        val ts = "${readBootCount()},${SystemClock.elapsedRealtime()},$wc"
        return runCatching { ConfigManager.saveData(appContext, CONFIG_KEY_IDLE_TS, ts) }.isSuccess
    }

    /**
     * 超时销毁是否真正启用过（区别于 armed-only 的门禁态）。
     * 判定只看 limit：limit>0 即启用，ts 是否存在不参与
     * （写入时序为 ts 先 limit 后，limit>0 而 ts 缺失 = 篡改，交给雷管处理）。
     */
    suspend fun isIdleActivated(): Boolean {
        if (!isIdleArmed()) return false
        return readIdleState().let { it.readable && it.limit > 0 }
    }

    /**
     * 冷启动超时判定（在门禁验证与一切配置加载之前调用）。
     * 读到任何不合法状态 → 雷管引爆（fail-destroy）。
     *
     * 并发语义：tryLock——已有检查在途时本次直接放行（在途检查读取的是
     * 同一状态，其判定结果覆盖本次）；持有锁期间完成 read-judge-destroy
     * 全序列。MainActivity 的 runBlocking 调用因此不会被在途销毁阻塞
     * 主线程（销毁含 IO 等待，最长 ~5s）
     *
     * 状态判定规则：
     * - limit<=0：一律视为未启用，忽略 ts 残留（部分写入态/垃圾值不引爆）
     * - limit>0 且不在档位表：引爆
     * - limit>0 且 ts 为空：引爆（正常写入时序为 ts 先写，此状态不可达，
     *   出现即密文被定向篡改；旧版本启用过超时的用户本就会被旧代码引爆，
     *   行为一致）
     * - 锚点三段式 boot,er,wc：boot 相等走双锚点交叉校验；boot 增大（重启）
     *   走墙钟判定；boot 减小（不可回拨却回拨）引爆
     * - 墙钟倒退判定带容差（ROLLBACK_TOLERANCE_MS）：NTP 小幅向后校正是
     *   正常设备行为，不误杀（销毁不可逆，误报代价极高）；大幅倒退仍按
     *   回拨/篡改引爆。同开机到期判定只依赖 er（单调不可回拨）
     * - 旧版两段式锚点 er,wc：按旧规则评估一次（不比旧代码更严），通过后
     *   迁移写入新格式
     *
     * 通过（未到期）即按当前锚点剩余时间布防到期复查闹钟——保证
     * "app 永不再打开且不再重启"时销毁仍有触发点（见 armDeadlineAlarm）。
     *
     * @return true = 已触发销毁（调用方无需额外处理，销毁含复位）
     */
    suspend fun checkIdleExpired(): Boolean {
        val result = DefenseProtocol.tryWithDestroyLock { checkIdleExpiredLocked() }
        return result ?: false
    }

    private suspend fun checkIdleExpiredLocked(): Boolean {
        // 验证器存在而 armed 消失 = sync_preferences 被定向篡改 = 自毁
        if (GateManager.isGateEnabled() && !isIdleArmed()) {
            DefenseProtocol.destroyForCoercionLocked()
            return true
        }
        // 未 armed 且未设门禁 = 从未启用（全新安装/存量未使用用户），正常流程
        if (!isIdleArmed()) return false

        val st = readIdleState()
        // 基础设施异常（DataStore 构造竞态/scope 状态错）：绝非密文损坏，
        // 引爆判定推迟到下一触发点（不引爆、不 touch、不布防——防误毁
        // 优先于销毁及时性；ConfigManager 已加构造互斥，此为纵深兜底）
        if (!st.readable && st.retryable) return false
        // 密文不可读（Tink/Keystore 已死或密文损坏）：按已销毁处理，走销毁复位，
        // 不让异常逃逸造成崩溃循环
        if (!st.readable) {
            DefenseProtocol.destroyForCoercionLocked()
            return true
        }
        // limit<=0：未启用。ts 残留视为垃圾忽略（修复：旧版会把 (0, ts≠"") 引爆）
        if (st.limit <= 0L) return false
        // 注：旧版对 limit 不在档位表即引爆——该值存于 GCM 加密的
        // DataStore，攻击者无法在不持密钥的情况下写入任意值（密文一旦
        // 被改写，解密直接失败走 readable=false 路径）；"表外值"的唯一
        // 现实来源是未来版本新增档位后降级安装旧版——引爆 = 纯误毁。
        // 表校验仅保留在 UI 层（getCurrentIdleTimeout 显示用）
        // limit>0 而 ts 为空：写入时序上不可达（ts 先写），出现即篡改
        if (st.ts.isEmpty()) {
            DefenseProtocol.destroyForCoercionLocked()
            return true
        }

        val limitMs = st.limit * 60_000L
        val boot = readBootCount()
        val er = SystemClock.elapsedRealtime()
        val wc = System.currentTimeMillis()
        // 错钟守卫（收窄版）：当前墙钟明显不可信（RTC 耗尽回到出厂值/
        // 1970，NTP 未及恢复）时，一切依赖 wc 的判定（漂移/倒退/跨重启
        // 到期）推迟到时钟恢复后的下一触发点——继续走必然引爆真实用户
        // （误毁零容忍）。到期判定虽只依赖单调 er，但错钟期间 writeAnchor
        // 拒绝落盘 → 锚点无法续期 → "钟坏但活跃"的用户（RTC 掉电 + 无网，
        // 短档位）会被陈旧 er0 误判到期，同样推迟。冻结/回拨方向由 daemon
        // 侧 uptime 锚点兜底（不依赖墙钟）。
        // 时钟无关的篡改判定不受守卫影响，照常执行（封堵"冻结时钟至
        // <2020 令全部判定失效"的旁路）：锚点格式非法/字段越界/
        // BOOT_COUNT 回退/同开机 er 回退——四者均为精确不变量，与当前
        // 墙钟无关，错钟期间执行零误毁风险
        val wallOk = wc >= WC0_MIN
        val parts = st.ts.split(",")

        // 通过路径的到期剩余毫秒（布防闹钟用；null = 不布防）
        var armDelayMs: Long? = null

        when (parts.size) {
            3 -> {
                val boot0 = parts[0].toIntOrNull()
                val er0 = parts[1].toLongOrNull()
                val wc0 = parts[2].toLongOrNull()
                if (boot0 == null || er0 == null || wc0 == null ||
                    er0 < 0 || wc0 < WC0_MIN
                ) {
                    DefenseProtocol.destroyForCoercionLocked()
                    return true
                }
                if (boot < 0 || boot0 < 0) {
                    // 设备不支持 BOOT_COUNT：退回双锚点启发式（与旧行为一致）。
                    // 注意：评估完直接短路（不走下方 when）——否则 boot=-1 与
                    // 合法 boot0 比较落入 else 分支误判"BOOT_COUNT 回退"误杀。
                    // 启发式含墙钟判定，错钟期间整体推迟
                    if (wallOk) {
                        if (checkLegacyAnchor(er0, wc0, er, wc, limitMs)) {
                            DefenseProtocol.destroyForCoercionLocked()
                            return true
                        }
                        armDelayMs = legacyRemaining(er0, wc0, er, wc, limitMs)
                    }
                } else when {
                    boot == boot0 -> {
                        // 同一开机：er 单调，er<er0 即非法（时钟无关，
                        // 错钟期间照常执行）
                        if (er < er0) {
                            DefenseProtocol.destroyForCoercionLocked()
                            return true
                        }
                        if (wallOk) {
                            val erDiff = er - er0
                            val wcDiff = wc - wc0
                            // 双锚点交叉校验——单向引爆（仅 wc 落后 er 超容差）：
                            // - 落后方向（erDiff - wcDiff > 容差）= 墙钟冻结/回拨，
                            //   是攻击方向（拖后墙钟 = 延长跨重启死线）→ 引爆
                            // - 超前方向（wc 前跳）= 用户手动对时/长途时区跨越/
                            //   长时间离线后 NTP 步进校正，攻击无收益（同开机死线
                            //   只依赖 er，前跳只会让墙钟死线提前）→ 绝不引爆
                            //   （误毁零容忍）。不重写锚点：er0 是同开机死线基准，
                            //   重写会放宽它；跨重启 wc0 基线保持旧值，前跳至多
                            //   令跨重启判定顺延同等时长，无引爆风险
                            if (erDiff - wcDiff > ANCHOR_DRIFT_TOLERANCE_MS) {
                                DefenseProtocol.destroyForCoercionLocked()
                                return true
                            }
                            if (erDiff >= limitMs) {
                                DefenseProtocol.destroyForCoercionLocked()
                                return true
                            }
                            armDelayMs = (er0 + limitMs) - er
                        }
                    }

                    boot > boot0 -> {
                        // 重启过（可能多次）：er 跨开机比较无意义，墙钟判定。
                        // 大幅倒退（超出容差）= 回拨 = 非法；容差内倒退容忍
                        // （NTP 校正），死线至多顺延同等时长。错钟期间墙钟
                        // 判定无意义，整体推迟
                        if (wallOk) {
                            if (wc < wc0 - ROLLBACK_TOLERANCE_MS) {
                                DefenseProtocol.destroyForCoercionLocked()
                                return true
                            }
                            if (wc - wc0 >= limitMs) {
                                DefenseProtocol.destroyForCoercionLocked()
                                return true
                            }
                            armDelayMs = (wc0 + limitMs) - wc
                        }
                    }

                    else -> {
                        // BOOT_COUNT 单调递减：不可回拨却回拨 = 篡改
                        DefenseProtocol.destroyForCoercionLocked()
                        return true
                    }
                }
            }

            2 -> {
                // 旧版两段式锚点（更早版本写入）：按旧规则评估一次，通过则迁移新格式。
                // 评估逻辑与旧代码一致，不比旧版更严格（升级用户不引入新误炸）。
                // 字段合法性为时钟无关判定照常执行；墙钟评估与迁移写入
                // （writeAnchor 在错钟期间拒绝落盘，迁移必然失败）推迟
                val er0 = parts[0].toLongOrNull()
                val wc0 = parts[1].toLongOrNull()
                if (er0 == null || wc0 == null || er0 < 0 || wc0 < WC0_MIN) {
                    DefenseProtocol.destroyForCoercionLocked()
                    return true
                }
                if (wallOk) {
                    if (checkLegacyAnchor(er0, wc0, er, wc, limitMs)) {
                        DefenseProtocol.destroyForCoercionLocked()
                        return true
                    }
                    writeAnchor()
                    armDelayMs = legacyRemaining(er0, wc0, er, wc, limitMs)
                }
            }

            else -> {
                DefenseProtocol.destroyForCoercionLocked()
                return true
            }
        }
        // 同开机：er 单调 → 到期点 = er0 + limit（不可回拨，精确）；
        // 跨开机：只剩墙钟 → 到期点 = wc0 + limit（大幅时钟回拨由下次复查的
        // wc < wc0-容差 判定捕获，闹钟至多被延迟，不会缺席）
        armDelayMs?.let { armDeadlineAlarm(it) }
        // 错钟推迟路径的"下一个触发点"：wallOk=false 时若用户不再打开
        // app 且不再重启，判定被无限期悬空（冻结时钟类攻击的目标态）。
        // 布防 ELAPSED_REALTIME 复查闹钟（单调时钟，冻结墙钟无效）：
        // 到点时钟已恢复 → 正常判定；仍错 → 持续错钟 → 按冻结引爆。
        // 24h 足够：RTC 掉电用户的 NTP/手动恢复是分钟级；root 攻击者
        // 本可 force-stop 杀闹钟（已声明边界），本闹钟防的是"低扰冻结
        // 取证"（无进程操作），对其 24h 检出绰绰有余。独立 requestCode
        // 不与死线闹钟互相覆盖；到点仍未到期会按剩余时间重新布防
        //（本方法的 armDeadlineAlarm 路径），链条自续
        if (!wallOk && limitMs > 0) {
            armRecheckAlarm()
        }
        return false
    }

    /**
     * 双锚点启发式（旧版规则，仅两处使用）：
     * er>=er0 视为同一开机做交叉校验；er<er0 视为重启走墙钟判定。
     * BOOT_COUNT 不可用设备的新锚点（boot=-1）也走此路径。
     * 漂移校验与三段式分支同为单向（仅 wc 落后方向引爆，见其注释）。
     */
    private fun checkLegacyAnchor(
        er0: Long, wc0: Long, er: Long, wc: Long, limitMs: Long
    ): Boolean {
        if (er >= er0) {
            // 同开机语义（与三段式分支一致）：到期只看 er；漂移仅 wc 落后
            // 方向引爆（wc 前跳 = 用户对时，攻击无收益，绝不引爆）
            val erDiff = er - er0
            val wcDiff = wc - wc0
            if (erDiff - wcDiff > ANCHOR_DRIFT_TOLERANCE_MS) {
                return true
            }
            return erDiff >= limitMs
        }
        // er 倒退：按重启处理，墙钟判定（回拨容差同三段式分支）
        if (wc < wc0 - ROLLBACK_TOLERANCE_MS) return true
        return wc - wc0 >= limitMs
    }

    /** 旧版锚点规则下的到期剩余毫秒（调用前提：checkLegacyAnchor 已通过，恒正） */
    private fun legacyRemaining(
        er0: Long, wc0: Long, er: Long, wc: Long, limitMs: Long
    ): Long =
        if (er >= er0) (er0 + limitMs) - er else (wc0 + limitMs) - wc

    /**
     * 布防到期复查闹钟（ELAPSED_REALTIME_WAKEUP：单调时钟 + 睡眠中唤醒）。
     *
     * 精确性由 manifest 权限组合保证（安装即授予，无需用户操作）：
     * - API 30：精确闹钟无需权限（Android 12 前不受限）
     * - API 31-32：SCHEDULE_EXACT_ALARM（normal 级，安装即授予）
     * - API 33+：USE_EXACT_ALARM（安装即授予，用户/系统均不可撤销）
     *
     * 降级路径（仅 Android 12/12L 用户手动撤销权限的罕见情形）：
     * setAndAllowWhileIdle——完全不可见（无任何状态栏图标），Doze 下可
     * 延迟约 9-15 分钟，销毁被推迟但不缺席。曾评估 setAlarmClock（免权限
     * 且精确）但已否决：它会登记为系统"下一个闹钟"，状态栏出现图标——
     * 常态暴露不可接受；且 IAlarmManager 的精确性校验在 system_server 侧，
     * 客户端 hidden API 反射无法绕过，不存在"零权限零图标精确"路径。
     *
     * FLAG_UPDATE_CURRENT：重复布防覆盖旧闹钟（幂等，最多一枚在途）。
     */
    private fun armDeadlineAlarm(delayMs: Long) {
        if (delayMs <= 0L) return
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(
                appContext, 0,
                Intent(appContext, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerElapsed = SystemClock.elapsedRealtime() + delayMs
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending
                )
            } else {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending
                )
            }
        }
    }

    /**
     * 错钟推迟路径的复查闹钟（24h，requestCode=1 与死线闹钟互不覆盖）。
     * 机制与精确性声明同 [armDeadlineAlarm]（ELAPSED_REALTIME_WAKEUP
     * 单调时钟 + manifest 权限组合 + Doze 降级容差）。到点进入
     * AlarmReceiver → checkIdleExpired：时钟恢复 → 正常判定并按剩余
     * 时间布防；仍错 → 重新进入 wallOk=false 路径并再次布防本闹钟
     * （24h 链条自续，直到时钟恢复或 daemon 侧 uptime 死线完成销毁）
     */
    private fun armRecheckAlarm() {
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(
                appContext, 1,
                Intent(appContext, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerElapsed = SystemClock.elapsedRealtime() + RECHECK_ALARM_DELAY_MS
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending
                )
            } else {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pending
                )
            }
        }
    }

    /**
     * 刷新计时锚点（写入三段式锚点）。只在"有效使用"时调用：
     * 无门禁冷启动判定后 / 门禁验证通过后 / onStart 回前台 / RESUMED 心跳。
     *
     * 有门禁且本会话未验证通过时拒绝刷新——未验证的打开（含冷启动后的
     * 首个 onStart）对计时器透明，防止胁迫者在门禁页停留/反复打开续命。
     *
     * 门禁判定只看 limit>0（旧版要求 ts 非空导致首写不可达，已修复）。
     * 锚点写入后顺带向守护进程续期（daemon 不在线则静默跳过）。
     */
    suspend fun touchIdle() {
        if (!isIdleArmed()) return
        if (GateManager.isGateEnabled() && !GateManager.sessionUnlocked) return
        val st = readIdleState()
        if (!st.readable || st.limit <= 0) return
        writeAnchor()
        DaemonManager.renewIdleDeadline(st.limit)
    }

    /**
     * 首次启用/修改档位。写入时序严格：
     * ① armed 哨兵（明文，先立于不败）→ ② 锚点（无生效意义）→ ③ limit（提交标志）。
     * 任意步骤间崩溃产生的部分状态均为合法态：
     * (armed, 0, "") / (armed, 0, ts) → 未启用；(armed, limit>0, ts) → 完整启用。
     * "limit>0 而 ts 空"不可达，出现即篡改（雷管覆盖）。
     *
     * B1 修复：writeAnchor 在墙钟不可信（< WC0_MIN）时拒绝落盘——若此处
     * 无视失败继续写 limit，会产生 (armed, limit>0, ts="") 的"不可达"状态，
     * 下一次检查（ts 空检查先于错钟守卫执行）即判篡改引爆。错钟期间整体
     * 中止启用（本次设置不生效，时钟恢复后用户重新启用即可）；
     * 锚点 IO 失败同理——宁可不启用，不制造引爆态
     *
     * @return false = 启用中止（锚点落盘失败），本次设置未生效——调用方
     * 不得按已生效反馈（UI 回填新档位），否则用户看到"已启用 X 分钟
     * 销毁"而实际防护仍是旧值/未启用（虚假安全感）
     */
    suspend fun setIdleTimeout(minutes: Long): Boolean {
        // commit（同步落盘）：与注释"先立于不败"的时序声明一致——
        // apply 异步落盘在写入后数毫秒内进程死亡会丢失哨兵（无门禁
        // 用户启用超时后 app 侧超时静默失效）
        prefs().edit(commit = true) { putBoolean(KEY_ARMED, true) }
        if (!writeAnchor()) return false
        ConfigManager.saveData(appContext, CONFIG_KEY_IDLE_LIMIT, minutes)
        DaemonManager.renewIdleDeadline(minutes)
        return true
    }

    /** 当前档位（未启用返回 null，用于设置页副标题） */
    suspend fun getCurrentIdleTimeout(): Long? {
        if (!isIdleArmed()) return null
        val st = readIdleState()
        if (!st.readable) return null
        return if (st.limit > 0 && st.limit in idleTimeoutOptions) st.limit else null
    }

    /**
     * 销毁后复位（由 [DefenseProtocol] 在销毁序列内调用）：防连环雷管
     * 自毁循环（销毁清空 DataStore → 读默认 0 → 再引爆）。
     * 激活态由调用方在 wipe 之前快照传入（wipe 后读取恒为未启用，
     * 旧实现因此恒为 no-op）。仅"真正启用过"（limit>0）时写默认档 +
     * 当前锚点，计时器自愈；armed-only（只设过门禁未启用超时）销毁后
     * 回到未启用态 = 全新状态语义。armed 永不清除。
     *
     * B1 同源修复：锚点先行（与"ts 先 limit 后"不变量一致）——锚点
     * 落盘失败（错钟/IO）则不写 limit，保持销毁后的未启用态
     * （limit 已被 wipe 清零），绝不制造 (limit>0, ts="") 引爆态
     */
    internal suspend fun resetIdleAfterDestroy(wasActivated: Boolean) {
        if (!wasActivated) return
        if (!writeAnchor()) return
        ConfigManager.saveData(
            appContext, CONFIG_KEY_IDLE_LIMIT, DEFAULT_IDLE_LIMIT_MINUTES
        )
    }
}
