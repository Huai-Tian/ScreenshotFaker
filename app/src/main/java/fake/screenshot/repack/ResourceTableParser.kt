package fake.screenshot.repack

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 最小化的 resources.arsc 解析器：把资源 ID 解析为 APK 内的文件路径（含所有密度/配置变体）。
 *
 * 重打包替换启动图标时不能假设资源文件路径固定（release 构建可能开启资源路径缩短/混淆），
 * 通过 arsc 拿到 [fake.screenshot.R.mipmap.ic_launcher] 等 ID 的真实路径后按路径替换才可靠。
 *
 * 除解析外还支持"条目删除"（[removeAdaptiveIconEntries]）：在 TYPE chunk 的条目
 * 偏移表中写入 NO_ENTRY，使资源配置项不再参与解析。因为只改写 2/4 字节的偏移值、
 * 不增删任何 chunk，产物与原文件尺寸完全一致，不破坏偏移与对齐——这与
 * [AxmlEditor] 的补丁思路相同。
 */
class ResourceTableParser(data: ByteArray) {

    private companion object {
        const val RES_TABLE_TYPE = 0x0002
        const val RES_TABLE_PACKAGE_TYPE = 0x0200
        const val RES_TABLE_TYPE_TYPE = 0x0201
        const val UTF8_FLAG = 0x100
        const val TYPE_STRING = 0x03
        const val ENTRY_FLAG_COMPLEX = 0x0001
        const val TYPE_FLAG_SPARSE = 0x01
        const val TYPE_FLAG_OFFSET16 = 0x02

        /** ResTable_config 中 density 字段相对 config 起始的偏移 */
        const val CONFIG_DENSITY_OFFSET = 14

        /** DENSITY_ANY：anydpi 配置（自适应图标 XML 等） */
        const val DENSITY_ANY = 0xFFFE
    }

    private val d = data

    private class EntryInfo(
        val path: String,
        val density: Int,
        /** TYPE chunk 偏移表中本条目偏移字段的绝对位置（删除条目时写 NO_ENTRY） */
        val offsetSlot: Int,
        /** 偏移字段是否为 u32（dense 表）；false 表示 u16（sparse / offset16 表） */
        val offsetSlotU32: Boolean,
    )

    /** 资源 ID -> 条目信息（路径 / 密度 / 偏移表槽位） */
    private val entriesById = HashMap<Int, MutableList<EntryInfo>>()

    /**
     * ResTable_package 头中的包名（构建时的 applicationId）。
     *
     * 重打包只改 manifest 的 package 属性，不改这里的包名（按 ID 的运行时资源解析
     * 不查包名，改它无收益反而有风险）。因此克隆进程里 context.packageName 与本值
     * 不同：按名称查资源（Resources.getIdentifier）必须用本值作为 defPackage，
     * 用 context.packageName 会查不到（返回 0），导致"克隆的克隆"无法替换图标。
     */
    val packageName: String

    /** 全局字符串池内容（按下标访问） */
    private val globalStrings = mutableListOf<String>()

    /** 对原始字节的补丁：偏移 -> (新值, 是否 u32 宽度) */
    private val patches = HashMap<Int, Pair<Int, Boolean>>()

