package ng.name.nsikak.libaccession.data.api

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

sealed interface ConnectionResult {
    data class Ok(val millis: Long) : ConnectionResult
    data class Failed(val message: String) : ConnectionResult
}

/** Turns a network exception into a sentence a librarian can act on. */
fun describeError(t: Throwable): String = when {
    t is ApiException -> t.detail
    t is UnknownServiceException && t.message.orEmpty().contains("CLEARTEXT", ignoreCase = true) ->
        "Blocked: Android only allows plain http:// to hosts listed in network_security_config.xml. " +
            "Use an https:// address instead."
    t is SSLException ->
        "Secure connection failed (certificate or TLS problem). Check the https:// address and the device clock."
    t is InterruptedIOException ->
        "Timed out. Is the server on, and does this device have a connection to it?"
    t is NoRouteToHostException ->
        "No route to that address. Check the address and the network."
    t is ConnectException ->
        "Couldn't connect. Is the server running on that port, and is this device on the same network?"
    t is UnknownHostException ->
        "Can't resolve that host name. Check the spelling, and that the tunnel is still running."
    t is HttpException && t.code() in setOf(502, 503, 504, 530) ->
        "The address answers, but nothing is running behind it (HTTP ${t.code()}). Are the server and its tunnel up?"
    t is HttpException ->
        "Server replied HTTP ${t.code()}."
    t is SerializationException ->
        "Reached something, but it isn't the library server (unexpected reply)."
    t is IllegalArgumentException ->
        "That address isn't valid."
    else -> t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
}
