package eu.pkgsoftware.babybuddywidgets.networking.babybuddy

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

var SystemServerTimeOffsetTracker = ServerTimeOffsetTracker()

const val DATE_TIME_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ssX"
const val DATE_ONLY_FORMAT_STRING = "yyyy-MM-dd"

fun parseNullOrDate(s: String, format: String): Date? {
    val sdf = SimpleDateFormat(format, Locale.ENGLISH)
    return try {
        var strDate = s
        strDate = strDate.replace("\\.[0-9]+([+-Z])".toRegex(), "$1")
        strDate = strDate.replace("Z$".toRegex(), "+00:00")
        sdf.parse(strDate)
    } catch (_: ParseException) {
        null
    }
}

fun formatDate(d: Date, format: String): String {
    val sdf = SimpleDateFormat(format, Locale.ENGLISH)
    return sdf.format(d)
}

/**
 * In pure local standalone mode, device clock IS the authoritative clock (0ms offset).
 */
fun clientToServerTime(d: Date): Date = d

fun serverTimeToClientTime(d: Date): Date = d

fun nowServer(): Date = Date()

fun maxDate(d1: Date, d2: Date): Date {
    return if (d1.after(d2)) d1 else d2
}
