package com.gulshan.pocketprint.data

import android.content.Context
import androidx.datastore.core.DataStore
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

/** Printers the user has explicitly saved, persisted as JSON. */
class PrinterRepository(private val context: Context) {

    private val key = stringPreferencesKey("saved_printers")

    val saved: Flow<List<Printer>> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<List<Printer>>(raw) }.getOrDefault(emptyList())
        }.orEmpty()
    }

    suspend fun list(): List<Printer> = saved.first()

    suspend fun save(printer: Printer) {
        val entry = printer.copy(saved = true)
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<Printer>>(it) }.getOrNull() }
                .orEmpty()
            val merged = current.filterNot { it.id == entry.id } + entry
            prefs[key] = json.encodeToString(merged)
        }
    }

    suspend fun remove(printerId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<Printer>>(it) }.getOrNull() }
                .orEmpty()
            prefs[key] = json.encodeToString(current.filterNot { it.id == printerId })
        }
    }

    suspend fun find(printerId: String): Printer? = list().firstOrNull { it.id == printerId }
}

/** A bounded history of print jobs. */
class JobRepository(private val context: Context) {

    private val key = stringPreferencesKey("job_history")
    private val limit = 100

    val history: Flow<List<PrintJobRecord>> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<List<PrintJobRecord>>(raw) }
                .getOrDefault(emptyList())
        }.orEmpty().sortedByDescending { it.createdAtEpochMs }
    }

    suspend fun upsert(record: PrintJobRecord) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<PrintJobRecord>>(it) }.getOrNull() }
                .orEmpty()
            val merged = (listOf(record) + current.filterNot { it.id == record.id })
                .sortedByDescending { it.createdAtEpochMs }
                .take(limit)
            prefs[key] = json.encodeToString(merged)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it[key] = json.encodeToString(emptyList<PrintJobRecord>()) }
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
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrNull()
        } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
                ?: AppSettings()
            prefs[key] = json.encodeToString(transform(current))
        }
    }
}
