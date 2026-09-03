package fake.screenshot.services.privileged.overlay

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor

import android.view.SurfaceControl
import fake.screenshot.Auxiliary
import java.io.FileDescriptor
import java.lang.reflect.Method

/**
 * 纯 Surface 方案的反射基础设施（root 进程内使用）。
 *
 * root（uid=0）进程不受 hidden API 策略约束，且已在服务启动时显式调用
 * VMRuntime.setHiddenApiExemptions("L") 双保险，这里统一封装本模块用到的
 * 反射入口，避免各组件散落 try/catch。
 *
 * 无痕要求：本模块不输出任何日志。诊断日志是对方案存在性的自述，且
 * 实测 -keep 规则下 R8 不会消除 OLog 风格开关的字符串常量——唯一可靠
 * 的无痕做法是源码级不存在的日志。
 */
internal object OverlayHiddenApi {

    /**
     * layer / monitor / channel / 线程名统一随机化：字符与长度均随机
     * （固定长度分布本身也是指纹），dump 侧不可关联到应用。
     * SecureRandom：这些名字中包含 root 通道 socket 名等直接构成通道
     * 地址的值，必须对抗猜测（kotlin.random 的输出可预测，不适用）。
     */
    fun randomName(lengthRange: IntRange = 8..20): String =
        Auxiliary.getSecureRandomString(Auxiliary.getSecureRandomInt(lengthRange))

    /**
     * IInputManager binder 代理（经 ServiceManager.getService("input")）。
     *
     * 接口类全版本（11-16，AOSP 逐版本核对）为
     * android.hardware.input.IInputManager$Stub（android.view 包名不存在，
     * 会导致 ClassNotFoundException）。列出两个候选仅为防未来包名迁移。
     */
    @Volatile
    private var inputManagerProxy: Any? = null

    @Volatile
    private var proxyResolved = false

