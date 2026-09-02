package com.gulshan.pocketprint.ipp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * Builds one attribute group. IPP is order-sensitive: within the operation
 * group, attributes-charset and attributes-natural-language must come first,
 * followed by the target printer-uri. The builder does not reorder for you.
 */
class IppGroupBuilder internal constructor(private val out: DataOutputStream) {

    private fun writeHeader(tag: Int, name: String) {
        out.writeByte(tag)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
    }

    private fun writeString(tag: Int, name: String, value: String) {
        writeHeader(tag, name)
        val v = value.toByteArray(Charsets.UTF_8)
        out.writeShort(v.size)
        out.write(v)
    }

    /** Extra members of a 1setOf carry an empty name. */
    private fun writeAdditional(tag: Int, value: String) = writeString(tag, "", value)

    fun charset(name: String, value: String = "utf-8") = writeString(IppTag.CHARSET, name, value)
    fun naturalLanguage(name: String, value: String = "en-us") =
        writeString(IppTag.NATURAL_LANGUAGE, name, value)

    fun uri(name: String, value: String) = writeString(IppTag.URI, name, value)
    fun keyword(name: String, value: String) = writeString(IppTag.KEYWORD, name, value)
    fun nameValue(name: String, value: String) =
        writeString(IppTag.NAME_WITHOUT_LANGUAGE, name, value)
    fun text(name: String, value: String) =
        writeString(IppTag.TEXT_WITHOUT_LANGUAGE, name, value)
    fun mimeType(name: String, value: String) = writeString(IppTag.MIME_MEDIA_TYPE, name, value)

    fun keywords(name: String, values: List<String>) {
        if (values.isEmpty()) return
        keyword(name, values.first())
        values.drop(1).forEach { writeAdditional(IppTag.KEYWORD, it) }
    }

    fun integer(name: String, value: Int) {
        writeHeader(IppTag.INTEGER, name)
        out.writeShort(4)
        out.writeInt(value)
    }

    fun enumValue(name: String, value: Int) {
        writeHeader(IppTag.ENUM, name)
        out.writeShort(4)
        out.writeInt(value)
    }

    fun boolean(name: String, value: Boolean) {
        writeHeader(IppTag.BOOLEAN, name)
        out.writeShort(1)
        out.writeByte(if (value) 1 else 0)
    }

    fun resolution(name: String, x: Int, y: Int, units: Int = 3) {
        writeHeader(IppTag.RESOLUTION, name)
        out.writeShort(9)
        out.writeInt(x)
        out.writeInt(y)
        out.writeByte(units)
    }

    fun rangeOfInteger(name: String, lower: Int, upper: Int) {
        writeHeader(IppTag.RANGE_OF_INTEGER, name)
        out.writeShort(8)
        out.writeInt(lower)
        out.writeInt(upper)
    }

    fun ranges(name: String, ranges: List<IntRange>) {
        if (ranges.isEmpty()) return
        rangeOfInteger(name, ranges.first().first, ranges.first().last)
        ranges.drop(1).forEach {
            writeHeader(IppTag.RANGE_OF_INTEGER, "")
            out.writeShort(8)
            out.writeInt(it.first)
            out.writeInt(it.last)
        }
    }

    /**
     * Writes a collection such as media-col. Members are emitted as
     * memberAttrName followed by the member's own value.
     */
    fun collection(name: String, block: IppCollectionBuilder.() -> Unit) {
        writeHeader(IppTag.BEG_COLLECTION, name)
        out.writeShort(0)
        IppCollectionBuilder(out).apply(block)
        writeHeader(IppTag.END_COLLECTION, "")
        out.writeShort(0)
    }
}

class IppCollectionBuilder internal constructor(private val out: DataOutputStream) {

    private fun memberName(name: String) {
        out.writeByte(IppTag.MEMBER_ATTR_NAME)
        out.writeShort(0)
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeShort(n.size)
        out.write(n)
    }

    private fun valueHeader(tag: Int) {
        out.writeByte(tag)
        out.writeShort(0)
    }

