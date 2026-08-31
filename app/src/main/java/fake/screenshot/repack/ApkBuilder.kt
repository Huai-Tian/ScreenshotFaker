package fake.screenshot.repack

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * APK（ZIP）重写器。
 *
 * - 逐条复制源 APK 条目并保留各自的压缩方式（resources.arsc 在 targetSdk 30+
 *   必须保持未压缩，因此不能简单地全部重新 DEFLATE）；
 * - 应用传入的条目替换（改写后的 AndroidManifest.xml、图标等）；
 * - 剔除 META-INF/ 下的旧签名；
 * - STORED 条目写入时通过 local header 的 extra 字段填充，保证数据 4096 对齐。
 */
object ApkBuilder {

    private const val LOCAL_FILE_HEADER_SIG = 0x04034b50
    private const val CENTRAL_DIR_ENTRY_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50
    private const val ALIGNMENT = 4096
    private const val EXTRA_ID_PADDING = 0x1986

    fun build(sourceApk: File, outputApk: File, replacements: Map<String, ByteArray>) {
        ZipFile(sourceApk).use { zip ->
            val metas = mutableListOf<EntryMeta>()
            FileOutputStream(outputApk).use { fileStream ->
                val out = LittleEndianOutputStream(BufferedOutputStream(fileStream, 1 shl 16))
                var offset = 0
                val written = HashSet<String>()

                fun writeEntry(name: String, data: ByteArray, method: Int) {
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    val crc = CRC32().apply { update(data) }.value
                    val payload = if (method == ZipEntry.DEFLATED) deflate(data) else data

                    // STORED 条目：用 extra 字段填充，让数据起始位置按 ALIGNMENT 对齐
                    val extra: ByteArray = if (method == ZipEntry.STORED) {
                        var padding = (ALIGNMENT - (offset + 30 + nameBytes.size) % ALIGNMENT) % ALIGNMENT
                        if (padding < 4) padding += ALIGNMENT
                        ByteArray(padding).also {
                            it[0] = (EXTRA_ID_PADDING and 0xFF).toByte()
                            it[1] = ((EXTRA_ID_PADDING shr 8) and 0xFF).toByte()
                            val size = padding - 4
                            it[2] = (size and 0xFF).toByte()
                            it[3] = ((size shr 8) and 0xFF).toByte()
                        }
                    } else {
                        ByteArray(0)
                    }

                    out.u32(LOCAL_FILE_HEADER_SIG)
                    out.u16(20)                          // version needed
                    out.u16(0x0800)                      // UTF-8 文件名标志
                    out.u16(method)
                    out.u16(0)                           // mod time
                    out.u16(0x21)                        // mod date (1980-01-01)
                    out.u32(crc.toInt())
                    out.u32(payload.size)
                    out.u32(data.size)
                    out.u16(nameBytes.size)
                    out.u16(extra.size)
                    out.write(nameBytes)
                    out.write(extra)
                    out.write(payload)

                    metas.add(
                        EntryMeta(
                            name = name,
                            method = method,
                            crc = crc,
                            compressedSize = payload.size,
                            uncompressedSize = data.size,
                            localHeaderOffset = offset
                        )
                    )
                    offset += 30 + nameBytes.size + extra.size + payload.size
                }

                for (entry in zip.entries().asSequence()) {
                    val name = entry.name
                    // 只剔除旧签名文件：整目录丢弃会连带删掉 META-INF/services/**
                    //（ServiceLoader 注册，如 kotlinx.coroutines 主调度器工厂），
                    // 混淆不充分的克隆会因缺少 Main dispatcher 启动即崩溃
                    if (isSignatureEntry(name)) continue

                    written.add(name)
                    val replacement = replacements[name]
                    if (replacement != null) {
                        // 替换条目继承源条目的压缩方式：
                        // resources.arsc 等 STORED 条目在 targetSdk 30+ 必须保持未压缩
                        writeEntry(name, replacement, entry.method)
                    } else {
                        writeEntry(name, zip.getInputStream(entry).readBytes(), entry.method)
                    }
                }

                // 源 APK 中不存在的新条目（如重打包迁移信息）
                for ((name, data) in replacements) {
                    if (name !in written) writeEntry(name, data, ZipEntry.DEFLATED)
                }

                // 中央目录
                val centralDirOffset = offset
                var centralDirSize = 0
                for (meta in metas) {
                    val nameBytes = meta.name.toByteArray(Charsets.UTF_8)
                    out.u32(CENTRAL_DIR_ENTRY_SIG)
                    out.u16(20)                          // version made by
                    out.u16(20)                          // version needed
                    out.u16(0x0800)
                    out.u16(meta.method)
                    out.u16(0)
                    out.u16(0x21)
                    out.u32(meta.crc.toInt())
                    out.u32(meta.compressedSize)
                    out.u32(meta.uncompressedSize)
                    out.u16(nameBytes.size)
                    out.u16(0)                           // extra len
                    out.u16(0)                           // comment len
                    out.u16(0)                           // disk start
                    out.u16(0)                           // internal attrs
                    out.u32(0)                           // external attrs
                    out.u32(meta.localHeaderOffset)
                    out.write(nameBytes)
                    centralDirSize += 46 + nameBytes.size
                }

                // EOCD
                out.u32(EOCD_SIG)
                out.u16(0)
                out.u16(0)
                out.u16(metas.size)
                out.u16(metas.size)
                out.u32(centralDirSize)
                out.u32(centralDirOffset)
                out.u16(0)
                out.flush()
            }
        }
    }

    /** v1 签名体系文件（重新用 v2/v3 签名，v1 清单与块必须剔除） */
    private fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        if (name == "META-INF/MANIFEST.MF") return true
        val lower = name.lowercase()
        return lower.endsWith(".sf") || lower.endsWith(".rsa") ||
                lower.endsWith(".dsa") || lower.endsWith(".ec")
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2)
        val buffer = ByteArray(1 shl 16)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count > 0) out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    private class EntryMeta(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int
    )

    private class LittleEndianOutputStream(private val sink: OutputStream) {
        private val scratch = ByteArray(4)

        fun u16(value: Int) {
            scratch[0] = (value and 0xFF).toByte()
            scratch[1] = ((value ushr 8) and 0xFF).toByte()
            sink.write(scratch, 0, 2)
        }

        fun u32(value: Int) {
            scratch[0] = (value and 0xFF).toByte()
            scratch[1] = ((value ushr 8) and 0xFF).toByte()
            scratch[2] = ((value ushr 16) and 0xFF).toByte()
            scratch[3] = ((value ushr 24) and 0xFF).toByte()
            sink.write(scratch, 0, 4)
        }

        fun write(bytes: ByteArray) = sink.write(bytes)

        fun flush() = sink.flush()
    }
}
