# defense 包：隐私防御体系（vault 架构）

所有与"对抗取证者/root/注入/hook"相关的防御代码集中于此包。
本 README 是威胁→防线→代码位置的地图，与层级依赖规则。

## 核心架构决策：密钥保管进程隔离（vault）

DK（数据密钥）的唯一持有者是**独立 vault 进程**（打包为
`libsyncsvc.so`，源码 [vault.cpp](../../../../cpp/vault.cpp)，无 ART）。
主 app 进程——无论 Java 层被如何 hook——永远只能拿到密文与
"请求-结果"RPC 接口，拿不到 DK/CK 本身。

这是**结构性**防御而非检测性防御：不试图检测 hook 是否存在，
而是让 hook 无物可偷。LSPlant/自研 Java hook 框架对无 ART 的
进程无处下钩；同 uid ptrace 被 vault 的 `PR_SET_DUMPABLE=0`
内核级拒绝；root 直读 vault 内存是已声明边界（威胁表 #21）。

进程形态：app 侧 native（libmemsys.so 内 vault\_client）double-fork
+execve 启动，协议跑在 fork 前 socketpair 的 dup2(fd,0/1) 上——
无文件系统路径、无 listen，物理上只有主进程持对端。主进程死亡 =
EOF = vault 清密钥退出；vault 死亡由下一次 RPC 感知并懒重启。

## 层级结构（依赖只能向下）

```
L3  集成层（包外）        MainActivity / BootCompletedReceiver / 磁贴 / 各页面
        │  调用 defense 公开 API
L2  策略层（本包）        DefenseProtocol · GateManager · IdleWatchdog
        │  编排 L1 原语
L1  原语层（本包）        VaultClient · SensitiveStore
        │  RPC 帧协议（socketpair，vault_client.cpp 管道）
L0a 保管层（独立进程）    vault.cpp（DK 唯一持有者，全部 DK/CK 密码学操作）
L0b 执行层（本包+native） GuardManager（JNI 桥）→ guard.cpp（主进程雷管）
```

包外依赖仅两类，均受控：

* `wrappers.ConfigManager / DaemonManager / ScreenShareManager /
  OverlayServiceManager`：

  * SensitiveStore 经 ConfigManager 读写密文 DataStore（存储介质）；

  * **DefenseProtocol 是 defense 包唯一允许向上引用业务服务的点**
    （销毁必须停共享/守护进程/悬浮窗）——新增向上引用必须先在该类
    头注释文档化理由。

包内协作环（运行时方法调用，非初始化依赖，安全）：
IdleWatchdog 判定命中 → DefenseProtocol 销毁 → 销毁序列回调
IdleWatchdog.resetIdleAfterDestroy。

## 威胁 → 防线 → 代码位置

