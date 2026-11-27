package com.nifty.sellerfootprint.engine

import com.nifty.sellerfootprint.model.FuturesContractRecord
import com.nifty.sellerfootprint.model.OptionContractRecord
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.SessionMarketData
import com.nifty.sellerfootprint.model.StrikeBelt
import com.nifty.sellerfootprint.model.StrikeMicrostructureMetrics
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

object MultiStrikeAnalyzer {

    const val IPRR_C = 0.25

    data class ChainEnvelopeResult(
        val metrics: List<StrikeMicrostructureMetrics>,
        val envelopeMinStrike: Double,
        val envelopeMaxStrike: Double,
        val totalStrikesInExpiry: Int,
        val retainedStrikesCount: Int
    )

    private fun isReliableContract(c: OptionContractRecord): Boolean {
        if (!c.strikePrice.isFinite() || c.strikePrice <= 0.0) return false
        if (!c.isLotSizeAvailable || c.lotSize <= 0 || c.openInterestUnits < 0L) return false
        if (!c.effectivePrice.isFinite() || c.effectivePrice <= 0.0) return false
        if (c.highPrice > 0.0 && c.lowPrice > 0.0 && c.highPrice < c.lowPrice) return false
        return true
    }

    /**
     * Dynamically discovers the relevant active strike envelope for [session] and [targetExpiry]
     * without restricting to ATM or a fixed strike count, and computes v3.0 unit-invariant
     * microstructure metrics:
     * - IPRR = sign(deltaOI) * PRR * (1 - exp(-0.25 * max(0, Z_deltaOI)))
     * - FuturesAlignment in [-1, +1] from near-month NIFTY Futures return, OI impulse, and basis change
     * - Cross-Expiry Roll detection across all listed expiries in the same Bhavcopy
     * - Composite ContradictionScore in [0, 1]
     */
    fun analyzeSessionChain(
        session: SessionMarketData,
        targetExpiry: LocalDate,
        priorSessions: List<SessionMarketData>
    ): ChainEnvelopeResult {
        val spot = session.underlyingClose
        if (!spot.isFinite() || spot <= 0.0) {
            return ChainEnvelopeResult(emptyList(), 0.0, 0.0, 0, 0)
        }

        val expiryContracts = session.contracts
            .filter { it.expiryDate == targetExpiry && isReliableContract(it) }
        if (expiryContracts.isEmpty()) {
            return ChainEnvelopeResult(emptyList(), 0.0, 0.0, 0, 0)
        }

        val allStrikesSorted = expiryContracts.map { it.strikePrice }.distinct().sorted()
        val totalOiAllStrikes = expiryContracts.sumOf { max(0.0, it.openInterestLotsOrNull ?: 0.0) }
        if (totalOiAllStrikes <= 0.0) {
            return ChainEnvelopeResult(emptyList(), 0.0, 0.0, allStrikesSorted.size, 0)
        }

        // 1. 95% Cumulative OI Mass envelope centered around spot
        val strikesByDistance = allStrikesSorted.sortedBy { abs(it - spot) }
        val oiByStrike = expiryContracts.groupBy { it.strikePrice }
            .mapValues { (_, list) -> list.sumOf { max(0.0, it.openInterestLotsOrNull ?: 0.0) } }

        var cumulativeOi = 0.0
        val massSelectedStrikes = mutableSetOf<Double>()
        for (k in strikesByDistance) {
            massSelectedStrikes.add(k)
            cumulativeOi += oiByStrike[k] ?: 0.0
            if (cumulativeOi / totalOiAllStrikes >= 0.95) {
                break
            }
        }

        val minMassStrike = massSelectedStrikes.minOrNull() ?: (spot * 0.965)
        val maxMassStrike = massSelectedStrikes.maxOrNull() ?: (spot * 1.035)

        // 2. 2.5-Sigma Volatility Horizon Envelope
        val dailyVol = resolveDailyVolatility(session, priorSessions)
        val dte = max(1L, ChronoUnit.DAYS.between(session.tradeDate, targetExpiry))
        val sigmaMove = spot * dailyVol * sqrt(dte.toDouble()) * 2.5
        val volLower = spot - max(sigmaMove, spot * 0.025)
        val volUpper = spot + max(sigmaMove, spot * 0.025)

        val envelopeMin = max(min(minMassStrike, volLower), allStrikesSorted.first())
        val envelopeMax = min(max(maxMassStrike, volUpper), allStrikesSorted.last())

        val retainedContracts = expiryContracts.filter {
            it.strikePrice in envelopeMin..envelopeMax
        }
        val retainedStrikesCount = retainedContracts.map { it.strikePrice }.distinct().size

        if (retainedContracts.isEmpty()) {
            return ChainEnvelopeResult(emptyList(), envelopeMin, envelopeMax, allStrikesSorted.size, 0)
        }

        // Cross-sectional statistics for Z-score & median ATS normalization
        val deltaOiValues = retainedContracts.map { it.changeInOiLotsOrNull ?: 0.0 }
        val meanDeltaOi = deltaOiValues.average()
        val stdDeltaOi = standardDeviation(deltaOiValues, meanDeltaOi).let { if (it < 1.0) 1.0 else it }

        val atsValues = retainedContracts.mapNotNull { c ->
            val volLots = c.tradedVolumeLotsOrNull ?: 0.0
            if (c.totalTradesExecuted > 0 && volLots > 0.0) {
                volLots / c.totalTradesExecuted.toDouble()
            } else null
        }.sorted()
        val medianAts = if (atsValues.isNotEmpty()) atsValues[atsValues.size / 2] else 1.0

        val prevSession = priorSessions.lastOrNull()
        val spotDelta = if (prevSession != null && prevSession.underlyingClose > 0.0) {
            spot - prevSession.underlyingClose
        } else {
            session.indexSnapshot?.pointsChange ?: 0.0
        }

        // Directional Futures Flow Index Phi_F in [-1, +1]
        val futuresFlowPhi = computeFuturesDirectionalFlow(session, prevSession)

        // Institutional Participant Net Short Alignment from Stream 2 & Stream 5
        val institutionalShortBias = computeInstitutionalOptionShortBias(session, prevSession)

        val zBySideAndStrike = retainedContracts.associate { c ->
            val dOi = c.changeInOiLotsOrNull ?: 0.0
            Pair(c.optionType, c.strikePrice) to ((dOi - meanDeltaOi) / stdDeltaOi)
        }

        // Precompute IPRR per (side, strike) so cross-leg contradiction can inspect opposite-side IPRR
        val iprrBySideAndStrike = retainedContracts.associate { c ->
            val dOi = c.changeInOiLotsOrNull ?: 0.0
            val volLots = c.tradedVolumeLotsOrNull ?: 0.0
            val prr = if (volLots > 0.0) abs(dOi) / volLots else 0.0
            val z = (dOi - meanDeltaOi) / stdDeltaOi
            val iprrVal = computeIprr(dOi, prr, z)
            Pair(c.optionType, c.strikePrice) to iprrVal
        }

        // Next listed expiry contracts for cross-expiry roll detection
        val nextExpiryDate = session.contracts
            .map { it.expiryDate }
            .filter { it.isAfter(targetExpiry) }
            .minOrNull()
        val nextExpiryContracts = if (nextExpiryDate != null) {
            session.contracts.filter { it.expiryDate == nextExpiryDate && isReliableContract(it) }
        } else {
            emptyList()
        }

        val metricsList = retainedContracts.map { contract ->
            val distPct = ((contract.strikePrice - spot) / spot) * 100.0
            val absDistPct = abs(distPct)
            val belt = when {
                absDistPct <= 1.2 -> StrikeBelt.CORE_GAMMA
                absDistPct <= 3.5 -> StrikeBelt.PRIMARY_WALL
                else -> StrikeBelt.TAIL_HEDGE
            }

            val oiLots = contract.openInterestLotsOrNull ?: 0.0
            val deltaOiLots = contract.changeInOiLotsOrNull ?: 0.0
            val volLots = contract.tradedVolumeLotsOrNull ?: 0.0

            val zDelta = (deltaOiLots - meanDeltaOi) / stdDeltaOi
            val actualPriceChange = if (contract.prevClosePrice > 0.0) {
                contract.effectivePrice - contract.prevClosePrice
            } else {
                0.0
            }

            val moneynessDecay = exp(-0.22 * absDistPct)
            val approxDelta = when (contract.optionType) {
                OptionSide.CE -> if (contract.strikePrice <= spot) min(0.95, 0.5 + 0.45 * (1.0 - moneynessDecay)) else 0.5 * moneynessDecay
                OptionSide.PE -> if (contract.strikePrice >= spot) -min(0.95, 0.5 + 0.45 * (1.0 - moneynessDecay)) else -0.5 * moneynessDecay
            }
            val expectedPriceChange = spotDelta * approxDelta
            val excessDecay = actualPriceChange - expectedPriceChange

            val prr = if (volLots > 0.0) {
                abs(deltaOiLots) / volLots
            } else {
                0.0
            }
            val iprr = computeIprr(deltaOiLots, prr, zDelta)

            val ats = if (contract.totalTradesExecuted > 0) {
                volLots / contract.totalTradesExecuted.toDouble()
            } else {
                0.0
            }
            val atsRatio = if (medianAts > 0.0) ats / medianAts else 1.0

            val (persistence, activeDefense) = computePersistenceAndActiveDefense(
                strike = contract.strikePrice,
                side = contract.optionType,
                targetExpiry = targetExpiry,
                priorSessions = priorSessions
            )

            val verticalRollSource = detectVerticalRollSource(
                strike = contract.strikePrice,
                side = contract.optionType,
                zDelta = zDelta,
                spot = spot,
                zMap = zBySideAndStrike
            )

            val crossExpiryRollRatio = detectCrossExpiryRollRatio(
                contract = contract,
                nextExpiryContracts = nextExpiryContracts
            )
            val calendarDte = ChronoUnit.DAYS.between(session.tradeDate, targetExpiry)
            val isCrossExpiryRoll = calendarDte in 0..2 && crossExpiryRollRatio >= 0.45

            val futuresAlign = when (contract.optionType) {
                OptionSide.PE -> futuresFlowPhi
                OptionSide.CE -> -futuresFlowPhi
            }

            val oppSide = if (contract.optionType == OptionSide.PE) OptionSide.CE else OptionSide.PE
            val oppZ = zBySideAndStrike[Pair(oppSide, contract.strikePrice)] ?: 0.0
            val oppIprr = iprrBySideAndStrike[Pair(oppSide, contract.strikePrice)] ?: 0.0
            val partBiasForSide = institutionalShortBias[contract.optionType] ?: 0.0

            val contradiction = computeContradictionScore(
                deltaOiLots = deltaOiLots,
                zDelta = zDelta,
                excessDecay = excessDecay,
                oppZ = oppZ,
                oppIprr = oppIprr,
                futuresAlignment = futuresAlign,
                participantBiasForSide = partBiasForSide,
                isCrossExpiryRoll = isCrossExpiryRoll
            )

            StrikeMicrostructureMetrics(
                tradeDate = session.tradeDate,
                expiryDate = targetExpiry,
                strikePrice = contract.strikePrice,
                optionType = contract.optionType,
                distanceFromSpotPct = distPct,
                belt = belt,
                oiLots = oiLots,
                deltaOiLots = deltaOiLots,
                zDeltaOi = zDelta,
                settlePrice = contract.effectivePrice,
                priceChange = actualPriceChange,
                excessPriceDecay = excessDecay,
                positionalRetentionRatio = prr,
                averageTradeSizeLots = ats,
                atsRatioToMedian = atsRatio,
                persistenceSessions = persistence,
                activeDefenseSessions = activeDefense,
                isPairedWithOppositeRoll = verticalRollSource != null || isCrossExpiryRoll,
                oppositeRollSourceStrike = verticalRollSource,
                iprr = iprr,
                futuresAlignment = futuresAlign,
                isCrossExpiryRoll = isCrossExpiryRoll,
                crossExpiryRollRatio = crossExpiryRollRatio,
                contradictionScore = contradiction
            )
        }.sortedWith(compareBy({ it.strikePrice }, { it.optionType }))

        return ChainEnvelopeResult(
            metrics = metricsList,
            envelopeMinStrike = envelopeMin,
            envelopeMaxStrike = envelopeMax,
            totalStrikesInExpiry = allStrikesSorted.size,
            retainedStrikesCount = retainedStrikesCount
        )
    }

