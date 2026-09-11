package fake.screenshot.hooks

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.util.LruCache
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * E2a 截屏检测吞噬引擎（纯 system_server，检测者进程零注入）。
 *
 * 检测者的两类截屏感知通道（对照 ScreenshotDetector 实测源码）：
 *
 * 【通道 1：ScreenCaptureCallback（API 34+ 主路径）】
 * 检测者（Activity.registerScreenCaptureCallback）→ binder →
 * ActivityRecord 持有 observer；截屏时 WMS 回调派发。双点吞噬
 * （精确方法名，AOSP 标准形 + ColorOS 实证形）：
 * - registerScreenCaptureObserver（AOSP 注册）：masked → 跳过注册
 *   （observer 列表为空，派发无从发生；反注册为无遍历删除，客户端
 *   void 调用无感知）；
 * - dispatchScreenCaptureCallback（AOSP 派发）/ reportScreenCaptured
 *   （ColorOS 15 派发，OEM 改名，实测）：masked → 吞噬——与注册路径
 *   互为冗余覆盖。
 * 查询形（isRegisteredForScreenCaptureCallback）不碰：吞噬查询会
 * 破坏 WMS 内部派发判定。
 *
 * 【通道 2：媒体库 ContentObserver（激进检测过滤，全版本路径 + Shell 通道）】
 * 检测者监听 MediaStore.Images / Downloads（截图落盘 + screencap 落盘
 * Download/）。ContentService#registerContentObserver（system_server）：
 * 调用者开启激进过滤且 URI 属媒体域（authority media/downloads）时，
 * 注册一律替换为影子 observer（java.lang.reflect.Proxy；asBinder 落回
 * 原 binder：死亡通知与反注册匹配走原生路径，零生命周期管理）。
 * 档位在 [dispatch] 派发时实时判定（非注册时快照——配置切换对已
 * 注册 observer 即时生效，无需重启检测者）：
 * - 激进已关 → 直通（零过滤，与未装模块等价）；
 * - 激进+子关「允许监听自身媒体事件」关 → 全吞；
 * - 激进+子开 → 行级 owner_package_name（MediaProvider insert 时记录
 *   的调用者包名，隐藏列，system_server 特权查询可见）归属判定：
 *   事件 owner == 注册者自己的包 → 转发原 observer，其余吞。判定
 *   自参照（不枚举"谁是截屏应用"）：OEM 包名漂移/第三方截屏/adb
 *   screencap 全通道覆盖，且检测者自插探测天然通过（自己的写
 *   owner 归自己）——屏蔽不可自证。已知代价：该应用对他人来源的
 *   正常媒体变更（存图等）同样无感——「激进」语义。
 * 树对账（[reconcileTree]，配置推送 + 新代装配触发）：激进开↔关对
 * 树上已注册 observer 的形态迁移（非激进期原生注册的换影子 / 激进
 * 已关的影子还原），以及热重载残留（清扫还原后新代主动回挂）。
 * 归属判定异步单线程派发（observer 本就是异步语义，毫秒级延迟无感）；
 * 查询失败/非 item uri/无 owner 列（Downloads）一律 fail-closed 吞。
 * 热重载清扫：ContentService 持有的影子 handler 钉住旧模块
 * classloader，[prepareHotReload] 在旧代回调里遍历 observer 树还原。
 * 装配时序：onSystemServerStarting 早于 ContentService 出版——冷启动
 * 挂 ServiceManager.addService 出版哨兵，"content" 出版瞬间装配。
 *
 * 与 E1 的组合语义：DENY 时截屏已被拒（媒体库无新增、secure 派发不发生），
 * 本引擎作用于 ALLOW/FOLLOW 下"截屏成功但检测者不应知晓"的场景。
 *
 * 已知边界（文档化接受，system_server 无杠杆）：
 * - MediaProvider 冷启动回查（检测者进程对 media provider 的 query，
 *   15s 回看窗内命中——需注入 media 进程，违反零注入体例）；
 * - API<34 无障碍按键流回退路径（需检测者自启无障碍服务，罕见）。
 */
object CaptureDetectionHook {

    /** 媒体域 authority（MediaStore 全系 + 旧版 Downloads provider） */
    private val MEDIA_AUTHORITIES = setOf("media", "downloads")

    // ---- ActivityRecord 归属解析缓存（install 解析一次，ownerMasked 消费）----

    /** WindowToken#getOwningPackage（AOSP 方法候选） */
    private var getOwningPackage: Method? = null

    /** ActivityRecord#mPackageName（AOSP 字段候选） */
    private var pkgField: Field? = null

