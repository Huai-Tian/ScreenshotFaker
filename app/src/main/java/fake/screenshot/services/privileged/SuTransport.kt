package fake.screenshot.services.privileged

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor

/**
 * su 直连模式的帧协议常量。
 *
 * 帧格式（两个方向对称）：
 *   [int32 code][int32 payloadLen][payload bytes]
 *
 * code 与 IRootDisplay 的事务码保持一致（1~14），便于对照；
 * 15（registerCallback）不走协议——su 模式下回调对象在 root 进程
 * 启动时就地注入（binder 对象无法跨 socket 序列化）。
 * 媒体帧（code 5/6）的 fd 经 SCM_RIGHTS 随帧头同一次 write 传递。
 */
internal object SuProto {
    /** root -> app：onSwitchMedia(int delta)，payload 为 1 个 int32 */
    const val CODE_ON_SWITCH_MEDIA = 100

    /** app -> root：销毁 root 端服务并退出进程 */
    const val CODE_DESTROY = 999
}

/**
 * Su 直连模式下应用进程 <-> root（`su -c app_process`）进程的帧传输层。
 *
 * - 应用端 [connect]、root 端由 SuLauncher 用 LocalServerSocket.accept 得到
 *   LocalSocket 后经 [accepted] 包装，两端共用同一套读写实现。
 * - 双工单 socket：主线程/手势线程发送（写锁互斥），专用读线程接收。
 * - 媒体 fd：发送端在帧字节的一次 write 前调 setFileDescriptorsForSend
 *   挂上 fd（内核在 SCM_RIGHTS 传递时自动 dup，与 binder 语义一致，
 *   调用方继续拥有并关闭自己的副本）；接收端在逐段 read 后经
 *   getAncillaryFileDescriptors 收集，归入当前完成的帧。
 * - 任一方向 EOF / 协议错误：readFrame 返回 null，调用方据此判定对端死亡。
 *
 * 不模仿 binder Parcel 格式（接口令牌/对象编组）：参数以 DataOutputStream
 * 原语按方法签名逐个写入，两端手工对照，避免脆弱的 Parcel 布局依赖。
 */
internal class SuTransport private constructor(private val socket: LocalSocket) {

    class Frame(val code: Int, val payload: ByteArray, val fds: List<FileDescriptor>)

    private val writeLock = Any()

    fun sendFrame(
        code: Int,
        fds: Array<FileDescriptor>? = null,
        write: (DataOutputStream) -> Unit = {}
    ) {
        val payload = ByteArrayOutputStream().let { bos ->
            DataOutputStream(bos).use(write)
            bos.toByteArray()
        }
        val frame = ByteArrayOutputStream(8 + payload.size).let { bos ->
            val dos = DataOutputStream(bos)
            dos.writeInt(code)
            dos.writeInt(payload.size)
            dos.write(payload)
            bos.toByteArray()
        }
        synchronized(writeLock) {
            val hasFds = !fds.isNullOrEmpty()
            if (hasFds) {
                socket.setFileDescriptorsForSend(fds)
            }
            try {
                val out = socket.outputStream
                out.write(frame)
                out.flush()
            } finally {
                if (hasFds) {
                    // 清除发送队列，避免后续普通帧误带 ancillary
                    socket.setFileDescriptorsForSend(null)
                }
            }
        }
    }

    /**
     * 读一帧；对端关闭或协议异常（负长度/超大长度/参数截断）返回 null。
     * 无缓冲：逐段 read 保证 ancillary fd 与其所属帧的对应关系。
     */
    fun readFrame(): Frame? {
        var pendingFds: Array<FileDescriptor>? = null

        fun readN(n: Int): ByteArray? {
            if (n <= 0) return ByteArray(0)
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = socket.inputStream.read(buf, off, n - off)
                if (r < 0) return null
                off += r
                // fd 伴随包含其帧字节的某一次底层 read 到达；取最近一次结果
                socket.ancillaryFileDescriptors?.let { pendingFds = it }
            }
            return buf
        }

        val header = readN(8) ?: return null
        val dis = DataInputStream(ByteArrayInputStream(header))
        val code = dis.readInt()
        val len = dis.readInt()
        if (len < 0 || len > MAX_FRAME) return null
        val payload = readN(len) ?: return null
        return Frame(code, payload, pendingFds?.toList() ?: emptyList())
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val MAX_FRAME = 1 shl 20

        /** 应用端连接 root 端监听的抽象命名空间 socket；失败返回 null。 */
        fun connect(name: String, timeoutMs: Int): SuTransport? {
            return runCatching {
                val s = LocalSocket()
                s.connect(
                    LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT),
                    timeoutMs
                )
                SuTransport(s)
            }.getOrNull()
        }

        /** root 端包装 accept() 得到的已连接 socket。 */
        fun accepted(socket: LocalSocket): SuTransport = SuTransport(socket)
    }
}
