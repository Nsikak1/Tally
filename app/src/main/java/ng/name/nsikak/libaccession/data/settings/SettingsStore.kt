package ng.name.nsikak.libaccession.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tally_settings")

class SettingsStore(private val context: Context) {
    private val urlKey = stringPreferencesKey("server_url")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val zoomKey = floatPreferencesKey("double_tap_zoom")
    private val ocrModelKey = stringPreferencesKey("ocr_model")
    // v2: the school moved to two digits-only formats; an older saved "ILS-XXXX" must not hide them.
    private val formatsKey = stringPreferencesKey("accession_formats_v2")
    private val lastPatternKey = stringPreferencesKey("last_accession_pattern")
    private val autoAccessionKey = booleanPreferencesKey("auto_accession")
    private val autoCheckUpdatesKey = booleanPreferencesKey("auto_check_updates")

    // Milestone 1 stored host and port separately; still read them so an existing install keeps working.
    private val legacyHostKey = stringPreferencesKey("server_host")
    private val legacyPortKey = intPreferencesKey("server_port")

    val serverConfig: Flow<ServerConfig> = context.settingsDataStore.data.map { prefs ->
        prefs[urlKey]?.let(::parseServerAddress)
            ?: prefs[legacyHostKey]?.let { host ->
                parseServerAddress("$host:${prefs[legacyPortKey] ?: ServerConfig.DEFAULT_PORT}")
            }
            ?: ServerConfig.DEFAULT
    }

    /** The X-API-Key for adding, editing and deleting library copies. Empty until the user enters it. */
    val apiKey: Flow<String> = context.settingsDataStore.data.map { it[apiKeyKey].orEmpty() }

    /** Zoom ratio a double-tap on the camera zooms to (barcode mode). */
    val doubleTapZoom: Flow<Float> = context.settingsDataStore.data.map {
        (it[zoomKey] ?: DEFAULT_DOUBLE_TAP_ZOOM).coerceIn(MIN_DOUBLE_TAP_ZOOM, MAX_DOUBLE_TAP_ZOOM)
    }

    /** Which handwriting model size to use: "small" or "medium" (whichever are installed in the app). */
    val ocrModel: Flow<String> = context.settingsDataStore.data.map { it[ocrModelKey] ?: DEFAULT_OCR_MODEL }

    /** The handwriting model size: saved on its own so the Settings screen can change it. */
    suspend fun saveOcrModel(ocrModel: String) {
        context.settingsDataStore.edit { prefs -> prefs[ocrModelKey] = ocrModel }
    }

    /**
     * The accepted accession number formats, one per entry. X = a digit, @ = a letter, X+ = one or more digits,
     * anything else is literal. Default: ILS-SR-XX-X+ and ILS-X+.
     */
    val accessionFormats: Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        parseFormatLines(prefs[formatsKey] ?: DEFAULT_ACCESSION_FORMAT)
    }

    /** Which format the accession page had selected last, so the next book starts with the same prefix. */
    val lastAccessionPattern: Flow<String?> = context.settingsDataStore.data.map { prefs -> prefs[lastPatternKey] }

    suspend fun saveLastAccessionPattern(pattern: String) {
        context.settingsDataStore.edit { prefs -> prefs[lastPatternKey] = pattern }
    }

    suspend fun saveAccessionFormats(lines: String) {
        context.settingsDataStore.edit { prefs -> prefs[formatsKey] = parseFormatLines(lines).joinToString("\n") }
    }

    suspend fun save(config: ServerConfig) {
        context.settingsDataStore.edit { prefs -> prefs[urlKey] = config.baseUrl }
    }

    suspend fun saveApiKey(key: String) {
        context.settingsDataStore.edit { prefs -> prefs[apiKeyKey] = key.trim() }
    }

    /** After an ISBN is scanned or typed, go straight on to reading the accession number (add-copies mode). */
    val autoAccession: Flow<Boolean> = context.settingsDataStore.data.map { it[autoAccessionKey] ?: true }

    suspend fun saveAutoAccession(on: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[autoAccessionKey] = on }
    }

    /** Automatically check for app updates when launching/opening settings. */
    val autoCheckUpdates: Flow<Boolean> = context.settingsDataStore.data.map { it[autoCheckUpdatesKey] ?: true }

    suspend fun saveAutoCheckUpdates(on: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[autoCheckUpdatesKey] = on }
    }

    suspend fun saveDoubleTapZoom(zoom: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[zoomKey] = zoom.coerceIn(MIN_DOUBLE_TAP_ZOOM, MAX_DOUBLE_TAP_ZOOM)
        }
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_ZOOM = 2.5f
        const val DEFAULT_OCR_MODEL = "small"
        /** ILS-X+ = ILS- and any number of digits; ILS-SR-XX-X+ = ILS-SR-, two digits, a dash, then any number of digits. */
        val DEFAULT_ACCESSION_FORMATS = listOf("ILS-SR-XX-X+", "ILS-X+")
        val DEFAULT_ACCESSION_FORMAT: String = DEFAULT_ACCESSION_FORMATS.joinToString("\n")

        /** One format per line (or comma): trimmed, upper-cased, empties dropped; never empty (falls back to the default). */
        fun parseFormatLines(text: String): List<String> =
            text.split('\n', ',', ';').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                .distinct().ifEmpty { DEFAULT_ACCESSION_FORMATS }
        const val MIN_DOUBLE_TAP_ZOOM = 1.5f
        const val MAX_DOUBLE_TAP_ZOOM = 6f
    }
}
