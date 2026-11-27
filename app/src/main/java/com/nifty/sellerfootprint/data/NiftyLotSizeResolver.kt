package com.nifty.sellerfootprint.data

import java.time.LocalDate

/**
 * Resolves NIFTY F&O lot sizes strictly from:
 * 1. Actual report column (`NewBrdLotQty` in UDiFF Bhavcopy) whenever > 0.
 * 2. Verified SEBI/NSE contract specification circular dates when the report omits lot size
 *    (such as legacy pre-UDiFF Bhavcopy files).
 * 3. Explicitly marks the lot size as UNAVAILABLE (`lotSize = 0`, source = "UNAVAILABLE")
 *    if neither source is valid (including post-2026 dates when `NewBrdLotQty` is missing).
 *    Never blindly assumes 65 for unverified future dates.
 */
object NiftyLotSizeResolver {

    const val SOURCE_REPORT = "REPORT_NEWBRDLOTQTY"
    const val SOURCE_VERIFIED_SCHEDULE = "VERIFIED_NSE_SCHEDULE"
    const val SOURCE_UNAVAILABLE = "UNAVAILABLE"

    data class LotResolution(
        val lotSize: Int,
        val source: String
    ) {
        val isAvailable: Boolean get() = lotSize > 0
    }

    /**
     * Resolves lot size from report value (`NewBrdLotQty`) first, then verified historical
     * SEBI/NSE circulars. Returns `LotResolution(0, "UNAVAILABLE")` whenever the lot size
     * cannot be reliably established.
     */
    fun resolve(reportLotQty: Int, tradeDate: LocalDate): LotResolution {
        if (reportLotQty > 0) {
            return LotResolution(
                lotSize = reportLotQty,
                source = SOURCE_REPORT
            )
        }
        val circularLot = resolveFromVerifiedCircularSchedule(tradeDate)
        return if (circularLot != null) {
            LotResolution(
                lotSize = circularLot,
                source = SOURCE_VERIFIED_SCHEDULE
            )
        } else {
            LotResolution(
                lotSize = 0,
                source = SOURCE_UNAVAILABLE
            )
        }
    }

    /**
     * Verified NSE NIFTY 50 F&O market-lot schedule from published NSE/SEBI circulars:
     * - 2021-07-01 .. 2024-04-25: 50 units (NSE/FAOP/48108)
     * - 2024-04-26 .. 2024-11-19: 25 units (NSE/FAOP/61742)
     * - 2024-11-20 .. 2025-12-30: 75 units (NSE/FAOP/64625)
     * - 2025-12-31 .. 2026-12-31: 65 units (NSE/FAOP/70762)
     *
     * Returns null for dates outside verified circular bounds (including 2027+ future dates)
     * so unknown lot sizes are explicitly marked UNAVAILABLE rather than blindly assumed.
     */
    fun resolveFromVerifiedCircularSchedule(tradeDate: LocalDate): Int? {
        return when {
            tradeDate in LocalDate.of(2021, 7, 1)..LocalDate.of(2024, 4, 25) -> 50
            tradeDate in LocalDate.of(2024, 4, 26)..LocalDate.of(2024, 11, 19) -> 25
            tradeDate in LocalDate.of(2024, 11, 20)..LocalDate.of(2025, 12, 30) -> 75
            tradeDate in LocalDate.of(2025, 12, 31)..LocalDate.of(2026, 12, 31) -> 65
            else -> null
        }
    }
}