| #  | 威胁                                                                                                     | 防线                                                                                                                                                                                                                 | 代码位置                                                                                                                                        |
|----|--------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | root 拷走密文文件离线爆破门禁密码                                                                                    | Argon2id（t=3, m=64MiB, p=1，OWASP 推荐档）内存硬 KDF——拖库后离线试密码每次付 64MiB 内存 + 数百 ms；pepper 体系随 KeyVault 退役（Keystore 硬件密钥本就不可迁移，被扣押设备上密文与条目同毁，pepper 无增量）                                                                    | vault.cpp ARGON\_\* 与 DK 包裹格式                                                                                                               |
| 2  | 硬件断点/软件 patch hook 比较函数恒真恒假，一行绕过门禁                                                                     | 消灭比较点：vault 内密码正确性 = GCM 解密 tag 校验（ARMv8 密码学层），vault 内不存在可被 hook 的应用层比较函数；vault 进程无 ART，Java hook 框架无处下钩                                                                                                           | vault.cpp 验证哲学（头注释）；tryUnwrapDk                                                                                                             |
| 3  | Java 层 hook（LSPlant/ArtMethod swap）操控 app 交出 DK/凭据                                                     | 结构隔离：DK/CK 永不出 vault，主进程（含被 hook 的 Java 层）只有密文与 RPC；检测对象消失——旧栈流审计/常量时间双实现比较随之退役（武器失去靶标，非撤销武装），残余暴露见 #23                                                                                                            | vault.cpp（进程形态）；vault\_client.cpp（spawn/管道）；VaultClient.kt（RPC 门面）                                                                          |
| 4  | root-as-uid 解密 DataStore 读 SSH/共享凭据全文                                                                  | 敏感字段 DK 第二层加密（`<key>_sec`），seal/open 的执行者在 vault 进程内（DK 不经主进程）                                                                                                                                                     | SensitiveStore；vault.cpp opSeal/opOpen（含流式 FSEAL/FOPEN）                                                                                     |
| 5  | 劫持主进程 native 执行路径（hook 检测本身/引爆逻辑/雷管函数）；GG 修改器类 ptrace 扫描器                                              | 自我 hook 审计三线（GOT/PLT 越界 + 入口序言模式 + 信号 handler 归属，DuckDetector 方法论——检测劫持效果而非注入痕迹，与载体隐蔽方式无关）+ TracerPid 1s 快轮询 + PR\_SET\_DUMPABLE=0；检测与兜底引爆全在 native（Java 层被接管仍工作；引爆=先经 socketpair 令 vault DESTROY，再覆写密文文件+SIGKILL） | guard.cpp audit\_execution\_paths（watchdog 自主线程）；GuardManager.checkNow→DefenseProtocol 完整销毁；vault\_client.cpp vault\_detonate\_best\_effort |
| 6  | inline patch 改已有代码（nop 掉引爆分支/改检测判定常量）                                                                  | 自完整性校验：.text 与磁盘基准逐字节比对                                                                                                                                                                                            | guard.cpp check\_self\_integrity                                                                                                            |
| 7  | 胁迫场景（被逼交出密码）                                                                                           | 双密码门禁：胁迫验证项命中 → vault **就地**改写 sync\_key.bin 为销毁态（DK 立即密码学死亡，Java 层被拦截也已完成）→ 返回 COERCION 由 Java 侧执行完整销毁序列——双层独立引爆；界面无任何区分；验证项跨销毁保留（门禁行为前后一致，不暴露"销毁发生过"）                                                            | vault.cpp UNLOCK 胁迫分支；DefenseProtocol.destroyForCoercion；GatePage                                                                           |
| 8  | 设备被扣后长期不使用；重启后 app 永不被打开                                                                               | 未使用自动销毁**始终武装**（装机即默认 6 个月，不存在"未启用"态，用户只能选时长 5min\~12mo）：三段式锚点（BOOT\_COUNT+er+wc）反回拨（墙钟倒退/漂移带 10min 容差，NTP 校正与 RTC 纽扣电池老化不误杀，与 daemon 侧 600s 对齐），检查通过即布防到期复查闹钟（链条自续）                                               | IdleWatchdog；BootCompletedReceiver（重启缺口）；AlarmReceiver（到期复查）                                                                                |
| 9  | root dump 内存抓 DK（SIGSTOP 先手/进程驻留）                                                                      | DK 驻留窗口 = vault 进程内的解锁会话：息屏立即/后台 30s/前台无操作 5min 自动锁定（vault 清 DK）；锁定后磁贴与敏感功能 fail-closed，超时计时不受影响                                                                                                                   | vault.cpp opLock；GateManager.lockSession；MainActivity 心跳                                                                                    |
| 10 | 激活/改密/移除门禁时崩溃致密钥孤儿化                                                                                    | 单文件状态机（sync\_key.bin）+ tmp+rename 原子写——状态机内无跨文件事务窗口（旧 DK 迁移事务协议随 KeyVault 退役）                                                                                                                                      | vault.cpp writeLivePw/writeLiveWk                                                                                                           |
| 11 | 在线爆破门禁密码（被扣押设备上交互式试密码）                                                                                 | UNLOCK 在线限速：连续 5 次失败起指数退避（1s,2s,4s…封顶 60s），成功复位；计数器仅驻内存（vault 被杀重启即复位，但每次尝试本身要付一次 64MiB Argon2id，重杀重启的攻击成本不低于等待退避）                                                                                                 | vault.cpp recordFailure/resetFailure                                                                                                        |
| 12 | 恶意悬浮窗盖密码框 tapjacking/偷窥                                                                                | 通知栏遮盖防护（HIDE\_OVERLAY\_WINDOWS：31+ 公开 API；API 30 hidden flag 经 HiddenApiBypass 反射）                                                                                                                                 | MainActivity.applyOverlayProtection                                                                                                         |
| 13 | 胁迫者翻最近任务归因"app 刚被用过"                                                                                   | 最近任务排除（默认开启；运行时 setExcludeFromRecents）                                                                                                                                                                             | MainActivity.applyWindowSecurityConfig                                                                                                      |
| 14 | 无头销毁路径（Boot/AlarmReceiver goAsync \~10s 预算）被挂起拖垮：root 设备 exec 卡 su 授权弹窗数十秒 → 进程在删 Keystore 前被广播 ANR 杀掉 | 销毁步骤 1-2（停共享/停 daemon）有界执行（3s/3.5s），超时后台继续、序列推进到 Keystore 删除（密码学销毁优先于进程清理）                                                                                                                                         | DefenseProtocol.runBounded                                                                                                                  |
| 15 | 磁贴冷启动进程（未开过 app 即点共享磁贴）                                                                                | 全入口 defense 组件初始化（lateinit context 漏初始化即崩溃）                                                                                                                                                                        | ScreenShareManager.toggleScreenShare（与 Screenshot/Record 磁贴一致）                                                                              |
| 16 | 本地恶意进程 connect daemon 固定端口后不发数据，阻塞单线程命令循环                                                              | 控制信道 accept 后设 5s 读超时（SO\_RCVTIMEO），超时按坏连接关闭                                                                                                                                                                       | daemon.cpp accept 循环                                                                                                                        |
| 17 | 共享配置对话框回填遗漏密码字段 → 确认时旧密码被静默抹除                                                                          | 对话框打开时回填密码；putSensitive 空值写空串（清空=未配置语义，app/daemon 两侧一致判定）；接收配置保存检查写入返回值并提示失败                                                                                                                                       | ExtensionPage 对话框回填；SensitiveStore.putSensitive；ScreenShareReceiverManager                                                                  |
| 18 | 悬浮窗服务先于 MainActivity 运行 → 通知渠道以默认名创建，系统设置永久残留（取证指纹）                                                    | 渠道随机化前移至 Application.onCreate（早于一切组件），MainActivity 侧调用保留为幂等兜底                                                                                                                                                      | randomizeOverlayChannelNames；LSPosedServiceManager.onCreate                                                                                 |
| 19 | 冻结墙钟至 <2020 令看门狗整体失明（销毁被无限推迟；RTC 掉电用户也会落入同态）                                                           | 错钟期切换 uptime 数轴判定：daemon 侧 /proc/uptime 单调死线（同开机换算剩余量 / 跨开机 limit×60 重基线 / 恢复期逆换算回墙钟 + WC0 垃圾死线守卫）；app 侧错钟守卫推迟墙钟判定但时钟无关篡改判定照常 + 24h 复查闹钟（单调时钟，链条自续）                                                                | daemon.cpp watchdog\_main 错钟分支；IdleWatchdog（wallOk 守卫 + armRecheckAlarm）                                                                    |
| 20 | 错钟/IO 期间锚点无法续期 → 持续活跃用户被陈旧 er0/wc0 误判到期（误毁）                                                            | 单调活性凭证（明文键 sync\_cycle，"boot,er"）：同开机推进到期基线 max(er0,e)；跨开机锚点同轴换算（最后活跃真实墙钟 ≈ wc0+e−er0，仅 est>wc0 时生效，永不提前引爆）；当前开机窗口豁免；豁免路径以有效基线布防闹钟（链条不死）                                                                           | IdleWatchdog（KEY\_TOUCH；touchIdle 写入端 / checkIdleExpiredLocked 消费端）                                                                         |
| 21 | root 经 /proc/pid/mem、process\_vm\_readv 静默直读 vault 内存抓 DK                                              | **声明边界**：内核无"内存被读"通知，原理上不可检测。缓解=缩小 DK 驻留窗口（#9）+ dumpable=0 挡同 uid（无 root 的恶意 app 完全无法 attach/dump）；root dump vault 是已声明边界                                                                                          | guard.cpp 诚实边界；vault.cpp prctl                                                                                                              |
| 22 | 无门禁模式（LIVE\_WK）的固有弱保护                                                                                  | **声明边界**：DK 常驻 vault 内存（等价旧单段模式从盘自动恢复的自选弱保护，LOCK 对该模式无意义，跳过）；WK（无门禁包裹密钥）在 vault 冷启动时经主进程瞬时转交——无门禁模式固有下界                                                                                                            | vault.cpp 状态机 ST\_LIVE\_WK；VaultClient WK 递交                                                                                                |
| 23 | 被劫持的主进程在解锁会话内冒调 open/compose 逐条取明文（RPC oracle）                                                         | **声明边界**：与"用户主动查看凭据"同级（T3）——偷不到 DK，无法离线/锁后解密，密钥不泄露，仅操作可冒用；锁后/杀 vault 后 oracle 即死                                                                                                                                   | VaultClient 头注释；vault.cpp opOpen/opCompose（dkValid 门禁）                                                                                      |