    init {
        require(u16(0) == RES_TABLE_TYPE) { "not a resources.arsc" }
        val headerSize = u16(2)
        // 表头至少 12 字节（type/headerSize/size/packageCount）才有后续解析意义
        require(headerSize in 8..d.size) { "arsc header size out of range: $headerSize" }
        var pos = headerSize
        var pkgName = ""

        // 全局字符串池（路径等字符串都在这里）
        val globalPool = parsePool(pos)
        globalPool.strings.forEachIndexed { _, s -> globalStrings.add(s) }
        pos += globalPool.chunkSize

        while (pos + 8 <= d.size) {
            val chunkType = u16(pos)
            val chunkHeaderSize = u16(pos + 2)
            val chunkSize = u32(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > d.size) break
            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                if (pkgName.isEmpty()) pkgName = parsePackageName(pos)
                parsePackage(pos, chunkHeaderSize)
            }
            pos += chunkSize
        }
        packageName = pkgName
    }

    /** ResTable_package 头 offset 12 处的 256 字节 UTF-16LE 包名（\0 截断）。 */
    private fun parsePackageName(pkgStart: Int): String {
        val builder = StringBuilder()
        // 读取范围 clamp 到文件末尾：外层只校验 chunk 边界，包名固定字段
        // （头 12 + 名 256）可能超出小 chunk 声明
        var i = pkgStart + 12
        val end = minOf(i + 256, d.size - 1)
        while (i + 1 < end) {
            val c = ((d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8))
            if (c == 0) break
            builder.append(c.toChar())
            i += 2
        }
        return builder.toString()
    }

    /** 返回该资源 ID 在 APK 内的全部文件路径（可能为空）。 */
    fun pathsForResource(resourceId: Int): List<String> =
        entriesById[resourceId]?.map { it.path }?.distinct() ?: emptyList()

    /**
     * 删除 [resourceId] 的自适应图标相关配置条目：
     * - anydpi 配置（density == DENSITY_ANY）的全部条目，无论指向 XML 还是位图；
     * - 任意密度下指向 .xml drawable 的条目。
     *
     * 背景：绝不能让 anydpi 配置项解析到位图文件。anydpi 的密度是
     * DENSITY_ANY(0xFFFE)，BitmapDrawable 绘制时按 targetDensity/65534 的比例
     * （480dpi 设备约 1/136）缩放位图，192px 的图会被画成约 1.4 像素；启动器把
     * 这个点放大铺满图标蒙版后，整个图标显示为位图中心像素的纯色。删除条目让
     * 资源解析回退到 mdpi~xxxhdpi 的正常密度条目即可正确缩放。位图变体的文件
     * 字节由调用方另行替换，互不影响。
     *
     * 同时兼容已被旧版本补丁处理过的基包（anydpi 已被改指向位图）：一并删除。
     *
     * @return 是否至少删除了一个条目
     */
    fun removeAdaptiveIconEntries(resourceId: Int): Boolean {
        var removed = false
        for (entry in entriesById[resourceId] ?: emptyList()) {
            if (entry.density != DENSITY_ANY && !entry.path.endsWith(".xml")) continue
            val noEntry = if (entry.offsetSlotU32) -1 else 0xFFFF
            patches[entry.offsetSlot] = noEntry to entry.offsetSlotU32
            removed = true
        }
        return removed
    }

    /** 应用补丁并输出新的 arsc 字节；无补丁时返回 null（调用方无需替换该条目）。 */
    fun buildIfPatched(): ByteArray? {
        if (patches.isEmpty()) return null
        val out = d.copyOf()
        for ((offset, patch) in patches) {
            if (patch.second) writeU32(out, offset, patch.first)
            else writeU16(out, offset, patch.first)
        }
        return out
    }

    private fun parsePackage(pkgStart: Int, pkgHeaderSize: Int) {
        val pkgId = u32(pkgStart + 8)
        // ResTable_package: header(8) + id(4) + name(256) + typeStrings + lastPublicType + keyStrings + lastPublicKey
        var sub = pkgStart + pkgHeaderSize
        val pkgEnd = pkgStart + u32(pkgStart + 4)

        while (sub + 8 <= pkgEnd) {
            val subType = u16(sub)
            val subHeaderSize = u16(sub + 2)
            val subChunkSize = u32(sub + 4)
            if (subChunkSize <= 0 || sub + subChunkSize > pkgEnd) break

            if (subType == RES_TABLE_TYPE_TYPE) {
                parseTypeChunk(sub, subHeaderSize, pkgId)
            }
            sub += subChunkSize
        }
    }

    private fun parseTypeChunk(chunkStart: Int, headerSize: Int, pkgId: Int) {
        // chunk 固定头 20 字节（id/flags/reserved + entryCount + entriesStart）
        // 是本函数全部固定偏移读取的前提；headerSize 不足的损坏 chunk 跳过
        if (headerSize < 20) return
        val chunkEnd = chunkStart + u32(chunkStart + 4)
        val typeId = d[chunkStart + 8].toInt() and 0xFF
        val flags = d[chunkStart + 9].toInt() and 0xFF
        val entryCount = u32(chunkStart + 12)
        val entriesStart = u32(chunkStart + 16)
        val indicesStart = chunkStart + headerSize
        val entriesAbs = chunkStart + entriesStart

        // 偏移表与条目区均在 chunk 范围内是 record() 全部读取的前提：
        // entryCount/entriesStart/offset 来自文件，越界值静默采用会把
        // 无关字节解析成 ResTable_entry（错误补丁槽位 → 写坏其他字段）。
        // 偏移表规模用 Long 运算：entryCount 取大值（如 0x40000000）时
        // entryCount*4 整型溢出为 0/负值 → 校验形同虚设 → 循环以越界
        // 下标调 u16/u32 抛 AIOOBE；entriesAbs 同理用 Long 比较
        val indicesBytesLong = if (flags and TYPE_FLAG_SPARSE != 0) entryCount * 4L
        else entryCount * (if (flags and TYPE_FLAG_OFFSET16 != 0) 2L else 4L)
        if (entryCount < 0 || entriesStart < 0 ||
            indicesStart.toLong() + indicesBytesLong > chunkEnd ||
            entriesAbs.toLong() > chunkEnd
        ) return

        // ResTable_type 固定字段（header(8)+id/flags/reserved(4)+entryCount(4)+entriesStart(4)）
        // 之后紧跟 ResTable_config；注意 headerSize 已把 config 计入（= 20 + config.size），
        // 因此 config 起始是固定偏移 20，不能用 headerSize，否则会读到条目偏移表。
        val configStart = chunkStart + 20
        val density = if (configStart + CONFIG_DENSITY_OFFSET + 2 <= chunkEnd) {
            u16(configStart + CONFIG_DENSITY_OFFSET)
        } else {
            0
        }

        val fullIdBase = (pkgId shl 24) or (typeId shl 16)

        if (flags and TYPE_FLAG_SPARSE != 0) {
            // 稀疏表：(entryId u16, offset u16) 对
            for (i in 0 until entryCount) {
                val entryId = u16(indicesStart + i * 4)
                val offset = u16(indicesStart + i * 4 + 2)
                if (offset != 0xFFFF) {
                    record(
                        fullIdBase or entryId, entriesAbs + offset, density,
                        offsetSlot = indicesStart + i * 4 + 2, offsetSlotU32 = false
                    )
                }
            }
        } else {
            val offset16 = flags and TYPE_FLAG_OFFSET16 != 0
            for (entryId in 0 until entryCount) {
                val offset = if (offset16) {
                    val v = u16(indicesStart + entryId * 2)
                    if (v == 0xFFFF) -1 else v
                } else {
                    u32(indicesStart + entryId * 4)
                }
                if (offset >= 0) {
                    record(
                        fullIdBase or entryId, entriesAbs + offset, density,
                        offsetSlot = if (offset16) indicesStart + entryId * 2
                        else indicesStart + entryId * 4,
                        offsetSlotU32 = !offset16
                    )
                }
            }
        }
    }

    private fun record(
        resourceId: Int,
        entry: Int,
        density: Int,
        offsetSlot: Int,
        offsetSlotU32: Boolean,
    ) {
        // ResTable_entry: u16 size, u16 flags, u32 key；其后紧跟 Res_value
        // 固定 16 字节读取范围校验（复杂条目 map 首部更长，16 覆盖共同前缀）
        if (entry < 0 || entry + 16 > d.size) return
        val entryFlags = u16(entry + 2)
        if (entryFlags and ENTRY_FLAG_COMPLEX != 0) return // 数组等复杂条目没有文件路径
        val dataType = d[entry + 11].toInt() and 0xFF
        if (dataType != TYPE_STRING) return
        val stringIndex = u32(entry + 12)
        val path = globalString(stringIndex) ?: return
        entriesById.getOrPut(resourceId) { mutableListOf() }
            .add(EntryInfo(path, density, offsetSlot, offsetSlotU32))
    }

    private fun globalString(index: Int): String? = globalStrings.getOrNull(index)

    private class Pool(val strings: List<String>, val chunkSize: Int)

    private fun parsePool(start: Int): Pool {
        // 边界校验前置（先于任何字段读取/分配）：池头固定 28 字节
        //（chunk 头 8 + stringCount/styleCount/flags/stringsStart/stylesStart
        // 各 4）不足即空池。校验放在读取之后是失效的——IntArray(stringCount)
        // 的无界分配（OOM）与 u32 越界读（AIOOBE）会先于校验发生；
        // 偏移表规模用 Long 运算，防 stringCount*4 整型溢出绕过
        if (start < 0 || start + 28 > d.size) return Pool(emptyList(), 0)
        val chunkSize = u32(start + 4)
        val stringCount = u32(start + 8)
        val flags = u32(start + 16)
        val stringsStart = u32(start + 20)
        val utf8 = (flags and UTF8_FLAG) != 0
        // chunk 越界 / stringCount 非法 / 偏移表越界 / 字符串数据起点越界
        //（stringsStart 高位置位时 u32 读成负 Int，start+stringsStart 溢出为
        // 负值可绕过 > d.size 比较——负值在此一并拦截）→ 按空池处理
        //（损坏输入的受控降级：调用方按"资源无路径"处理）
        if (chunkSize <= 0 || start + chunkSize > d.size ||
            stringCount < 0 || start + 28L + stringCount * 4L > d.size ||
            stringsStart < 0 || start.toLong() + stringsStart > d.size
        ) {
            return Pool(emptyList(), if (chunkSize > 0) chunkSize else 0)
        }
        val offsets = IntArray(stringCount) { u32(start + 28 + it * 4) }
        val dataStart = start + stringsStart
        val strings = ArrayList<String>(stringCount)
        for (offset in offsets) {
            // 偏移用 Long 比较：dataStart+offset 接近 Int 上限时溢出为负
            // 可绕过 > d.size 检查，ByteBuffer.wrap 随即抛越界
            if (offset < 0 || dataStart.toLong() + offset > d.size) continue
            val sb = ByteBuffer.wrap(d, dataStart + offset, d.size - dataStart - offset)
                .order(ByteOrder.LITTLE_ENDIAN)
            strings.add(if (utf8) decodeUtf8(sb) else decodeUtf16(sb))
        }
        return Pool(strings, chunkSize)
    }

    private fun decodeUtf8(buffer: ByteBuffer): String {
        readLen8(buffer)
        val byteLength = readLen8(buffer)
        // 声明长度对剩余字节核对：损坏长度字段（最大 0x7FFF）超出缓冲时
        // buffer.get 抛 BufferUnderflowException——截断到可读范围（受控降级）
        val readable = minOf(byteLength, buffer.remaining())
        if (readable <= 0) return ""
        val bytes = ByteArray(readable)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLen8(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 1) return 0
        var length = buffer.get().toInt() and 0xFF
        if (length and 0x80 != 0) {
            // AOSP ResourceTypes.decodeLength：((b1 & 0x7F) << 8) | b2
            if (buffer.remaining() < 1) return 0
            length = ((length and 0x7F) shl 8) or (buffer.get().toInt() and 0xFF)
        }
        return length
    }

    private fun decodeUtf16(buffer: ByteBuffer): String {
        if (buffer.remaining() < 2) return ""
        var length = buffer.short.toInt() and 0xFFFF
        if (length and 0x8000 != 0) {
            // AOSP：((w1 & 0x7FFF) << 16) | w2
            if (buffer.remaining() < 2) return ""
            length = ((length and 0x7FFF) shl 16) or (buffer.short.toInt() and 0xFFFF)
        }
        // 声明长度（双字扩展后可达 0x7FFFFFFF）对剩余字节核对：超限时
        // StringBuilder(length) 直接 OOM、逐字读取抛下溢——截断到可读范围
        val readable = minOf(length, buffer.remaining() / 2)
        if (readable <= 0) return ""
        val builder = StringBuilder(readable)
        repeat(readable) { builder.append(buffer.char) }
        return builder.toString()
    }

    private fun u16(position: Int): Int =
        (d[position].toInt() and 0xFF) or ((d[position + 1].toInt() and 0xFF) shl 8)

    private fun u32(position: Int): Int =
        (d[position].toInt() and 0xFF) or
                ((d[position + 1].toInt() and 0xFF) shl 8) or
                ((d[position + 2].toInt() and 0xFF) shl 16) or
                ((d[position + 3].toInt() and 0xFF) shl 24)

    private fun writeU32(target: ByteArray, position: Int, value: Int) {
        target[position] = (value and 0xFF).toByte()
        target[position + 1] = ((value ushr 8) and 0xFF).toByte()
        target[position + 2] = ((value ushr 16) and 0xFF).toByte()
        target[position + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeU16(target: ByteArray, position: Int, value: Int) {
        target[position] = (value and 0xFF).toByte()
        target[position + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}