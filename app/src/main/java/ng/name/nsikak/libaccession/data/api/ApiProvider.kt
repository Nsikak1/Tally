package ng.name.nsikak.libaccession.data.api

import kotlinx.coroutines.flow.first
import ng.name.nsikak.libaccession.data.settings.SettingsStore

/** Hands out a [LibraryApi] for the CURRENT saved address and API key, rebuilding it only when either changes. */
class ApiProvider(
    private val settings: SettingsStore,
    private val factory: ApiFactory,
) {
    private data class Key(val baseUrl: String, val apiKey: String)

    private var cachedKey: Key? = null
    private var cachedApi: LibraryApi? = null

    suspend fun get(): LibraryApi {
        val config = settings.serverConfig.first()
        val key = Key(config.baseUrl, settings.apiKey.first().trim())
        synchronized(this) {
            val api = cachedApi
            if (api != null && cachedKey == key) return api
            val fresh = factory.create(config, key.apiKey)
            cachedKey = key
            cachedApi = fresh
            return fresh
        }
    }
}