## 组件职责一句话

* **DefenseProtocol**：统一销毁入口（8 步序列，含 2.5 vault 层销毁）+ 检查/销毁互斥锁 + init 汇聚点（幂等）

* **GateManager**：启动门禁编排器（瘦身后：验证与密钥操作全部下沉 vault，本类只保留会话状态镜像与历史调用面兼容）

* **VaultClient**：vault RPC 门面（帧编解码、懒 spawn、WK 递交、状态镜像；DK/CK 永不进入 Java 层）

* **IdleWatchdog**：超时销毁状态机（始终武装；锚点/档位/复位/到期闹钟布防/单调活性凭证豁免与跨开机换算）

* **AlarmReceiver**：到期复查闹钟入口（未到期重排链条自续；不跨重启，由 BootCompletedReceiver 接管）

* **SensitiveStore**：敏感字段读写/响应式流（`_sec` 密文，seal/open 委托 vault）

* **GuardManager**：libmemsys.so 的 Java 桥（init/checkNow；三线+TracerPid+自完整性的编排入口）

* **vault.cpp**（cpp/ 目录，打包 libsyncsvc.so）：DK 唯一持有者——Argon2id 验证（消灭比较点）、DK 包裹落盘、seal/open 流式加密、CK 派生（HMAC-SHA256(DK,"ScreenshotFaker/channel/v1")，DK 永不出 vault）、UNLOCK 限速、胁迫就地销毁