    @SuppressLint("PrivateApi")
    private fun resolveInputManagerProxy(): Any? {
        if (proxyResolved) return inputManagerProxy
        proxyResolved = true
        inputManagerProxy = runCatching {
            // ServiceManager 为隐藏 API：反射获取 input 服务的 binder
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "input") as? IBinder
                ?: return@runCatching null
            val stubNames = listOf(
                "android.hardware.input.IInputManager\$Stub", // 11-16 全版本实际包名
                "android.view.IInputManager\$Stub"           // 未来迁移预留
            )
            for (name in stubNames) {
                val stub = runCatching { Class.forName(name) }.getOrNull() ?: continue
                return@runCatching stub.getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            }
            null
        }.getOrNull()
        return inputManagerProxy
    }

    /**
     * monitorGestureInput 跨版本签名差异：
     * - 旧：monitorGestureInput(String name, int displayId)
     * - 新：monitorGestureInput(IBinder token, String name, int displayId)
     *
     * token 参数的引入版本点在 ROM 分支上存在差异（非严格对应某 API 级），
     * 故不依赖 SDK_INT 硬分支：按 SDK 猜测的优先顺序把两种签名都试一遍。
     * uid=0 下 MONITOR_INPUT（signature|privileged）校验直接通过。
     *
     * @return android.view.InputMonitor（实例，方法见 [callMonitor]），全部失败返回 null
     */
    fun createGestureMonitor(displayId: Int): Any? {
        val proxy = resolveInputManagerProxy() ?: return null
        val iInt = Int::class.javaPrimitiveType!!
        val name = randomName()
        val attempts = buildList<Pair<Array<Class<*>>, Array<Any>>> {
            if (Build.VERSION.SDK_INT >= 33) {
                add(
                    arrayOf<Class<*>>(IBinder::class.java, String::class.java, iInt) to
                            arrayOf<Any>(Binder(), name, displayId)
                )
                add(
                    arrayOf<Class<*>>(String::class.java, iInt) to arrayOf<Any>(name, displayId)
                )
            } else {
                add(
                    arrayOf<Class<*>>(String::class.java, iInt) to arrayOf<Any>(name, displayId)
                )
                add(
                    arrayOf<Class<*>>(IBinder::class.java, String::class.java, iInt) to
                            arrayOf<Any>(Binder(), name, displayId)
                )
            }
        }
        for ((types, args) in attempts) {
            val mon = runCatching {
                proxy.javaClass.getMethod("monitorGestureInput", *types).invoke(proxy, *args)
            }.getOrNull() ?: continue
            return mon
        }
        return null
    }

    /** InputMonitor 反射方法调用（getInputChannel / dispose）。 */
    fun callMonitor(monitor: Any?, method: String): Any? {
        if (monitor == null) return null
        return runCatching {
            monitor.javaClass.getMethod(method).invoke(monitor)
        }.getOrNull()
    }

    /**
     * InputChannel → 本进程独占的 fd 副本（ParcelFileDescriptor）。
     *
     * Java 层 InputChannel（11-16 全版本）不暴露 fd 访问器，唯一出口是
     * writeToParcel。两种 wire 格式（AIDL 生成器单测源码 + 真机 v10 诊断
     * 数据双重验证，dataSize=160 / envelope=156=dataSize-4 逐项吻合）：
     *
     * Android 13-16（android.os.InputChannelCore，AIDL parcelable）：
     *   [int32 1][int32 信封=后续字节数][name：String16][int32 fd非空标志
     *   +1][fd：flat binder][token：binder]
     *   ↑ v8-v9 失败根因：pos 4 的 int32 是 AIDL 尺寸信封（156），被
     *     readString 当成字符串长度（156 > 剩余 152 → null → 全链路错位）
     *
     * Android 11-12（native InputChannel::write）：
     *   [int32 1][name：CString][token：binder 24B][fd：binder 24B]
     *   fd 恒在末尾 24 字节，按位置直取。
     */
    fun channelPfd(inputChannel: Any?): ParcelFileDescriptor? {
        if (inputChannel == null) return null

        // 直接访问器（全版本均不存在，快速跳过）
        for (methodName in listOf("getFd", "getFileDescriptor")) {
            val ret = runCatching {
                inputChannel.javaClass.getMethod(methodName).invoke(inputChannel)
            }.getOrNull() ?: continue
            when (ret) {
                is FileDescriptor ->
                    return runCatching { ParcelFileDescriptor.dup(ret) }.getOrNull()

                is Int ->
                    if (ret >= 0) {
                        return runCatching { ParcelFileDescriptor.fromFd(ret) }.getOrNull()
                    }
            }
        }

        val parcel = Parcel.obtain()
        try {
            // 注意：invoke 对 void 方法返回 null，不能用 getOrNull() 判定成功
            val wrote = runCatching {
                inputChannel.javaClass.getMethod(
                    "writeToParcel", Parcel::class.java, Int::class.javaPrimitiveType
                ).invoke(inputChannel, parcel, 0)
            }
            if (wrote.isFailure) return null

            val total = parcel.dataSize()
            parcel.setDataPosition(0)
            val initialized = runCatching { parcel.readInt() }.getOrDefault(0)
            if (initialized != 1) return null

            // ---- 全 parcel 扫描定位 FD 对象 ----
            // 字段顺序在不同版本/OEM 间不可靠（v10/v12 两种布局推断均失败），
            // 但 FD 对象必为 24 字节 flat_binder_object（type=BINDER_TYPE_FD
            // =0x66642a85），且 8 字节对齐。逐位置尝试 readFileDescriptor：
            // 命中返回 PFD（fd 为 copyTo 在本进程 dup 的副本，数值对本进程有效）。
            var pos = 4
            while (pos + 24 <= total) {
                parcel.setDataPosition(pos)
                val found = runCatching { parcel.readFileDescriptor() }.getOrNull()
                if (found != null) {
                    try {
                        val dup = runCatching {
                            ParcelFileDescriptor.dup(found.fileDescriptor)
                        }.getOrNull()
                        if (dup != null) {
                            // fd 必须是 socket（InputChannel 为 SOCK_SEQPACKET）。
                            // binder handle 被误读 / stale fd 都不是 socket，跳过。
                            val isSocket = runCatching {
                                val st = android.system.Os.fstat(dup.fileDescriptor)
                                android.system.OsConstants.S_ISSOCK(st.st_mode)
                            }.getOrDefault(false)
                            if (isSocket) return dup
                            runCatching { dup.close() }
                        }
                    } finally {
                        // readFileDescriptor 的 dup 副本归本进程所有，必须
                        // 显式关闭（返回的 dup 持有独立 fd，不受影响）——
                        // 否则 CloseGuard 告警 + fd 泄漏
                        runCatching { found.close() }
                    }
                }
                pos += 4
            }
            return null
        } finally {
            parcel.recycle()
        }
    }

    // ==================== SurfaceControl 反射面 ====================

    // SurfaceControl.Builder 在 SDK 中公开，但 setBufferSize / setFormat /
    // setParent 为 @hide：反射调用（编译期不可见）。
    private val builderClass = SurfaceControl.Builder::class.java

    fun newLayerBuilder(name: String): SurfaceControl.Builder =
        SurfaceControl.Builder().setName(name)

    /**
     * Android 11 截图排除（setSkipScreenshot 的 11 代等价物）。
     *
     * 机制（AOSP 11 源码逐环节核对 + 真机实测双重验证）：
     * Builder.setMetadata(METADATA_WINDOW_TYPE=2, 441731) →
     * SurfaceFlinger::createLayer 检测 windowType==441731
     * （WINDOW_TYPE_DONT_SCREENSHOT，系统圆角 overlay 同款机制）→
     * layer->setPrimaryDisplayOnly() → latchCompositionState 写入
     * compositionState->internalOnly →
     * - 截图遍历 traverseLayersInDisplay（belongsToDisplay(stack,false)）
     *   排除该 layer → 截图显示下层内容（穿透）；
     * - 虚拟显示器（MediaProjection 录屏）排除 → 录屏同样穿透。
     *
     * 12+ 的 setSkipScreenshot 最终映射到 outputFilter.toInternalDisplay
     * （A15 LayerSnapshotBuilder 证实），与本机制同源同语义。
     *
     * 必须在 build() 前调用（检查只发生在 createLayer 时刻，
     * Transaction.setMetadata 后设无效）。
     */
    fun builderExcludeScreenshot(builder: SurfaceControl.Builder): Boolean {
        // 仅 Android 11：12+ 的 createLayer 已无 441731 检查（改由
        // setSkipScreenshot 的 eLayerSkipScreenshot flag 承担），且 12+
        // metadata windowType 会参与 input/trusted-overlay 判定，避免误用
        if (Build.VERSION.SDK_INT > 30) return false
        return runCatching {
            val m = builderClass.getMethod(
                "setMetadata", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            m.invoke(builder, 2 /* METADATA_WINDOW_TYPE */, 441731 /* WINDOW_TYPE_DONT_SCREENSHOT */)
            true
        }.getOrDefault(false)
    }

    fun builderSetBufferSize(builder: SurfaceControl.Builder, w: Int, h: Int) {
        runCatching {
            builderClass.getMethod("setBufferSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(builder, w, h)
        }
    }

    fun builderSetFormat(builder: SurfaceControl.Builder, format: Int) {
        runCatching {
            builderClass.getMethod("setFormat", Int::class.javaPrimitiveType).invoke(builder, format)
        }
    }

    fun builderSetParent(builder: SurfaceControl.Builder, parent: SurfaceControl) {
        runCatching {
            builderClass.getMethod("setParent", SurfaceControl::class.java).invoke(builder, parent)
        }
    }

    /**
     * setSkipScreenshot(SurfaceControl, boolean)：Transaction 隐藏方法（12+）。
     * root 进程无限制。返回 false 表示本层排除失败：截图/录屏将包含
     * 悬浮窗，直接违背无痕语义——调用方必须致命化处理（上报回落），
     * 不得静默降级。
     */
    fun applySkipScreenshot(layer: SurfaceControl): Boolean {
        return runCatching {
            val t = SurfaceControl.Transaction()
            val m = SurfaceControl.Transaction::class.java
                .getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
            m.invoke(t, layer, true)
            t.apply()
            true
        }.getOrDefault(false)
    }

    // ==================== Transaction 反射面 ====================
    //
    // setLayer/show/hide/setAlpha 虽在 API 29+ 公开，但 setPosition(31+ 公开)、
    // setBufferSize(隐藏) 直接编译调用在 API 30 设备上会命中 hidden API
    // 运行时拦截（方法引用按编译期签名链接）。为跨版本行为一致，本模块
    // 对 Transaction 的全部几何操作统一反射（root 进程豁免后稳定可用）。

    private val txClass = SurfaceControl.Transaction::class.java

    private fun txIntInt(name: String): Method? =
        runCatching {
            txClass.getMethod(name, SurfaceControl::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()

    private fun txFloat(name: String): Method? =
        runCatching {
            txClass.getMethod(name, SurfaceControl::class.java, Float::class.javaPrimitiveType)
        }.getOrNull()

    private val setPositionMethod by lazy {
        runCatching {
            txClass.getMethod(
                "setPosition", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
        }.getOrNull()
    }
    private val setLayerMethod by lazy { txIntInt("setLayer") }
    private val setBufferSizeMethod by lazy { txIntInt("setBufferSize") }
    private val setAlphaMethod by lazy { txFloat("setAlpha") }
    private val showMethod by lazy {
        runCatching {
            txClass.getMethod("show", SurfaceControl::class.java)
        }.getOrNull()
    }

    // setMatrix(SurfaceControl, dsdx, dtdx, dtdy, dsdy)：SF 合成器 2x2
    // 仿射缩放（GPU 路径，不重分配 buffer、不触发 canvas 重绘）——
    // resize 手势 live 阶段的零成本几何变换。
    private val setMatrixMethod by lazy {
        runCatching {
            txClass.getMethod(
                "setMatrix", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    fun txSetPosition(tx: SurfaceControl.Transaction, sc: SurfaceControl, x: Int, y: Int) {
        setPositionMethod?.invoke(tx, sc, x.toFloat(), y.toFloat())
    }

    fun txSetLayer(tx: SurfaceControl.Transaction, sc: SurfaceControl, layer: Int) {
        setLayerMethod?.invoke(tx, sc, layer)
    }

    /** content/handle layer 以左上角为锚点缩放 sx/sy（等比于 buffer 尺寸）。 */
    fun txSetMatrix(tx: SurfaceControl.Transaction, sc: SurfaceControl, sx: Float, sy: Float) {
        // matrix = [dsdx dtdx; dtdy dsdy]，纯缩放取 [sx 0; 0 sy]
        setMatrixMethod?.invoke(tx, sc, sx, 0f, 0f, sy)
    }

    fun txSetBufferSize(tx: SurfaceControl.Transaction, sc: SurfaceControl, w: Int, h: Int) {
        setBufferSizeMethod?.invoke(tx, sc, w, h)
    }

    fun txSetAlpha(tx: SurfaceControl.Transaction, sc: SurfaceControl, alpha: Float) {
        setAlphaMethod?.invoke(tx, sc, alpha)
    }

    fun txShow(tx: SurfaceControl.Transaction, sc: SurfaceControl) {
        showMethod?.invoke(tx, sc)
    }
}