    fun integer(name: String, value: Int) {
        memberName(name)
        valueHeader(IppTag.INTEGER)
        out.writeShort(4)
        out.writeInt(value)
    }

    fun keyword(name: String, value: String) {
        memberName(name)
        valueHeader(IppTag.KEYWORD)
        val v = value.toByteArray(Charsets.UTF_8)
        out.writeShort(v.size)
        out.write(v)
    }

    fun nested(name: String, block: IppCollectionBuilder.() -> Unit) {
        memberName(name)
        valueHeader(IppTag.BEG_COLLECTION)
        out.writeShort(0)
        IppCollectionBuilder(out).apply(block)
        valueHeader(IppTag.END_COLLECTION)
        out.writeShort(0)
    }
}

/** Assembles a complete IPP request message (header + groups, no document data). */
class IppRequest(
    private val operation: Int,
    private val requestId: Int,
    private val versionMajor: Int = 2,
    private val versionMinor: Int = 0,
) {
    private val buffer = ByteArrayOutputStream(512)
    private val out = DataOutputStream(buffer)
    private var started = false

    init {
        out.writeByte(versionMajor)
        out.writeByte(versionMinor)
        out.writeShort(operation)
        out.writeInt(requestId)
    }

    fun group(delimiterTag: Int, block: IppGroupBuilder.() -> Unit): IppRequest {
        out.writeByte(delimiterTag)
        IppGroupBuilder(out).apply(block)
        started = true
        return this
    }

    fun operationAttributes(block: IppGroupBuilder.() -> Unit) =
        group(IppTag.OPERATION_ATTRIBUTES, block)

    fun jobAttributes(block: IppGroupBuilder.() -> Unit) = group(IppTag.JOB_ATTRIBUTES, block)

    fun build(): ByteArray {
        out.writeByte(IppTag.END_OF_ATTRIBUTES)
        out.flush()
        return buffer.toByteArray()
    }
}

/** Parses an IPP response message. Trailing document data, if any, is ignored. */
object IppDecoder {

    fun decode(bytes: ByteArray): IppResponse {
        val r = Reader(bytes)
        val major = r.u8()
        val minor = r.u8()
        val status = r.u16()
        val requestId = r.i32()

        val groups = mutableListOf<IppGroup>()
        var currentTag = -1
        var current = mutableListOf<IppAttribute>()

        while (r.remaining() > 0) {
            val tag = r.u8()

            if (tag == IppTag.END_OF_ATTRIBUTES) break

            if (IppTag.isDelimiter(tag)) {
                if (currentTag >= 0) groups += IppGroup(currentTag, current.toList())
                currentTag = tag
                current = mutableListOf()
                continue
            }

            val name = r.lengthPrefixedString()

            if (tag == IppTag.BEG_COLLECTION) {
                r.skipValue()
                val collection = readCollection(r)
                appendValue(current, name, collection)
                continue
            }

            val value = readValue(r, tag)
            appendValue(current, name, value)
        }

        if (currentTag >= 0) groups += IppGroup(currentTag, current.toList())
        return IppResponse(major, minor, status, requestId, groups)
    }

    /**
     * An empty attribute name means "another value for the previous attribute",
     * so fold it into the attribute already on the list.
     */
    private fun appendValue(into: MutableList<IppAttribute>, name: String, value: IppValue) {
        if (name.isEmpty() && into.isNotEmpty()) {
            val last = into.removeAt(into.size - 1)
            into += last.copy(values = last.values + value)
        } else {
            into += IppAttribute(name, listOf(value))
        }
    }

    private fun readCollection(r: Reader): IppValue.Collection {
        val members = linkedMapOf<String, MutableList<IppValue>>()
        var pendingName: String? = null

        while (r.remaining() > 0) {
            val tag = r.u8()
            // Member entries always carry a zero-length attribute name.
            r.lengthPrefixedString()

            when (tag) {
                IppTag.END_COLLECTION -> {
                    r.skipValue()
                    return IppValue.Collection(members.mapValues { it.value.toList() })
                }
                IppTag.MEMBER_ATTR_NAME -> pendingName = r.lengthPrefixedString()
                IppTag.BEG_COLLECTION -> {
                    r.skipValue()
                    val nested = readCollection(r)
                    pendingName?.let { members.getOrPut(it) { mutableListOf() } += nested }
                }
                else -> {
                    val value = readValue(r, tag)
                    pendingName?.let { members.getOrPut(it) { mutableListOf() } += value }
                }
            }
        }
        return IppValue.Collection(members.mapValues { it.value.toList() })
    }