* **vault\_client.cpp**（cpp/ 目录，链入 libmemsys.so）：vault 启动器（double-fork+execve、socketpair 管道、close\_range 收紧 fd）与雷管联动（vault\_detonate\_best\_effort）

* **guard.cpp**（cpp/ 目录，打包 libmemsys.so）：主进程雷管（收缩版纵深）——watchdog、自我 hook 审计三线、TracerPid 轮询、自完整性；引爆先联动 vault DESTROY 再覆写密文+SIGKILL

## 冻结不变量（改动前必读）

以下存储契约与协议接口**任何重构不可变更**：

* 文件 `filesDir/sync_key.bin`（vault 拥有）：状态机
  `[0]'K' [1]ver=1 [2]state`，state ∈ LIVE\_PW/LIVE\_WK/DEAD\_PW
  （详见 vault.cpp 头注释的逐字段格式）；tmp+rename 原子写，
  `sync_key.bin.tmp` 是事务临时态

* 文件 `filesDir/sync_wrap.bin`（VaultClient 拥有）：WK 包裹
  （Keystore hardware\_encryption\_key AES-GCM）

* Keystore alias：`hardware_encryption_key`（WK 包裹）、
  `tink_master_key`（DataStore 加密）；`gate_pepper_key` 随 pepper
  体系退役

* DataStore 敏感字段后缀 `_sec`；idle 键 `idle_limit`/`idle_ts`；
  明文 prefs `sync_preferences` 的 `sync_cycle`（活性凭证）——
  中性命名是隐蔽性设计；旧验证器键（token\_hash\*/backup\*/dk\_\*/
  armed/dk\_split 等）为死数据，无读取方，清理即删

* 销毁步骤顺序（见 DefenseProtocol 头注释）；vault 验证项跨销毁
  保留（门禁行为前后一致）

* 帧协议 op 码：vault.cpp `Op` 枚举与 VaultClient.kt 常量一一对应
  （0x01~0x13），改动两侧同步；帧格式 `[4B BE 长度][payload]`

* JNI 符号 `Java_fake_screenshot_defense_GuardManager_*`（2 个）
  与 `Java_fake_screenshot_defense_VaultClient_*`（4 个：
  nativeStart/nativeRequest/nativeAlive/nativeClose）——包名/类名
  即契约，重命名必须同步 native 侧并以 `nm -D` 验证全部 4 ABI

