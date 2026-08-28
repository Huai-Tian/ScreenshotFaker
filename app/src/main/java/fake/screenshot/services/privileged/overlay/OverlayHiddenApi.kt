package fake.screenshot.services.privileged.overlay

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
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
 */
internal object OverlayHiddenApi {

    private const val TAG = "RootOverlay"

    /** layer / monitor / channel 名统一随机化：无特征，dump 侧不可关联到应用。 */
    fun randomName(): String = Auxiliary.getRandomString(16)

    /** 当前进程是否 uid=0（真正的 root 进程，隐藏 API 与签名权限全通过）。 */
    val isUidRoot: Boolean by lazy {
        runCatching { android.system.Os.getuid() == 0 }.getOrDefault(false)
    }

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

    private fun resolveInputManagerProxy(): Any? {
        if (proxyResolved) return inputManagerProxy
        proxyResolved = true
        inputManagerProxy = runCatching {
            // ServiceManager 为隐藏 API：反射获取 input 服务的 binder
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "input") as? IBinder
                ?: run {
                    Log.e(TAG, "ServiceManager.getService(\"input\") returned null")
                    return@runCatching null
                }
            val stubNames = listOf(
                "android.hardware.input.IInputManager\$Stub", // 11-16 全版本实际包名
                "android.view.IInputManager\$Stub"           // 未来迁移预留
            )
            for (name in stubNames) {
                val stub = runCatching { Class.forName(name) }.getOrNull() ?: continue
                return@runCatching stub.getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            }
            Log.e(TAG, "IInputManager\$Stub not found in either package")
            null
        }.onFailure {
            // 此前异常被静默吞掉导致无法定位（"proxy unavailable"无原因）
            Log.e(TAG, "IInputManager proxy reflection failed: $it")
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
        val proxy = resolveInputManagerProxy() ?: run {
            Log.e(TAG, "IInputManager proxy unavailable (see reflection error above)")
            return null
        }
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
            }.onFailure {
                Log.w(TAG, "monitorGestureInput ${types.contentToString()} failed: $it")
            }.getOrNull() ?: continue
            return mon
        }
        Log.e(TAG, "monitorGestureInput unavailable in all known signatures")
        return null
    }

    /** InputMonitor 反射方法调用（getInputChannel / pilferPointers / dispose）。 */
    fun callMonitor(monitor: Any?, method: String): Any? {
        if (monitor == null) return null
        return runCatching {
            monitor.javaClass.getMethod(method).invoke(monitor)
        }.onFailure {
            Log.w(TAG, "InputMonitor.$method failed: $it")
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
        if (inputChannel == null) {
            Log.e(TAG, "channelPfd: inputChannel is null")
            return null
        }

        // 直接访问器（全版本均不存在，快速跳过）
        for (methodName in listOf("getFd", "getFileDescriptor")) {
            val ret = runCatching {
                inputChannel.javaClass.getMethod(methodName).invoke(inputChannel)
            }.getOrNull() ?: continue
            when (ret) {
                is FileDescriptor ->
                    return runCatching { ParcelFileDescriptor.dup(ret) }
                        .onFailure { Log.e(TAG, "dup(FileDescriptor) failed: $it") }
                        .getOrNull()

                is Int ->
                    if (ret >= 0) {
                        return runCatching { ParcelFileDescriptor.fromFd(ret) }
                            .onFailure { Log.e(TAG, "fromFd($ret) failed: $it") }
                            .getOrNull()
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
            }.onFailure {
                Log.e(TAG, "writeToParcel reflection failed: $it")
            }
            if (wrote.isFailure) return null

            val total = parcel.dataSize()
            parcel.setDataPosition(0)
            val initialized = runCatching { parcel.readInt() }.getOrDefault(0)
            if (initialized != 1) {
                Log.e(TAG, "parcel not initialized (flag=$initialized, size=$total)")
                return null
            }
            val envelope = runCatching { parcel.readInt() }.getOrDefault(-1)
            Log.i(TAG, "parcel total=$total envelope=$envelope")

            // ---- 全 parcel 扫描定位 FD 对象 ----
            // 字段顺序在不同版本/OEM 间不可靠（v10/v12 两种布局推断均失败），
            // 但 FD 对象必为 24 字节 flat_binder_object（type=BINDER_TYPE_FD
            // =0x66642a85），且 8 字节对齐。逐位置尝试 readFileDescriptor：
            // 命中返回 PFD（fd 为 copyTo 在本进程 dup 的副本，数值对本进程有效）。
            // 同时输出 int map 供人工核对布局。
            val map = StringBuilder()
            var p = 0
            while (p + 4 <= total) {
                parcel.setDataPosition(p)
                val v = runCatching { parcel.readInt() }.getOrDefault(Int.MIN_VALUE)
                if (v == 0x66642a85) map.append(" [FD@").append(p).append("]")
                else map.append(' ').append(p).append(':').append(v)
                p += 4
            }
            Log.i(TAG, "parcel int map:$map")

            var pos = 4
            while (pos + 24 <= total) {
                parcel.setDataPosition(pos)
                val found = runCatching { parcel.readFileDescriptor() }.getOrNull()
                if (found != null) {
                    val dup = runCatching { ParcelFileDescriptor.dup(found.fileDescriptor) }
                        .onFailure { Log.e(TAG, "dup at pos=$pos failed: $it") }
                        .getOrNull()
                    if (dup != null) {
                        val fdNum = dup.fd
                        val statResult = runCatching {
                            val st = android.system.Os.fstat(dup.fileDescriptor)
                            android.system.OsConstants.S_ISSOCK(st.st_mode) to st.st_mode
                        }.getOrNull()
                        val isSocket = statResult?.first ?: false
                        Log.i(
                            TAG,
                            "fd object found at pos=$pos (scan): fdNum=$fdNum isSocket=$isSocket " +
                                    "mode=${statResult?.second?.let { "0x" + Integer.toHexString(it) } ?: "fstat failed"}"
                        )
                        // fd 必须是 socket（InputChannel 为 SOCK_SEQPACKET）。
                        // binder handle 被误读 / stale fd 都不是 socket，跳过。
                        if (isSocket) return dup
                        Log.w(TAG, "fd $fdNum at pos=$pos is not a socket, skipping")
                        runCatching { dup.close() }
                    }
                }
                pos += 4
            }

            Log.e(TAG, "no usable socket fd found by scan (total=$total envelope=$envelope)")
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
     * root 进程无限制；失败不影响主流程（FLAG_SECURE 已无窗口可用，这是
     * 截图排除的唯一手段——返回 false 提示调用方记录，不作为致命错误）。
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
