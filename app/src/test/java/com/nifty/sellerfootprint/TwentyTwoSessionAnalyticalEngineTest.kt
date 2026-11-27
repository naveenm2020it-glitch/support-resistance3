package com.nifty.sellerfootprint

import com.nifty.sellerfootprint.engine.MultiStrikeAnalyzer
import com.nifty.sellerfootprint.engine.PatternDiscoveryEngine
import com.nifty.sellerfootprint.engine.ZoneDerivationEngine
import com.nifty.sellerfootprint.model.DailyVolatilityRecord
import com.nifty.sellerfootprint.model.IndexSnapshotRecord
import com.nifty.sellerfootprint.model.OptionContractRecord
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.ParticipantOiRecord
import com.nifty.sellerfootprint.model.PatternId
import com.nifty.sellerfootprint.model.SessionMarketData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TwentyTwoSessionAnalyticalEngineTest {

    @Test
    fun multiStrikeAnalyzer_capturesFullChainWithoutAtmRestrictionAndFiltersFarTailNoise() {
        val baseDate = LocalDate.of(2026, 2, 10)
        val expiry = LocalDate.of(2026, 2, 17)
        val spot = 24000.0

        // Build strikes from 22500 to 25500 every 100 pts, with core OI mass between 23500 and 24500
        val contracts = mutableListOf<OptionContractRecord>()
        var strike = 22500.0
        while (strike <= 25500.0) {
            val isCore = strike in 23500.0..24500.0
            val oiLots = if (isCore) 12000L else 120L
            val deltaLots = if (strike == 23700.0 || strike == 24300.0) 3200L else 150L
            contracts.add(buildContract(baseDate, expiry, strike, OptionSide.PE, spot, oiLots, deltaLots, 55.0, 68.0))
            contracts.add(buildContract(baseDate, expiry, strike, OptionSide.CE, spot, oiLots, deltaLots, 52.0, 65.0))
            strike += 100.0
        }

        val session = SessionMarketData(
            sessionIndex = 1,
            tradeDate = baseDate,
            contracts = contracts,
            participantOi = emptyList(),
            indexSnapshot = IndexSnapshotRecord(baseDate, 23980.0, 24050.0, 23940.0, spot, 20.0, 0.08, 13.2),
            volatilityRecord = DailyVolatilityRecord(baseDate, spot, 0.0085, 0.1624)
        )

        val envelope = MultiStrikeAnalyzer.analyzeSessionChain(session, expiry, emptyList())
        assertEquals(31, envelope.totalStrikesInExpiry)
        // Must include OTM walls at 23700 and 24300 (not restricted to ATM 24000)
        assertTrue(envelope.metrics.any { it.strikePrice == 23700.0 && it.optionType == OptionSide.PE })
        assertTrue(envelope.metrics.any { it.strikePrice == 24300.0 && it.optionType == OptionSide.CE })
        // Extreme 22500 and 25500 strikes outside 95% OI mass + 2.5-sigma envelope are pruned
        assertTrue(envelope.retainedStrikesCount < envelope.totalStrikesInExpiry)
    }

    @Test
    fun fullTwentyTwoSessionPipeline_discoversValidatesAppliesVetoesAndDerivesNarrowZones() {
        val start = LocalDate.of(2026, 2, 1)
        val expiry = LocalDate.of(2026, 3, 10)
        val sessions = (0 until 22).map { idx ->
            val date = start.plusDays(idx.toLong())
            val spot = 24100.0
            val contracts = mutableListOf<OptionContractRecord>()
            val strikes = listOf(22800.0, 23700.0, 23900.0, 24000.0, 24100.0, 24300.0, 24500.0, 25400.0)
            for (k in strikes) {
                // Strong multi-session Put walls at 23900 (S1) and 23700 (S2)
                val peOi = when (k) {
                    23900.0 -> 14000L
                    23700.0 -> 12500L
                    22800.0 -> 9000L // far tail-wing margin hedge (P7 filter)
                    else -> 2200L
                }
                val peDelta = when (k) {
                    23900.0 -> 3400L
                    23700.0 -> 2800L
                    22800.0 -> 2500L
                    else -> 150L
                }
                val peSettle = if (k == 22800.0) 6.5 else 48.0

                // Strong Call walls at 24300 (R1) and 24500 (R2), plus breached short-covering at 24000 CE (P6 veto)
                val ceOi = when (k) {
                    24300.0 -> 15000L
                    24500.0 -> 13000L
                    25400.0 -> 8500L // far tail-wing margin hedge (P7 filter)
                    else -> 2100L
                }
                val ceDelta = when (k) {
                    24300.0 -> 3600L
                    24500.0 -> 2900L
                    24000.0 -> if (idx == 21) -4200L else 200L // P6 short-covering capitulation on Session 22
                    else -> 140L
                }
                val ceSettle = if (k == 25400.0) 5.5 else 46.0
                val cePrev = if (k == 24000.0 && idx == 21) 80.0 else 62.0
                val ceCur = if (k == 24000.0 && idx == 21) 145.0 else ceSettle

                contracts.add(buildContract(date, expiry, k, OptionSide.PE, spot, peOi, peDelta, peSettle, peSettle + 14.0))
                contracts.add(buildContract(date, expiry, k, OptionSide.CE, spot, ceOi, ceDelta, ceCur, cePrev))
            }

            val proShort = 500_000L + idx * 12_000L
            SessionMarketData(
                sessionIndex = idx + 1,
                tradeDate = date,
                contracts = contracts,
                participantOi = listOf(
                    ParticipantOiRecord(date, "Pro", 300_000L, 300_000L, proShort, proShort),
                    ParticipantOiRecord(date, "FII", 250_000L, 250_000L, proShort, proShort)
                ),
                indexSnapshot = IndexSnapshotRecord(date, 24060.0, 24140.0, 24040.0, spot, 35.0, 0.15, 13.4),
                volatilityRecord = DailyVolatilityRecord(date, spot, 0.0085, 0.1624)
            )
        }

        val discovery = PatternDiscoveryEngine.runTwentyTwoSessionPipeline(sessions, expiry)
        assertTrue(discovery.phase1LedgerA.isNotEmpty())
        assertTrue(discovery.combinedLedgerD.isNotEmpty())

        // Verify P6 veto fired on breached 24000 CE on Session 22
        val match24000Ce = discovery.session22StrikeMatches.firstOrNull {
            it.strikePrice == 24000.0 && it.optionType == OptionSide.CE
        }
        assertNotNull(match24000Ce)
        assertTrue(match24000Ce!!.hasVetoSignal)
        assertTrue(PatternId.P6_SHORT_COVERING_CAPITULATION in match24000Ce.matchedPatterns)

        val zones = ZoneDerivationEngine.deriveNarrowZones(
            session22 = sessions[21],
            prevSession21 = sessions[20],
            targetExpiry = expiry,
            discovery = discovery
        )

        // Verify S1, S2, R1, R2 are narrow, well-ordered, and non-overlapping
        assertTrue(zones.s1.isEstablished)
        assertTrue(zones.s2.isEstablished)
        assertTrue(zones.r1.isEstablished)
        assertTrue(zones.r2.isEstablished)

        assertTrue(zones.s2.upperBoundary!! < zones.s1.lowerBoundary!!)
        assertTrue(zones.s1.upperBoundary!! <= 24100.0)
        assertTrue(zones.r1.lowerBoundary!! >= 24100.0)
        assertTrue(zones.r2.lowerBoundary!! > zones.r1.upperBoundary!!)

        // Narrow zone width check (each zone <= 110 pts)
        assertTrue(zones.s1.widthPoints!! <= 110.0)
        assertTrue(zones.r1.widthPoints!! <= 110.0)
        assertFalse(zones.r1.relevantStrikes.contains(24000.0)) // Vetoed strike excluded
    }

    private fun buildContract(
        tradeDate: LocalDate,
        expiryDate: LocalDate,
        strike: Double,
        side: OptionSide,
        spot: Double,
        oiLots: Long,
        deltaOiLots: Long,
        settlePrice: Double,
        prevClose: Double
    ) = OptionContractRecord(
        tradeDate = tradeDate,
        expiryDate = expiryDate,
        strikePrice = strike,
        optionType = side,
        openPrice = prevClose,
        highPrice = maxOf(prevClose, settlePrice) + 5.0,
        lowPrice = minOf(prevClose, settlePrice) - 5.0,
        closePrice = settlePrice,
        prevClosePrice = prevClose,
        settlePrice = settlePrice,
        underlyingPrice = spot,
        openInterestUnits = oiLots * 65L,
        changeInOiUnits = deltaOiLots * 65L,
        totalTradedVolumeUnits = maxOf(kotlin.math.abs(deltaOiLots) * 4L, 1000L) * 65L,
        totalTurnoverValue = 50_000_000.0,
        totalTradesExecuted = 1500L,
        lotSize = 65
    )
}
