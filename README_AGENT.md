# ═══════════════════════════════════════════════════════════════
# README_AGENT — machine-facing project brief
#
# FILE CLASS : machine-facing brief. Audience: LLM agents / coding copilots
#              doing secondary or continued development on this repo.
# HUMANS     : stop here. Use README_ZH.md / README.md instead.
# FORMAT     : yaml-dialect (same convention as E2EE-Experiment's README_AGENT).
#              indent=2; keys are stable IDs; `#` comments are NORMATIVE
#              (constraints), not decoration. Whole body MUST parse as ONE
#              valid YAML document.
# EFFICIENCY : read THIS file top-to-bottom before touching code. It encodes
#              structure, philosophy, red lines, falsified dead ends and
#              OEM hard-won facts that cost weeks of real-device debugging —
#              none of which is recoverable from source alone.
# ═══════════════════════════════════════════════════════════════

project:
  id: ScreenshotFaker
  lang: "kotlin (app+engines) + c/cpp (jni, vendored) + java (scrcpy submodule)"
  nature:
    - lsposed-module        # libxposed api 102; hook engines run INSIDE target processes
    - shizuku-root-client   # privileged exec: stealth capture/share, invisible overlay
    - normal-app            # compose UI + encrypted config + anti-coercion defense
  maturity: early-development
  ui_language: zh
  comment_language: zh      # dense; inline dates (e.g. 2026-09-13) mark REAL-DEVICE verified facts — never delete them as "noise"
  license: GPL-3.0-only     # no commercial exceptions; see README_ZH.md 非商业声明

doc_index:
  zh: README_ZH.md
  en: README.md
  agent: README_AGENT.md    # this file
  defense_design: app/src/main/java/fake/screenshot/defense/README.md   # threat→defense→code-location map; best in-repo deep-dive

doc_protocol:                               # how to maintain THIS file
  update_triggers:                          # a PR that does any of these MUST update this file in the same change
    - new engine or engine renumbering
    - owner ruling (any verbal decision in review) → append to red_lines with date
    - a fix round verified on real device → append conclusion line to falsified_approaches or hard_lessons
    - landed feature → move its pending_work entry to architecture section
  yaml_selfcheck: "python3 -c 'import yaml; yaml.safe_load(open(\"README_AGENT.md\"))' — MUST succeed"

# ───────────────────────────────────────────────────────────────
# ARCHITECTURE — what exists and where
# ───────────────────────────────────────────────────────────────

