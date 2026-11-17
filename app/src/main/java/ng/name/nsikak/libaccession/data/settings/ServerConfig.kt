package ng.name.nsikak.libaccession.data.settings

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server the app talks to. [baseUrl] is always normalised to scheme://host[:port][/path]/
 * so Retrofit can use it directly. Create instances with [parseServerAddress], never by hand,
 * except for the constants below.
 */
data class ServerConfig(val baseUrl: String) {
    private val url: HttpUrl get() = baseUrl.toHttpUrl()

    val host: String get() = url.host
    val isHttps: Boolean get() = url.isHttps

    /** What to show the user: the base URL without the trailing slash. */
    val display: String get() = baseUrl.removeSuffix("/")

    /** True when Android would refuse this address: plain HTTP to a host that is not allow-listed. */
    val cleartextBlocked: Boolean get() = !isHttps && !CleartextPolicy.isAllowed(host)

    companion object {
        const val DEFAULT_HOST = "192.168.1.117"
        const val DEFAULT_PORT = 4321
        val DEFAULT = ServerConfig("http://$DEFAULT_HOST:$DEFAULT_PORT/")
    }
}

/**
 * Mirror of res/xml/network_security_config.xml. Android only allows plain-HTTP (cleartext) traffic
 * to hosts listed there. HTTPS to ANY domain (trycloudflare.com, lhr.life, your own domain) is always
 * allowed and needs no entry. Keep both lists in sync.
 */
object CleartextPolicy {
    val ALLOWED_HOSTS = setOf("192.168.1.117", "school.local")
    fun isAllowed(host: String): Boolean {
        val h = host.lowercase()
        return h in ALLOWED_HOSTS || h.endsWith(".local") || h == "local" || IPV4.matches(h)
    }
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/**
 * Turns whatever the user typed into a normalised [ServerConfig], or null if it isn't usable.
 *
 *   192.168.1.117:4321            -> http://192.168.1.117:4321/
 *   school.local:4321             -> http://school.local:4321/
 *   https://abc.trycloudflare.com -> https://abc.trycloudflare.com/
 *   abc.lhr.life                  -> https://abc.lhr.life/        (no scheme + real domain = https)
 *   https://nsikak.name.ng/lib    -> https://nsikak.name.ng/lib/  (a path prefix is kept)
 */
fun parseServerAddress(raw: String): ServerConfig? {
    var text = raw.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null

    if (!text.contains("://")) {
        val host = text.substringBefore('/').substringBefore(':').lowercase()
        val plainLan = IPV4.matches(host) || host.endsWith(".local") || !host.contains('.')
        text = (if (plainLan) "http://" else "https://") + text
    }

    val url = text.toHttpUrlOrNull() ?: return null
    if (url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) {
        return null
    }
    val normalised = url.toString().let { if (it.endsWith("/")) it else "$it/" }
    return ServerConfig(normalised)
}
