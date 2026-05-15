package com.filestech.sms.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Audit P5/P6: a single set of locale-aware [SimpleDateFormat] formatters scoped to a Composable
 * so we no longer pay the cost of allocating one per row / per message bubble on every
 * recomposition.
 *
 * Why `SimpleDateFormat` and not `java.time` ? min SDK is 26, so `java.time` is available — but
 * `SimpleDateFormat` is still cheaper for the trivial patterns we use here and stays consistent
 * with the OS calendar / numbering chosen by `Locale`. Both APIs are equally NOT thread-safe, so
 * we scope the instance to the composition (single-threaded by definition).
 */
class ChatFormatters(locale: Locale) {
    val time: SimpleDateFormat = SimpleDateFormat("HH:mm", locale)
    val dayLabel: SimpleDateFormat = SimpleDateFormat("EEE", locale)
    val weekdayFull: SimpleDateFormat = SimpleDateFormat("EEEE", locale)
    val dateMedium: SimpleDateFormat = SimpleDateFormat("d MMM", locale)
    val fullDay: SimpleDateFormat = SimpleDateFormat("EEEE d MMMM yyyy", locale)
}

@Composable
fun rememberChatFormatters(): ChatFormatters {
    val locales = LocalConfiguration.current.locales
    val locale: Locale = remember(locales) { if (locales.isEmpty) Locale.getDefault() else locales.get(0) }
    return remember(locale) { ChatFormatters(locale) }
}

/**
 * Returns a label suitable for the conversation row:
 *  - same day  → HH:mm
 *  - same week → short day name
 *  - else      → day + month
 */
fun ChatFormatters.relativeRowLabel(timestampMillis: Long, now: Calendar = Calendar.getInstance()): String {
    val then = Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return when {
        now.get(Calendar.DATE) == then.get(Calendar.DATE) &&
            now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> time.format(Date(timestampMillis))
        now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR) &&
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> dayLabel.format(Date(timestampMillis))
        else -> dateMedium.format(Date(timestampMillis))
    }
}

/**
 * Returns a long-form, human label for a day divider rendered between message bursts.
 *
 *  - Today → caller-supplied [todayLabel] (i.e. "Aujourd'hui" / "Today")
 *  - Yesterday → caller-supplied [yesterdayLabel]
 *  - This week → full weekday name (Lundi, Tuesday)
 *  - This year → day + month ("27 mars")
 *  - Earlier → full date ("27 mars 2024")
 *
 * The "today" / "yesterday" labels are injected so the call-site uses translated resources
 * (`stringResource(R.string.date_today)` etc.) — this keeps the helper free of Android deps.
 */
fun ChatFormatters.daySeparatorLabel(
    timestampMillis: Long,
    todayLabel: String,
    yesterdayLabel: String,
    now: Calendar = Calendar.getInstance(),
): String {
    val then = Calendar.getInstance().apply { timeInMillis = timestampMillis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameMonth = sameYear && now.get(Calendar.MONTH) == then.get(Calendar.MONTH)
    val sameDay = sameMonth && now.get(Calendar.DATE) == then.get(Calendar.DATE)
    if (sameDay) return todayLabel
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DATE, -1) }
    if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
        yesterday.get(Calendar.DATE) == then.get(Calendar.DATE)
    ) return yesterdayLabel
    val sameWeek = sameYear &&
        now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR)
    return when {
        sameWeek -> weekdayFull.format(Date(timestampMillis))
        sameYear -> dateMedium.format(Date(timestampMillis))
        else -> fullDay.format(Date(timestampMillis))
    }
}

