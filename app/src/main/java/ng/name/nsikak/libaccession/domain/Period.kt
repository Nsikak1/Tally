package ng.name.nsikak.libaccession.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val SERVER_PATTERN = "yyyy-MM-dd HH:mm:ss"

private fun serverFormat(zone: TimeZone): SimpleDateFormat =
    SimpleDateFormat(SERVER_PATTERN, Locale.US).apply { timeZone = zone; isLenient = false }

/** How far back the library list looks. Days are calendar days in the device's time zone. (API 25 safe: no java.time.) */
enum class Period(val label: String, private val daysBack: Int?) {
    TODAY("Today", 0),
    THREE_DAYS("3 days", 2),
    WEEK("7 days", 6),
    MONTH("30 days", 29),
    ALL("All", null),
    ;

    /**
     * The server stores UTC "YYYY-MM-DD HH:MM:SS". This is the start of the period (local midnight)
     * written the same way, so it can be compared as text; null for [ALL].
     */
    fun cutoffUtc(nowMillis: Long, zone: TimeZone): String? {
        val back = daysBack ?: return null
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, -back)
        return serverFormat(TimeZone.getTimeZone("UTC")).format(cal.time)
    }
}

/** A server timestamp (UTC) shown in local time: "14:05" if it is today, else "28 Oct, 09:12". Unparseable text is returned as is. */
fun formatServerTime(utc: String, nowMillis: Long, zone: TimeZone): String {
    if (utc.isBlank()) return ""
    return try {
        val date = serverFormat(TimeZone.getTimeZone("UTC")).parse(utc.trim()) ?: return utc
        val local = Calendar.getInstance(zone).apply { time = date }
        val today = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        val sameDay = local.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            local.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "d MMM, HH:mm"
        SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = zone }.format(date)
    } catch (e: Exception) {
        utc
    }
}
