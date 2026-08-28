package fake.screenshot.services.privileged.overlay

import android.os.Build
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * InputMessage 二进制协议的自解析与 ACK 编组（路线 B 核心）。
 *
 * InputChannel 底层为 socketpair(AF_UNIX, SOCK_SEQPACKET)：一次 read 恰好
 * 返回一条完整 InputMessage（消息边界由内核保留），仅携带
 * header + body 的实际 size() 字节（pointers 只传 pointerCount 部分）。
 *
 * ==================== 布局四代（AOSP InputTransport.h 逐版本核对） ====================
 *
 * 通用 header（8 字节）：
 *   [0] type: u32   （KEY=0 MOTION=1 FINISHED=2 ...，MOTION 恒为 1）
 *   [4] seq: u32    （API 30 为 padding；API 31+ 为派发序号）
 *
 * Gen1 = API 30（Android 11）：seq 在 body 首字段；带 xScale/yScale/xOffset/
 *   yOffset（无变换矩阵）：
 *   [8]seq u32 [12]eventId i32 [16]eventTime i64 [24]deviceId [28]source
 *   [32]displayId [36]hmac[32] [68]action [72]actionButton [76]flags
 *   [80]metaState [84]buttonState [88]classification u8+3pad [92]edgeFlags
 *   [96]downTime i64 [104..136) xScale,yScale,xOffset,yOffset,xPrec,yPrec,
 *   xCursor,yCursor（8×f32） [136]pointerCount u32 [140]empty3 u32
 *   [144]pointers[]（每项 8+128=136B，8 字节对齐）
 *
 * Gen2 = API 31-32（Android 12-13 前身）：header 带 seq；6 个 transform
 *   float + display 三元组：
 *   [8]eventId [12]empty1 [16]eventTime i64 [24]deviceId [28]source
 *   [32]displayId [36]hmac[32] [68]action [72]actionButton [76]flags
 *   [80]metaState [84]buttonState [88]class+3pad [92]edgeFlags [96]downTime
 *   i64 [104..128) dsdx,dtdx,dtdy,dsdy,tx,ty [128]xPrec [132]yPrec
 *   [136]xCursor [140]yCursor [144]displayOrientation u32 [148]displayWidth
 *   [152]displayHeight [156]pointerCount u32 [160]pointers[]（136B/项）
 *
 * Gen3 = API 33（Android 13）：pointerCount 前移 + 双变换矩阵：
 *   [8]eventId [12]pointerCount [16]eventTime i64 [24]deviceId [28]source
 *   [32]displayId [36]hmac[32] [68]action [72]actionButton [76]flags
 *   [80]metaState [84]buttonState [88]class+3pad [92]edgeFlags [96]downTime
 *   i64 [104..168) dsdx..ty + dsdxRaw..tyRaw（12×f32） [168]pointers[]
 *
 * Gen4 = API 34+（Android 14-16）：同 Gen3，PointerCoords 由 128B 变 136B
 *   （isResampled bool + 7 pad），pointer 每项 8+136=144B。
 *
 * PointerProperties 恒为 {id:i32, toolType:i32}=8B；PointerCoords =
 *   {bits:u64 + values[30]f32}（Gen1-3 为 128B，Gen4 追加 isResampled+7pad）。
 * X/Y/PRESSURE/SIZE 等轴值按 bits 位序打包在 values 中（按 axis id 升序）。
 *
 * ==================== ACK（必须立即回发，否则 InputDispatcher ANR） ====================
 *
 * FINISHED（type=2）：
 * - API 30：header{type,pad} + body{seq u32, handled u32} = 16B
 * - API 31+：header{type, seq} + body{handled bool+7pad, consumeTime i64} = 24B
 */
internal object InputMessageCodec {

    private const val TYPE_KEY = 0
    private const val TYPE_MOTION = 1
    private const val TYPE_FINISHED = 2

    // PointerProperties 大小（全版本 8B）
    private const val POINTER_PROPERTIES_SIZE = 8

    // PointerCoords 大小：API 30-33 为 128B（bits+values[30]），API 34+ 为 136B
    private val pointerCoordsSize: Int
        get() = if (Build.VERSION.SDK_INT >= 34) 136 else 128

    private val pointerSize: Int get() = POINTER_PROPERTIES_SIZE + pointerCoordsSize

    // Pointer 数组起始偏移（body 相对整条消息）
    private val motionPointersOffset: Int
        get() = when {
            Build.VERSION.SDK_INT <= 30 -> 144
            Build.VERSION.SDK_INT <= 32 -> 160
            else -> 168
        }

    private val motionPointerCountOffset: Int
        get() = when {
            Build.VERSION.SDK_INT <= 30 -> 136
            Build.VERSION.SDK_INT <= 32 -> 156
            else -> 12
        }

    private val motionSeqOffset: Int
        get() = if (Build.VERSION.SDK_INT <= 30) 8 else 4

    /** 一条 MOTION 消息的完整字节数（用于读定长前的校验）。 */
    fun motionMessageSize(pointerCount: Int): Int =
        motionPointersOffset + pointerCount * pointerSize