architecture:
  process_model:                            # engines are assigned per process kind, assembled in ScreenshotFaker.kt
    SYSTEM_SERVER:  [E1, E4, E2a, E2b, E2c, E2d, E2e, E3b]
    SCREENSHOT_APP: [E1_allow_leg, E3a]     # OEM screenshot app (system app, scoped)
    RECORDER_APP:   [E3c]                   # OEM recorder (system app, scoped); E1/E3a NOT mounted here
    SF_APP:         []                      # this app itself is NEVER hooked (process-name filter; self-exclusion is load-bearing)
  engine_invariants:
    - "one engine = one file under hooks/; engines NEVER reference each other; the ONLY shared spine is HookContext.kt"
    - "each engine mounts inside its own try-catch; one engine's assembly failure must not take down the rest"
  engines:                                  # numbering is a frozen vocabulary — reuse it in logs/commits/docs
    E1:  { file: SecurePolicyHook.kt,        where: "system_server + screenshot_app", does: "secure-flag三态 FOLLOW/ALLOW/DENY：isSecureLocked塑形＋DENY主动reconcile＋捕获管线三态＋OEM长截图判定腿" }
    E2a: { file: CaptureDetectionHook.kt,    where: system_server, does: "截屏检测吞噬：ScreenCaptureCallback注册跳过/派发吞噬（AOSP+ColorOS改名双形）＋媒体域ContentObserver影子观察者（owner_package_name行级归属判定）" }
    E2b: { file: RecordDetectionHook.kt,     where: system_server, does: "录屏检测吞噬：registerScreenRecordingCallback跳过＋虚拟显示器对masked调用者隐身" }
    E2c: { file: OverlayStealthHook.kt,      where: system_server, does: "自有悬浮窗标记TRUSTED_OVERLAY→下方窗口FLAG_WINDOW_IS_OBSCURED失活。全局生效（无法按检测者区分）——文档化接受" }
    E2d: { file: FocusStealthHook.kt,        where: system_server, does: "masked应用RESUMED期间一切非自身焦点请求重定向回该应用窗（InputMonitor#requestFocus唯一收口）——焦点丢失隐瞒" }
    E2e: { file: PresentationStealthHook.kt, where: "system_server, api>=35", does: "registerTrustedPresentationListener派发层改写：untrustedIds挪入trustedIds——窗口完整性检测失活" }
    E3a: { file: ScreenshotReplaceHook.kt,   where: screenshot_app, does: "截图假图：SurfaceControl#screenshot静态族/Bitmap#wrapHardwareBuffer/ScreenshotHardwareBuffer#asBitmap三腿；方向自适应（不跟随系统旋转）；冷启动竞态护栏" }
    E3b: { file: ProjectionReplaceHook.kt,   where: system_server, does: "录屏假图层：hook DMS#createVirtualDisplay*登记＋mirrorDisplay/mirrorSurface后置建buffer layer；帧中继视频接管（见 frame_relay）" }
    E3c: { file: AudioRecordReplaceHook.kt,  where: recorder_app, does: "录屏音频三态 OFF/REPLACE/MIX；Java腿（native_read_in_*汇聚点＋虚拟时钟pacing）＋native腿（shadowhook拦libaudioclient processAudioBuffer）" }
    E4:  { file: FreeformPierceHook.kt,      where: system_server, does: "自由小窗穿透：浮窗surface打setSkipScreenshot（SF捕获遍历剔除该子树）；ColorOS flexible task双路径检测；降级链setSkipScreenshot→setSecure" }
  frame_relay:                              # E3b core — the terminal answer to OEM stretch (round 8-16 saga)
    pipeline: "MediaPlayer→ImageReader(YUV_420_888,半分辨率)→中继线程: acquireLatestImage→实际几何解析→copyPlaneSafe→CPU YUV→RGB(BT.601)→中心裁剪cropStrip铺满→canvas post"
    geometry_solving: "不信任image报告几何：标准布局limit公式预检，越界由Y/U平面limit与rowStride联立解实际buffer几何（NV12/NV21与I420两系公式）；解不出回落报告几何"
    buffer_shape: "方形blanket——buffer恒为显示空间长边方形（旋转后mirror坐标系宽高互换，方形恒全覆盖）；方向看护线程50ms轮询刷新条带几何"
    failure_semantics: "单帧跳帧可恢复；连续3败判死冻结末帧（fail-soft）；装配失败回落直挂（拉伸但可用）"
    warm_and_peek: "warm player空闲预装配（setDataSource→prepare→解码首帧）；peekFirstFrame volatile镜像零阻塞——mirror创建帧画视频0帧（消残影），未命中黑屏；替换图降级为视频失败回落postFallback"
  config_chain:                             # single source of truth + one-way export
    authority: "App私有Tink加密DataStore（ConfigManager；密钥android-keystore://tink_master_key；文件名data_ref随机化）"
    flow: "UI(TemplateManager.saveConfig)→DataStore(权威)→HookConfigCodec.encode(AES-GCM信封,Base64)→LSPosed RemotePreferences group=sync key=s_c→hook侧HookContext.reloadFromPrefs→decode→@Volatile config无锁快照读"
    cold_start_guard: "awaitConfigSynced latch（截屏进程被OEM杀后首截早于配置首推~1.25s窗口的量纲依据）；未同步退避重拉1s→16s"
    codec_keys: "JSON短键（re/ve/g/vi/vg…）；codec对称性由round-trip单测守护（pending_work.D）——历史上'vg'只decode不encode，保存即丢"
  resource_chain:
    remote_files: "图=sf_img_<id>，视频=sf_vid_<id>（id：全局图'g'，模板图=模板id）；经XposedService.openRemoteFile fd派发（绕binder 1MB限）"
    envelopes: "三套独立种子：sf.hookcfg.v1/sf.replaceimg.v1/sf.replacevid.v1；格式[12B nonce][AES-GCM密文+tag]；视频流式解密（1MB分块）→memfd（MediaPlayer直接吃fd）"
    key_derivation_stance: "密钥派生自源码常量——这是反泛化取证扫描的混淆层（磁盘无明文包名/策略/可解码图像），不是对抗定向逆向的密码学边界。不要'修复'它"
    audio_source: "无独立音频文件——ReplaceAudioStore复用sf_vid信封解音轨→PCM I16全量内存（64MB上限）→fill按帧位供给（线性插值重采样+声道映射+循环）"
    reconciliation: "服务绑定时对账：补投配置内缺失＋删除配置外孤儿；本地明文副本仅UI预览用"
  defense:                                  # anti-coercion vault architecture — see defense/README.md for the full threat model
    vault_isolation: "DK(数据密钥)唯一持有者=独立vault进程（libsyncsvc.so，无ART，PR_SET_DUMPABLE=0，double-fork+socketpair dup2 0/1）；主进程只持密文与RPC；主进程死亡=EOF=vault清钥退出"
    destruction: "三类触发（胁迫密码/注入检测/超时判定）同锁汇聚；两阶段销毁——全量密码学擦除优先，演出路径关键~0.5s与正常解锁耗时不可区分"
    idle_watchdog: "始终武装（装机默认6个月）；三段式锚点BOOT_COUNT+er+wc反回拨；胁迫销毁联动远端键remove（hook侧decode为DEFAULT=全关）"
    channel_key: "CK=HMAC-SHA256(DK,'ScreenshotFaker/channel/v1')在vault内派生——DK永不进Java层"
  native_artifacts:                         # ALL library names are NEUTRAL — never leak function in a .so name (maps/cmdline anti-attribution)
    libnetsvc_so: "daemon.cpp——可执行daemon就地运行（OpenSSH/libssh2静态链）；日志触发截录屏＋卸载后存续载体；CK经stdin递交（不经argv）"
    libmemsys_so: "guard.cpp+vault_client.cpp——主进程雷管（自我hook审计三线+TracerPid轮询+.text自完整性）与vault启动器"
    libmediafx_so: "audio_replace.cpp——E3c native腿（仅ARM ABI真实产物；x86编OBJECT EXCLUDE_FROM_ALL保IDE语言服务）"
    libsyncsvc_so: "vault.cpp+argon2——独立密钥保管进程"
    libextsvr_so: "scrcpy server改造版APK（包名fake.screenshot.scrcpy，入口vendor.entry.Main）——构建期由:scrcpy产物重命名并入jniLibs"
    shadowhook: "vendored源码静态编译（v2.0.1）——上游AAR无.a且prefab对x86必败；mediafx.map把导出符号压到只剩Java_*"
  overlay:                                  # root invisible overlay
    backend: "OverlaySurfaceBackend纯Surface方案——SurfaceControl直挂SF，完全绕开WindowManager/View；setSkipScreenshot截图排除"
    input: "GestureInputMonitor自解析Android 11-16四代InputMessage二进制布局（纯观察者）+OverlayGestureController手势状态机"
    duality: "RootOverlayService单文件双人格：root进程侧服务本体+App侧companion接口；双后端Shizuku UserService优先/su app_process兜底"
  repack:                                   # custom-package-name reinstall
    pipeline: "RepackManager编排ApkBuilder(ZIP逐条重写)→AxmlEditor(二进制AXML包名/label/图标改写)→ResourceTableParser(定位图标条目自绘替换)→RepackSigner(apksig自签)"
    limit: "AXML UTF-8池0x7FFF长度上限封死包名/应用名长度——入口约束，无绕行"
  screen_share:
    sender: "ScreenShareManager：特权exec拉relay(app_process载vendor.entry.Main)→拉scrcpy server(libextsvr.so)→推流；局域网直连或JSch SSH隧道；胁迫销毁闩锁双轨（布尔闸+纪元计数防TOCTOU）"
    receiver: "ScreenShareReceiver自实现scrcpy客户端协议（视频Surface/音频AudioTrack/控制注入/SSH本地转发）；按发送端通道自适应降级"
  module_map:                               # fast orientation
    hooks/: "引擎层（单文件单引擎）＋HookConfig.kt(模型+codec)＋HookContext.kt(进程共享脊柱)＋Replace{Image,Video,Audio}Store.kt"
    wrappers/: "App侧业务管理器（ConfigManager/TemplateManager/EncryptManager/Replace{Image,Video}Manager/ScreenShare*/DaemonManager/RepackManager…）"
    pages/: "Compose UI（12页面）＋styles/"
    services/: "三磁贴＋两前台服务＋privileged/overlay/（root无痕悬浮窗6文件）＋BootCompletedReceiver"
    defense/: "反胁迫体系（8文件＋README威胁模型）"
    repack/: "重打包四件套"
    cpp/: "native源＋vendored(openssl/libssh2/argon2/shadowhook)"
    scrcpy/: "scrcpy server改名子模块（Gradle module :scrcpy）"

