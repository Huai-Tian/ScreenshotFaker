# defense 包：隐私防御体系

所有与"对抗取证者/root/注入/hook"相关的防御代码集中于此包。
本 README 是威胁→防线→代码位置的地图，与层级依赖规则。

## 层级结构（依赖只能向下）

```
L3  集成层（包外）        MainActivity / BootCompletedReceiver / 磁贴 / 各页面
        │  调用 defense 公开 API
L2  策略层（本包）        DefenseProtocol · GateManager · IdleWatchdog
        │  编排 L1 原语
L1  原语层（本包）        KeyVault · SensitiveStore
        │  使用 L0 比较/检测 + 包外纯密码学原语
L0  执行层（本包+native） GuardManager（JNI 桥）→ app/src/main/cpp/guard.cpp
```

包外依赖仅两类，均受控：

* `wrappers.EncryptManager`：纯软件密码学原语（PBKDF2 + AES-GCM），无状态无落盘；

* `wrappers.ConfigManager / DaemonManager / ScreenShareManager`：

  * KeyVault/SensitiveStore 经 ConfigManager 读写密文 DataStore（存储介质）；

  * **DefenseProtocol 是 defense 包唯一允许向上引用业务服务的点**
    （销毁必须停共享与守护进程）——新增向上引用必须先在该类头注释文档化理由。

包内协作环（运行时方法调用，非初始化依赖，安全）：
IdleWatchdog 判定命中 → DefenseProtocol 销毁 → 销毁序列回调
IdleWatchdog.isIdleActivated/resetIdleAfterDestroy。

## 威胁 → 防线 → 代码位置

