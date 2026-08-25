package fake.screenshot.repack

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 二进制 AXML（编译后的 Android 资源 XML，如 AndroidManifest.xml、自适应图标 XML）解析与重写器。
 *
 * GameGuardian "随机包名克隆"的核心：组件类名在编译期已被 aapt 展开为全限定名，
 * 与 manifest 的 package 属性无关，因此运行时只需要改写编译后 manifest 里的
 * package / label / description 等属性即可让同一份 DEX 以全新身份安装。
 *
 * 实现要点：
 * 1. 解析文件头部的字符串池（UTF-8 / UTF-16 两种编码）；
 * 2. 修改属性时把新字符串追加到字符串池尾部（既有索引不变，所有引用依然有效）；
 * 3. 用补丁的方式改写元素属性（rawValue / dataType / data）；
 * 4. 重新序列化字符串池（重算偏移），其余 chunk 原样保留。
 */
class AxmlEditor(private val data: ByteArray) {

    private companion object {
        const val RES_XML_TYPE = 0x0003
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val RES_XML_END_ELEMENT_TYPE = 0x0103
        const val UTF8_FLAG = 0x100
        const val SORTED_FLAG = 0x1
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val XML_HEADER_SIZE = 8
        const val POOL_HEADER_SIZE = 28
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    private class ElementInfo(
        val name: String,
        val chunkStart: Int,
        val attrBase: Int,
        val attrSize: Int,
        val attrCount: Int
    )

    private val strings: MutableList<String>
    private val utf8: Boolean
    private val styleCount: Int
    private val styleBytes: ByteArray
    private val originalFlags: Int

    /** 字符串池之后的所有 chunk 原始字节（resource map、命名空间、元素等） */
    private val tail: ByteArray

    /** 对 tail 的 u32 补丁：位置 -> 值 */
    private val intPatches = HashMap<Int, Int>()

    /** 对 tail 的 u8 补丁：位置 -> 值 */
    private val bytePatches = HashMap<Int, Int>()

    /** 要从 tail 中整体删除的元素区间（闭开区间，覆盖元素 START..END 的全部 chunk） */
    private val removedRanges = mutableListOf<IntRange>()

    private val elements = mutableListOf<ElementInfo>()

    /** 根元素名，例如 manifest / adaptive-icon / vector */
    val rootElementName: String

    init {
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(header.short.toInt() == RES_XML_TYPE) { "not a binary AXML file" }
        header.short // headerSize，恒为 8
        val fileSize = header.int

        // 字符串池 chunk 紧跟文件头
        val pool = ByteBuffer.wrap(data, XML_HEADER_SIZE, fileSize - XML_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        require(pool.short.toInt() == RES_STRING_POOL_TYPE) { "string pool not found" }
        pool.short // pool headerSize = 28
        val poolChunkSize = pool.int
        val stringCount = pool.int
        styleCount = pool.int
        originalFlags = pool.int
        utf8 = (originalFlags and UTF8_FLAG) != 0
        val stringsStart = pool.int
        val stylesStart = pool.int
        val offsets = IntArray(stringCount) { pool.int }

        val stringDataStart = XML_HEADER_SIZE + stringsStart
        strings = ArrayList(stringCount)
        for (offset in offsets) {
            val sb = ByteBuffer.wrap(data, stringDataStart + offset, data.size - stringDataStart - offset)
                .order(ByteOrder.LITTLE_ENDIAN)
            strings.add(if (utf8) decodeUtf8(sb) else decodeUtf16(sb))
        }

        val poolEnd = XML_HEADER_SIZE + poolChunkSize
        styleBytes = if (styleCount > 0 && stylesStart > 0) {
            data.copyOfRange(XML_HEADER_SIZE + stylesStart, poolEnd)
        } else {
            ByteArray(0)
        }
        tail = data.copyOfRange(poolEnd, data.size)

        // 遍历 tail 内所有 chunk，记录元素与属性位置
        var root = ""
        var pos = 0
        while (pos + 8 <= tail.size) {
            val type = tailU16(pos)
            val size = tailU32(pos + 4)
            if (size <= 0 || pos + size > tail.size) break
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val nameIdx = tailU32(pos + 20)
                val elementName = strings.getOrNull(nameIdx) ?: "?"
                if (root.isEmpty()) root = elementName
                // attributeStart 相对于 attrExt 结构（chunk + 16）
                val attrBase = pos + 16 + tailU16(pos + 24)
                val attrSize = tailU16(pos + 26).coerceAtLeast(20)
                val attrCount = tailU16(pos + 28)
                elements.add(ElementInfo(elementName, pos, attrBase, attrSize, attrCount))
            }
            pos += size
        }
        rootElementName = root
    }

    /**
     * 把 [element] 元素上名为 [attrName] 的属性设置为字符串值。
     * 属性会从资源引用（@string/xxx）变为内联字符串。
     */
    fun setAttributeValue(element: String, attrName: String, value: String, androidNs: Boolean = true): Boolean {
        val attrs = findAttributes(element, attrName, androidNs)
        if (attrs.isEmpty()) return false
        val index = intern(value)
        for (attrOffset in attrs) {
            intPatches[attrOffset + 8] = index      // rawValue
            bytePatches[attrOffset + 15] = TYPE_STRING // dataType
            intPatches[attrOffset + 16] = index     // data（字符串索引）
        }
        return true
    }

    /** 把 [element] 元素上名为 [attrName] 的属性设置为资源引用。 */
    fun setAttributeReference(element: String, attrName: String, resId: Int, androidNs: Boolean = true): Boolean {
        val attrs = findAttributes(element, attrName, androidNs)
        if (attrs.isEmpty()) return false
        for (attrOffset in attrs) {
            intPatches[attrOffset + 8] = -1             // rawValue = NO_INDEX
            bytePatches[attrOffset + 15] = TYPE_REFERENCE // dataType
            intPatches[attrOffset + 16] = resId         // data（资源 ID）
        }
        return true
    }

    /**
     * 从文档中整体移除名为 [elementName] 的元素（含其子元素与属性）。
     *
     * 典型用途：把自适应图标中的 <monochrome> 层删掉。monochrome 指向彩色位图时，
     * 开启"主题图标/单色图标"的启动器（Android 13+，HyperOS/ColorOS 等）会把整张
     * 不透明图片染成单一颜色，表现为纯色图标；移除该层后启动器回退为显示全彩图标。
     *
     * AXML 各 chunk 顺序排列且互相之间没有绝对偏移引用，删除整个元素 chunk 区间
     * 后其余 chunk 原样保留即可，字符串池中残留的元素名不会影响解析。
     *
     * @return 是否至少移除了一个元素
     */
    fun removeElement(elementName: String): Boolean {
        var removed = false
        var pos = 0
        while (pos + 8 <= tail.size) {
            val type = tailU16(pos)
            val size = tailU32(pos + 4)
            if (size <= 0 || pos + size > tail.size) break
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val nameIndex = tailU32(pos + 20)
                if (strings.getOrNull(nameIndex) == elementName) {
                    // 深度扫描定位与该 START 配对的 END_ELEMENT
                    var depth = 1
                    var end = pos + size
                    while (end + 8 <= tail.size && depth > 0) {
                        val subType = tailU16(end)
                        val subSize = tailU32(end + 4)
                        if (subSize <= 0 || end + subSize > tail.size) break
                        if (subType == RES_XML_START_ELEMENT_TYPE) depth++
                        if (subType == RES_XML_END_ELEMENT_TYPE) depth--
                        end += subSize
                    }
                    if (depth == 0) {
                        removedRanges.add(pos until end)
                        removed = true
                        pos = end
                        continue
                    }
                }
            }
            pos += size
        }
        return removed
    }