# ───────────────────────────────────────────────────────────────
# CONSTITUTION — design principles (project 宪法, still fully in force)
# ───────────────────────────────────────────────────────────────

constitution:
  fail_open_replacement: "替换链路绝不阻断截屏流程本身——无策略/无图/解码失败一律原生结果（截屏失败比真内容更可疑）。fail-open只属于E3系"
  fail_closed_keys: "销毁/密钥类一律fail-closed——拿不到密钥就拒绝半可用状态，绝不做降级可用"
  zero_injection: "E2系纯system_server实现，检测者进程零注入（不注入media provider等）——屏蔽不可自证：吞掉'收不到自己事件'这一可观测差异，owner归属判定自参照，检测者自插探测天然通过"
  hot_reload_safety: "HookContext只持静态缓存与listener，不触碰native、不注册系统回调；onHotReloading注销即无残留，框架可整体卸载旧classloader"
  local_plaintext_authority: "本地明文是权威源；远程托管区只是分发通道，绑定服务时校验+自愈重投"
  probe_discipline: "校准探针（候选清单dump/一次性结果标记）用完即删；装配摘要与事件确认日志（E2a swallowed/E3a hit/E3b overlaid同口径）保留。成功路径逐步INFO不留——失败WARN与单行健康日志是可观测面"
  defensive_dual_candidates: "防御性双候选（mirrorDisplay/mirrorSurface双方法名等）=OEM差异容错，不是猜测代码，保留勿删"
  library_name_neutrality: "所有产物库名中性化（netsvc/memsys/syncsvc/extsvr/mediafx）——maps/cmdline不留自曝字样。引入第三方库时同样改名"
  empirical_driven: "hook点/去优化清单/包名清单均须真机实证；注释中的实测日期是证据，不是垃圾"
  deoptimize_iron_rule: "Java层hook必配deoptimize（OEM AOT内联绕过trampoline）；native方法无字节码不可内联——唯一无需去优化的腿。deoptimize不能消除已内联副本——所以E3c还有read懒登记兜底"