| #  | 威胁                                                                                                     | 防线                                                                                                                                                  | 代码位置                                                                                         |
|----|--------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| 1  | root 拷走 prefs 离线爆破门禁密码                                                                                 | Keystore pepper 掺盐（脱离设备验证在数学上不可行）                                                                                                                   | KeyVault.getOrCreatePepper；GateManager v2/v3 验证器                                             |
| 2  | 硬件断点内核外挂 hook 比较函数恒真，一行绕过门禁                                                                            | v3 解密式验证（无比较点可 hook，GCM tag 在密码学层）                                                                                                                  | GateManager.verifyV3Blob                                                                     |
| 3  | 同上——hook 常量时间比较                                                                                        | 双实现交叉验证（断点资源耗尽）+ native canary 哨兵语义自检                                                                                                               | GuardManager.constantTimeEquals；guard.cpp ct\_eq\_byte/ct\_eq\_word/canary\_check            |
| 4  | root 冒充 app uid 解包 hw\_key.bin 静态取 DK                                                                  | DK 拆分 DK=A⊕B，B 仅存在于用户记忆                                                                                                                             | KeyVault（拆分生命周期/迁移事务）                                                                        |
| 5  | root-as-uid 解密 DataStore 读 SSH/共享凭据全文                                                                  | 敏感字段 DK 第二层加密（`<key>_sec`）                                                                                                                          | SensitiveStore                                                                               |
| 6  | Frida/LSPosed/Substrate 注入；GG 修改器类 ptrace 扫描                                                           | maps 黑名单 + TracerPid 1s 快轮询 + PR\_SET\_DUMPABLE=0；检测与兜底引爆全在 native（Java 层被接管仍工作，引爆=覆写密文+SIGKILL）                                                    | guard.cpp（watchdog 自主线程）；GuardManager.checkNow→DefenseProtocol 完整销毁                          |
| 7  | inline patch 改已有代码（POKE 掉比较常量）                                                                         | 自完整性校验：.text 与磁盘基准逐字节比对                                                                                                                             | guard.cpp check\_self\_integrity                                                             |
| 8  | 胁迫场景（被逼交出密码）                                                                                           | 双密码门禁：胁迫密码命中走完整销毁序列，界面无任何区分                                                                                                                         | GateManager（两级验证器）；DefenseProtocol.destroyForCoercion；GatePage                               |
| 9  | 设备被扣后长期不使用；重启后 app 永不被打开                                                                               | 未使用自动销毁：三段式锚点（BOOT\_COUNT+er+wc）反回拨（墙钟倒退/漂移带 10min 容差，NTP 校正与 RTC 纽扣电池老化不误杀，与 daemon 侧 600s 对齐），档位 5min\~12mo；检查通过即布防到期复查闹钟（链条自续）                   | IdleWatchdog；BootCompletedReceiver（重启缺口）；AlarmReceiver（到期复查）                                 |
| 10 | SIGSTOP 先手 dump 内存抓 DK                                                                                 | 会话自动锁定收窄 DK 驻留（息屏/后台30s/前台5min）                                                                                                                     | GateManager.lockSession；MainActivity 心跳                                                      |
| 11 | 激活/改密/解除拆分时崩溃致密钥孤儿化                                                                                    | DK 迁移事务协议（pending+rename 备份+原子 commit+恢复）                                                                                                           | KeyVault（beginDkMigration 等）                                                                 |
| 12 | 定向篡改 sync\_preferences（删 armed 哨兵）                                                                     | 验证器存在而 armed 消失 = 自毁                                                                                                                                | GateManager.setPasswords（同 commit 写 armed）；IdleWatchdog.checkIdleExpiredLocked 首判            |
| 13 | 恶意悬浮窗盖密码框 tapjacking/偷窥                                                                                | 通知栏遮盖防护（HIDE\_OVERLAY\_WINDOWS：31+ 公开 API；API 30 hidden flag 经 HiddenApiBypass 反射）                                                                  | MainActivity.applyOverlayProtection                                                          |
| 14 | 胁迫者翻最近任务归因"app 刚被用过"                                                                                   | 最近任务排除（默认开启；运行时 setExcludeFromRecents）                                                                                                              | MainActivity.applyWindowSecurityConfig（hide\_from\_recent）                                   |
| 15 | 无头销毁路径（Boot/AlarmReceiver goAsync \~10s 预算）被挂起拖垮：root 设备 exec 卡 su 授权弹窗数十秒 → 进程在删 Keystore 前被广播 ANR 杀掉 | 销毁步骤 1-2（停共享/停 daemon）有界执行（3s/3.5s），超时后台继续、序列推进到 Keystore 删除（密码学销毁优先于进程清理）                                                                          | DefenseProtocol.runBounded                                                                   |
| 16 | 磁贴冷启动进程（未开过 app 即点共享磁贴）                                                                                | 全入口 defense 组件初始化（KeyVault 等 context 为 lateinit，漏初始化即崩溃）                                                                                            | ScreenShareManager.toggleScreenShare（与 Screenshot/Record 磁贴一致）                               |
| 17 | 本地恶意进程 connect daemon 固定端口后不发数据，阻塞单线程命令循环（stop/purge/renew 不可达）                                        | 控制信道 accept 后设 5s 读超时（SO\_RCVTIMEO），超时按坏连接关闭；信道本就有 pkill-by-discovery 兜底，此为消除排队延迟                                                                   | daemon.cpp accept 循环                                                                         |
| 18 | 共享配置对话框回填遗漏密码字段 → 确认时旧密码被静默抹除（daemon 侧 auth\_password 消失 = 无鉴权共享裸奔）                                    | 对话框打开时回填密码；putSensitive 空值写空串（清空=未配置语义，app/daemon 两侧一致判定）；接收配置保存检查写入返回值并提示失败                                                                        | ExtensionPage 对话框回填；SensitiveStore.putSensitive；ScreenShareReceiverManager                   |
| 19 | 悬浮窗服务先于 MainActivity 运行 → 通知渠道以默认名 "Display"/"Control" 创建，系统设置永久残留（取证指纹）                               | 渠道随机化前移至 Application.onCreate（早于一切组件），MainActivity 侧调用保留为幂等兜底                                                                                       | randomizeOverlayChannelNames；LSPosedServiceManager.onCreate                                  |
| 20 | 冻结墙钟至 <2020 令看门狗整体失明（销毁被无限推迟；RTC 掉电用户也会落入同态）                                                           | 错钟期切换 uptime 数轴判定：daemon 侧 /proc/uptime 单调死线（同开机换算剩余量 / 跨开机 limit×60 重基线 / 恢复期逆换算回墙钟 + WC0 垃圾死线守卫）；app 侧错钟守卫推迟墙钟判定但时钟无关篡改判定照常 + 24h 复查闹钟（单调时钟，链条自续） | daemon.cpp watchdog\_main 错钟分支；IdleWatchdog（wallOk 守卫 + armRecheckAlarm）                     |
| 21 | 错钟/IO 期间锚点无法续期 → 持续活跃用户被陈旧 er0/wc0 误判到期（误毁）                                                            | 单调活性凭证（明文键 sync\_cycle，"boot,er"）：同开机推进到期基线 max(er0,e)；跨开机锚点同轴换算（最后活跃真实墙钟 ≈ wc0+e−er0，仅 est>wc0 时生效，永不提前引爆）；当前开机窗口豁免；豁免路径以有效基线布防闹钟（剩余恒正，链条不死）       | IdleWatchdog（KEY\_TOUCH；touchIdle 写入端 / checkIdleExpiredLocked 消费端 / evalLegacyAnchor 旧格式对齐） |

## 组件职责一句话

* **DefenseProtocol**：统一销毁入口（8 步序列）+ 检查/销毁互斥锁 + init 汇聚点

* **GateManager**：门禁验证器（v1/v2/v3 + 自动迁移）+ 会话状态 + DK 拆分编排