    /** ActivityRecord#getUid（AOSP 方法候选） */
    private var uidMethod: Method? = null

    /** ActivityRecord#mUid（字段候选） */
    private var uidField: Field? = null

    /** WindowToken#getOwnerUid（方法候选） */
    private var ownerUidMethod: Method? = null

    /** WindowToken#mOwnerUid（字段候选） */
    private var ownerUidField: Field? = null

    /**
     * 吞噬目标（AOSP 标准形 + ColorOS 15 实证形；精确名匹配，
     * 查询形 isRegistered* 不碰——见类注释）
     */
    private val AR_CAPTURE_METHODS = setOf(
        "registerScreenCaptureObserver",
        "dispatchScreenCaptureCallback",
        "reportScreenCaptured",
    )

    fun installSystemServer(classLoader: ClassLoader) {
        installActivityRecordLeg(classLoader)
        installContentObserverLeg()
    }

    // ==================== 通道 1：ScreenCaptureCallback ====================

    private fun installActivityRecordLeg(classLoader: ClassLoader) {
        runCatching {
            val arClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
            // 归属解析链（ColorOS 15 实测 getOwningPackage 方法与 mPackageName
            // 字段均被 OEM 移除）：包名方法 → 包名字段 → uid 方法（getUid，
            // AOSP ActivityRecord 标准公开）→ uid 字段（mUid）→ WindowToken
            // ownerUid 方法/字段；uid 结果经 IPackageManager 反查包名集合
            getOwningPackage = methodInHierarchy(arClass, "getOwningPackage")
                ?.apply { isAccessible = true }
            pkgField = fieldInHierarchy(arClass, "mPackageName")?.apply { isAccessible = true }
            uidMethod = methodInHierarchy(arClass, "getUid")?.apply { isAccessible = true }
            uidField = fieldInHierarchy(arClass, "mUid")?.apply { isAccessible = true }
            ownerUidMethod = methodInHierarchy(arClass, "getOwnerUid")?.apply { isAccessible = true }
            ownerUidField = fieldInHierarchy(arClass, "mOwnerUid")?.apply { isAccessible = true }
            if (getOwningPackage == null && pkgField == null && uidMethod == null &&
                uidField == null && ownerUidMethod == null && ownerUidField == null
            ) {
                // OEM 校准弹药：ActivityRecord 链字段清单（归属解析全灭时）
                val fTrace = hierarchyOf(arClass)
                    .flatMap { it.declaredFields.toList() }
                    .filter { it.type == String::class.java || it.type == Int::class.javaPrimitiveType }
                    .joinToString { "${it.name}:${it.type.simpleName}" }
                HookContext.log(Log.WARN, "E2a activityRecord leg abort: no owner resolution; fields: $fTrace")
                return
            }
            var hooked = 0
            hierarchyOf(arClass)
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name in AR_CAPTURE_METHODS }
                .forEach { m ->
                    m.isAccessible = true
                    HookContext.hookE("E2a", m).intercept { chain ->
                        if (ownerMasked(chain.thisObject)) {
                            HookContext.log(Log.INFO, "E2a capture callback swallowed (owner resolved)")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }
            if (hooked == 0) {
                // OEM 校准弹药：ActivityRecord 链上含 capture 语义的方法清单
                val trace = hierarchyOf(arClass)
                    .flatMap { it.declaredMethods.toList() }
                    .filter { it.name.contains("apture", true) }
                    .joinToString { "${it.name}(${it.parameterCount})" }
                HookContext.log(Log.WARN, "E2a activityRecord: 0 hooked; capture-ish: $trace")
            } else {
                val via = listOfNotNull(
                    "pkg-method" to (getOwningPackage != null),
                    "pkg-field" to (pkgField != null),
                    "uid-method" to (uidMethod != null),
                    "uid-field" to (uidField != null),
                    "ownerUid-method" to (ownerUidMethod != null),
                    "ownerUid-field" to (ownerUidField != null),
                ).filter { it.second }.joinToString { it.first }
                HookContext.log(
                    Log.INFO,
                    "E2a activityRecord: $hooked hooked of ${AR_CAPTURE_METHODS.size} targets (owner via $via)"
                )
            }
        }.onFailure { HookContext.log(Log.WARN, "E2a activityRecord leg error: ${it.message}") }
    }

    /** ActivityRecord 归属命中检测屏蔽：包名直判 → uid 反查包名集任一命中 */
    private fun ownerMasked(ar: Any?): Boolean = runCatching {
        (getOwningPackage?.invoke(ar) as? String)?.let { return@runCatching HookContext.maskCaptureDetection(it) }
        (pkgField?.get(ar) as? String)?.let { return@runCatching HookContext.maskCaptureDetection(it) }
        val uid = (uidMethod?.invoke(ar) as? Int)
            ?: (uidField?.get(ar) as? Int)
            ?: (ownerUidMethod?.invoke(ar) as? Int)
            ?: (ownerUidField?.get(ar) as? Int)
            ?: return@runCatching false
        HookContext.anyPkgForUid(uid) { HookContext.maskCaptureDetection(it) }
    }.getOrDefault(false)

    // ==================== 通道 2：媒体库 ContentObserver ====================

    private fun installContentObserverLeg() {
        runCatching {
            // ColorOS 15 实测：ContentService 既不在模块 CL 可见域（bare
            // Class.forName CNFE），也不在 system_server 服务 CL 的委托链。
            // 改走实例反查：system_server 进程内 getService("content") 返回
            // 本进程注册的本地 Binder（ContentService 实例自身），javaClass
            // 即运行类，绕开全部 classloader 结构问题。
            // 时序（9/11 日志实证）：onSystemServerStarting 早于
            // startOtherServices 的 ContentService 出版，冷启动分辨率恒
            // null——此前全靠热重载续命。出版前改挂 addService 哨兵，
            // "content" 出版瞬间经实例反查完成装配，重启路径自洽
            val csClass = resolveContentServiceClass()
            if (csClass != null) {
                if (hookRegisterPaths(csClass) > 0) armReconcile()
            } else {
                installPublicationSentinel()
            }
        }.onFailure { HookContext.log(Log.WARN, "E2a contentObserver leg error: ${it.message}") }
    }

    /** 对账接线标记（一次性；哨兵路径装配成功后同样进入） */
    @Volatile
    private var reconcileWired = false

    /**
     * 对账接线：注册成功即对账一次 + 订阅配置推送。装配期对账收口
     * 热重载残留（[prepareHotReload] 还原的原 observer 主动回挂，
     * 无需重启检测者）；配置推送对账收口激进开↔关的树上形态迁移
     */
    private fun armReconcile() {
        if (reconcileWired) return
        reconcileWired = true
        HookContext.addConfigReloadListener {
            runCatching { dispatcher.execute { reconcileTree() } }
        }
        runCatching { dispatcher.execute { reconcileTree() } }
    }

    /** registerContentObserver 注册路径装配（ContentService 运行类），返回命中数 */
    private fun hookRegisterPaths(csClass: Class<*>): Int {
        var hooked = 0
        runCatching {
            csClass.declaredMethods.filter { it.name == "registerContentObserver" }.forEach { m ->
                val uriIdx = m.parameterTypes.indexOfFirst { it == Uri::class.java }
                if (uriIdx < 0) return@forEach
                m.isAccessible = true
                HookContext.hookE("E2a", m).intercept { chain ->
                    val uri = chain.args.getOrNull(uriIdx) as? Uri
                    val callerUid = Binder.getCallingUid()
                    if (uri == null || uri.authority !in MEDIA_AUTHORITIES) {
                        return@intercept chain.proceed()
                    }
                    // observer 形参解析（ColorOS 15 实测 IContentObserver FQN 为
                    // android.database.*，AOSP 为 android.content.*，按名匹配两系；
                    // 形参类型本身即接口 Class，直接喂 Proxy，无需类层次反查）
                    val obsIdx = m.parameterTypes.indexOfFirst { it.name.endsWith(".IContentObserver") }
                    val original = if (obsIdx >= 0) chain.args.getOrNull(obsIdx) else null
                    // 档案登记（原 observer → uid/包名集/接口 Class）：对账
                    // （[reconcileTree]）的双向原料——非激进注册也登记，激进
                    // 开启时代为换影子；弱 key 随 binder 消亡自然回收
                    if (original != null) {
                        HookContext.packagesForUid(callerUid)?.let { pkgs ->
                            regs[original] = Reg(callerUid, pkgs.toSet(), m.parameterTypes[obsIdx])
                        }
                    }
                    when {
                        // 非激进调用者：原生注册
                        !HookContext.anyPkgForUid(callerUid) { HookContext.aggressiveFilter(it) } ->
                            chain.proceed()
                        // 激进调用者：一律影子（子开/子关统一挂载——档位由
                        // [dispatch] 派发时实时判定，配置切换对已注册
                        // observer 即时生效，无需重启检测者。不再吞注册：
                        // 吞掉的 observer 不在树上，子关→子开切换后无从
                        // 派发，只能重启恢复）
                        else -> {
                            val shadow = original?.let { createShadowObserver(it, callerUid, m.parameterTypes[obsIdx]) }
                            if (shadow == null) {
                                // 异形（无 observer 参数/接口形状不符）→ 保守
                                // 全吞（检测泄漏比探测识破更糟——威胁模型优先级）。
                                // 诊断口径入日志：params 清单一次定案形状
                                HookContext.log(
                                    Log.WARN,
                                    "E2a shadow abort: obsIdx=$obsIdx params=" +
                                            m.parameterTypes.joinToString(",") { it.name } +
                                            " orig=${original?.javaClass?.name} uid=$callerUid"
                                )
                                null
                            } else {
                                HookContext.log(Log.INFO, "E2a shadow observer armed (uid=$callerUid)")
                                chain.proceed(chain.args.toTypedArray().also { it[obsIdx] = shadow })
                            }
                        }
                    }
                }
                hooked++
            }
        }.onFailure { HookContext.log(Log.WARN, "E2a contentObserver hook error: ${it.message}") }
        if (hooked == 0) {
            HookContext.log(
                Log.WARN,
                "E2a contentObserver: 0 hooked; register-ish: " +
                        csClass.declaredMethods.filter { it.name.startsWith("register") }
                            .joinToString { it.name }
            )
        } else {
            HookContext.log(Log.INFO, "E2a contentObserver: $hooked register paths hooked (${csClass.name})")
        }
        return hooked
    }

    /**
     * ContentService 运行类解析：本地 binder 实例反查（须已出版）。
     * 无 CL 回落——android.content.ContentService 类名从未存在（服务
     * 真身在 services.jar 的 com.android.server.content，该 CL 又不可
     * 见），历史日志两证皆死
     */
    private fun resolveContentServiceClass(): Class<*>? {
        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "content")
            // 本地 Binder 实例（非 BinderProxy）即服务实现，且须含目标方法
            if (binder != null && binder.javaClass.name != "android.os.BinderProxy" &&
                binder.javaClass.declaredMethods.any { it.name == "registerContentObserver" }
            ) binder.javaClass else null
        }.getOrNull()
    }

    /** 通道 2 已装配标记（哨兵一次性消费；热重载新代对象级复位） */
    @Volatile
    private var legArmed = false

    /**
     * 出版哨兵：分辨率失败（冷启动）时挂 ServiceManager.addService 全
     * 静态重载（String, IBinder, ...），"content" 出版先原生放行、再经
     * binder 实例反查装配。哨兵常驻进程（addService 冷启动后近乎零流
     * 量）；热重载新代直连装配成功时，哨兵 id 不在新集合即被解除
     */
    private fun installPublicationSentinel() {
        val sm = runCatching { Class.forName("android.os.ServiceManager") }.getOrNull() ?: run {
            HookContext.log(Log.WARN, "E2a contentObserver leg abort: no ServiceManager")
            return
        }
        var sentinels = 0
        sm.declaredMethods.filter { m ->
            m.name == "addService" && Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.size >= 2 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == IBinder::class.java
        }.forEach { m ->
            m.isAccessible = true
            HookContext.hookE("E2a", m).intercept { chain ->
                val result = chain.proceed() // 先原生出版，服务全局就位后再装配
                if (!legArmed && chain.args.getOrNull(0) == "content") {
                    val binder = chain.args.getOrNull(1)
                    if (binder != null && binder.javaClass.name != "android.os.BinderProxy") {
                        if (hookRegisterPaths(binder.javaClass) > 0) {
                            legArmed = true
                            armReconcile()
                        }
                    }
                }
                result
            }
            sentinels++
        }
        if (sentinels == 0) {
            HookContext.log(Log.WARN, "E2a contentObserver leg abort: 0 addService sentinels")
        } else {
            HookContext.log(Log.INFO, "E2a contentObserver deferred via $sentinels addService sentinels")
        }
    }

    // ==================== 影子 observer（激进档位） ====================

    /** 影子注册表（proxy → 原 observer），热重载清扫与对账还原消费 */
    private val shadows = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    /** 注册档案：observer 归属 uid/包名集（档位判定）+ IContentObserver 接口 Class（影子装配） */
    private class Reg(val uid: Int, val pkgs: Set<String>, val iface: Class<*>)

    /**
     * 注册档案表（key = 原 observer，弱引用随 binder 消亡）：
     * [reconcileTree] 对账的双向原料——树上 observer 形态与当前配置
     * 一致化。跨代蒸发（热重载后新代表为空）→ 对账走 entry.uid 兜底
     */
    private val regs = Collections.synchronizedMap(WeakHashMap<Any, Reg>())

    /**
     * 归属判定派发器：单线程 + 30s 空闲自灭（不常驻线程，体例）。影子
     * 收到事件仅入队即刻返回（system_server binder 线程零阻塞），worker
     * 里再查 owner 再转发——避免同步回查 MediaProvider 撞锁
     * （media 进程持 DB 锁 notify ↔ system_server 派发中回查的环）。
     * 直接构造 ThreadPoolExecutor（Executors.newSingleThreadExecutor 的
     * DelegatedExecutorService 包装无 allowCoreThreadTimeOut 面）：
     * core=max=1 + keepAlive 30s + core 超时允许 = 空闲 30s 线程自灭，
     * 新事件到达时再孵化
     */
    private val dispatcher = ThreadPoolExecutor(
        1, 1, 30_000, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { r -> Thread(r, "sf.e2a.shadow").apply { isDaemon = true } }.apply {
        allowCoreThreadTimeOut(true)
    }

    /** owner 查询 LRU：同一次 insert 通知派发给同应用 N 个 observer，重复查询收敛为 1 */
    private val ownerCache = LruCache<String, Optional<String>>(64)

    /**
     * 归属查询用 resolver 缓存（system_server 应用上下文的
     * ContentResolver，进程生命周期单例，随进程消亡——非泄漏）。刻意
     * 不静态持有 Context 本身：Android Studio StaticFieldLeak 检查命中
     * object 单例的 Context 静态字段，且 resolver 已是本引擎消费的
     * 全部所需（最窄依赖面，引用方向 模块→框架，不钉模块 classloader）
     */
    @Volatile
    private var sysResolver: ContentResolver? = null

    // 一次性结果探针（真机校准用：首次转发/首次吞各一条）
    private var fwdLogged = false
    private var dropLogged = false

    /**
     * 影子装配：接口由调用方按 registerContentObserver 形参传入（跨 FQN
     * 稳定：ColorOS android.database.* / AOSP android.content.*），Proxy
     * 类加载器用接口自身的（boot）——影子类本体不钉模块 classloader，
     * 仅 handler 会钉（清扫见 [prepareHotReload]）
     */
    private fun createShadowObserver(original: Any, callerUid: Int, iface: Class<*>): Any? {
        // 形参类型即 IContentObserver 接口（ColorOS android.database.* / AOSP
        // android.content.* 由调用方按名解析）；非接口（异形）保守放弃
        if (!iface.isInterface) return null
        val shadow = runCatching {
            Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), ShadowHandler(original, callerUid))
        }.getOrNull() ?: return null
        shadows[shadow] = original
        return shadow
    }

    /**
     * 影子派发处理器：onChange* 族异步入队，asBinder 落回原 binder
     * （ContentService 的死亡通知与反注册按 asBinder 比对——原生路径
     * 匹配，无需反向映射），Object 方法按代理身份应答，其余透明转发
     */
    private class ShadowHandler(
        private val original: Any,
        private val callerUid: Int,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? = when {
            method.declaringClass == Any::class.java -> when (method.name) {
                "equals" -> args?.get(0) === proxy
                "hashCode" -> System.identityHashCode(proxy)
                else -> "SFShadow($original)" // toString
            }
            method.name == "asBinder" ->
                method.invoke(original, *(args ?: arrayOfNulls(0)))
            method.name.startsWith("onChange") -> {
                runCatching { dispatcher.execute { dispatch(method, args, original, callerUid) } }
                null // oneway void
            }
            else -> method.invoke(original, *(args ?: arrayOfNulls(0)))
        }
    }

    /**
     * 档位判定（worker 线程，实时读配置——非注册时快照，配置切换对
     * 已注册影子即时生效）三态：
     * - 激进已关 → 直通（零过滤，与未装模块等价）；
     * - 激进+子关 → 全吞（等价注册期吞注册的旧行为）；
     * - 激进+子开 → 行级 owner 归属判定（事件携带的全部 uri 均可归属
     *   且 owner 均属注册者自己的包集 → 转发原 observer；任一不可
     *   归属/非自己 → 吞。fail-closed：查询失败、删除事件行已消失、
     *   表级通知、Downloads 无 owner 列——与子关全吞语义一致，且均
     *   非截屏信号）
     */
    private fun dispatch(method: Method, args: Array<Any?>?, original: Any, callerUid: Int) {
        val selfPkgs = HookContext.packagesForUid(callerUid) ?: return dropOnce("uid unresolved")
        if (selfPkgs.none { HookContext.aggressiveFilter(it) }) return forward(method, args, original)
        if (selfPkgs.none { HookContext.allowSelfMediaEvents(it) }) return dropOnce("self-media off")
        val uris = urisOf(args)
        if (uris.isEmpty()) return dropOnce("no uri")
        uris.forEach { uri ->
            val owner = ownerOf(uri) ?: return dropOnce("unattributable ${uri.lastPathSegment}")
            if (owner !in selfPkgs) return dropOnce("owner=$owner")
        }
        forward(method, args, original)
    }

    private fun forward(method: Method, args: Array<Any?>?, original: Any) {
        if (!fwdLogged) {
            fwdLogged = true
            HookContext.log(Log.INFO, "E2a shadow forwarded self event")
        }
        runCatching { method.invoke(original, *(args ?: arrayOfNulls(0))) }
    }

    private fun dropOnce(reason: String) {
        if (!dropLogged) {
            dropLogged = true
            HookContext.log(Log.INFO, "E2a shadow dropped event ($reason)")
        }
    }

    /** 事件携带 uri 提取（onChangeEtc 各版本 Uri/Uri[]/List&lt;Uri&gt; 形状无关） */
    private fun urisOf(args: Array<Any?>?): List<Uri> {
        if (args == null) return emptyList()
        return buildList {
            args.forEach { a ->
                when (a) {
                    is Uri -> add(a)
                    is Array<*> -> a.filterIsInstance<Uri>().forEach { add(it) }
                    is List<*> -> a.filterIsInstance<Uri>().forEach { add(it) }
                }
            }
        }
    }

    /**
     * 行级归属反查：item uri（数字末段）→ MediaProvider 的
     * owner_package_name（insert 时记录的调用者包名，隐藏列——
     * system_server 特权调用可见）。非 item uri / provider 无该列
     * （Downloads）/ 查询失败 → null（不可归属，调用方 fail-closed）。
     * 失败同样入缓存（该 uri 的后续通知不再重试——通知是一次性事件）
     */
    private fun ownerOf(uri: Uri): String? {
        if (uri.lastPathSegment?.toIntOrNull() == null) return null
        val key = uri.toString()
        ownerCache.get(key)?.let { return it.orElse(null) }
        val owner = runCatching {
            (sysResolver ?: resolveSystemContext()?.contentResolver?.also { sysResolver = it })
                ?.query(uri, arrayOf("owner_package_name"), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        ownerCache.put(key, Optional.ofNullable(owner))
        return owner
    }

    private fun resolveSystemContext(): Context? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()

    /**
     * ContentService 内部态（mLock / mRootNode），树访问公共入口
     * （[prepareHotReload] 与 [reconcileTree] 共用）：本地 binder 实例
     * 反查（须已出版；BinderProxy = 服务不在本进程，放弃）
     */
    private fun contentTree(): Pair<Any?, Any>? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "content") ?: return null
        if (binder.javaClass.name == "android.os.BinderProxy") return null
        val lock = runCatching {
            binder.javaClass.getDeclaredField("mLock").apply { isAccessible = true }.get(binder)
        }.getOrNull()
        val root = runCatching {
            binder.javaClass.getDeclaredField("mRootNode").apply { isAccessible = true }.get(binder)
        }.getOrNull() ?: return null
        lock to root
    }.getOrNull()

    /**
     * 热重载旧代清扫（onHotReloading 里旧代自身回调，[shadows] 可见）：
     * 遍历 ContentService observer 树，把影子还原为原 observer——影子
     * handler→dispatcher→旧静态 的引用链断开，旧 classloader 可回收。
     * 还原导致的过滤空窗由新代 [reconcileTree] 主动回挂收口（不再
     * 依赖检测者下次注册）。树结构反射（mRootNode/mChildren/mObservers/
     * mObserver）为 AOSP 标准形，OEM 漂移时 WARN 放弃（接受 classloader
     * 泄漏——热重载是开发场景，产物路径走重启装配）
     */
    internal fun prepareHotReload() {
        if (shadows.isEmpty()) return
        runCatching {
            val tree = contentTree() ?: return
            synchronized(tree.first ?: Any()) { walkObserverNode(tree.second) }
            HookContext.log(Log.INFO, "E2a ${shadows.size} shadows restored for hot reload")
        }.onFailure {
            HookContext.log(Log.WARN, "E2a shadow restore failed (leak accepted): ${it.message}")
        }
    }

    /**
     * 泛化树遍历：节点上全部 List 字段逐元素处理——含 mObservers 的
     * ObserverEntry（经 mObserver 字段还原）、mLegacyObservers（observer
     * 直存列表，原位替换）、mChildren（子节点递归）。按形状探测不按
     * 字段名硬编码集合，OEM 增删列表字段自动适配
     */
    @Suppress("UNCHECKED_CAST")
    private fun walkObserverNode(node: Any?) {
        if (node == null) return
        runCatching {
            node.javaClass.declaredFields.filter { List::class.java.isAssignableFrom(it.type) }
                .forEach { f ->
                    f.isAccessible = true
                    val list = f.get(node) as? MutableList<Any?> ?: return@forEach
                    list.indices.forEach { i ->
                        val el = list[i] ?: return@forEach
                        when {
                            isObserverEntry(el) -> restoreEntryObserver(el)
                            el in shadows -> shadows[el]?.let { list[i] = it }
                            hasDeclaredField(el, "mObservers") || hasDeclaredField(el, "mChildren") ->
                                walkObserverNode(el)
                        }
                    }
                }
        }
    }

    /** ObserverEntry observer 字段还原（影子 → 原 observer） */
    private fun restoreEntryObserver(entry: Any): Any? = runCatching {
        val f = observerFieldOf(entry) ?: return@runCatching null
        val obs = f.get(entry) ?: return@runCatching null
        shadows[obs]?.also { f.set(entry, it) }
    }.getOrNull()

    // ==================== 树对账（配置热切换 / 热重载残留） ====================

    /**
     * 树对账：ContentService observer 树上的 observer 形态与当前配置
     * 一致化——修复两类"需重启检测者才生效"缺口：
     * - 配置热切换（激进开↔关 / 子开↔子关时 observer 已注册在树上；
     *   子开关切换本身由 [dispatch] 实时判定覆盖，此处只管激进开↔关
     *   的影子挂载/还原）；
     * - 热重载残留（[prepareHotReload] 还原为原 observer 后，新代主动
     *   回挂——旧代档案已蒸发，走 entry.uid 兜底）。
     * 两类条目：
     * - 同代（[regs] 命中，纯内存判定，锁内安全）：非激进期原生注册
     *   的换影子 / 激进已关的影子还原；
     * - 跨代 orphan（档案未命中）：entry.uid 反查归属（uid→包名集为
     *   binder 调用，必须在锁外执行——ContentService 锁内发 binder
     *   调用有死锁面），第二段锁内校验 observer 未变再替换。
     * 在 [dispatcher] 单线程执行（与事件派发串行，免树并发写）
     */
    internal fun reconcileTree() {
        runCatching {
            val tree = contentTree() ?: return
            val (lock, root) = tree
            val orphans = ArrayList<Pair<Any, Any>>() // (entry, observer 快照)
            // 阶段 1（锁内）：同代对账 + orphan 收集
            synchronized(lock ?: Any()) { reconcileNode(root, orphans) }
            if (orphans.isEmpty()) return
            // 阶段 1.5（锁外）：uid 兜底归属反查
            val pendings = orphans.mapNotNull { (entry, obs) ->
                val uid = runCatching {
                    entry.javaClass.getDeclaredField("uid").apply { isAccessible = true }.getInt(entry)
                }.getOrNull() ?: return@mapNotNull null
                val pkgs = HookContext.packagesForUid(uid)?.toSet() ?: return@mapNotNull null
                if (pkgs.none { HookContext.aggressiveFilter(it) }) return@mapNotNull null
                val iface = observerInterfaceOf(obs) ?: return@mapNotNull null
                Triple(entry, obs, Reg(uid, pkgs, iface))
            }
            if (pendings.isEmpty()) return
            // 阶段 2（锁内）：校验 observer 未变（反注册/重注册竞态）后回挂
            var armed = 0
            synchronized(lock ?: Any()) {
                pendings.forEach { (entry, obs, reg) ->
                    runCatching {
                        val f = observerFieldOf(entry) ?: return@runCatching
                        if (f.get(entry) !== obs) return@runCatching
                        createShadowObserver(obs, reg.uid, reg.iface)?.let {
                            regs[obs] = reg // 回挂即同代化：后续对账走内存路径
                            f.set(entry, it)
                            armed++
                        }
                    }
                }
            }
            if (armed > 0) {
                HookContext.log(Log.INFO, "E2a reconcile: $armed orphan observers armed (hot-reload residue)")
            }
        }.onFailure { HookContext.log(Log.WARN, "E2a reconcile failed: ${it.message}") }
    }

    /**
     * 对账树遍历（与 [walkObserverNode] 同形状探测）：ObserverEntry 对账
     * （[reconcileEntry]）、legacy 直存列表（observer 本体，原位替换）、
     * 子节点递归
     */
    @Suppress("UNCHECKED_CAST")
    private fun reconcileNode(node: Any?, orphans: MutableList<Pair<Any, Any>>) {
        if (node == null) return
        runCatching {
            node.javaClass.declaredFields.filter { List::class.java.isAssignableFrom(it.type) }
                .forEach { f ->
                    f.isAccessible = true
                    val list = f.get(node) as? MutableList<Any?> ?: return@forEach
                    list.indices.forEach { i ->
                        val el = list[i] ?: return@forEach
                        when {
                            isObserverEntry(el) -> reconcileEntry(el, orphans)
                            // legacy 直存：本代影子——激进已关/档案丢失 → 还原
                            el in shadows -> {
                                val original = shadows[el]
                                val reg = original?.let { regs[it] }
                                if (reg == null || reg.pkgs.none { HookContext.aggressiveFilter(it) }) {
                                    list[i] = original
                                }
                            }
                            // legacy 直存：原 observer——激进已开 → 影子化
                            el in regs -> {
                                val reg = regs[el] ?: return@forEach
                                if (reg.pkgs.any { HookContext.aggressiveFilter(it) }) {
                                    createShadowObserver(el, reg.uid, reg.iface)?.let { list[i] = it }
                                }
                            }
                            hasDeclaredField(el, "mObservers") || hasDeclaredField(el, "mChildren") ->
                                reconcileNode(el, orphans)
                        }
                    }
                }
        }
    }

    /**
     * ObserverEntry 对账：影子（激进已关）→ 还原；原 observer（激进
     * 已开）→ 影子化；档案均未命中（跨代残留/他应用）→ orphan 收集
     */
    private fun reconcileEntry(entry: Any, orphans: MutableList<Pair<Any, Any>>) {
        runCatching {
            val f = observerFieldOf(entry) ?: return
            val obs = f.get(entry) ?: return
            when {
                obs in shadows -> {
                    val original = shadows[obs] ?: return
                    val reg = regs[original]
                    if (reg == null || reg.pkgs.none { HookContext.aggressiveFilter(it) }) {
                        f.set(entry, original)
                    }
                }
                obs in regs -> {
                    val reg = regs[obs] ?: return
                    if (reg.pkgs.any { HookContext.aggressiveFilter(it) }) {
                        createShadowObserver(obs, reg.uid, reg.iface)?.let { f.set(entry, it) }
                    }
                }
                else -> orphans.add(entry to obs)
            }
        }
    }

    /** ObserverEntry 形状判定（ColorOS 15 实证 mObserver / AOSP observer 两系字段名） */
    private fun isObserverEntry(el: Any): Boolean =
        hasDeclaredField(el, "mObserver") || hasDeclaredField(el, "observer")

    /** ObserverEntry 的 observer 字段解析（按 entry 类缓存；null 缓存经 Optional 包装） */
    private val obsFieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Optional<Field>>()

    private fun observerFieldOf(entry: Any): Field? =
        obsFieldCache.computeIfAbsent(entry.javaClass) { cls ->
            Optional.ofNullable(
                listOf("mObserver", "observer").firstNotNullOfOrNull { n ->
                    runCatching { cls.getDeclaredField(n).apply { isAccessible = true } }.getOrNull()
                }
            )
        }.orElse(null)

    /** 跨代兜底：从 observer 对象（远端代理/本地 stub）类层次解析 IContentObserver 接口 */
    private fun observerInterfaceOf(obs: Any): Class<*>? =
        hierarchyOf(obs.javaClass)
            .flatMap { it.interfaces.toList() }
            .firstOrNull { it.name.endsWith(".IContentObserver") }

    private fun hasDeclaredField(obj: Any, name: String): Boolean =
        runCatching { obj.javaClass.getDeclaredField(name) }.isSuccess

    // ==================== 反射工具 ====================

    private fun hierarchyOf(cls: Class<*>): Sequence<Class<*>> =
        generateSequence(cls) { it.superclass }

    private fun fieldInHierarchy(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }

    private fun methodInHierarchy(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = runCatching { c!!.getDeclaredMethod(name, *params) }.getOrNull()
            if (m != null) return m
            c = c.superclass
        }
        return null
    }
}