# ───────────────────────────────────────────────────────────────
# RED LINES — owner rulings. HIGHEST authority; violating = reject
# ───────────────────────────────────────────────────────────────

red_lines:
  faithful_replacement: "忠实替换，不替用户做选择——绝不做动态内容合成（时钟/电量/通知数实时渲染、内容池轮换、局部redact都在此列）。万一电量就是用户不想被获取的呢？替换物=用户提供的原样资产"
  no_target_app_injection: "hook面只允许system_server＋系统应用（OEM截屏器/OEM录屏器，LSPosed可scope）。绝不注入第三方目标app。推论：第三方录屏app的音频替换若唯一路径是注入目标app→方案废弃；被认可的兜底=system_server策略腿（见pending_work.F，MediaProjection音频捕获授权降级→原生语义静音）"
  network_video_abandoned: "网络视频流已废弃，不属于本项目范围——不要再提出或'顺手实现'"
  media_never_backed_up: "替换媒体（图/视频）绝不进备份。恢复后配置引用悬空→hook侧负缓存静默失效，这是设计行为不是bug，不要'修复'"
  audit_belongs_elsewhere: "检测事件审计/记录归ScreenshotDetector项目（另一仓库）负责，本项目不做，也不要在吞噬点加回传"
  static_oem_whitelists: "OEM包名白名单保持静态硬编码（SCREENSHOT_PACKAGES约5包＋RECORDER_PACKAGES现仅com.oplus.screenrecorder）——不做运行时指纹自适应；新OEM靠发版补充"
  e2ee_integration_binary_only: "问题反馈的端到端加密由E2EE-Experiment承担（github.com/Huai-Tian/E2EE-Experiment），只集成其二进制库（C ABI冻结add-only），不在本仓库重实现加密"

