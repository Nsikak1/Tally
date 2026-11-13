package ng.name.nsikak.libaccession.data.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads a book cover (covers.openlibrary.org needs internet; on a LAN without it this returns null
 * and the UI shows a placeholder). Small in-memory cache, no extra library.
 */
class CoverLoader(client: OkHttpClient) {
    private val http = client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                fetch(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap != null) cache.put(url, bitmap)
        return bitmap
    }

    private fun fetch(url: String): Bitmap? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        http.newCall(Request.Builder().url(httpUrl).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return decode(bytes)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Open Library answers a 1x1 placeholder image when it has no cover.
        if (bounds.outWidth <= 10 || bounds.outHeight <= 10) return null
        var sample = 1
        while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
