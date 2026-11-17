package ng.name.nsikak.libaccession.data.api

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import ng.name.nsikak.libaccession.data.settings.ServerConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds a Retrofit client for whatever address is currently configured (LAN IP, school.local,
 * or any https:// domain such as a tunnel). The OkHttpClient is shared; Retrofit instances are cheap.
 */
class ApiFactory(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    // The server is small and slow when cold (server briefing): 15 s read timeout.
    private val baseClient = OkHttpClient.Builder()
        .dns(MdnsDnsResolver(context.applicationContext))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** The shared client, also used to download cover images. */
    val httpClient: OkHttpClient get() = baseClient

    /**
     * [apiKey] is sent as X-API-Key on every request that is not a GET (reads are open;
     * POST/PATCH/DELETE on /holdings need it).
     */
    fun create(config: ServerConfig, apiKey: String = ""): LibraryApi {
        val client = if (apiKey.isBlank()) {
            baseClient
        } else {
            baseClient.newBuilder()
                .addInterceptor(
                    Interceptor { chain ->
                        val request = chain.request()
                        if (request.method == "GET") {
                            chain.proceed(request)
                        } else {
                            chain.proceed(request.newBuilder().header("X-API-Key", apiKey).build())
                        }
                    },
                )
                .build()
        }
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LibraryApi::class.java)
    }

    /** Calls GET /health against [config] (which may be unsaved) and times it. Never throws. */
    suspend fun checkHealth(config: ServerConfig): ConnectionResult {
        val start = SystemClock.elapsedRealtime()
        return try {
            val response = create(config).health()
            if (response.ok) {
                ConnectionResult.Ok(SystemClock.elapsedRealtime() - start)
            } else {
                ConnectionResult.Failed("The server answered but reported that it is not OK.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult.Failed(describeError(e))
        }
    }
}