# ───────────────────────────────────────────────────────────────
# FALSIFIED APPROACHES — dead ends with verdicts. DO NOT RETRY
# (each line cost a real-device debugging round, 2026-09 round 8-17)
# ───────────────────────────────────────────────────────────────

falsified_approaches:
  decoder_direct_attach_geometry: "round 8-12：一切layer几何手段（builder尺寸/事后事务/帧确立post/信箱）对解码器直挂图层全部无效——OEM(ColorOS)恒把buffer缩放到显示空间满幅。终解=帧中继（放弃直挂）"
  u_plane_width_rebuild: "round 15：U平面推断宽度的reader重建条件从未触发——几何错配在行距×行数维度（越plane limit），不在宽度维度"
  watchdog_isolation: "2026-09-13：导入卡死被误诊为OEM provider/MMR挂死，加了Future.get看门狗隔离——真凶是LaunchedEffect自取消＋codec漏encode字段，看门狗从未触发即删。教训（规范性）：没有实证根因不写防御代码"
  post_lead_null_black_redraw: "round 17后：null→纯黑重画冗余且有害（把peek命中的0帧占位盖回黑屏数百ms）——已删，勿复加"

hard_lessons:                               # code-level traps that WILL bite again
  launchedeffect_self_cancel: "LaunchedEffect(key)的effect第一行置空自己的key→重组时key已变→effect被取消→结果丢失。置空移finally且条件置空（if(pendingVideoUri==uri)）"
  codec_symmetry: "codec每个decode键必须有encode写者（'vg'曾只decode不encode，保存即丢模板级视频）——round-trip单测是唯一护栏"
  openremotefile_no_truncate: "openRemoteFile是RW|CREATE不截断——重写更短文件旧字节残留尾部，AES-GCM tag在文件尾恒败。写前ftruncate(0)+写后截精确长度"
  pfd_close_chain_ebdf: "close流会链式关pfd——流关闭后ftruncate原句柄必EBADF；post-truncate用dup出的独立fd"
  cold_start_race: "OEM o-stop杀截屏进程后，次日首截进程拉起→捕获仅~0.4-0.8s，RemotePreferences首推~1.25s——护栏的量纲依据，勿删awaitConfigSynced"

# ───────────────────────────────────────────────────────────────
# OEM FACTS — ColorOS 15 empirically verified (losing these = re-suffering)
# ───────────────────────────────────────────────────────────────

