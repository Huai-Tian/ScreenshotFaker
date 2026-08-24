package fake.screenshot.repack

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 最小化的 resources.arsc 解析器：把资源 ID 解析为 APK 内的文件路径（含所有密度/配置变体）。
 *
 * 重打包替换启动图标时不能假设资源文件路径固定（release 构建可能开启资源路径缩短/混淆），
 * 通过 arsc 拿到 [fake.screenshot.R.mipmap.ic_launcher] 等 ID 的真实路径后按路径替换才可靠。
 *
 * 除解析外还支持"条目改指向"（[repointPath]）：把某个资源配置项的值（全局字符串池中的
 * 文件路径索引）替换为池中已有的另一条路径。因为只改写 4 字节的索引值、不增删字符串，
 * 产物与原文件尺寸完全一致，不破坏任何偏移与对齐——这与 [AxmlEditor] 的补丁思路相同。
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

    private class EntryInfo(val path: String, val density: Int, val valueOffset: Int)

    /** 资源 ID -> 条目信息（路径 / 密度 / 值字段在文件中的绝对偏移） */
    private val entriesById = HashMap<Int, MutableList<EntryInfo>>()

    /** 全局字符串池内容（按下标访问） */
    private val globalStrings = mutableListOf<String>()

    /** 全局字符串池：字符串 -> 首次出现的索引 */
    private val stringIndex = HashMap<String, Int>()

    /** 对原始字节的 u32 补丁：偏移 -> 新字符串索引 */
    private val patches = HashMap<Int, Int>()

    init {
        require(u16(0) == RES_TABLE_TYPE) { "not a resources.arsc" }
        val headerSize = u16(2)
        var pos = headerSize

        // 全局字符串池（路径等字符串都在这里）
        val globalPool = parsePool(pos)
        globalPool.strings.forEachIndexed { index, s ->
            globalStrings.add(s)
            stringIndex.putIfAbsent(s, index)
        }
        pos += globalPool.chunkSize

        while (pos + 8 <= d.size) {
            val chunkType = u16(pos)
            val chunkHeaderSize = u16(pos + 2)
            val chunkSize = u32(pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > d.size) break
            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                parsePackage(pos, chunkHeaderSize)
            }
            pos += chunkSize
        }
    }

    /** 返回该资源 ID 在 APK 内的全部文件路径（可能为空）。 */
    fun pathsForResource(resourceId: Int): List<String> =
        entriesById[resourceId]?.map { it.path }?.distinct() ?: emptyList()

    /**
     * 该资源 ID 最高密度的位图文件路径（webp/png）。
     * 用作图标位图的"最佳变体"；密度为 0（无密度限定）按 mdpi 160 估算。
     */
    fun bestBitmapPath(resourceId: Int): String? =
        entriesById[resourceId]
            ?.filter { it.path.isBitmapFile() }
            ?.maxByOrNull { if (it.density == 0 || it.density == DENSITY_ANY) 160 else it.density }
            ?.path

    /**
     * 把 [resourceId] 当前值为 [fromPath] 的配置条目改指向 [toPath]。
     * [toPath] 必须已存在于全局字符串池（通常是同一资源的某个位图变体路径）。
     *
     * @return 是否至少改写了一个条目
     */
    fun repointPath(resourceId: Int, fromPath: String, toPath: String): Boolean {
        val target = stringIndex[toPath] ?: return false
        var repointed = false
        for (entry in entriesById[resourceId] ?: emptyList()) {
            if (entry.path != fromPath) continue
            patches[entry.valueOffset] = target
            repointed = true
        }
        return repointed
    }

    /** 应用补丁并输出新的 arsc 字节；无补丁时返回 null（调用方无需替换该条目）。 */
    fun buildIfPatched(): ByteArray? {
        if (patches.isEmpty()) return null
        val out = d.copyOf()
        for ((offset, value) in patches) writeU32(out, offset, value)
        return out
    }

    private fun String.isBitmapFile(): Boolean =
        endsWith(".webp") || endsWith(".png") || endsWith(".jpg") || endsWith(".jpeg")

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
        val typeId = d[chunkStart + 8].toInt() and 0xFF
        val flags = d[chunkStart + 10].toInt() and 0xFF
        val entryCount = u32(chunkStart + 12)
        val entriesStart = u32(chunkStart + 16)
        val indicesStart = chunkStart + headerSize
        val entriesAbs = chunkStart + entriesStart

        // ResTable_config 紧跟 type chunk 头，density 字段用于挑选最佳位图变体
        val configStart = chunkStart + headerSize
        val density = if (configStart + CONFIG_DENSITY_OFFSET + 2 <= chunkStart + u32(chunkStart + 4)) {
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
                    record(fullIdBase or entryId, entriesAbs + offset, density)
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
                    record(fullIdBase or entryId, entriesAbs + offset, density)
                }
            }
        }
    }

    private fun record(resourceId: Int, entry: Int, density: Int) {
        // ResTable_entry: u16 size, u16 flags, u32 key；其后紧跟 Res_value
        val entryFlags = u16(entry + 2)
        if (entryFlags and ENTRY_FLAG_COMPLEX != 0) return // 数组等复杂条目没有文件路径
        val dataType = d[entry + 11].toInt() and 0xFF
        if (dataType != TYPE_STRING) return
        val stringIndex = u32(entry + 12)
        val path = globalString(stringIndex) ?: return
        entriesById.getOrPut(resourceId) { mutableListOf() }
            .add(EntryInfo(path, density, entry + 12))
    }

    private fun globalString(index: Int): String? = globalStrings.getOrNull(index)

    private class Pool(val strings: List<String>, val chunkSize: Int)

    private fun parsePool(start: Int): Pool {
        val chunkSize = u32(start + 4)
        val stringCount = u32(start + 8)
        val styleCount = u32(start + 12)
        val flags = u32(start + 16)
        val stringsStart = u32(start + 20)
        val utf8 = (flags and UTF8_FLAG) != 0
        val offsets = IntArray(stringCount) { u32(start + 28 + it * 4) }
        val dataStart = start + stringsStart
        val strings = ArrayList<String>(stringCount)
        for (offset in offsets) {
            val sb = ByteBuffer.wrap(d, dataStart + offset, d.size - dataStart - offset)
                .order(ByteOrder.LITTLE_ENDIAN)
            strings.add(if (utf8) decodeUtf8(sb) else decodeUtf16(sb))
        }
        return Pool(strings, chunkSize)
    }

    private fun decodeUtf8(buffer: ByteBuffer): String {
        readLen8(buffer)
        val byteLength = readLen8(buffer)
        val bytes = ByteArray(byteLength)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLen8(buffer: ByteBuffer): Int {
        var length = buffer.get().toInt() and 0xFF
        if (length and 0x80 != 0) {
            length = (length and 0x7F) or ((buffer.get().toInt() and 0xFF) shl 7)
        }
        return length
    }

    private fun decodeUtf16(buffer: ByteBuffer): String {
        var length = buffer.short.toInt() and 0xFFFF
        if (length and 0x8000 != 0) {
            length = (length and 0x7FFF) or ((buffer.short.toInt() and 0xFFFF) shl 15)
        }
        val builder = StringBuilder(length)
        repeat(length) { builder.append(buffer.char) }
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
}