    // ==================== 二进制读取小工具（小端） ====================

    private fun wrap(bytes: ByteArray, size: Int): ByteBuffer =
        ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)

    private fun u32(buf: ByteBuffer, off: Int): Int = buf.getInt(off)
    private fun i64(buf: ByteBuffer, off: Int): Long = buf.getLong(off)
    private fun f32(buf: ByteBuffer, off: Int): Float = buf.getFloat(off)

    // ==================== 消息头解析 ====================

    fun typeOf(bytes: ByteArray, size: Int): Int =
        if (size >= 4) wrap(bytes, size).getInt(0) else -1

    // ==================== MOTION 解析 → MotionEvent ====================

    /**
     * 解析 MOTION 消息并构造标准 MotionEvent。
     *
     * 坐标语义：手势监视窗口 frame 覆盖整个 display 且 transform 为恒等，
     * PointerCoords 中的 X/Y 即屏幕绝对坐标——直接作为 raw 坐标使用，
     * 手势控制器内自行换算窗口局部坐标。
     *
     * @return null 表示消息非法（长度不足/指针数越界），调用方应丢弃并 ACK。
     */
    fun parseMotion(bytes: ByteArray, size: Int): MotionEvent? {
        if (size < motionPointersOffset) return null
        val buf = wrap(bytes, size)

        val pointerCount = u32(buf, motionPointerCountOffset)
        if (pointerCount < 1 || pointerCount > 32) return null
        if (size < motionMessageSize(pointerCount)) return null

        val eventTime = i64(buf, 16) / 1_000_000L // ns → ms
        val deviceId = u32(buf, 24)
        val source = u32(buf, 28)
        val action = u32(buf, 68)
        val downTime = i64(buf, 96) / 1_000_000L
        val pointerCountReal = pointerCount

        val props = arrayOfNulls<MotionEvent.PointerProperties>(pointerCountReal)
        val coords = arrayOfNulls<MotionEvent.PointerCoords>(pointerCountReal)

        for (i in 0 until pointerCountReal) {
            val base = motionPointersOffset + i * pointerSize
            props[i] = MotionEvent.PointerProperties().apply {
                id = u32(buf, base)
                toolType = u32(buf, base + 4)
            }
            coords[i] = readPointerCoords(buf, base + POINTER_PROPERTIES_SIZE)
        }

        return MotionEvent.obtain(
            downTime, eventTime, action, pointerCountReal,
            props.requireNoNulls(), coords.requireNoNulls(),
            0, 0, 0f, 0f, // metaState, buttonState, xPrecision, yPrecision
            deviceId, 0, source, 0 // deviceId, edgeFlags, source, flags
        )
    }

    /**
     * PointerCoords：bits 位图按 axis id 升序映射到 values 紧凑数组。
     * X=0, Y=1, PRESSURE=2, SIZE=3, TOOL_MAJOR=4, TOOL_MINOR=5, TOUCH_MAJOR=6...
     * （AMOTION_EVENT_AXIS_* 常量值自 API 12 起恒定，硬编码安全。）
     */
    private fun readPointerCoords(buf: ByteBuffer, off: Int): MotionEvent.PointerCoords {
        val out = MotionEvent.PointerCoords()
        var valueIndex = 0
        val bitsRaw = i64(buf, off)
        for (axis in 0 until 64) {
            if (bitsRaw and (1L shl axis) == 0L) continue
            val v = f32(buf, off + 8 + valueIndex * 4)
            valueIndex++
            when (axis) {
                0 -> out.x = v
                1 -> out.y = v
                2 -> out.pressure = v
                3 -> out.size = v
                // 其余轴（工具尺寸/倾角等）对判定无意义：跳过
            }
        }
        return out
    }

    // ==================== FINISHED ACK 编组 ====================

    /**
     * 生成本条消息的 FINISHED ACK 字节。
     * 必须在收到 KEY/MOTION 后立即回发（手势处理之前），否则
     * InputDispatcher 5 秒派发超时触发系统级 ANR（严重暴露面）。
     *
     * @param seq 消息序号（API 30 取自 body，31+ 取自 header）
     * @param consumeTimeMs 当前 uptime（API 31+ 消息需要）
     */
    fun encodeFinishedAck(bytes: ByteArray, size: Int): ByteArray? {
        if (size < 8) return null
        val seq = wrap(bytes, size).getInt(motionSeqOffsetForAck(size))
        return if (Build.VERSION.SDK_INT <= 30) {
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(TYPE_FINISHED).putInt(0) // header: type, padding
                .putInt(seq).putInt(1) // body: seq, handled=true
                .array()
        } else {
            ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(TYPE_FINISHED).putInt(seq) // header: type, seq
                .put(1).put(ByteArray(7)) // body: handled=true + 7pad
                .putLong(android.os.SystemClock.uptimeMillis() * 1_000_000L)
                .array()
        }
    }

    private fun motionSeqOffsetForAck(size: Int): Int =
        if (Build.VERSION.SDK_INT <= 30) 8 else 4
}