    /** 读取属性值（字符串或 "@0x..." 形式的资源引用），用于测试与调试。 */
    fun getAttributeValue(element: String, attrName: String, androidNs: Boolean = true): String? {
        val attrOffset = findAttributes(element, attrName, androidNs).firstOrNull() ?: return null
        val rawIndex = tailU32(attrOffset + 8)
        if (rawIndex != -1) return strings.getOrNull(rawIndex)
        val dataType = tail[attrOffset + 15].toInt() and 0xFF
        val value = tailU32(attrOffset + 16)
        return if (dataType == TYPE_STRING) strings.getOrNull(value) else "@0x${Integer.toHexString(value)}"
    }

    /** 应用所有修改并输出新的 AXML 字节。 */
    fun build(): ByteArray {
        val patchedTail = rebuildTail()
        val pool = encodeStringPool()
        val totalSize = XML_HEADER_SIZE + pool.size + patchedTail.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE.toShort())
        out.putShort(XML_HEADER_SIZE.toShort())
        out.putInt(totalSize)
        out.put(pool)
        out.put(patchedTail)
        return out.array()
    }

    /**
     * 按 chunk 重组 tail：被 [removeElement] 标记的区间整段跳过，
     * 其余 chunk 原样保留并应用属性补丁（补丁位置随删除前移）。
     */
    private fun rebuildTail(): ByteArray {
        if (removedRanges.isEmpty()) {
            val patched = tail.copyOf()
            intPatches.forEach { (position, value) -> writeU32(patched, position, value) }
            bytePatches.forEach { (position, value) -> patched[position] = value.toByte() }
            return patched
        }
        val out = ByteArrayOutputStream(tail.size)
        var pos = 0
        while (pos + 8 <= tail.size) {
            val size = tailU32(pos + 4)
            if (size <= 0 || pos + size > tail.size) break
            if (!isRemoved(pos, pos + size)) {
                val chunk = tail.copyOfRange(pos, pos + size)
                intPatches.forEach { (position, value) ->
                    if (position >= pos && position + 4 <= pos + size) {
                        writeU32(chunk, position - pos, value)
                    }
                }
                bytePatches.forEach { (position, value) ->
                    if (position >= pos && position < pos + size) {
                        chunk[position - pos] = value.toByte()
                    }
                }
                out.write(chunk)
            }
            pos += size
        }
        // 不构成完整 chunk 的尾部残余原样保留（正常文件不会出现）
        if (pos < tail.size) {
            val chunk = tail.copyOfRange(pos, tail.size)
            intPatches.forEach { (position, value) ->
                if (position >= pos) writeU32(chunk, position - pos, value)
            }
            bytePatches.forEach { (position, value) ->
                if (position >= pos) chunk[position - pos] = value.toByte()
            }
            out.write(chunk)
        }
        return out.toByteArray()
    }

    private fun isRemoved(start: Int, end: Int): Boolean =
        removedRanges.any { it.first <= start && end <= it.last + 1 }

    /**
     * 把 [element] 元素上名为 [attrName] 的属性中，以 [oldPrefix] 开头的字符串值
     * 替换为 [newPrefix] 前缀。
     *
     * 用于改包名时同步改写权限名 / provider authorities 等以旧包名为前缀的全局唯一标识，
     * 避免新应用与原应用共存时触发 INSTALL_FAILED_DUPLICATE_PERMISSION /
     * INSTALL_FAILED_CONFLICTING_PROVIDER。
     *
     * 注意只按元素+属性名精确匹配，不会碰组件类名（activity/service 等的 name
     * 指向 DEX 中真实存在的类，改了会 ClassNotFoundException）。
     *
     * @return 被替换的属性个数
     */
    fun replaceAttributeValuePrefix(
        element: String,
        attrName: String,
        oldPrefix: String,
        newPrefix: String,
        androidNs: Boolean = true
    ): Int {
        var replaced = 0
        for (attrOffset in findAttributes(element, attrName, androidNs)) {
            val valueIndex = tailU32(attrOffset + 16)
            val current = strings.getOrNull(valueIndex) ?: continue
            if (!current.startsWith(oldPrefix)) continue
            val newValue = newPrefix + current.removePrefix(oldPrefix)
            val index = intern(newValue)
            intPatches[attrOffset + 8] = index
            bytePatches[attrOffset + 15] = TYPE_STRING
            intPatches[attrOffset + 16] = index
            replaced++
        }
        return replaced
    }

    private fun findAttributes(element: String, attrName: String, androidNs: Boolean): List<Int> {
        val result = mutableListOf<Int>()
        for (e in elements) {
            if (e.name != element) continue
            for (i in 0 until e.attrCount) {
                val attrOffset = e.attrBase + i * e.attrSize
                val nsIndex = tailU32(attrOffset)
                val nameIndex = tailU32(attrOffset + 4)
                val name = strings.getOrNull(nameIndex) ?: continue
                if (name != attrName) continue
                val ns = if (nsIndex == -1) null else strings.getOrNull(nsIndex)
                if (androidNs) {
                    if (ns != ANDROID_NS) continue
                } else {
                    if (ns != null) continue
                }
                result.add(attrOffset)
            }
        }
        return result
    }

    private fun intern(value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        strings.add(value)
        return strings.size - 1
    }

    private fun encodeStringPool(): ByteArray {
        val count = strings.size
        val offsets = IntArray(count)
        val stringBytes = ByteArrayOutputStream()
        for (i in 0 until count) {
            offsets[i] = stringBytes.size()
            stringBytes.write(if (utf8) encodeUtf8(strings[i]) else encodeUtf16(strings[i]))
        }
        val stringData = stringBytes.toByteArray()

        val stringsStart = (POOL_HEADER_SIZE + 4 * count + 3) and 3.inv()
        val paddingAfterOffsets = stringsStart - (POOL_HEADER_SIZE + 4 * count)

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(POOL_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_STRING_POOL_TYPE.toShort())
        header.putShort(POOL_HEADER_SIZE.toShort())
        header.putInt(0) // chunkSize 占位，稍后回填
        header.putInt(count)
        header.putInt(styleCount)
        // 追加新字符串后不再有序，清除 SORTED 标志
        header.putInt(originalFlags and SORTED_FLAG.inv())
        header.putInt(stringsStart)
        header.putInt(0) // stylesStart 占位，稍后回填
        out.write(header.array())
        val offsetsBuffer = ByteBuffer.allocate(4 * count).order(ByteOrder.LITTLE_ENDIAN)
        offsets.forEach { offsetsBuffer.putInt(it) }
        out.write(offsetsBuffer.array())
        repeat(paddingAfterOffsets) { out.write(0) }
        out.write(stringData)

        // 字符串数据必须补齐到 4 字节边界：
        // AXML 所有 chunk 均要求 4 对齐，UTF-16 字符串变长后若不补齐，
        // 池 chunkSize 及整个文件都会错位，系统安装器直接报"安装包已损坏"
        while (out.size() % 4 != 0) out.write(0)

        if (styleCount > 0 && styleBytes.isNotEmpty()) {
            val stylesStartValue = out.size()
            out.write(styleBytes)
            while (out.size() % 4 != 0) out.write(0)
            val chunk = out.toByteArray()
            // chunkSize 位于池 chunk 头部偏移 4（8 是 stringCount，别写错位置）
            writeU32(chunk, 4, chunk.size)
            writeU32(chunk, 24, stylesStartValue)
            return chunk
        }

        val chunk = out.toByteArray()
        writeU32(chunk, 4, chunk.size)
        return chunk
    }


    // ---------- 字符串编解码 ----------

    private fun decodeUtf8(buffer: ByteBuffer): String {
        readLen8(buffer) // UTF-16 单元数，跳过
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

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size + 5)
        writeLen8(out, value.length)
        writeLen8(out, bytes.size)
        out.write(bytes)
        out.write(0)
        return out.toByteArray()
    }

    private fun writeLen8(out: ByteArrayOutputStream, value: Int) {
        if (value > 0x7F) {
            out.write((value and 0x7F) or 0x80)
            out.write(value ushr 7)
        } else {
            out.write(value)
        }
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

    private fun encodeUtf16(value: String): ByteArray {
        val out = ByteArrayOutputStream((value.length + 2) * 2)
        val length = value.length
        if (length > 0x7FFF) {
            out.write16((length and 0x7FFF) or 0x8000)
            out.write16(length ushr 15)
        } else {
            out.write16(length)
        }
        for (ch in value) out.write16(ch.code)
        out.write16(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.write16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    // ---------- 字节读取工具 ----------

    private fun tailU16(position: Int): Int =
        (tail[position].toInt() and 0xFF) or ((tail[position + 1].toInt() and 0xFF) shl 8)

    private fun tailU32(position: Int): Int =
        (tail[position].toInt() and 0xFF) or
                ((tail[position + 1].toInt() and 0xFF) shl 8) or
                ((tail[position + 2].toInt() and 0xFF) shl 16) or
                ((tail[position + 3].toInt() and 0xFF) shl 24)

    private fun writeU32(target: ByteArray, position: Int, value: Int) {
        target[position] = (value and 0xFF).toByte()
        target[position + 1] = ((value ushr 8) and 0xFF).toByte()
        target[position + 2] = ((value ushr 16) and 0xFF).toByte()
        target[position + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