    /**
     * Unit-invariant Impulse-Weighted Positional Retention Ratio:
     * IPRR = sign(deltaOI) * PRR * (1 - exp(-c * max(0, Z_deltaOI))), c = 0.25.
     * Invariant to lot size scaling because PRR and Z_deltaOI are dimensionless ratios.
     */
    fun computeIprr(deltaOiLots: Double, prr: Double, zDeltaOi: Double, c: Double = IPRR_C): Double {
        if (!deltaOiLots.isFinite() || !prr.isFinite() || !zDeltaOi.isFinite()) return 0.0
        if (deltaOiLots == 0.0 || prr <= 0.0) return 0.0
        val impulseGate = 1.0 - exp(-c * max(0.0, zDeltaOi))
        return sign(deltaOiLots) * prr.coerceIn(0.0, 1.0) * impulseGate
    }

    /**
     * Computes directional NIFTY Futures flow index Phi_F in [-1, +1] using near-month futures
     * return, futures OI impulse, and basis change (FuturesSettle - Spot).
     */
    fun computeFuturesDirectionalFlow(
        current: SessionMarketData,
        prev: SessionMarketData?
    ): Double {
        val fut = current.nearMonthFutures ?: return 0.0
        val spot = current.underlyingClose
        if (spot <= 0.0 || fut.effectivePrice <= 0.0) return 0.0

        val prevFut = prev?.nearMonthFutures
        val refPrice = when {
            fut.prevClosePrice > 0.0 -> fut.prevClosePrice
            prevFut != null && prevFut.effectivePrice > 0.0 -> prevFut.effectivePrice
            else -> fut.effectivePrice
        }
        val futReturn = if (refPrice > 0.0) (fut.effectivePrice - refPrice) / refPrice else 0.0

        val futOi = fut.openInterestLotsOrNull ?: 0.0
        val futDeltaOi = fut.changeInOiLotsOrNull ?: 0.0
        val futOiImpulse = if (futOi > 0.0) {
            (futDeltaOi / max(1.0, 0.05 * futOi)).coerceIn(-2.0, 2.0)
        } else {
            0.0
        }

        val curBasis = fut.effectivePrice - spot
        val prevBasis = if (prevFut != null && prev != null && prev.underlyingClose > 0.0) {
            prevFut.effectivePrice - prev.underlyingClose
        } else {
            curBasis
        }
        val deltaBasis = curBasis - prevBasis

        return tanh(100.0 * futReturn + 0.35 * futOiImpulse + 0.02 * deltaBasis).coerceIn(-1.0, 1.0)
    }

