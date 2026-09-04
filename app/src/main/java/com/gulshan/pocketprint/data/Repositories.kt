package com.gulshan.pocketprint.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintJobRecord
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.Printer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketprint")

internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Preserves a payload this build could not read, instead of writing over it.
 *
 * Only the most recent one is kept, which is the one that matters: a second
 * unreadable payload can only appear after this build has already written a
 * readable one over the first, and at that point the first is describing a
 * situation that no longer exists.
 */
private fun MutablePreferences.quarantine(key: Preferences.Key<String>, raw: String?) {
    if (raw != null) this[stringPreferencesKey("${key.name}__unreadable")] = raw
}

/** Printers the user has explicitly saved, persisted as versioned JSON. */
class PrinterRepository(private val context: Context) {

    private val key = stringPreferencesKey("saved_printers")
    private val codec = VersionedCodec(Printer.serializer(), VERSION)

    companion object {
        /**
         * Bump when the stored shape changes in a way old records need help
         * with, and give VersionedCodec a migrate function that handles it.
         * Version 0 is what this app wrote before it stamped anything: a bare
         * JSON array, which the codec still reads.
         */
        private const val VERSION = 1
    }

    val saved: Flow<List<Printer>> = context.dataStore.data.map { prefs ->
        codec.decode(prefs[key]).also { StorageHealth.report("printers", it) }.items
    }

    suspend fun list(): List<Printer> = saved.first()

    suspend fun save(printer: Printer) {
        val entry = printer.copy(saved = true)
        mutate { it.filterNot { existing -> existing.id == entry.id } + entry }
    }

    suspend fun remove(printerId: String) {
        mutate { it.filterNot { existing -> existing.id == printerId } }
    }

    suspend fun find(printerId: String): Printer? = list().firstOrNull { it.id == printerId }

    /**
     * Every write goes through here, because a write is where an unreadable
     * payload used to become a deleted one: the read failed to an empty list,
     * the new record was merged onto it, and the result was persisted.
     */
    private suspend fun mutate(transform: (List<Printer>) -> List<Printer>) {
        context.dataStore.edit { prefs ->
            val stored = codec.decode(prefs[key])
            StorageHealth.report("printers", stored)
            prefs.quarantine(key, stored.unreadable)
            prefs[key] = codec.encode(transform(stored.items))
        }
    }
}

/** A bounded history of print jobs. */
class JobRepository(private val context: Context) {

    private val key = stringPreferencesKey("job_history")
    private val limit = 100
    private val codec = VersionedCodec(PrintJobRecord.serializer(), VERSION)

    companion object {
        /**
         * 1 is the first stamped version. JobState gained SENT in this line of
         * work, which is exactly the kind of change that used to be dangerous:
         * a record written here and then read by an older build has an enum
         * constant that build has never heard of.
         */
        private const val VERSION = 1
    }

    val history: Flow<List<PrintJobRecord>> = context.dataStore.data.map { prefs ->
        codec.decode(prefs[key])
            .also { StorageHealth.report("print jobs", it) }
            .items
            .sortedByDescending { it.createdAtEpochMs }
    }

    suspend fun upsert(record: PrintJobRecord) {
        context.dataStore.edit { prefs ->
            val stored = codec.decode(prefs[key])
            StorageHealth.report("print jobs", stored)
            prefs.quarantine(key, stored.unreadable)
            val merged = (listOf(record) + stored.items.filterNot { it.id == record.id })
                .sortedByDescending { it.createdAtEpochMs }
                .take(limit)
            prefs[key] = codec.encode(merged)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it[key] = codec.encode(emptyList()) }
    }
}

@Serializable
data class AppSettings(
    /** Endpoint that converts Office documents to PDF; blank disables the feature. */
    val officeConverterUrl: String = "",
    val defaultMediaId: String = MediaSize.A4.id,
    val defaultDpi: Int = 300,
    val defaultColor: Boolean = false,
    val exposeToSystemPrint: Boolean = true,
    val ditherImages: Boolean = true,
) {
    fun toPrintOptions(): PrintOptions = PrintOptions(
        mediaSize = MediaSize.byId(defaultMediaId) ?: MediaSize.A4,
        dpi = defaultDpi,
        colorMode = if (defaultColor) ColorMode.COLOR else ColorMode.MONOCHROME,
        dither = ditherImages,
    )
}

class SettingsRepository(private val context: Context) {

    private val key = stringPreferencesKey("app_settings")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val raw = prefs[key]
            val current = decode(raw)
            // Quarantined for the same reason as the lists, though the stakes
            // are lower: settings is a single object whose every field has a
            // default, so there is no partial salvage to attempt and the blast
            // radius of losing it is one screen of preferences rather than
            // every printer the user has ever set up. Hence no version envelope
            // here either.
            if (raw != null && current == AppSettings() && raw != json.encodeToString(current)) {
                prefs.quarantine(key, raw)
            }
            prefs[key] = json.encodeToString(transform(current))
        }
    }

    private fun decode(raw: String?): AppSettings =
        raw?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings()
}