    private fun readValue(r: Reader, tag: Int): IppValue {
        val len = r.u16()
        return when (tag) {
            IppTag.INTEGER -> IppValue.IntValue(r.i32Checked(len))
            IppTag.ENUM -> IppValue.EnumValue(r.i32Checked(len))
            IppTag.BOOLEAN -> IppValue.BoolValue(if (len >= 1) r.u8() != 0 else false)
            IppTag.RESOLUTION -> {
                if (len < 9) { r.skip(len); IppValue.Resolution(0, 0, 3) }
                else IppValue.Resolution(r.i32(), r.i32(), r.u8()).also { r.skip(len - 9) }
            }
            IppTag.RANGE_OF_INTEGER -> {
                if (len < 8) { r.skip(len); IppValue.IntRangeValue(0, 0) }
                else IppValue.IntRangeValue(r.i32(), r.i32()).also { r.skip(len - 8) }
            }
            IppTag.NO_VALUE, IppTag.UNKNOWN, IppTag.UNSUPPORTED -> {
                r.skip(len); IppValue.NoValue
            }
            IppTag.TEXT_WITH_LANGUAGE, IppTag.NAME_WITH_LANGUAGE -> {
                // sub-length-prefixed language then text; keep just the text.
                val bytes = r.bytes(len)
                IppValue.StringValue(decodeWithLanguage(bytes), tag)
            }
            IppTag.TEXT_WITHOUT_LANGUAGE, IppTag.NAME_WITHOUT_LANGUAGE, IppTag.KEYWORD,
            IppTag.URI, IppTag.URI_SCHEME, IppTag.CHARSET, IppTag.NATURAL_LANGUAGE,
            IppTag.MIME_MEDIA_TYPE, IppTag.MEMBER_ATTR_NAME ->
                IppValue.StringValue(String(r.bytes(len), Charsets.UTF_8), tag)
            else -> IppValue.Raw(tag, r.bytes(len))
        }
    }

    private fun decodeWithLanguage(bytes: ByteArray): String {
        if (bytes.size < 4) return String(bytes, Charsets.UTF_8)
        val langLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val textStart = 2 + langLen + 2
        if (textStart > bytes.size) return String(bytes, Charsets.UTF_8)
        val textLen = ((bytes[2 + langLen].toInt() and 0xFF) shl 8) or
            (bytes[3 + langLen].toInt() and 0xFF)
        val end = minOf(textStart + textLen, bytes.size)
        return String(bytes, textStart, end - textStart, Charsets.UTF_8)
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0
        fun remaining() = b.size - pos
        fun u8(): Int = need(1).let { b[pos++].toInt() and 0xFF }
        fun u16(): Int = need(2).let { (u8() shl 8) or u8() }
        fun i32(): Int = need(4).let { (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8() }

        fun i32Checked(len: Int): Int =
            if (len == 4) i32() else { skip(len); 0 }

        fun bytes(n: Int): ByteArray {
            val take = minOf(n, remaining())
            val out = b.copyOfRange(pos, pos + take)
            pos += take
            return out
        }

        fun skip(n: Int) { pos = minOf(b.size, pos + maxOf(0, n)) }
        fun skipValue() { skip(u16()) }

        fun lengthPrefixedString(): String {
            val len = u16()
            return String(bytes(len), Charsets.UTF_8)
        }

        private fun need(n: Int) {
            if (remaining() < n) throw EOFException("Truncated IPP message at offset $pos")
        }
    }
}

class IppException(message: String, val statusCode: Int? = null) : IOException(message)
