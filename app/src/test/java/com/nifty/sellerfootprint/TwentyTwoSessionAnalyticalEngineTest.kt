package com.nifty.sellerfootprint

import com.nifty.sellerfootprint.engine.MultiStrikeAnalyzer
import com.nifty.sellerfootprint.engine.PatternDiscoveryEngine
import com.nifty.sellerfootprint.engine.ZoneDerivationEngine
import com.nifty.sellerfootprint.model.DailyVolatilityRecord
import com.nifty.sellerfootprint.model.FuturesContractRecord
import com.nifty.sellerfootprint.model.IndexSnapshotRecord
import com.nifty.sellerfootprint.model.OptionContractRecord
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.ParticipantOiRecord
import com.nifty.sellerfootprint.model.ParticipantVolumeRecord
import com.nifty.sellerfootprint.model.PatternId
import com.nifty.sellerfootprint.model.SessionMarketData
import com.nifty.sellerfootprint.model.StrikeBelt
import com.nifty.sellerfootprint.model.ZoneEvidenceStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

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
                // Strong Put walls at 23900 (S1) and 23700 (S2); both clear the Session 22 chain-wide OI-impulse threshold
                val peOi = when (k) {
                    23900.0 -> 14000L
                    23700.0 -> 12500L
                    22800.0 -> 9000L // far tail-wing margin hedge (P7 filter)
                    else -> 2200L
                }
                val peDelta = when (k) {
                    23900.0 -> 3400L
                    23700.0 -> 3200L
                    22800.0 -> 2500L
                    else -> 150L
                }
                val peSettle = if (k == 22800.0) 6.5 else 48.0

                // Strong Call walls at 24300 (R1) and 24500 (R2) clear the chain-wide impulse threshold; 24000 CE is P6-vetoed
                val ceOi = when (k) {
                    24300.0 -> 15000L
                    24500.0 -> 13000L
                    25400.0 -> 8500L // far tail-wing margin hedge (P7 filter)
                    else -> 2100L
                }
                val ceDelta = when (k) {
                    24300.0 -> 3600L
                    24500.0 -> 3300L
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

    @Test
    fun testIprrUnitInvarianceAndZeroBounds() {
        val iprrLot25 = MultiStrikeAnalyzer.computeIprr(
            deltaOiLots = 1000.0,
            prr = 0.25,
            zDeltaOi = 2.0
        )
        val iprrLot75 = MultiStrikeAnalyzer.computeIprr(
            deltaOiLots = 1000.0,
            prr = 0.25,
            zDeltaOi = 2.0
        )
        assertEquals(iprrLot25, iprrLot75, 1e-9)
        assertTrue(iprrLot25 > 0.0)

        val iprrUnwind = MultiStrikeAnalyzer.computeIprr(
            deltaOiLots = -500.0,
            prr = 0.20,
            zDeltaOi = 1.5
        )
        assertTrue(iprrUnwind < 0.0)

        assertEquals(0.0, MultiStrikeAnalyzer.computeIprr(0.0, 0.3, 2.0), 1e-9)
        assertEquals(0.0, MultiStrikeAnalyzer.computeIprr(100.0, 0.0, 2.0), 1e-9)
    }

    @Test
    fun testCrossExpiryRollNotPenalizedAsCapitulation() {
        val d1 = LocalDate.of(2026, 2, 10)
        val d2 = LocalDate.of(2026, 2, 11)
        val nearExp = LocalDate.of(2026, 2, 12)
        val nextExp = LocalDate.of(2026, 2, 19)

        val s1 = buildSyntheticSession(
            sessionIndex = 1,
            date = d1,
            spot = 24000.0,
            high = 24040.0,
            low = 23950.0,
            nearExpiry = nearExp,
            nextExpiry = nextExp,
            peWallStrike = 23900.0,
            ceWallStrike = 24200.0,
            ceWallDeltaLots = 1200L,
            ceWallOiLots = 10000L,
            nextExpCeDeltaLots = 0L
        )
        val s2 = buildSyntheticSession(
            sessionIndex = 2,
            date = d2,
            spot = 24250.0,
            high = 24270.0,
            low = 24020.0,
            nearExpiry = nearExp,
            nextExpiry = nextExp,
            peWallStrike = 24100.0,
            ceWallStrike = 24200.0,
            ceWallDeltaLots = -4000L,
            ceWallOiLots = 6000L,
            nextExpCeDeltaLots = 3600L
        )

        val res = MultiStrikeAnalyzer.analyzeSessionChain(s2, nearExp, listOf(s1))
        val ce24200 = res.metrics.first { it.strikePrice == 24200.0 && it.optionType == OptionSide.CE }

        assertTrue("Cross-expiry roll ratio should be >= 0.45", ce24200.crossExpiryRollRatio >= 0.45)
        assertTrue("isCrossExpiryRoll should be true", ce24200.isCrossExpiryRoll)

        val patterns = PatternDiscoveryEngine.detectPatternsForStrike(ce24200, s2, res.metrics, s1)
        assertFalse(
            "Cross-expiry roll must NOT trigger P6 capitulation veto",
            PatternId.P6_SHORT_COVERING_CAPITULATION in patterns
        )
        assertTrue(
            "Cross-expiry roll should trigger P5 defensive roll",
            PatternId.P5_VERTICAL_DEFENSIVE_ROLL in patterns
        )
    }

    @Test
    fun testDuplicateContractRowsDeduplicatedWithoutSumming() {
        val d1 = LocalDate.of(2026, 2, 10)
        val d2 = LocalDate.of(2026, 2, 11)
        val exp = LocalDate.of(2026, 2, 12)

        val s1 = buildSyntheticSession(
            sessionIndex = 1,
            date = d1,
            spot = 24000.0,
            high = 24030.0,
            low = 23960.0,
            nearExpiry = exp,
            nextExpiry = null,
            peWallStrike = 23900.0,
            ceWallStrike = 24200.0,
            ceWallDeltaLots = 1000L,
            ceWallOiLots = 9000L,
            nextExpCeDeltaLots = 0L
        )
        val baseS2 = buildSyntheticSession(
            sessionIndex = 2,
            date = d2,
            spot = 24020.0,
            high = 24050.0,
            low = 23980.0,
            nearExpiry = exp,
            nextExpiry = null,
            peWallStrike = 23900.0,
            ceWallStrike = 24200.0,
            ceWallDeltaLots = 2500L,
            ceWallOiLots = 12000L,
            nextExpCeDeltaLots = 0L
        )

        // Duplicate identical EOD snapshot rows for 24200 CE and 23900 PE
        val duplicatedContracts = baseS2.contracts + baseS2.contracts.filter {
            (it.strikePrice == 24200.0 && it.optionType == OptionSide.CE) ||
                (it.strikePrice == 23900.0 && it.optionType == OptionSide.PE)
        }
        val duplicatedS2 = baseS2.copy(contracts = duplicatedContracts)

        val cleanRes = MultiStrikeAnalyzer.analyzeSessionChain(baseS2, exp, listOf(s1))
        val dupRes = MultiStrikeAnalyzer.analyzeSessionChain(duplicatedS2, exp, listOf(s1))

        val cleanCe = cleanRes.metrics.first { it.strikePrice == 24200.0 && it.optionType == OptionSide.CE }
        val dupCe = dupRes.metrics.first { it.strikePrice == 24200.0 && it.optionType == OptionSide.CE }

        assertEquals("Same-contract EOD OI must not be summed", cleanCe.oiLots, dupCe.oiLots, 1e-6)
        assertEquals("Same-contract EOD deltaOI must not be summed", cleanCe.deltaOiLots, dupCe.deltaOiLots, 1e-6)
        assertEquals("Same-contract EOD PRR must not be altered", cleanCe.positionalRetentionRatio, dupCe.positionalRetentionRatio, 1e-6)
        assertTrue(
            "Both CE and PE at the same strike must remain preserved",
            dupRes.metrics.any { it.strikePrice == 24200.0 && it.optionType == OptionSide.PE }
        )
    }

    @Test
    fun testStandingOiAloneClassifiedAsInconclusiveNotActiveDefence() {
        val startDate = LocalDate.of(2026, 1, 1)
        val targetExpiry = LocalDate.of(2026, 2, 5)
        val sessions = (0 until 22).map { i ->
            val d = startDate.plusDays(i.toLong())
            val spot = 24000.0 + (i % 3) * 15.0
            buildSyntheticSession(
                sessionIndex = i + 1,
                date = d,
                spot = spot,
                high = spot + 35.0,
                low = spot - 35.0,
                nearExpiry = targetExpiry,
                nextExpiry = targetExpiry.plusDays(7),
                peWallStrike = 23900.0,
                ceWallStrike = 24200.0,
                ceWallDeltaLots = 2200L,
                ceWallOiLots = 12000L,
                nextExpCeDeltaLots = 0L
            )
        }

        // Modify Session 22 so PE side has ONLY passive standing OI at 23800 PE (deltaOI = 0, no active defense, no absorption)
        val s22Raw = sessions[21]
        val modifiedContracts = s22Raw.contracts.map { c ->
            if (c.optionType == OptionSide.PE) {
                if (c.strikePrice == 23800.0) {
                    c.copy(
                        openInterestUnits = 30000L * 65L,
                        changeInOiUnits = 0L,
                        totalTradedVolumeUnits = 500L * 65L,
                        prevClosePrice = c.settlePrice - 4.0 // price expanded rather than absorbed
                    )
                } else {
                    c.copy(
                        openInterestUnits = 1500L * 65L,
                        changeInOiUnits = 0L,
                        totalTradedVolumeUnits = 400L * 65L
                    )
                }
            } else {
                c
            }
        }
        val modifiedSessions = sessions.toMutableList().apply {
            this[21] = s22Raw.copy(contracts = modifiedContracts)
        }

        val discovery = PatternDiscoveryEngine.runTwentyTwoSessionPipeline(modifiedSessions, targetExpiry)
        val zones = ZoneDerivationEngine.deriveNarrowZones(
            session22 = modifiedSessions[21],
            prevSession21 = modifiedSessions[20],
            targetExpiry = targetExpiry,
            discovery = discovery
        )

        assertFalse("Standing OI alone must NOT establish S1 as active defence", zones.s1.isEstablished)
        assertEquals(ZoneEvidenceStrength.INCONCLUSIVE, zones.s1.strength)
        assertTrue(
            "Inconclusive S1 must identify potential standing OI",
            zones.s1.confidenceBasis.contains("Potential standing OI", ignoreCase = true)
        )
    }

    @Test
    fun testLegitimateAtmShortStraddleNotPenalizedAsContradiction() {
        val d1 = LocalDate.of(2026, 2, 10)
        val d2 = LocalDate.of(2026, 2, 11)
        val exp = LocalDate.of(2026, 2, 13)

        val s1 = buildSyntheticSession(
            sessionIndex = 1,
            date = d1,
            spot = 24000.0,
            high = 24030.0,
            low = 23970.0,
            nearExpiry = exp,
            nextExpiry = null,
            peWallStrike = 23900.0,
            ceWallStrike = 24100.0,
            ceWallDeltaLots = 1000L,
            ceWallOiLots = 8000L,
            nextExpCeDeltaLots = 0L
        )
        val baseS2 = buildSyntheticSession(
            sessionIndex = 2,
            date = d2,
            spot = 24000.0,
            high = 24025.0,
            low = 23975.0,
            nearExpiry = exp,
            nextExpiry = null,
            peWallStrike = 23900.0,
            ceWallStrike = 24100.0,
            ceWallDeltaLots = 1000L,
            ceWallOiLots = 8000L,
            nextExpCeDeltaLots = 0L
        )

        // Heavy short straddle selling on BOTH 24000 CE and 24000 PE right at ATM with decaying premiums
        val straddleContracts = baseS2.contracts.map { c ->
            if (c.strikePrice == 24000.0) {
                c.copy(
                    openInterestUnits = 18000L * 65L,
                    changeInOiUnits = 4500L * 65L,
                    totalTradedVolumeUnits = 16000L * 65L,
                    settlePrice = 62.0,
                    closePrice = 62.0,
                    prevClosePrice = 74.0
                )
            } else {
                c
            }
        }
        val s2Straddle = baseS2.copy(contracts = straddleContracts)
        val res = MultiStrikeAnalyzer.analyzeSessionChain(s2Straddle, exp, listOf(s1))

        val atmCe = res.metrics.first { it.strikePrice == 24000.0 && it.optionType == OptionSide.CE }
        val atmPe = res.metrics.first { it.strikePrice == 24000.0 && it.optionType == OptionSide.PE }

        assertTrue(
            "Near-ATM short straddle CE contradiction score should remain low (< 0.25)",
            atmCe.contradictionScore < 0.25
        )
        assertTrue(
            "Near-ATM short straddle PE contradiction score should remain low (< 0.25)",
            atmPe.contradictionScore < 0.25
        )
    }

    @Test
    fun testProvisionalSigmaNormalizationAndDynamicTailThreshold() {
        val d1 = LocalDate.of(2026, 2, 10)
        val d2 = LocalDate.of(2026, 2, 11)
        val exp = LocalDate.of(2026, 2, 12)

        val s1 = buildSyntheticSession(
            sessionIndex = 1,
            date = d1,
            spot = 24000.0,
            high = 24040.0,
            low = 23960.0,
            nearExpiry = exp,
            nextExpiry = null,
            peWallStrike = 23900.0,
            ceWallStrike = 24100.0,
            ceWallDeltaLots = 1000L,
            ceWallOiLots = 8000L,
            nextExpCeDeltaLots = 0L
        )
        val s2 = buildSyntheticSession(
            sessionIndex = 2,
            date = d2,
            spot = 24000.0,
            high = 24040.0,
            low = 23960.0,
            nearExpiry = exp,
            nextExpiry = null,
            peWallStrike = 23900.0,
            ceWallStrike = 24100.0,
            ceWallDeltaLots = 1500L,
            ceWallOiLots = 9500L,
            nextExpCeDeltaLots = 0L
        )

        val res = MultiStrikeAnalyzer.analyzeSessionChain(s2, exp, listOf(s1))
        val atm = res.metrics.first { it.strikePrice == 24000.0 && it.optionType == OptionSide.CE }
        val tail = res.metrics.first { it.strikePrice == 22800.0 && it.optionType == OptionSide.PE }

        assertEquals(StrikeBelt.CORE_GAMMA, atm.belt)
        assertEquals(StrikeBelt.TAIL_HEDGE, tail.belt)
        assertTrue(tail.volNormalizedDistance > MultiStrikeAnalyzer.PROVISIONAL_PRIMARY_WALL_SIGMA_MAX)

        val tailThreshLowSpot = PatternDiscoveryEngine.computeDynamicTailPremiumThreshold(10000.0, emptyList())
        val tailThreshHighSpot = PatternDiscoveryEngine.computeDynamicTailPremiumThreshold(40000.0, res.metrics)
        assertEquals(PatternDiscoveryEngine.PROVISIONAL_TAIL_PREMIUM_FLOOR, tailThreshLowSpot, 1e-6)
        assertTrue(tailThreshHighSpot > tailThreshLowSpot)
        assertTrue(tailThreshHighSpot <= PatternDiscoveryEngine.PROVISIONAL_TAIL_PREMIUM_CAP)
    }

    @Test
    fun testTwentyTwoSessionPipelinePrecisionSeparationAndFiveSessionOosGuard() {
        val startDate = LocalDate.of(2026, 1, 1)
        val targetExpiry = LocalDate.of(2026, 2, 5)

        val sessions = (0 until 22).map { i ->
            val d = startDate.plusDays(i.toLong())
            val spot = 24000.0 + (i % 3) * 20.0
            val isS22 = (i == 21)
            buildSyntheticSession(
                sessionIndex = i + 1,
                date = d,
                spot = if (isS22) 24100.0 else spot,
                high = (if (isS22) 24100.0 else spot) + 45.0,
                low = (if (isS22) 24100.0 else spot) - 40.0,
                nearExpiry = targetExpiry,
                nextExpiry = targetExpiry.plusDays(7),
                peWallStrike = 23900.0,
                ceWallStrike = 24300.0,
                ceWallDeltaLots = if (isS22) 3600L else 2200L,
                ceWallOiLots = if (isS22) 15000L else 11000L,
                nextExpCeDeltaLots = 0L,
                includeSecondaryWalls = isS22,
                includeOversizedPutCluster = isS22
            )
        }

        val discovery = PatternDiscoveryEngine.runTwentyTwoSessionPipeline(sessions, targetExpiry)

        // F2: Feature model calibrated strictly on Sessions 1..17 (in-sample)
        assertTrue(discovery.calibratedFeatureModel.inSampleTrialsCount > 0)
        assertEquals(
            discovery.calibratedFeatureModel.inSampleTrialsCount,
            discovery.calibratedFeatureModel.totalValidationTrials
        )

        // F2: 4 OOS sessions in 22-session pipeline (< 5 required) -> Unrated confidence
        val (unrated4Sessions, unratedReason) = PatternDiscoveryEngine.computeStatisticallyJustifiedConfidence(
            oosTrials = 20,
            oosSessionCount = 4,
            oosDefendedRate = 0.85
        )
        assertNull("4 OOS sessions (< 5) must remain Unrated even with 20 strike observations", unrated4Sessions)
        assertTrue(unratedReason.contains("Unrated"))

        val (rated5Sessions, ratedReason) = PatternDiscoveryEngine.computeStatisticallyJustifiedConfidence(
            oosTrials = 6,
            oosSessionCount = 5,
            oosDefendedRate = 0.8333
        )
        assertNotNull("5 independent OOS sessions (>= 5) should yield a rated Wilson lower bound", rated5Sessions)
        assertTrue(rated5Sessions!! in 0.0..0.99)
        assertTrue(ratedReason.contains("Wilson 90% OOS lower bound"))

        val zones = ZoneDerivationEngine.deriveNarrowZones(
            session22 = sessions[21],
            prevSession21 = sessions[20],
            targetExpiry = targetExpiry,
            discovery = discovery
        )

        // F2: Derived zones in the 22-session design (only 4 OOS sessions) must remain Unrated
        assertNull("S1 confidenceScore must remain Unrated with only 4 OOS sessions", zones.s1.confidenceScore)
        assertNull("R1 confidenceScore must remain Unrated with only 4 OOS sessions", zones.r1.confidenceScore)

        // Narrow zone width enforcement (<= 50 points) & E narrow-core extraction
        assertTrue("S1 must be established via narrow-core extraction", zones.s1.isEstablished)
        assertTrue("S1 width <= 50 pts", (zones.s1.widthPoints ?: 999.0) <= 50.0)
        assertTrue("R1 must be established", zones.r1.isEstablished)
        assertTrue("R1 width <= 50 pts", (zones.r1.widthPoints ?: 999.0) <= 50.0)

        if (zones.s2.isEstablished) {
            assertTrue("S2 width <= 50 pts", (zones.s2.widthPoints ?: 999.0) <= 50.0)
            assertTrue("S2 strictly below S1", zones.s2.upperBoundary!! < zones.s1.lowerBoundary!!)
        }
        if (zones.r2.isEstablished) {
            assertTrue("R2 width <= 50 pts", (zones.r2.widthPoints ?: 999.0) <= 50.0)
            assertTrue("R2 strictly above R1", zones.r2.lowerBoundary!! > zones.r1.upperBoundary!!)
        }
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

    private fun buildSyntheticSession(
        sessionIndex: Int,
        date: LocalDate,
        spot: Double,
        high: Double,
        low: Double,
        nearExpiry: LocalDate,
        nextExpiry: LocalDate?,
        peWallStrike: Double,
        ceWallStrike: Double,
        ceWallDeltaLots: Long,
        ceWallOiLots: Long,
        nextExpCeDeltaLots: Long,
        includeSecondaryWalls: Boolean = false,
        includeOversizedPutCluster: Boolean = false
    ): SessionMarketData {
        val lotSize = 65
        val strikes = listOf(
            22800.0, 23600.0, 23700.0, 23800.0, 23850.0, 23900.0, 23950.0,
            24000.0, 24050.0, 24100.0, 24200.0, 24300.0, 24400.0, 24500.0, 25400.0
        )

        val contracts = mutableListOf<OptionContractRecord>()
        for (k in strikes) {
            for (side in listOf(OptionSide.PE, OptionSide.CE)) {
                val isPrimaryPe = (side == OptionSide.PE && k == peWallStrike)
                val isPrimaryCe = (side == OptionSide.CE && k == ceWallStrike)
                val isClusterAdjacentPe = includeOversizedPutCluster &&
                    side == OptionSide.PE && (k == 23850.0 || k == 23800.0)
                val isSecondaryPe = includeSecondaryWalls && side == OptionSide.PE && k == 23600.0
                val isSecondaryCe = includeSecondaryWalls && side == OptionSide.CE && k == 24500.0
                val isTail = (k <= 22800.0 || k >= 25400.0)

                val oiLots = when {
                    isPrimaryCe -> ceWallOiLots
                    isPrimaryPe -> 14500L
                    isClusterAdjacentPe -> 12800L
                    isSecondaryPe -> 12000L
                    isSecondaryCe -> 13000L
                    isTail -> 9000L
                    else -> 2500L
                }
                val dOiLots = when {
                    isPrimaryCe -> ceWallDeltaLots
                    isPrimaryPe -> 3500L
                    isClusterAdjacentPe -> 3100L
                    isSecondaryPe -> 2800L
                    isSecondaryCe -> 2900L
                    isTail -> 400L
                    else -> 150L
                }
                val volLots = when {
                    isPrimaryCe || isPrimaryPe || isClusterAdjacentPe || isSecondaryPe || isSecondaryCe ->
                        maxOf(4500L, abs(dOiLots) * 3L)
                    else -> 1800L
                }
                val trades = when {
                    isPrimaryCe || isPrimaryPe || isClusterAdjacentPe || isSecondaryPe || isSecondaryCe -> 250L
                    else -> 600L
                }
                val settle = when {
                    isTail -> if (side == OptionSide.PE) 6.5 else 5.5
                    isPrimaryCe || isPrimaryPe -> 46.0
                    isClusterAdjacentPe -> 42.0
                    isSecondaryPe || isSecondaryCe -> 32.0
                    else -> 58.0
                }
                val prevClose = when {
                    isPrimaryCe || isPrimaryPe || isClusterAdjacentPe || isSecondaryPe || isSecondaryCe -> settle + 8.0
                    else -> settle + 1.0
                }

                contracts.add(
                    OptionContractRecord(
                        tradeDate = date,
                        expiryDate = nearExpiry,
                        strikePrice = k,
                        optionType = side,
                        openPrice = prevClose,
                        highPrice = prevClose + 3.0,
                        lowPrice = settle - 2.0,
                        closePrice = settle,
                        prevClosePrice = prevClose,
                        settlePrice = settle,
                        underlyingPrice = spot,
                        openInterestUnits = oiLots * lotSize,
                        changeInOiUnits = dOiLots * lotSize,
                        totalTradedVolumeUnits = volLots * lotSize,
                        totalTurnoverValue = volLots * settle * lotSize,
                        totalTradesExecuted = trades,
                        lotSize = lotSize
                    )
                )
            }
        }

        if (nextExpiry != null && nextExpCeDeltaLots != 0L) {
            contracts.add(
                OptionContractRecord(
                    tradeDate = date,
                    expiryDate = nextExpiry,
                    strikePrice = ceWallStrike,
                    optionType = OptionSide.CE,
                    openPrice = 110.0,
                    highPrice = 115.0,
                    lowPrice = 102.0,
                    closePrice = 105.0,
                    prevClosePrice = 112.0,
                    settlePrice = 105.0,
                    underlyingPrice = spot,
                    openInterestUnits = 15000L * lotSize,
                    changeInOiUnits = nextExpCeDeltaLots * lotSize,
                    totalTradedVolumeUnits = 9000L * lotSize,
                    totalTurnoverValue = 20_000_000.0,
                    totalTradesExecuted = 300L,
                    lotSize = lotSize
                )
            )
        }

        val futContract = FuturesContractRecord(
            tradeDate = date,
            expiryDate = nearExpiry,
            openPrice = spot + 25.0,
            highPrice = high + 25.0,
            lowPrice = low + 25.0,
            closePrice = spot + 22.0,
            prevClosePrice = spot + 18.0,
            settlePrice = spot + 22.0,
            underlyingPrice = spot,
            openInterestUnits = 420000L * lotSize,
            changeInOiUnits = 8000L * lotSize,
            totalTradedVolumeUnits = 85000L * lotSize,
            totalTradesExecuted = 18000L,
            lotSize = lotSize
        )

        return SessionMarketData(
            sessionIndex = sessionIndex,
            tradeDate = date,
            contracts = contracts,
            participantOi = listOf(
                ParticipantOiRecord(date, "Pro", 10000, 12000, 40000, 52000, 38000, 50000),
                ParticipantOiRecord(date, "FII", 25000, 20000, 60000, 74000, 58000, 72000)
            ),
            indexSnapshot = IndexSnapshotRecord(
                tradeDate = date,
                niftyOpen = spot - 15.0,
                niftyHigh = high,
                niftyLow = low,
                niftyClose = spot,
                pointsChange = 15.0,
                percentChange = 0.06,
                indiaVixClose = 13.4
            ),
            volatilityRecord = DailyVolatilityRecord(
                tradeDate = date,
                underlyingClose = spot,
                dailyVolatility = 0.0085,
                annualizedVolatility = 0.162
            ),
            participantVolume = listOf(
                ParticipantVolumeRecord(date, "Pro", 5000, 5200, 18000, 21000, 17000, 20000),
                ParticipantVolumeRecord(date, "FII", 8000, 7900, 22000, 25000, 21000, 24000)
            ),
            futuresContracts = listOf(futContract)
        )
    }
}