* native 库名中性化契约：guard→**libmemsys.so**（guard.cpp 自完整性
  按它定位自身映射）、daemon→**libnetsvc.so**、vault→**libsyncsvc.so**
  （cmdline 可见）、scrcpy-server→**libextsvr.so**——改名必须同步
  CMake OUTPUT\_NAME、Kotlin/C++ 引用、guard.cpp 自完整性定位

* **构建脚本（\*.gradle.kts）只允许改字符串字面量，禁止改构建逻辑**
  （task 结构/dependsOn/commandLine/inputs/outputs）——维护者本地为
  Windows，逻辑变更极易导致其构建失败。隐蔽性改名只动：CMake
  OUTPUT\_NAME、Kotlin/C++ 中的库名与类名引用、gradle 中成对出现的
  输出文件名与读取路径（二者必须同步，否则构建找不到产物）

## 用户高风险操作导致的自毁（面向使用者的诚实告知）

销毁不可逆（删 Keystore 条目 + 全部密文）。以下行为会触发它——
其中一类是**用户主动设计用途**，另一类是**无意高风险操作被防线
正确判定**。使用者必须知情：

**主动触发（功能设计本身）：**

* **输入胁迫密码**：门禁页输入胁迫密码 = vault 立即就地销毁 DK +
  完整销毁序列（界面无任何区分，这是防胁迫的核心设计）。忘记自己
  设过胁迫密码而误输入 = 误毁，无法撤销。

* **选择短档位超时**：始终武装下不存在"启用"动作——装机即默认
  6 个月，改选 5 分钟档后，app 退后台且不解锁设备超过档位时长
  （计时基于锚点，重启/闹钟都无法绕过）即自毁。档位选择即销毁承诺。

**高风险操作被防线判定（多为无意触发）：**

* **忘记安全密码**：DK 的包裹密钥仅由安全密码派生、只存在于用户
  记忆。忘记密码后：

  * 无任何恢复途径（无密码提示、无安全问题、无云端备份——
    这是防拷贝取证的设计前提）；

  * 改密自救不可用：任意错误密码在 vault 验证层即被拒（BAD，
    无害），但**误用胁迫密码改密/移除门禁**会命中胁迫验证项 →
    vault 孤儿化重生成（新随机 DK，门禁照常，历史密文永不可解）
    \= 历史加密产物软销毁（vault.cpp verifyCurrent 胁迫分支）。

* **大幅回拨系统时钟**（root 或恢复模式改时间）：墙钟倒退/漂移
  超过 10min 容差，或冻结墙钟（倒退至 <2020 更会被错钟分支以
  uptime 数轴判定）= 按防回拨/防冻结引爆。正常 NTP 校正、RTC
  纽扣电池老化（10min 容差内）不会触发。

* **重启后大幅前跳系统时钟**：同开机判定只依赖单调时钟，墙钟
  前跳（用户对时/时区跨越/NTP 步进）绝不引爆；但**跨开机**后
  墙钟是唯一证据源，"真实闲置超期"与"时钟前跳 Δ"原理上不可
  区分——重启 + 前跳幅度超过（档位时长 - 真实闲置）时会提前
  引爆。短档位（如 5 分钟）+ 长期离线后联网 NTP 大步进校正的
  组合可能命中；需要绝对安全请用较长档位。

* **篡改本 app 私有存储**（root 备份/恢复、钛备份、手动改
  prefs/DataStore 文件）：

  * 损坏 idle 密文锚点或 vault 密钥文件（GCM 解密失败/文件不可
    解析）→ 按已销毁/疑似篡改处理（vault CORRUPT 态上报
    gateOn 是 fail-closed 决策）；

  * 整体备份恢复到新设备 → Keystore 条目不存在 → 密文全部
    不可解（等效销毁，此为防移植取证的设计边界）。

**已被防线排除的误毁（正常使用无需担心）：**

* 暂时性 IO 故障 / DataStore 基础设施异常：按"本轮无法判定"
  放行，不引爆（retryable 路径）；

* RTC 掉电期间持续使用：错钟期活跃由单调活性凭证豁免，时钟
  恢复后按换算基线判定（锚点同开机形态）；中间开机凭证的真实
  墙钟原理上不可恢复 → 豁免 + 以当前时钟重基线，一次性重计
  档位窗口（两段式旧锚点走近似豁免）；