    /**
     * Detects cross-expiry roll ratio R_roll when front-expiry OI unwinds (deltaOI < 0)
     * while the next listed expiry adds fresh OI (deltaOI > 0) at the same or outer strike (0..200 pts).
     */
    fun detectCrossExpiryRollRatio(
        contract: OptionContractRecord,
        nextExpiryContracts: List<OptionContractRecord>
    ): Double {
        val frontDelta = contract.changeInOiLotsOrNull ?: return 0.0
        if (frontDelta >= 0.0 || nextExpiryContracts.isEmpty()) return 0.0

        val rollOffsets = listOf(0.0, 50.0, 100.0, 150.0, 200.0)
        var bestNextDelta = 0.0
        for (offset in rollOffsets) {
            val targetNextStrike = when (contract.optionType) {
                OptionSide.CE -> contract.strikePrice + offset
                OptionSide.PE -> contract.strikePrice - offset
            }
            val nextMatch = nextExpiryContracts.firstOrNull {
                it.optionType == contract.optionType && it.strikePrice == targetNextStrike
            }
            val nextDelta = nextMatch?.changeInOiLotsOrNull ?: 0.0
            if (nextDelta > bestNextDelta) {
                bestNextDelta = nextDelta
            }
        }
        return if (bestNextDelta > 0.0) bestNextDelta / abs(frontDelta) else 0.0
    }

