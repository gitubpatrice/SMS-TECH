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
class ChatFormatters(val locale: Locale) {
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
fun ChatFormatters.relativeRowLabel(
    timestampMillis: Long,
    // v1.28.12 — le `Calendar` prend la MÊME locale que les formateurs. Il prenait celle du
    // PROCESSUS (`Calendar.getInstance()` sans argument) tandis que les formateurs prennent
    // celle de la CONFIGURATION, c'est-à-dire la langue de l'application. Or un `Calendar`
    // porte `firstDayOfWeek` et `minimalDaysInFirstWeek`, et la comparaison ci-dessous se
    // fait sur `WEEK_OF_YEAR` : samedi et dimanche tombent dans la même semaine en
    // fr/de/it/es et dans DEUX semaines en `en`. Une Italienne voyait « sab » ; en basculant
    // l'application en anglais, la même ligne affichait « 12 Sept ». Relevé le 2026-09-17.
    now: Calendar = Calendar.getInstance(locale),
): String {
    val then = Calendar.getInstance(locale).apply { timeInMillis = timestampMillis }
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
    // v1.28.12 — même locale que les formateurs, pour la même raison que
    // [relativeRowLabel] : `WEEK_OF_YEAR` dépend de `firstDayOfWeek`, qui vient du
    // `Calendar`. Le correctif est posé sur les DEUX fonctions jumelles à la fois : c'est
    // de les avoir corrigées une à une que sont nés plusieurs défauts de ce dépôt.
    now: Calendar = Calendar.getInstance(locale),
): String {
    val then = Calendar.getInstance(locale).apply { timeInMillis = timestampMillis }
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

