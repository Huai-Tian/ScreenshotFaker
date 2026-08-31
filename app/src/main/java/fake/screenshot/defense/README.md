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

| #  | 威胁                                                                                                     | 防线                                                                                                                    | 代码位置                                                                              |
|----|--------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| 1  | root 拷走 prefs 离线爆破门禁密码                                                                                 | Keystore pepper 掺盐（脱离设备验证在数学上不可行）                                                                                     | KeyVault.getOrCreatePepper；GateManager v2/v3 验证器                                  |
| 2  | 硬件断点内核外挂 hook 比较函数恒真，一行绕过门禁                                                                            | v3 解密式验证（无比较点可 hook，GCM tag 在密码学层）                                                                                    | GateManager.verifyV3Blob                                                          |
| 3  | 同上——hook 常量时间比较                                                                                        | 双实现交叉验证（断点资源耗尽）+ native canary 哨兵语义自检                                                                                 | GuardManager.constantTimeEquals；guard.cpp ct\_eq\_byte/ct\_eq\_word/canary\_check |
| 4  | root 冒充 app uid 解包 hw\_key.bin 静态取 DK                                                                  | DK 拆分 DK=A⊕B，B 仅存在于用户记忆                                                                                               | KeyVault（拆分生命周期/迁移事务）                                                             |
| 5  | root-as-uid 解密 DataStore 读 SSH/共享凭据全文                                                                  | 敏感字段 DK 第二层加密（`<key>_sec`）                                                                                            | SensitiveStore                                                                    |
| 6  | Frida/LSPosed/Substrate 注入；GG 修改器类 ptrace 扫描                                                           | maps 黑名单 + TracerPid 1s 快轮询 + PR\_SET\_DUMPABLE=0；检测与兜底引爆全在 native（Java 层被接管仍工作，引爆=覆写密文+SIGKILL）                      | guard.cpp（watchdog 自主线程）；GuardManager.checkNow→DefenseProtocol 完整销毁               |
| 7  | inline patch 改已有代码（POKE 掉比较常量）                                                                         | 自完整性校验：.text 与磁盘基准逐字节比对                                                                                               | guard.cpp check\_self\_integrity                                                  |
| 8  | 胁迫场景（被逼交出密码）                                                                                           | 双密码门禁：胁迫密码命中走完整销毁序列，界面无任何区分                                                                                           | GateManager（两级验证器）；DefenseProtocol.destroyForCoercion；GatePage                    |
| 9  | 设备被扣后长期不使用；重启后 app 永不被打开                                                                               | 未使用自动销毁：三段式锚点（BOOT\_COUNT+er+wc）反回拨（墙钟倒退带 2min 容差，NTP 小幅校正不误杀，与 daemon 侧 120s 容差对齐），档位 5min\~12mo；检查通过即布防到期复查闹钟（链条自续） | IdleWatchdog；BootCompletedReceiver（重启缺口）；AlarmReceiver（到期复查）                      |
| 10 | SIGSTOP 先手 dump 内存抓 DK                                                                                 | 会话自动锁定收窄 DK 驻留（息屏/后台30s/前台5min）                                                                                       | GateManager.lockSession；MainActivity 心跳                                           |
| 11 | 激活/改密/解除拆分时崩溃致密钥孤儿化                                                                                    | DK 迁移事务协议（pending+rename 备份+原子 commit+恢复）                                                                             | KeyVault（beginDkMigration 等）                                                      |
| 12 | 定向篡改 sync\_preferences（删 armed 哨兵）                                                                     | 验证器存在而 armed 消失 = 自毁                                                                                                  | GateManager.setPasswords（同 commit 写 armed）；IdleWatchdog.checkIdleExpiredLocked 首判 |
| 13 | 恶意悬浮窗盖密码框 tapjacking/偷窥                                                                                | 通知栏遮盖防护（HIDE\_OVERLAY\_WINDOWS：31+ 公开 API；API 30 hidden flag 经 HiddenApiBypass 反射）                                    | MainActivity.applyOverlayProtection                                               |
| 14 | 胁迫者翻最近任务归因"app 刚被用过"                                                                                   | 最近任务排除（默认开启；运行时 setExcludeFromRecents）                                                                                | MainActivity.applyWindowSecurityConfig（hide\_from\_recent）                        |
| 15 | 无头销毁路径（Boot/AlarmReceiver goAsync \~10s 预算）被挂起拖垮：root 设备 exec 卡 su 授权弹窗数十秒 → 进程在删 Keystore 前被广播 ANR 杀掉 | 销毁步骤 1-2（停共享/停 daemon）有界执行（3s/3.5s），超时后台继续、序列推进到 Keystore 删除（密码学销毁优先于进程清理）                                            | DefenseProtocol.runBounded                                                        |
| 16 | 磁贴冷启动进程（未开过 app 即点共享磁贴）                                                                                | 全入口 defense 组件初始化（KeyVault 等 context 为 lateinit，漏初始化即崩溃）                                                              | ScreenShareManager.toggleScreenShare（与 Screenshot/Record 磁贴一致）                    |
| 17 | 本地恶意进程 connect daemon 固定端口后不发数据，阻塞单线程命令循环（stop/purge/renew 不可达）                                        | 控制信道 accept 后设 5s 读超时（SO\_RCVTIMEO），超时按坏连接关闭；信道本就有 pkill-by-discovery 兜底，此为消除排队延迟                                     | daemon.cpp accept 循环                                                              |
| 18 | 共享配置对话框回填遗漏密码字段 → 确认时旧密码被静默抹除（daemon 侧 auth\_password 消失 = 无鉴权共享裸奔）                                    | 对话框打开时回填密码；putSensitive 空值写空串（清空=未配置语义，app/daemon 两侧一致判定）；接收配置保存检查写入返回值并提示失败                                          | ExtensionPage 对话框回填；SensitiveStore.putSensitive；ScreenShareReceiverManager        |
| 19 | 悬浮窗服务先于 MainActivity 运行 → 通知渠道以默认名 "Display"/"Control" 创建，系统设置永久残留（取证指纹）                               | 渠道随机化前移至 Application.onCreate（早于一切组件），MainActivity 侧调用保留为幂等兜底                                                         | randomizeOverlayChannelNames；LSPosedServiceManager.onCreate                       |

## 组件职责一句话

* **DefenseProtocol**：统一销毁入口（8 步序列）+ 检查/销毁互斥锁 + init 汇聚点

* **GateManager**：门禁验证器（v1/v2/v3 + 自动迁移）+ 会话状态 + DK 拆分编排

* **IdleWatchdog**：超时销毁状态机（锚点/档位/复位/到期闹钟布防）

* **AlarmReceiver**：到期复查闹钟入口（未到期重排链条自续；不跨重启，由 BootCompletedReceiver 接管）

* **KeyVault**：DK 拆分、迁移事务、Keystore pepper、DK 加解密入口

* **SensitiveStore**：敏感字段读写/迁移/响应式流（`_sec` 密文）

* **GuardManager**：libmemsys.so 的 Java 桥（init/checkNow/常量时间比较）

* **guard.cpp**（cpp/ 目录）：watchdog、maps 扫描、TracerPid 轮询、自完整性、双实现比较

## 冻结不变量（改动前必读）

以下存储契约直接兼容存量用户数据，**任何重构不可变更**：

* prefs 文件 `sync_preferences` 及其键名：`token_hash(_v2/_v3)`、`token_seed`、
  `backup_hash(_v2/_v3)`、`backup_seed`、`armed`、`dk_split`、`dk_seed`、
  `dk_check`、`dk_migration`、`gate_pepper`（中性命名是隐蔽性设计）

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

