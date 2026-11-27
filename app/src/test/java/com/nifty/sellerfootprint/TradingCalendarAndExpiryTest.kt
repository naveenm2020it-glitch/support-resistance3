package com.nifty.sellerfootprint

import com.nifty.sellerfootprint.data.ExpiryResolver
import com.nifty.sellerfootprint.data.TradingCalendarEngine
import com.nifty.sellerfootprint.model.OptionContractRecord
import com.nifty.sellerfootprint.model.OptionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TradingCalendarAndExpiryTest {

    @Test
    fun validateTradingDate_rejectsWeekendsHolidaysAndFutureDates() {
        val referenceToday = LocalDate.of(2026, 3, 10)

        // Future date
        val futureRes = TradingCalendarEngine.validateTradingDate(
            date = LocalDate.of(2026, 3, 11),
            todayIst = referenceToday
        )
        assertTrue(futureRes is TradingCalendarEngine.DateValidationResult.Invalid)

        // Weekend (Saturday 2026-03-07 & Sunday 2026-03-08)
        val satRes = TradingCalendarEngine.validateTradingDate(
            date = LocalDate.of(2026, 3, 7),
            todayIst = referenceToday
        )
        assertTrue(satRes is TradingCalendarEngine.DateValidationResult.Invalid)

        val sunRes = TradingCalendarEngine.validateTradingDate(
            date = LocalDate.of(2026, 3, 8),
            todayIst = referenceToday
        )
        assertTrue(sunRes is TradingCalendarEngine.DateValidationResult.Invalid)

        // Known NSE Holiday: Holi on 2026-03-03
        val holiRes = TradingCalendarEngine.validateTradingDate(
            date = LocalDate.of(2026, 3, 3),
            todayIst = referenceToday
        )
        assertTrue(holiRes is TradingCalendarEngine.DateValidationResult.Invalid)

        // Valid trading day: Monday 2026-03-09
        val validRes = TradingCalendarEngine.validateTradingDate(
            date = LocalDate.of(2026, 3, 9),
            todayIst = referenceToday
        )
        assertTrue(validRes is TradingCalendarEngine.DateValidationResult.Valid)
    }

    @Test
    fun generateCandidateBackwardSessions_returnsChronologicalTradingDaysExcludingHolidays() {
        val endDate = LocalDate.of(2026, 3, 9)
        val candidates = TradingCalendarEngine.generateCandidateBackwardSessions(endDate, targetCount = 22)

        assertEquals(22, candidates.size)
        assertEquals(endDate, candidates.last())
        assertTrue(candidates.first().isBefore(candidates.last()))
        assertFalse(candidates.contains(LocalDate.of(2026, 3, 3))) // Holi excluded
        assertTrue(candidates.none { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun previewCalendarExpiries_respectsThursdayToTuesdayShiftAndHolidays() {
        // Pre-Sept 2025: Thursday expiries
        val preShiftDate = LocalDate.of(2025, 8, 11) // Monday
        val preExpiries = ExpiryResolver.previewCalendarExpiries(preShiftDate)
        assertTrue(preExpiries.isNotEmpty())
        assertEquals(LocalDate.of(2025, 8, 14), preExpiries.first().expiryDate)
        assertEquals(DayOfWeek.THURSDAY, preExpiries.first().expiryDate.dayOfWeek)

        // Post-Sept 2025: Tuesday expiries (with 2026-03-03 Holi shifting Tuesday expiry backward to Monday 2026-03-02)
        val postShiftDate = LocalDate.of(2026, 3, 2) // Monday
        val postExpiries = ExpiryResolver.previewCalendarExpiries(postShiftDate)
        assertTrue(postExpiries.isNotEmpty())
        assertEquals(LocalDate.of(2026, 3, 2), postExpiries.first().expiryDate)
        assertEquals(0L, postExpiries.first().dteCalendarDays)
    }

    @Test
    fun resolveFromContracts_extractsActualBhavcopyExpiriesAndSelectsNearest() {
        val tradeDate = LocalDate.of(2026, 3, 9)
        val exp1 = LocalDate.of(2026, 3, 10)
        val exp2 = LocalDate.of(2026, 3, 17)
        val expMonthly = LocalDate.of(2026, 3, 30) // Adjusted from 2026-03-31 Mahavir Jayanti

        val contracts = listOf(
            sampleContract(tradeDate, exp1, 24000.0, OptionSide.PE, 1000L),
            sampleContract(tradeDate, exp2, 24000.0, OptionSide.CE, 800L),
            sampleContract(tradeDate, expMonthly, 24500.0, OptionSide.CE, 2500L)
        )

        val resolved = ExpiryResolver.resolveFromContracts(tradeDate, contracts)
        assertEquals(3, resolved.size)
        assertEquals(exp1, resolved[0].expiryDate)
        assertFalse(resolved[0].isMonthly)
        assertEquals(expMonthly, resolved[2].expiryDate)
        assertTrue(resolved[2].isMonthly)

        val nearest = ExpiryResolver.selectNearestExpiry(resolved)
        assertNotNull(nearest)
        assertEquals(exp1, nearest!!.expiryDate)
    }

    private fun sampleContract(
        tradeDate: LocalDate,
        expiryDate: LocalDate,
        strike: Double,
        side: OptionSide,
        oiLots: Long
    ) = OptionContractRecord(
        tradeDate = tradeDate,
        expiryDate = expiryDate,
        strikePrice = strike,
        optionType = side,
        openPrice = 100.0,
        highPrice = 110.0,
        lowPrice = 90.0,
        closePrice = 95.0,
        prevClosePrice = 105.0,
        settlePrice = 95.0,
        underlyingPrice = 24100.0,
        openInterestUnits = oiLots * 65L,
        changeInOiUnits = 100L * 65L,
        totalTradedVolumeUnits = 1000L * 65L,
        totalTurnoverValue = 1_000_000.0,
        totalTradesExecuted = 200L,
        lotSize = 65
    )
}
