package fake.screenshot.hooks

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.util.Log
import android.util.LruCache
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
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
 * 子开关「允许监听自身媒体事件」决定档位（默认开启）：
 * - 子开（影子 observer）：注册经参数替换继续成功——ContentService
 *   持有 java.lang.reflect.Proxy 影子（asBinder 落回原 binder：死亡
 *   通知与反注册匹配走原生路径，零生命周期管理）。派发时按行级
 *   owner_package_name（MediaProvider insert 时记录的调用者包名，
 *   隐藏列，system_server 特权查询可见）归属判定：事件 owner ==
 *   注册者自己的包 → 转发原 observer，其余吞。判定自参照（不枚举
 *   "谁是截屏应用"）：OEM 包名漂移/第三方截屏/adb screencap 全通道
 *   覆盖，且检测者自插探测天然通过（自己的写 owner 归自己）——
 *   屏蔽不可自证。已知代价：该应用对他人来源的正常媒体变更（存图
 *   等）同样无感——「激进」语义。
 * - 子关（全吞）：静默跳过注册（注册/反注册 binder 调用正常返回，
 *   客户端零感知，observer 永不触发）；自插探测可识破（收不到自己
 *   插入的事件 = 发觉被屏蔽），换来零归属查询开销。
 * 归属判定异步单线程派发（observer 本就是异步语义，毫秒级延迟无感）；
 * 查询失败/非 item uri/无 owner 列（Downloads）一律 fail-closed 吞。
 * 配置变更（关子开关/关激进）对已注册影子在下一次注册时生效——
 * 与激进总开关同粒度（observer 生命周期归检测者，无法代其重注册）。
 * 热重载清扫：ContentService 持有的影子 handler 钉住旧模块
 * classloader，[prepareHotReload] 在旧代回调里遍历 observer 树还原。
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
        installContentObserverLeg(classLoader)
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

    private fun installContentObserverLeg(classLoader: ClassLoader) {
        runCatching {
            // ColorOS 15 实测：ContentService 既不在模块 CL 可见域（bare
            // Class.forName CNFE），也不在 system_server 服务 CL 的委托链
            // （loadClass CNFE——该 CL 仅 services.jar/apex 服务模块）。
            // 改走实例反查：system_server 进程内 getService("content") 返回
            // 本进程注册的本地 Binder（ContentService 实例自身），javaClass
            // 即运行类，绕开全部 classloader 结构问题
            val csClass = resolveContentServiceClass(classLoader) ?: run {
                HookContext.log(Log.WARN, "E2a contentObserver leg abort: no ContentService")
                return
            }
            var hooked = 0
            csClass.declaredMethods.filter { it.name == "registerContentObserver" }.forEach { m ->
                val uriIdx = m.parameterTypes.indexOfFirst { it == Uri::class.java }
                if (uriIdx < 0) return@forEach
                m.isAccessible = true
                HookContext.hookE("E2a", m).intercept { chain ->
                    val uri = chain.args.getOrNull(uriIdx) as? Uri
                    if (uri == null || uri.authority !in MEDIA_AUTHORITIES) {
                        return@intercept chain.proceed()
                    }
                    val callerUid = Binder.getCallingUid()
                    when {
                        // 非激进调用者：原生
                        !HookContext.anyPkgForUid(callerUid) { HookContext.aggressiveFilter(it) } ->
                            chain.proceed()
                        // 子开（影子）：observer 参数替换后原生注册
                        HookContext.anyPkgForUid(callerUid) { HookContext.allowSelfMediaEvents(it) } -> {
                            val obsIdx =
                                m.parameterTypes.indexOfFirst { it.name == "android.content.IContentObserver" }
                            val original = if (obsIdx >= 0) chain.args.getOrNull(obsIdx) else null
                            val shadow = original?.let { createShadowObserver(it, callerUid) }
                            if (shadow == null) {
                                // 异形（无 observer 参数/接口形状不符）→ 保守
                                // 全吞（检测泄漏比探测识破更糟——威胁模型优先级）
                                null
                            } else {
                                HookContext.log(Log.INFO, "E2a shadow observer armed (uid=$callerUid)")
                                chain.proceed(chain.args.toTypedArray().also { it[obsIdx] = shadow })
                            }
                        }
                        // 子关（全吞，旧行为）：静默跳过注册
                        else -> null
                    }
                }
                hooked++
            }
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
        }.onFailure { HookContext.log(Log.WARN, "E2a contentObserver leg error: ${it.message}") }
    }

    /** ContentService 运行类解析：本地 binder 实例反查 → system_server CL 回落 */
    private fun resolveContentServiceClass(classLoader: ClassLoader): Class<*>? {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "content")
            // 本地 Binder 实例（非 BinderProxy）即服务实现，且须含目标方法
            if (binder != null && binder.javaClass.name != "android.os.BinderProxy" &&
                binder.javaClass.declaredMethods.any { it.name == "registerContentObserver" }
            ) return binder.javaClass
        }
        return runCatching { classLoader.loadClass("android.content.ContentService") }.getOrNull()
    }

    // ==================== 影子 observer（子开档位） ====================

    /** 影子注册表（proxy → 原 observer），热重载清扫消费（[prepareHotReload]） */
    private val shadows = Collections.synchronizedMap(WeakHashMap<Any, Any>())

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
     * 影子装配：IContentObserver 接口从原 observer 的类层次反查（跨进程
     * binder 代理必实现之），Proxy 类加载器用接口自身的（boot）——影子
     * 类本体不钉模块 classloader，仅 handler 会钉（清扫见
     * [prepareHotReload]）
     */
    private fun createShadowObserver(original: Any, callerUid: Int): Any? {
        val iface = generateSequence(original.javaClass) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .firstOrNull { it.name == "android.content.IContentObserver" }
            ?: return null
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
     * 归属判定（worker 线程）：事件携带的全部 uri 均可归属（item uri
     * 且 owner 查询成功）且 owner 均属注册者自己的包集 → 转发原
     * observer；任一不可归属/非自己 → 吞（fail-closed：查询失败、
     * 删除事件行已消失、表级通知、Downloads 无 owner 列——与子关的
     * 全吞语义一致，且均非截屏信号）
     */
    private fun dispatch(method: Method, args: Array<Any?>?, original: Any, callerUid: Int) {
        val uris = urisOf(args)
        if (uris.isEmpty()) return dropOnce("no uri")
        val selfPkgs = HookContext.packagesForUid(callerUid) ?: return dropOnce("uid unresolved")
        uris.forEach { uri ->
            val owner = ownerOf(uri) ?: return dropOnce("unattributable ${uri.lastPathSegment}")
            if (owner !in selfPkgs) return dropOnce("owner=$owner")
        }
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
     * 热重载旧代清扫（onHotReloading 里旧代自身回调，[shadows] 可见）：
     * 遍历 ContentService observer 树，把影子还原为原 observer——还原后
     * 注册状态与原生一致（已注册但无过滤），新代在该应用下次注册时重新
     * 换影；影子 handler→dispatcher→旧静态 的引用链断开，旧 classloader
     * 可回收。树结构反射（mRootNode/mChildren/mObservers/mObserver）为
     * AOSP 标准形，OEM 漂移时 WARN 放弃（接受 classloader 泄漏——热重载
     * 是开发场景，产物路径走重启装配）
     */
    internal fun prepareHotReload() {
        if (shadows.isEmpty()) return
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "content") ?: return
            if (binder.javaClass.name == "android.os.BinderProxy") return
            val lock = runCatching {
                binder.javaClass.getDeclaredField("mLock").apply { isAccessible = true }.get(binder)
            }.getOrNull()
            val root = runCatching {
                binder.javaClass.getDeclaredField("mRootNode").apply { isAccessible = true }.get(binder)
            }.getOrNull() ?: return
            synchronized(lock ?: Any()) { walkObserverNode(root) }
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
                            hasDeclaredField(el, "mObserver") -> restoreEntryObserver(el)
                            el in shadows -> shadows[el]?.let { list[i] = it }
                            hasDeclaredField(el, "mObservers") || hasDeclaredField(el, "mChildren") ->
                                walkObserverNode(el)
                        }
                    }
                }
        }
    }

    /** ObserverEntry.mObserver 字段还原（影子 → 原 observer） */
    private fun restoreEntryObserver(entry: Any): Any? = runCatching {
        val f = entry.javaClass.getDeclaredField("mObserver").apply { isAccessible = true }
        val obs = f.get(entry) ?: return@runCatching null
        shadows[obs]?.also { f.set(entry, it) }
    }.getOrNull()

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
