package com.nifty.sellerfootprint.data

import com.nifty.sellerfootprint.model.ExpiryOption
import com.nifty.sellerfootprint.model.OptionContractRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object ExpiryResolver {

    /**
     * Primary data-driven expiry discovery: extracts actual listed NIFTY option expiries
     * directly from the selected trading date's UDiFF/F&O Bhavcopy contracts.
     * Automatically selects the nearest expiry >= [selectedTradingDate].
     */
    fun resolveFromContracts(
        selectedTradingDate: LocalDate,
        contracts: List<OptionContractRecord>
    ): List<ExpiryOption> {
        if (contracts.isEmpty()) return emptyList()

        val validContracts = contracts.filter {
            it.tradeDate == selectedTradingDate && !it.expiryDate.isBefore(selectedTradingDate)
        }
        if (validContracts.isEmpty()) return emptyList()

        val groupedByExpiry = validContracts.groupBy { it.expiryDate }
        val sortedExpiries = groupedByExpiry.keys.sorted()

        // Determine monthly expiries as the latest expiry date within each (Year, Month) bucket
        val lastExpiryPerMonth = sortedExpiries
            .groupBy { Pair(it.year, it.monthValue) }
            .mapValues { (_, dates) -> dates.maxOrNull() }

        return sortedExpiries.map { expDate ->
            val expContracts = groupedByExpiry[expDate].orEmpty()
            val validLotContracts = expContracts.filter { it.isLotSizeAvailable }
            val unavailableLotCount = expContracts.size - validLotContracts.size
            val totalOiLots = validLotContracts
                .mapNotNull { it.openInterestLotsOrNull?.takeIf { lots -> lots.isFinite() && lots >= 0.0 } }
                .sum()
                .toLong()
            val isMonthly = lastExpiryPerMonth[Pair(expDate.year, expDate.monthValue)] == expDate
            val dte = ChronoUnit.DAYS.between(selectedTradingDate, expDate)
            ExpiryOption(
                expiryDate = expDate,
                dteCalendarDays = dte,
                isMonthly = isMonthly,
                totalActiveContracts = expContracts.size,
                totalOpenInterestLots = totalOiLots,
                unavailableLotSizeContracts = unavailableLotCount
            )
        }
    }

    /**
     * Pre-download calendar preview of likely NIFTY expiries for [selectedTradingDate]
     * before the Bhavcopy is fetched. Accounts for NSE's shift from Thursday to Tuesday
     * expiries in September 2025 and adjusts backward for NSE market holidays.
     * Once the Bhavcopy downloads, [resolveFromContracts] replaces this preview with exact exchange contracts.
     */
    fun previewCalendarExpiries(selectedTradingDate: LocalDate): List<ExpiryOption> {
        val expiryDayOfWeek = if (!selectedTradingDate.isBefore(LocalDate.of(2025, 9, 1))) {
            DayOfWeek.TUESDAY
        } else {
            DayOfWeek.THURSDAY
        }

        val candidates = linkedSetOf<LocalDate>()
        var cursor = selectedTradingDate.with(TemporalAdjusters.nextOrSame(expiryDayOfWeek))
        repeat(6) {
            val adjusted = adjustBackwardForHoliday(cursor)
            if (!adjusted.isBefore(selectedTradingDate)) {
                candidates.add(adjusted)
            }
            cursor = cursor.plusWeeks(1)
        }

        val sorted = candidates.sorted()
        val lastInMonth = sorted
            .groupBy { Pair(it.year, it.monthValue) }
            .mapValues { (_, dates) -> dates.maxOrNull() }

        return sorted.map { expDate ->
            ExpiryOption(
                expiryDate = expDate,
                dteCalendarDays = ChronoUnit.DAYS.between(selectedTradingDate, expDate),
                isMonthly = lastInMonth[Pair(expDate.year, expDate.monthValue)] == expDate,
                totalActiveContracts = 0,
                totalOpenInterestLots = 0L
            )
        }
    }

    private fun adjustBackwardForHoliday(date: LocalDate): LocalDate {
        var d = date
        var guard = 0
        while (!TradingCalendarEngine.isProbableTradingDay(d) && guard < 10) {
            d = d.minusDays(1)
            guard++
        }
        return d
    }

    fun selectNearestExpiry(expiries: List<ExpiryOption>): ExpiryOption? {
        return expiries.minByOrNull { it.expiryDate }
    }
}