    /**
     * Computes composite structural contradiction score in [0, 1] for a candidate seller wall.
     */
    fun computeContradictionScore(
        deltaOiLots: Double,
        zDelta: Double,
        excessDecay: Double,
        oppZ: Double,
        oppIprr: Double,
        futuresAlignment: Double,
        participantBiasForSide: Double,
        isCrossExpiryRoll: Boolean
    ): Double {
        val cOpp = if (oppZ >= 1.15 && oppIprr > 0.0) {
            (oppIprr / 0.25).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val cUnwind = if (!isCrossExpiryRoll && deltaOiLots < 0.0 && excessDecay > 1.0) {
            (max(0.0, -zDelta) / 2.5).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val cFut = if (futuresAlignment < -0.35) {
            (-futuresAlignment).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val cPart = if (participantBiasForSide < -0.25) 0.20 else 0.0

        return (0.45 * cOpp + 0.35 * cUnwind + 0.20 * cFut + cPart).coerceIn(0.0, 1.0)
    }

    /**
     * Combines Stream 2 (Participant OI) and Stream 5 (Participant Trading Volume) to measure
     * institutional (Pro + FII) persistent short retention vs intraday churn for CE and PE.
     */
    private fun computeInstitutionalOptionShortBias(
        current: SessionMarketData,
        prev: SessionMarketData?
    ): Map<OptionSide, Double> {
        if (prev == null || current.participantOi.isEmpty() || prev.participantOi.isEmpty()) {
            return emptyMap()
        }
        val instTypes = setOf("PRO", "FII")
        val curInstOi = current.participantOi.filter { it.clientType.uppercase() in instTypes }
        val prevInstOi = prev.participantOi.filter { it.clientType.uppercase() in instTypes }
        if (curInstOi.isEmpty() || prevInstOi.isEmpty()) return emptyMap()

        val callShortDelta = curInstOi.sumOf { it.optionIndexCallShort } - prevInstOi.sumOf { it.optionIndexCallShort }
        val putShortDelta = curInstOi.sumOf { it.optionIndexPutShort } - prevInstOi.sumOf { it.optionIndexPutShort }

        val curInstVol = current.participantVolume.filter { it.clientType.uppercase() in instTypes }
        val callShortVol = curInstVol.sumOf { it.optionIndexCallShort }.coerceAtLeast(1L)
        val putShortVol = curInstVol.sumOf { it.optionIndexPutShort }.coerceAtLeast(1L)

        val ceBias = if (curInstVol.isNotEmpty()) {
            (callShortDelta.toDouble() / callShortVol.toDouble()).coerceIn(-1.0, 1.0)
        } else {
            sign(callShortDelta.toDouble()) * 0.25
        }
        val peBias = if (curInstVol.isNotEmpty()) {
            (putShortDelta.toDouble() / putShortVol.toDouble()).coerceIn(-1.0, 1.0)
        } else {
            sign(putShortDelta.toDouble()) * 0.25
        }

        return mapOf(OptionSide.CE to ceBias, OptionSide.PE to peBias)
    }

    private fun resolveDailyVolatility(
        current: SessionMarketData,
        priorSessions: List<SessionMarketData>
    ): Double {
        current.volatilityRecord?.dailyVolatility?.takeIf { it.isFinite() && it in 0.002..0.10 }?.let { return it }

        val closes = (priorSessions.takeLast(15) + current)
            .map { it.underlyingClose }
            .filter { it.isFinite() && it > 0.0 }
        if (closes.size >= 4) {
            val logReturns = (1 until closes.size).map { i -> ln(closes[i] / closes[i - 1]) }
            val mean = logReturns.average()
            val sd = standardDeviation(logReturns, mean)
            if (sd.isFinite() && sd in 0.002..0.10) return sd
        }
        return 0.0095
    }

    private fun computePersistenceAndActiveDefense(
        strike: Double,
        side: OptionSide,
        targetExpiry: LocalDate,
        priorSessions: List<SessionMarketData>
    ): Pair<Int, Int> {
        var streak = 0
        var activeDefenseCount = 0
        for (past in priorSessions.asReversed()) {
            val sameExpiry = past.contracts.filter {
                it.expiryDate == targetExpiry && it.optionType == side && isReliableContract(it)
            }
            val fallbackAll = if (sameExpiry.isNotEmpty()) {
                sameExpiry
            } else {
                past.contracts.filter { it.optionType == side && isReliableContract(it) }
            }
            if (fallbackAll.isEmpty()) break

            val sortedOi = fallbackAll.map { it.openInterestLotsOrNull ?: 0.0 }.sortedDescending()
            val topThresholdIdx = max(0, (sortedOi.size * 0.20).toInt() - 1)
            val topThresholdOi = sortedOi[topThresholdIdx]

            val match = fallbackAll.firstOrNull { it.strikePrice == strike } ?: break
            val matchOi = match.openInterestLotsOrNull ?: 0.0
            val matchDelta = match.changeInOiLotsOrNull ?: 0.0
            val matchVol = match.tradedVolumeLotsOrNull ?: 0.0

            val breached = when (side) {
                OptionSide.CE -> past.underlyingClose > match.strikePrice + min(25.0, match.effectivePrice * 0.45)
                OptionSide.PE -> past.underlyingClose < match.strikePrice - min(25.0, match.effectivePrice * 0.45)
            }
            if (!breached && matchOi >= topThresholdOi && matchOi > 0.0) {
                streak++
                val prr = if (matchVol > 0.0) abs(matchDelta) / matchVol else 0.0
                val priceNotExpanding = match.prevClosePrice <= 0.0 || match.effectivePrice <= match.prevClosePrice * 1.03
                if (matchDelta > 0.0 && prr >= 0.12 && priceNotExpanding) {
                    activeDefenseCount++
                }
            } else {
                break
            }
        }
        return Pair(streak, activeDefenseCount)
    }

    private fun detectVerticalRollSource(
        strike: Double,
        side: OptionSide,
        zDelta: Double,
        spot: Double,
        zMap: Map<Pair<OptionSide, Double>, Double>
    ): Double? {
        if (zDelta < 1.20) return null
        val stepCandidates = listOf(50.0, 100.0, 150.0, 200.0, 250.0)
        for (step in stepCandidates) {
            val innerStrike = when (side) {
                OptionSide.CE -> strike - step
                OptionSide.PE -> strike + step
            }
            val innerValidSide = when (side) {
                OptionSide.CE -> innerStrike >= spot - 100.0
                OptionSide.PE -> innerStrike <= spot + 100.0
            }
            if (!innerValidSide) continue
            val innerZ = zMap[Pair(side, innerStrike)] ?: continue
            if (innerZ <= -1.15) {
                return innerStrike
            }
        }
        return null
    }

    private fun standardDeviation(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(max(0.0, variance))
    }
}