* **IdleWatchdog**：超时销毁状态机（锚点/档位/复位/到期闹钟布防/单调活性凭证豁免与跨开机换算）

* **AlarmReceiver**：到期复查闹钟入口（未到期重排链条自续；不跨重启，由 BootCompletedReceiver 接管）

* **KeyVault**：DK 拆分、迁移事务、Keystore pepper、DK 加解密入口

* **SensitiveStore**：敏感字段读写/迁移/响应式流（`_sec` 密文）

* **GuardManager**：libmemsys.so 的 Java 桥（init/checkNow/常量时间比较）

* **guard.cpp**（cpp/ 目录）：watchdog、maps 扫描、TracerPid 轮询、自完整性、双实现比较

## 冻结不变量（改动前必读）

以下存储契约直接兼容存量用户数据，**任何重构不可变更**：

* prefs 文件 `sync_preferences` 及其键名：`token_hash(_v2/_v3)`、`token_seed`、
  `backup_hash(_v2/_v3)`、`backup_seed`、`armed`、`dk_split`、`dk_seed`、
  `dk_check`、`dk_migration`、`gate_pepper`、`sync_cycle`（中性命名是隐蔽性设计）

* 文件 `filesDir/hw_key.bin`（+`.bak`/`.tmp` 事务临时态）

* Keystore alias：`hardware_encryption_key`、`gate_pepper_key`、`tink_master_key`

* DataStore 敏感字段后缀 `_sec`；idle 键 `idle_limit`/`idle_ts`

* 销毁步骤顺序（见 DefenseProtocol 头注释）；验证器与 pepper 跨销毁保留

* JNI 符号 `Java_fake_screenshot_defense_GuardManager_*`（包名/类名即契约，
  重命名必须同步 guard.cpp 并以 `nm -D` 验证全部 4 ABI）

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

* **输入胁迫密码**：门禁页输入胁迫密码 = 立即执行完整销毁序列
  （界面无任何区分，这是防胁迫的核心设计）。忘记自己设过胁迫
  密码而误输入 = 误毁，无法撤销。

* **启用短档位超时**：5 分钟档启用后，app 退后台且不解锁设备
  超过档位时长（计时基于锚点，重启/闹钟都无法绕过）即自毁。
  档位选择即销毁承诺。

**高风险操作被防线判定（多为无意触发）：**

* **忘记安全密码（DK 拆分已激活）**：DK 的 B 段仅由安全密码派生、
  只存在于用户记忆。忘记密码后：

  * 无任何恢复途径（无密码提示、无安全问题、无云端备份——
    这是防拷贝取证的设计前提）；

  * 改密自救不可用：任意错误密码在门禁验证层即被拒（INVALID，
    无害），但**误用胁迫密码改密/移除门禁**会被验证层放行
    （胁迫密码也是合法验证器）→ 以其重组 DK 失败 → DK 孤儿化
    \= 历史加密产物软销毁（KeyVault.assembleDaemonKey 校验失败
    路径）。

* **大幅回拨系统时钟**（root 或恢复模式改时间）：**仅当已启用
  定时销毁时**，墙钟倒退/漂移超过 10min 容差，或冻结墙钟（倒退至
  <2020 更会被错钟分支以 uptime 数轴判定）= 按防回拨/防冻结引爆
  （daemon 侧看门狗以"limit/deadline/锚点死线任一非零"为武装前提，
  与 app 侧 `limit<=0` 先行放行对齐——armed-only 用户手动调时间
  不引爆）。正常 NTP 校正、RTC 纽扣电池老化（10min 容差内）不会触发。

* **重启后大幅前跳系统时钟**（仅当已启用定时销毁时）：同开机判定
  只依赖单调时钟，墙钟前跳（用户对时/时区跨越/NTP 步进）绝不引爆；
  但**跨开机**后墙钟是唯一证据源，"真实闲置超期"与"时钟前跳 Δ"
  原理上不可区分——重启 + 前跳幅度超过（档位时长 - 真实闲置）时
  会提前引爆。短档位（如 5 分钟）+ 长期离线后联网 NTP 大步进校正
  的组合可能命中；需要绝对安全请用较长档位。

* **篡改本 app 私有存储**（root 备份/恢复、钛备份、手动改
  prefs/DataStore 文件）：

  * 删 `armed` 哨兵（验证器仍在）→ 判定向篡改 → 自毁；

  * 损坏 idle 密文锚点（GCM 解密失败）→ 按已销毁处理；

  * 整体备份恢复到新设备 → Keystore 条目不存在 → 密文全部
    不可解（等效销毁，此为防移植取证的设计边界）。

**已被防线排除的误毁（正常使用无需担心）：**

* 暂时性 IO 故障 / DataStore 基础设施异常：按"本轮无法判定"
  放行，不引爆（retryable 路径）；