oem_facts:
  dms_binder: "binder形参是VirtualDisplayConfigInternal（system parcelable，public final m*字段）而非公开API VirtualDisplayConfig；AOSP 13+ binder方法在DMS.BinderService内部类——宿主要扫外层+declaredClasses"
  screenshot_process_filter: "SystemUI主进程SurfaceControl#screenshot用于下拉实时背景——E3a进程过滤判据：processName含'screenshot'或==com.flyme.systemuiex"
  content_service_lookup: "ContentService不在模块CL可见域也不在system_server服务CL委托链——须实例反查（getService(\"content\")→javaClass）；onSystemServerStarting早于其出版，冷启动挂addService出版哨兵"
  activityrecord_owner: "ColorOS 15：ActivityRecord的getOwningPackage/mPackageName均被OEM移除——走getUid→IPackageManager反查"
  capture_callback_rename: "ScreenCaptureCallback派发名reportScreenCaptured（OEM改名）——hook须双形"
  icontentobserver_fqn: "IContentObserver FQN两系：ColorOS android.database.* / AOSP android.content.*"
  flexible_task_state: "ColorOS浮窗：windowing mode不变（仍fullscreen），浮窗态在TaskInfo扩展bundle key_flexible_task_state（2=浮窗/0=常规）；SceneSDK windowMode=100是OPPOSDK自有枚举，勿混"
  getdisplayids_scan: "DisplayManager.getDisplayIds ColorOS精确名0命中——用签名形变扫描（返回int[]）"
  recorder_is_native: "ColorOS录屏器走C++ AudioRecord（libaudioclient），Java AudioRecord类从未实例化——E3c主路径在native腿（shadowhook），Java腿覆盖AOSP SystemUI等其他路径"
  display_space_sizing: "系统录屏VD按码率降分辨率（如720x1608<物理屏1080x2412）——假图层buffer必须按DisplayManagerInternal显示空间逻辑分辨率定尺寸，VD分辨率仅作时序配对标记"

# ───────────────────────────────────────────────────────────────
# ENVIRONMENT — CI sandbox + build + collaboration loop
# ───────────────────────────────────────────────────────────────

environment:
  gradle_proxy: "gradle wrapper纯Java不读http_proxy环境变量；沙箱出口是自带代理18080——JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080' ./gradlew assembleDebug（或写~/.gradle/gradle.properties的systemProp）"
  vpn: "bash /workspace/tools/start-mihomo.sh（重启后拉起；HTTP 7890/SOCKS 7891/Mixed 7892/API 29090；节点连接经沙箱18080 CONNECT隧道dialer-proxy）"
  build_ok_criteria: "BUILD SUCCESSFUL即通过——llvm-strip对预置占位.so报'not recognized as a valid object file'属环境噪音"
  quick_check: "./gradlew compileDebugKotlin（改kotlin后的快速门）"
  ci_note: "CI先独立构建:scrcpy再:app（嵌套gradlew的daemon锁风险）；codeql.yml为java-kotlin免构建分析"
  git_identity: "GIT_AUTHOR_NAME='怀天' GIT_AUTHOR_EMAIL='2965373537@qq.com'（COMMITTER同）——沙箱无全局配置"
  no_push_credentials: "沙箱无GitHub凭据——提交由agent准备，push由owner手动完成"
  test_loop: "owner本地自己构建APK真机测试（非沙箱构建）；日志经4275.com文件中转站发来（LSPosed日志zip）——下载公式：页面是HTML壳，真实下载=https://4275.com/?m=download&a=down&code=<码>&token=<页内a.btn-download的data-token，==编码为%3D%3D>&cdn=1"
  commit_style: "单行长信息无正文（owner偏好）；结构'主题词：机制链路→根因→修复；边界语义'；全角标点，——/→/①分隔；owner推送前可能自行重排为多行缩略版，属正常"
  versions: "Gradle 9.8.0(腾讯镜像)/AGP 9.4.1/Kotlin 2.4.10/compileSdk 37/targetSdk 36/minSdk 30/NDK 28.2.13676358/CMake 3.22.1/4 ABI；Tink 1.23.0/apksig 9.4.0/JSch 2.28.7/Shizuku api 13.1.5/libxposed 102.0.0(useLegacyPackaging=true)"
  sibling_repos:
    ScreenshotDetector: "验收配套（E2a影子档位自插探测）；检测事件审计归它。已知分叉：本地754efa3(framework createBitmap免ktx) vs 远端d1c9532(androidx版)——pending_work.G"
    E2EE-Experiment: "反馈加密lib（AGPL-3.0）；C ABI 12符号冻结add-only（create/dial/speak/poll/bye/destroy覆盖文本反馈全链路）；README_AGENT在彼仓库——动其FFI前先读"

