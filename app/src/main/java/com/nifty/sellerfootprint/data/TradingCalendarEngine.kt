package com.nifty.sellerfootprint.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

object TradingCalendarEngine {

    val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * Known NSE Equity & F&O scheduled market holidays (2024–2026).
     * Additional unscheduled closures or special Muhurat sessions are also handled dynamically
     * when querying NSE archives.
     */
    private val KNOWN_NSE_HOLIDAYS: Map<LocalDate, String> = mapOf(
        // 2024
        LocalDate.of(2024, 1, 22) to "Special Holiday",
        LocalDate.of(2024, 1, 26) to "Republic Day",
        LocalDate.of(2024, 3, 8) to "Mahashivratri",
        LocalDate.of(2024, 3, 25) to "Holi",
        LocalDate.of(2024, 3, 29) to "Good Friday",
        LocalDate.of(2024, 4, 11) to "Id-Ul-Fitr (Ramadan Eid)",
        LocalDate.of(2024, 4, 17) to "Shri Ram Navami",
        LocalDate.of(2024, 5, 1) to "Maharashtra Day",
        LocalDate.of(2024, 5, 20) to "General Elections (Mumbai)",
        LocalDate.of(2024, 6, 17) to "Bakri Id",
        LocalDate.of(2024, 7, 17) to "Muharram",
        LocalDate.of(2024, 8, 15) to "Independence Day",
        LocalDate.of(2024, 10, 2) to "Mahatma Gandhi Jayanti",
        LocalDate.of(2024, 11, 1) to "Diwali Laxmi Pujan",
        LocalDate.of(2024, 11, 15) to "Gurunanak Jayanti",
        LocalDate.of(2024, 12, 25) to "Christmas",
        // 2025
        LocalDate.of(2025, 2, 26) to "Mahashivratri",
        LocalDate.of(2025, 3, 14) to "Holi",
        LocalDate.of(2025, 3, 31) to "Id-Ul-Fitr (Ramadan Eid)",
        LocalDate.of(2025, 4, 10) to "Shri Mahavir Jayanti",
        LocalDate.of(2025, 4, 14) to "Dr. Baba Saheb Ambedkar Jayanti",
        LocalDate.of(2025, 4, 18) to "Good Friday",
        LocalDate.of(2025, 5, 1) to "Maharashtra Day",
        LocalDate.of(2025, 8, 15) to "Independence Day",
        LocalDate.of(2025, 8, 27) to "Ganesh Chaturthi",
        LocalDate.of(2025, 10, 2) to "Mahatma Gandhi Jayanti / Dussehra",
        LocalDate.of(2025, 10, 21) to "Diwali Laxmi Pujan",
        LocalDate.of(2025, 10, 22) to "Diwali Balipratipada",
        LocalDate.of(2025, 11, 5) to "Prakash Gurpurb Sri Guru Nanak Dev",
        LocalDate.of(2025, 12, 25) to "Christmas",
        // 2026
        LocalDate.of(2026, 1, 26) to "Republic Day",
        LocalDate.of(2026, 3, 3) to "Holi",
        LocalDate.of(2026, 3, 26) to "Shri Ram Navami",
        LocalDate.of(2026, 3, 31) to "Shri Mahavir Jayanti",
        LocalDate.of(2026, 4, 3) to "Good Friday",
        LocalDate.of(2026, 4, 14) to "Dr. Baba Saheb Ambedkar Jayanti",
        LocalDate.of(2026, 5, 1) to "Maharashtra Day",
        LocalDate.of(2026, 5, 28) to "Bakri Id",
        LocalDate.of(2026, 6, 26) to "Muharram",
        LocalDate.of(2026, 9, 14) to "Ganesh Chaturthi",
        LocalDate.of(2026, 10, 2) to "Mahatma Gandhi Jayanti",
        LocalDate.of(2026, 10, 20) to "Dussehra",
        LocalDate.of(2026, 11, 10) to "Diwali Balipratipada",
        LocalDate.of(2026, 11, 24) to "Prakash Gurpurb Sri Guru Nanak Dev",
        LocalDate.of(2026, 12, 25) to "Christmas"
    )

    sealed class DateValidationResult {
        data class Valid(val date: LocalDate) : DateValidationResult()
        data class Invalid(val date: LocalDate, val reason: String) : DateValidationResult()
    }

    fun validateTradingDate(
        date: LocalDate,
        todayIst: LocalDate = LocalDate.now(IST_ZONE)
    ): DateValidationResult {
        if (date.isAfter(todayIst)) {
            return DateValidationResult.Invalid(
                date = date,
                reason = "Future date selected ($date). NSE EOD reports are only available for completed trading sessions."
            )
        }
        if (date.isBefore(LocalDate.of(2020, 1, 1))) {
            return DateValidationResult.Invalid(
                date = date,
                reason = "Date ($date) is outside supported historical archive range."
            )
        }
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            return DateValidationResult.Invalid(
                date = date,
                reason = "$date is a ${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}. NSE derivatives market is closed on weekends."
            )
        }
        val holidayName = KNOWN_NSE_HOLIDAYS[date]
        if (holidayName != null) {
            return DateValidationResult.Invalid(
                date = date,
                reason = "$date is an NSE Market Holiday ($holidayName). No trading session occurred."
            )
        }
        return DateValidationResult.Valid(date)
    }

    fun isProbableTradingDay(date: LocalDate): Boolean {
        return date.dayOfWeek != DayOfWeek.SATURDAY &&
            date.dayOfWeek != DayOfWeek.SUNDAY &&
            !KNOWN_NSE_HOLIDAYS.containsKey(date)
    }

    fun mostRecentProbableTradingDay(fromDate: LocalDate = LocalDate.now(IST_ZONE)): LocalDate {
        var cursor = fromDate
        var guard = 0
        while (!isProbableTradingDay(cursor) && guard < 15) {
            cursor = cursor.minusDays(1)
            guard++
        }
        return cursor
    }

    /**
     * Generates candidate trading dates walking backward from [endDate] inclusive.
     * Requests extra buffer sessions (e.g., 32 candidate dates for a 22-session window)
     * so unscheduled exchange holidays discovered via HTTP 404 do not truncate the 22-session window.
     */
    fun generateCandidateBackwardSessions(
        endDate: LocalDate,
        targetCount: Int = 32
    ): List<LocalDate> {
        if (targetCount <= 0) return emptyList()
        val result = mutableListOf<LocalDate>()
        var cursor = endDate
        var safetyCounter = 0
        val maxCalendarDays = maxOf(120, targetCount * 3)
        while (result.size < targetCount && safetyCounter < maxCalendarDays) {
            if (isProbableTradingDay(cursor)) {
                result.add(cursor)
            }
            cursor = cursor.minusDays(1)
            safetyCounter++
        }
        return result.reversed() // chronological order
    }
}