* RTC 掉电期间持续使用：错钟期活跃由单调活性凭证豁免，
  时钟恢复后按换算基线判定（锚点同开机形态）；**中间开机
  凭证（错钟期跨多次重启且中间开机活跃）的真实墙钟原理上
  不可恢复 → 豁免 + 以当前时钟重基线，一次性重计档位窗口
  （两段式旧锚点走近似豁免，豁免面含锚点开机的凭证——
  旧代码两者均被墙钟判定误毁，方向均为推迟而非误毁）**；

* 错钟期启用超时：writeAnchor 拒绝落盘 → 启用中止（本次
  设置不生效），不制造引爆态；

* 正常重启、force-stop、闹钟被 Doze 延迟：销毁被推迟但
  不缺席（重启缺口由 BootCompletedReceiver 接管）。

**新推迟面（如实记录）：** 两段式锚点的近似豁免路径在
`writeAnchor` 持续 IO 失败（墙钟健康 + DataStore 写持续
失败而读持续成功的复合故障）下构成"豁免 + 布防"循环，
销毁被推迟至 IO 恢复——与三段式形态③的既定语义一致
（推迟代价远低于误毁），daemon 侧看门狗为独立兜底。

## 已知边界（诚实声明，勿试图"修复"）

### 隐蔽性设计（进程/文件特征中性化）

运行时可见面（ps / maps / ls /data/local/tmp，无需 root 或需 root）：

* native 库名全部中性化：guard→**libmemsys.so**（maps）、daemon→**libnetsvc.so**
  （就地运行 cmdline）、scrcpy-server→**libextsvr.so**（APK 内）——CMake
  OUTPUT\_NAME 与 Kotlin 引用三处同步，guard.cpp 自完整性校验按 libmemsys.so
  定位自身映射，**改名必须四处同步**（否则自完整性静默失效）

* relay 入口类 `vendor.entry.Main`：app\_process cmdline 只暴露中性类名，
  不含 app 身份与功能提示（曾为 fake.screenshot.core.Relay——ps 全局可见
  的致命指纹）；daemon.cpp RELAY\_MARKER 与 Kotlin pkill/pgrep 模式同步

* FGS 通知：空标题/文本 + 随机 channel id（持久化）与随机 channel 名 +
  系统 small icon + IMPORTANCE\_LOW——无任何内容特征

* tmp 落地文件全部随机名（20-35 位字母数字）：relay/daemon 自拷贝/加密
  中间产物；daemon 锚点文件 `.`+密钥哈希 hex（仅持钥者可推导）；
  "FullRandomName" 仅为加密信道内的协议占位符，落地前 daemon 必替换

* 本地存储中性名：prefs `sync_preferences`、`hw_key.bin`（冻结不变量）

残余暴露（接受或暂缓）：

* `/data/local/tmp/.w_*`/`.s_*` 前缀（2-3 字符弱特征）：泛化 pkill 模式
  有误杀共享目录他人文件的风险，保留

* root 拿到 APK 后的静态分析：DEX 字符串（GuardManager 类名被 R8 native
  keep 规则保留、SF-GATE 标记、prefs 键名）可还原防御设计——彻底对抗
  需代码虚拟化/加壳，超出当前范围；R8 已混淆其余 defense 类名

* root 经 `/proc/pid/mem`、`process_vm_readv` 静默直读原理上不可检测
  （内核无"内存被读"通知）；唯一缓解是缩小 DK 驻留窗口（防线 10）

* root 可在解锁前 disable BootReceiver/AlarmReceiver 或 force-stop app
  （force-stop 同时取消全部已布防闹钟；代价是预知机制存在）

* 精确闹钟按**设备版本**全覆盖（与 targetSdk 无关）：API 30 无需权限 →
  31-32 `SCHEDULE_EXACT_ALARM`（安装即授予）→ 33+ `USE_EXACT_ALARM`
  （安装即授予且不可撤销）——精确到期复查默认可用、全程无用户操作、
  无任何状态栏图标。降级路径（仅 Android 12/12L 手动撤销权限的罕见
  情形）用不可见的 `setAndAllowWhileIdle`（Doze 下约 9-15 分钟延迟，
  销毁推迟不缺席）；曾评估 `setAlarmClock`（免权限精确）已否决——
  状态栏闹钟图标是常态暴露，且精确性校验在 system\_server 侧，
  客户端 hidden API 反射无法绕过

* 到期复查闹钟依赖"解锁后 CE 存储可用"（布防前提即检查通过）；
  永不解锁 = 数据本就处于 FBE 锁定态，无保护需求

* SecretKeySpec/字符串内部副本的内存清零受 GC 限制，已知边界

* 精确 hook（栈回溯甄别调用来源后选择性撒谎）无法被 canary 抓住，
  但成本数量级提升（见 guard.cpp 头注释）

