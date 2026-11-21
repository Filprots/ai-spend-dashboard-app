package app.aispend.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {
    fun money(amount: Double, currency: String): String {
        val symbol = when (currency.uppercase()) {
            "USD" -> "$"
            "EUR" -> "€"
            "CNY" -> "¥"
            "GBP" -> "£"
            else -> null
        }
        val digits = if (amount != 0.0 && kotlin.math.abs(amount) < 1) 3 else 2
        val number = String.format(Locale.US, "%,.${digits}f", amount)
        return if (symbol != null) "$symbol$number" else "$number $currency"
    }

    fun percent(value: Double): String = "${value.coerceIn(0.0, 999.0).toInt()}%"

    /** "in 2h 14m", "in 3d 4h", or "now" once the reset time has passed. */
    fun countdown(resetsAt: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (resetsAt - now) / 60_000
        return when {
            minutes <= 0 -> "now"
            minutes < 60 -> "in ${minutes}m"
            minutes < 24 * 60 -> "in ${minutes / 60}h ${minutes % 60}m"
            else -> "in ${minutes / 1440}d ${(minutes % 1440) / 60}h"
        }
    }

    /** Wall-clock form that stays true while a widget sits unrefreshed: "16:00" today, else "Mon 16:00". */
    fun clock(time: Long, now: Long = System.currentTimeMillis()): String {
        val a = Calendar.getInstance().apply { timeInMillis = time }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (sameDay) "HH:mm" else "EEE HH:mm", Locale.getDefault()).format(Date(time))
    }

    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - time) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / 1440}d ago"
        }
    }
}
