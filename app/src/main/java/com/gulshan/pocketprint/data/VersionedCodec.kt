package com.gulshan.pocketprint.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The result of reading a stored list, including what could not be read.
 *
 * The losses are part of the result rather than swallowed, because the previous
 * behaviour — quietly returning an empty list — is precisely what made a decode
 * failure destructive.
 */
data class StoredList<T>(
    val items: List<T>,
    /** Records that could not be decoded and were left out. */
    val dropped: Int = 0,
    /**
     * The whole payload was unreadable. Held so the caller can put it somewhere
     * safe before writing anything over the top of it.
     */
    val unreadable: String? = null,
    /** The version the payload was written at; null when there was nothing. */
    val fromVersion: Int? = null,
) {
    val healthy: Boolean get() = dropped == 0 && unreadable == null
}

/**
 * Reads and writes a list of records with a version stamp, one record at a time.
 *
 * Two separate failures used to be treated as "there is nothing saved". A
 * payload the parser choked on, and a payload that parsed but held one record
 * this build no longer understands. Both returned an empty list, and every
 * writer then merged onto that empty list and persisted it — so one renamed
 * enum constant in an update silently erased every printer the user had saved,
 * on the next save, with no error anywhere.
 *
 * Two changes fix that. Records are decoded individually, so one unreadable
 * printer costs one printer instead of all of them. And a payload that cannot
 * be parsed at all comes back as [StoredList.unreadable] carrying the original
 * text, so the caller can preserve it rather than overwrite it.
 *
 * The version stamp is what makes a deliberate format change possible later:
 * [migrate] sees each record with the version it was written at, and may return
 * null to drop it.
 */
class VersionedCodec<T>(
    private val serializer: KSerializer<T>,
    private val currentVersion: Int,
    private val migrate: (fromVersion: Int, record: JsonObject) -> JsonObject? = { _, r -> r },
) {

    companion object {
        private const val VERSION_FIELD = "v"
        private const val ITEMS_FIELD = "items"

        /**
         * What a payload written before there were versions is treated as.
         * Those are a bare JSON array rather than an envelope.
         */
        const val UNVERSIONED = 0
    }

    fun encode(items: List<T>): String {
        val envelope = buildJsonObject {
            put(VERSION_FIELD, JsonPrimitive(currentVersion))
            put(ITEMS_FIELD, JsonArray(items.map { json.encodeToJsonElement(serializer, it) }))
        }
        return json.encodeToString(JsonObject.serializer(), envelope)
    }

    fun decode(raw: String?): StoredList<T> {
        if (raw.isNullOrBlank()) return StoredList(emptyList())

        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return StoredList(emptyList(), unreadable = raw)

        val (version, array) = when {
            // A bare array is what this app wrote before it stamped versions.
            parsed is JsonArray -> UNVERSIONED to parsed

            parsed is JsonObject -> {
                val stamp = (parsed[VERSION_FIELD] as? JsonPrimitive)?.content?.toIntOrNull()
                val items = parsed[ITEMS_FIELD] as? JsonArray
                if (stamp == null || items == null) {
                    return StoredList(emptyList(), unreadable = raw)
                }
                stamp to items
            }

            else -> return StoredList(emptyList(), unreadable = raw)
        }

        // A payload from a newer build than this one. Its records may hold
        // fields and enum values this build has never heard of, so the
        // per-record decode below is doing real work rather than being a
        // formality - and whatever survives is kept rather than the lot being
        // thrown away.
        val decoded = mutableListOf<T>()
        var dropped = 0

        array.forEach { element ->
            val record = element as? JsonObject
            if (record == null) {
                dropped++
                return@forEach
            }
            val migrated = runCatching { migrate(version, record) }.getOrNull()
            if (migrated == null) {
                dropped++
                return@forEach
            }
            val value = runCatching { json.decodeFromJsonElement(serializer, migrated) }.getOrNull()
            if (value == null) dropped++ else decoded += value
        }

        return StoredList(decoded, dropped = dropped, fromVersion = version)
    }
}