# ───────────────────────────────────────────────────────────────
# QUALITY GATES — what "done" means here
# ───────────────────────────────────────────────────────────────

quality_gates:
  build: "assembleDebug BUILD SUCCESSFUL（改native后必做全量）"
  cleanup_after_verification: "每轮真机验证通过的修复，其调试/取证代码（probe/dump/成功INFO/看门狗式猜测防御）在下一轮清理——历史模式是'验证→清理→compileDebugKotlin→备份patch'。宁少勿留"
  no_speculative_defense: "重复三遍的规矩：没有实证根因不写防御代码（看门狗教训见falsified_approaches）"
  tests: "现为零（仅模板Example*Test）。新增单测对象=纯函数（见pending_work.D），不做需要真机的假测试"

# ───────────────────────────────────────────────────────────────
# PENDING WORK — current TODO board (audit 2026-10-13, owner-ratified)
# ───────────────────────────────────────────────────────────────

pending_work:
  A_feedback_e2ee:                          # 主任务（owner指定）
    status: not-started
    scope: "HomePage反馈卡片现在是onClick={}纯占位；README承诺'问题反馈的数据传输采用动态端到端加密'"
    integration_notes: "E2EE侧缺android交叉编译产物（aarch64-linux-android+armv7 cdylib）——长杆先启动；send_file不在其FFI面（FileArrived现映射tag0丢弃），带日志附件需先在E2EE仓库add-only扩展e2ee_send_file/take_file；接收端=owner侧CLI open-desk（--guard KEY --no-chain）；AGPL库入GPL应用合法（GPLv3§13），致谢需补；libe2ee.so必须按宪法改中性名"
  B_check_update_half_done:
    status: not-started
    scope: "SettingsPage的check_update开关有UI有存储（默认关）但全库零消费方——网络请求/版本比对/提示从未实现；实现或移除，二选一待owner定"
  C_relay_quality:                          # owner已同意
    items: "帧中继半分辨率→3/4分辨率（画质偏软）；模板级视频参与warm（首次录屏开头黑屏数百ms→压缩）"
  D_test_debt:                              # owner已同意，按历史真凶排序
    order: "HookConfigCodec round-trip（'vg'直接防回归）> E3b几何解析（limit联立解两系公式）+copyPlaneSafe > ReplaceAudioStore.fill（重采样/声道映射）> repack二进制解析（AxmlEditor/ResourceTableParser）> restoreAll控制字符拒绝"
  E_doc_debt:                               # owner已同意
    scope: "HookConfig.kt globalReplaceEnabled的KDoc'hook侧暂不消费'已失实（HookContext实际消费）＋全库过时注释清扫"
  F_in_scope_followups:
    e3c_policy_leg: "第三方录屏音频兜底=system_server策略腿：MediaProjection音频捕获授权降级→原生语义静音（符合no_target_app_injection红线；语义是静音兜底不是替换）——AudioRecordReplaceHook.kt头部注释既有方案"
    e4_decorative_residual: "浮窗标题栏/装饰层若由SysUI独立绘制则截图中可能残留边框——待实测确认（FreeformPierceHook.kt头部已知边界）"
  G_detector_fork: "低优先级：ScreenshotDetector本地/远端分叉处理（建议本地版推平远端）"

