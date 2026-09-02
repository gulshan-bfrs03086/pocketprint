package com.gulshan.pocketprint.ipp

/**
 * Binary IPP primitives, per RFC 8010 (encoding) and RFC 8011 (semantics).
 *
 * Wire layout of a message:
 *   version (2) | operation-id or status-code (2) | request-id (4)
 *   [ delimiter-tag (1) | attribute* ]*
 *   end-of-attributes-tag (0x03)
 *   document data
 *
 * Wire layout of one attribute:
 *   value-tag (1) | name-length (2) | name | value-length (2) | value
 * A name-length of zero means "another value for the attribute just before me",
 * which is how IPP encodes 1setOf.
 */
object IppTag {
    // Delimiters
    const val OPERATION_ATTRIBUTES = 0x01
    const val JOB_ATTRIBUTES = 0x02
    const val END_OF_ATTRIBUTES = 0x03
    const val PRINTER_ATTRIBUTES = 0x04
    const val UNSUPPORTED_ATTRIBUTES = 0x05

    // Out-of-band values
    const val UNSUPPORTED = 0x10
    const val UNKNOWN = 0x12
    const val NO_VALUE = 0x13

    // Integer family
    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23

    // octetString family
    const val OCTET_STRING = 0x30
    const val DATE_TIME = 0x31
    const val RESOLUTION = 0x32
    const val RANGE_OF_INTEGER = 0x33
    const val BEG_COLLECTION = 0x34
    const val TEXT_WITH_LANGUAGE = 0x35
    const val NAME_WITH_LANGUAGE = 0x36
    const val END_COLLECTION = 0x37

    // character-string family
    const val TEXT_WITHOUT_LANGUAGE = 0x41
    const val NAME_WITHOUT_LANGUAGE = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val URI_SCHEME = 0x46
    const val CHARSET = 0x47
    const val NATURAL_LANGUAGE = 0x48
    const val MIME_MEDIA_TYPE = 0x49
    const val MEMBER_ATTR_NAME = 0x4A

    fun isDelimiter(tag: Int) = tag in 0x00..0x0F
}

object IppOperation {
    const val PRINT_JOB = 0x0002
    const val PRINT_URI = 0x0003
    const val VALIDATE_JOB = 0x0004
    const val CREATE_JOB = 0x0005
    const val SEND_DOCUMENT = 0x0006
    const val CANCEL_JOB = 0x0008
    const val GET_JOB_ATTRIBUTES = 0x0009
    const val GET_JOBS = 0x000A
    const val GET_PRINTER_ATTRIBUTES = 0x000B
    const val IDENTIFY_PRINTER = 0x003C
}

object IppStatus {
    const val SUCCESSFUL_OK = 0x0000
    const val SUCCESSFUL_OK_IGNORED_ATTRIBUTES = 0x0001
    const val SUCCESSFUL_OK_CONFLICTING_ATTRIBUTES = 0x0002

    fun isSuccess(code: Int) = code in 0x0000..0x00FF

    fun describe(code: Int): String = when (code) {
        SUCCESSFUL_OK -> "successful-ok"
        SUCCESSFUL_OK_IGNORED_ATTRIBUTES -> "successful-ok-ignored-or-substituted-attributes"
        SUCCESSFUL_OK_CONFLICTING_ATTRIBUTES -> "successful-ok-conflicting-attributes"
        0x0400 -> "client-error-bad-request"
        0x0401 -> "client-error-forbidden"
        0x0402 -> "client-error-not-authenticated"
        0x0403 -> "client-error-not-authorized"
        0x0404 -> "client-error-not-possible"
        0x0405 -> "client-error-timeout"
        0x0406 -> "client-error-not-found"
        0x0407 -> "client-error-gone"
        0x0408 -> "client-error-request-entity-too-large"
        0x0409 -> "client-error-request-value-too-long"
        0x040A -> "client-error-document-format-not-supported"
        0x040B -> "client-error-attributes-or-values-not-supported"
        0x040C -> "client-error-uri-scheme-not-supported"
        0x040D -> "client-error-charset-not-supported"
        0x040E -> "client-error-conflicting-attributes"
        0x040F -> "client-error-compression-not-supported"
        0x0410 -> "client-error-compression-error"
        0x0411 -> "client-error-document-format-error"
        0x0412 -> "client-error-document-access-error"
        0x0500 -> "server-error-internal-error"
        0x0501 -> "server-error-operation-not-supported"
        0x0502 -> "server-error-service-unavailable"
        0x0503 -> "server-error-version-not-supported"
        0x0504 -> "server-error-device-error"
        0x0505 -> "server-error-temporary-error"
        0x0506 -> "server-error-not-accepting-jobs"
        0x0507 -> "server-error-busy"
        0x0508 -> "server-error-job-canceled"
        0x0509 -> "server-error-multiple-document-jobs-not-supported"
        else -> "ipp-status-0x%04x".format(code)
    }
}

