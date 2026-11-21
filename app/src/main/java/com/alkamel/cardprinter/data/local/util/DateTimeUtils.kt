package com.alkamel.cardprinter.data.local.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateTimeUtils {

    private const val SQL_DATE_PATTERN = "yyyy-MM-dd"
    private const val DISPLAY_DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm"

    fun formatDateKey(timeMillis: Long): String {
        val formatter = SimpleDateFormat(SQL_DATE_PATTERN, Locale.US)
        return formatter.format(Date(timeMillis))
    }

    fun formatDisplayDateTime(timeMillis: Long): String {
        val formatter = SimpleDateFormat(DISPLAY_DATE_TIME_PATTERN, Locale.US)
        return formatter.format(Date(timeMillis))
    }

    fun todayDateKey(): String = formatDateKey(System.currentTimeMillis())

    fun yesterdayDateKey(): String = daysAgoDateKey(1)

    fun daysAgoDateKey(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return formatDateKey(cal.timeInMillis)
    }

    fun monthStartDateKey(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return formatDateKey(cal.timeInMillis)
    }
}