known_boundaries:                           # documented-accepted — do NOT file as bugs
  - "E2系对无障碍窗口快照、MediaProvider冷启动回查无杠杆（文档化接受）"
  - "E1 display级captureFlags粒度限制（分屏混用两态）"
  - "E2c全局生效（自有窗口属性无法按检测者区分）"
  - "防御体系声明边界：root /proc/pid/mem直读不可检测；'烧毁设备纪律'（获释后不得再在该设备解锁）"
  - "OEM覆盖面依赖静态白名单（红线规定保持静态）"

# ───────────────────────────────────────────────────────────────
# GLOSSARY — zh canonical term → mechanism
# ───────────────────────────────────────────────────────────────

glossary:
  引擎编号: "E1-E4冻结词汇表（见architecture.engines）——日志/提交/文档统一使用"
  帧中继: "frame relay——解码器输出经ImageReader接管后CPU逐帧转绘制，绕开OEM满幅钳制（E3b round 13+终解）"
  warm: "空闲窗口预装配prepared player＋解码首帧——压缩会话建立后的接管窗口"
  peek: "volatile首帧镜像零阻塞读——mirror创建帧（binder线程可能持DMS锁）不可等warm解密锁"
  残影: "录屏开头替换图短暂可见的瑕疵——round 17以'初始占位改视频0帧'根除"
  暂存区: "staging——模板编辑页导入先暂存，保存才promote+pushRemote（避免半途取消留半截密文）"
  负缓存: "解密/加载失败的指纹缓存——同指纹不再重试（防黑屏/防刷日志），配置变更指纹变即失效"
  假图层: "mirror子树上置顶的buffer layer——E3b的替换画面载体"
  影子观察者: "E2a的Proxy替换ContentObserver注册——派发按行级owner归属判定吞噬"
  屏蔽不可自证: "吞掉'收不到自己事件'的可观测差异——owner归属判定自参照，检测者自插探测天然通过"
  雷管: "libmemsys——主进程内自我审计（GOT/PLT越界+入口序言+信号handler归属+TracerPid）"
  演出路径: "胁迫在场时的快速销毁路径——关键~0.5s与正常解锁耗时不可区分，全量擦除后台补完"
  茶馆: "E2EE术语——中继两人房间的courier；本项目反馈接收端不用它（用open-desk）"
  open_desk: "E2EE术语——--guard KEY --no-chain的常开台席：机器暗号永不过期、错钥刷不锁——owner侧反馈接收形态"
  暖屏: "N/A——勿与warm混淆，本项目无此概念"
  销毁: "coercion destroy——两阶段：全量密码学擦除优先＋演出路径；联动远端键remove"

# ───────────────────────────────────────────────────────────────
# VERIFY — how to check your work
# ───────────────────────────────────────────────────────────────

verify:
  full_build: "cd /workspace/ScreenshotFaker && ./gradlew assembleDebug（沙箱首跑需JAVA_TOOL_OPTIONS代理，见environment.gradle_proxy；dists重下~130MB约5分钟）"
  kotlin_only: "./gradlew compileDebugKotlin"
  yaml_selfcheck: "python3 -c 'import yaml; yaml.safe_load(open(\"README_AGENT.md\"))'"
  agent_preflight: "改代码前：读本文件＋触及文件的头部KDoc（引擎文件的头部注释=该引擎的设计文档）；改hook后：确认对应进程装配矩阵未破坏（ScreenshotFaker.kt）"

licensing:
  license: GPL-3.0-only
  non_commercial_stance: true               # full statement in human READMEs; never add dual-license-style clauses
  commercial_exceptions: never
  upcoming_dependency: "E2EE-Experiment lib（AGPL-3.0）——GPLv3§13兼容；集成落地时致谢清单须补充"