/** A single decoded IPP value. */
sealed interface IppValue {
    data class IntValue(val value: Int) : IppValue
    data class BoolValue(val value: Boolean) : IppValue
    data class EnumValue(val value: Int) : IppValue
    data class StringValue(val value: String, val tag: Int) : IppValue
    data class Resolution(val x: Int, val y: Int, val units: Int) : IppValue {
        /** units 3 = dots per inch, 4 = dots per centimetre. */
        val dpi: Int get() = if (units == 4) (x * 2.54f).toInt() else x
    }
    data class IntRangeValue(val lower: Int, val upper: Int) : IppValue
    data class Collection(val members: Map<String, List<IppValue>>) : IppValue
    data class Raw(val tag: Int, val bytes: ByteArray) : IppValue {
        override fun equals(other: Any?) =
            other is Raw && tag == other.tag && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * tag + bytes.contentHashCode()
    }
    data object NoValue : IppValue
}

data class IppAttribute(val name: String, val values: List<IppValue>) {
    val first: IppValue? get() = values.firstOrNull()

    fun asString(): String? = when (val v = first) {
        is IppValue.StringValue -> v.value
        is IppValue.IntValue -> v.value.toString()
        is IppValue.EnumValue -> v.value.toString()
        is IppValue.BoolValue -> v.value.toString()
        else -> null
    }

    fun asStrings(): List<String> = values.mapNotNull {
        when (it) {
            is IppValue.StringValue -> it.value
            is IppValue.IntValue -> it.value.toString()
            is IppValue.EnumValue -> it.value.toString()
            else -> null
        }
    }

    fun asInt(): Int? = when (val v = first) {
        is IppValue.IntValue -> v.value
        is IppValue.EnumValue -> v.value
        is IppValue.Resolution -> v.dpi
        else -> null
    }

    fun asInts(): List<Int> = values.mapNotNull {
        when (it) {
            is IppValue.IntValue -> it.value
            is IppValue.EnumValue -> it.value
            is IppValue.Resolution -> it.dpi
            else -> null
        }
    }

    fun asBool(): Boolean? = (first as? IppValue.BoolValue)?.value

    fun collections(): List<IppValue.Collection> = values.filterIsInstance<IppValue.Collection>()
}

data class IppGroup(val tag: Int, val attributes: List<IppAttribute>) {
    operator fun get(name: String): IppAttribute? = attributes.firstOrNull { it.name == name }
}

data class IppResponse(
    val versionMajor: Int,
    val versionMinor: Int,
    val statusCode: Int,
    val requestId: Int,
    val groups: List<IppGroup>,
) {
    val isSuccess: Boolean get() = IppStatus.isSuccess(statusCode)
    val statusText: String get() = IppStatus.describe(statusCode)

    fun printerGroup(): IppGroup? = groups.firstOrNull { it.tag == IppTag.PRINTER_ATTRIBUTES }
    fun jobGroup(): IppGroup? = groups.firstOrNull { it.tag == IppTag.JOB_ATTRIBUTES }

    operator fun get(name: String): IppAttribute? =
        groups.firstNotNullOfOrNull { g -> g.attributes.firstOrNull { it.name == name } }

    /** Attributes the printer told us it could not honour. */
    fun unsupported(): List<IppAttribute> =
        groups.firstOrNull { it.tag == IppTag.UNSUPPORTED_ATTRIBUTES }?.attributes.orEmpty()
}