* 错钟期改选档位：writeAnchor 拒绝落盘 → 本次设置不生效，
  不制造引爆态；

* 正常重启、force-stop、闹钟被 Doze 延迟：销毁被推迟但不缺席
  （重启缺口由 BootCompletedReceiver 接管）。

**新推迟面（如实记录）：** 两段式锚点的近似豁免路径在
`writeAnchor` 持续 IO 失败（墙钟健康 + DataStore 写持续失败
而读持续成功的复合故障）下构成"豁免 + 布防"循环，销毁被推迟
至 IO 恢复——推迟代价远低于误毁，daemon 侧看门狗为独立兜底。

## 已知边界（诚实声明，勿试图"修复"）

### 隐蔽性设计（进程/文件特征中性化）

运行时可见面（ps / maps / ls /data/local/tmp，无需 root 或需 root）：

* native 库名全部中性化：guard→**libmemsys.so**（maps）、
  daemon→**libnetsvc.so**（就地运行 cmdline）、
  vault→**libsyncsvc.so**（cmdline，与 daemon 同形——独立进程但
  无可区分特征）、scrcpy-server→**libextsvr.so**（APK 内）

* vault 协议跑在 socketpair 的 dup2(fd,0/1) 上：无抽象套接字/
  文件系统路径，ps 看到的是与 daemon 无异的 so 进程

* relay 入口类 `vendor.entry.Main`：app\_process cmdline 只暴露
  中性类名，不含 app 身份与功能提示；daemon.cpp RELAY\_MARKER 与
  Kotlin pkill/pgrep 模式同步

* FGS 通知：空标题/文本 + 随机 channel id（持久化）与随机
  channel 名 + 系统 small icon + IMPORTANCE\_LOW——无任何内容特征

* tmp 落地文件全部随机名（20-35 位字母数字）；本地存储中性名：
  prefs `sync_preferences`、`sync_key.bin`/`sync_wrap.bin`
  （冻结不变量）

残余暴露（接受或暂缓）：

* `/data/local/tmp/.w_*`/`.s_*` 前缀（2-3 字符弱特征）：泛化
  pkill 模式有误杀共享目录他人文件的风险，保留

* root 拿到 APK 后的静态分析：DEX 字符串（GuardManager/VaultClient
  类名被 R8 native keep 规则保留、SF-GATE 标记、prefs 键名）可
  还原防御设计——彻底对抗需代码虚拟化/加壳，超出当前范围；R8
  已混淆其余 defense 类名

* 门禁密码参数经 Java 层帧传递（输入框→VaultClient RPC）：被
  hook 的 Java 层可窃听密码参数（与旧实现一致的继承边界）——
  但窃听到密码还需拷走 sync\_key.bin 才能离线解开 DK（付
  Argon2id），且 vault 时代密钥文件与被 hook 进程同设备共存时
  攻击者本就有 #21 的直读路径

* root 经 `/proc/pid/mem`、process\_vm\_readv 静默直读原理上不可
  检测（内核无"内存被读"通知）；唯一缓解是缩小 DK 驻留窗口
  （#9，vault 进程隔离使主进程无物可读）

* root 可在解锁前 disable BootReceiver/AlarmReceiver 或
  force-stop app（force-stop 同时取消全部已布防闹钟；代价是
  预知机制存在）

* 精确闹钟按**设备版本**全覆盖（与 targetSdk 无关）：API 30
  无需权限 → 31-32 `SCHEDULE_EXACT_ALARM`（安装即授予）→ 33+
  `USE_EXACT_ALARM`（安装即授予且不可撤销）。降级路径（仅
  Android 12/12L 手动撤销权限的罕见情形）用不可见的
  `setAndAllowWhileIdle`（Doze 下约 9-15 分钟延迟，销毁推迟
  不缺席）

* 到期复查闹钟依赖"解锁后 CE 存储可用"（布防前提即检查通过）；
  永不解锁 = 数据本就处于 FBE 锁定态，无保护需求

* 旧 KeyVault 时代的栈流审计（防线 22）、常量时间双实现比较、
  DK 拆分、pepper、迁移事务已随 vault 架构退役——退役理由是
  **守护对象消失**（秘密不再流经主进程），检测能力本身有效；
  若未来架构回退（DK 回主进程），必须整体恢复而非部分重建

