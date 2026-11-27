package com.nifty.sellerfootprint.engine

import com.nifty.sellerfootprint.model.FootprintZone
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.PatternId
import com.nifty.sellerfootprint.model.SessionMarketData
import com.nifty.sellerfootprint.model.StrikeMicrostructureMetrics
import com.nifty.sellerfootprint.model.ZoneEvidenceStrength
import com.nifty.sellerfootprint.model.ZoneRole
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object ZoneDerivationEngine {

    const val PREFERRED_MIN_WIDTH = 10.0
    const val PREFERRED_MAX_WIDTH = 30.0
    const val HARD_MAX_WIDTH = 50.0
    const val MAX_ALLOWED_CONTRADICTION = 0.55
    private const val MIN_ZONE_SEPARATION = 10.0
    private const val MIN_PRIMARY_SCORE = 0.50
    private const val MIN_SECONDARY_SCORE = 0.54

    data class DerivedZonesBundle(
        val s1: FootprintZone,
        val s2: FootprintZone,
        val r1: FootprintZone,
        val r2: FootprintZone,
        val strongestZoneSummary: String,
        val weakestZoneSummary: String,
        val persistentAreasSummary: String,
        val newlyDetectedSummary: String,
        val shiftingSummary: String
    )

    private data class ScoredStrikeCandidate(
        val metric: StrikeMicrostructureMetrics,
        val match: PatternDiscoveryEngine.StrikePatternMatch,
        val calibratedScore: Double,
        val singleStrikeZone: NarrowZoneCandidate
    )

    private data class NarrowZoneCandidate(
        val side: OptionSide,
        val coreStrike: Double,
        val strikes: List<StrikeMicrostructureMetrics>,
        val matchedPatternIds: Set<PatternId>,
        val lowerBound: Double,
        val upperBound: Double,
        val widthPoints: Double,
        val calibratedScore: Double,
        val strength: ZoneEvidenceStrength,
        val confidenceScore: Double?,
        val confidenceBasis: String,
        val historicalValidationText: String,
        val totalOiLots: Long,
        val deltaOiLots: Long,
        val avgSettlePrice: Double,
        val avgPrr: Double,
        val avgIprr: Double,
        val avgFuturesAlignment: Double,
        val maxContradictionScore: Double,
        val maxAtsRatio: Double,
        val maxPersistence: Int,
        val participantAlignmentNote: String,
        val rollSourceStrike: Double?,
        val isCrossExpiryRolled: Boolean
    )

    fun deriveNarrowZones(
        session22: SessionMarketData,
        prevSession21: SessionMarketData,
        targetExpiry: LocalDate,
        discovery: PatternDiscoveryEngine.TwentyTwoSessionDiscoveryOutcome
    ): DerivedZonesBundle {
        val spot = session22.underlyingClose
        val dte = max(0L, ChronoUnit.DAYS.between(session22.tradeDate, targetExpiry))

        val matchByKey = discovery.session22StrikeMatches.associateBy {
            Pair(it.optionType, it.strikePrice)
        }
        val metricByKey = discovery.session22Metrics.associateBy {
            Pair(it.optionType, it.strikePrice)
        }

        val proCallShortDelta = netShortDelta(session22, prevSession21, "Pro", isCall = true)
        val fiiCallShortDelta = netShortDelta(session22, prevSession21, "FII", isCall = true)
        val proPutShortDelta = netShortDelta(session22, prevSession21, "Pro", isCall = false)
        val fiiPutShortDelta = netShortDelta(session22, prevSession21, "FII", isCall = false)

        val proCallVol = participantShortVol(session22, "Pro", isCall = true)
        val fiiCallVol = participantShortVol(session22, "FII", isCall = true)
        val proPutVol = participantShortVol(session22, "Pro", isCall = false)
        val fiiPutVol = participantShortVol(session22, "FII", isCall = false)

        val supportMetrics = discovery.session22Metrics.filter { m ->
            val match = matchByKey[Pair(m.optionType, m.strikePrice)]
            m.optionType == OptionSide.PE &&
                m.strikePrice < spot &&
                m.strikePrice >= spot * 0.94 &&
                match != null &&
                !match.isFilteredTailWing &&
                !match.hasVetoSignal &&
                m.contradictionScore < MAX_ALLOWED_CONTRADICTION &&
                !hasConflictingSellerSignals(m, metricByKey, matchByKey) &&
                hasActiveSellerEvidence(m, match)
        }.sortedByDescending { it.strikePrice }

        val resistanceMetrics = discovery.session22Metrics.filter { m ->
            val match = matchByKey[Pair(m.optionType, m.strikePrice)]
            m.optionType == OptionSide.CE &&
                m.strikePrice > spot &&
                m.strikePrice <= spot * 1.06 &&
                match != null &&
                !match.isFilteredTailWing &&
                !match.hasVetoSignal &&
                m.contradictionScore < MAX_ALLOWED_CONTRADICTION &&
                !hasConflictingSellerSignals(m, metricByKey, matchByKey) &&
                hasActiveSellerEvidence(m, match)
        }.sortedBy { it.strikePrice }

        val supportCandidates = buildNarrowCandidates(
            orderedMetrics = supportMetrics,
            side = OptionSide.PE,
            spot = spot,
            dte = dte,
            matchByKey = matchByKey,
            featureModel = discovery.calibratedFeatureModel,
            proNetShortDelta = proPutShortDelta,
            fiiNetShortDelta = fiiPutShortDelta,
            proShortVol = proPutVol,
            fiiShortVol = fiiPutVol
        )

        val resistanceCandidates = buildNarrowCandidates(
            orderedMetrics = resistanceMetrics,
            side = OptionSide.CE,
            spot = spot,
            dte = dte,
            matchByKey = matchByKey,
            featureModel = discovery.calibratedFeatureModel,
            proNetShortDelta = proCallShortDelta,
            fiiNetShortDelta = fiiCallShortDelta,
            proShortVol = proCallVol,
            fiiShortVol = fiiCallVol
        )

        val rawS1 = supportCandidates.firstOrNull {
            it.upperBound < spot && it.calibratedScore >= MIN_PRIMARY_SCORE
        }
        val validS1 = rawS1?.takeIf { it.widthPoints <= HARD_MAX_WIDTH }

        val rawS2 = if (validS1 != null) {
            supportCandidates.firstOrNull { c ->
                c.calibratedScore >= MIN_SECONDARY_SCORE &&
                    c.upperBound <= validS1.lowerBound - MIN_ZONE_SEPARATION &&
                    c.coreStrike < validS1.coreStrike
            }
        } else null
        val validS2 = rawS2?.takeIf { it.widthPoints <= HARD_MAX_WIDTH }

        val rawR1 = resistanceCandidates.firstOrNull {
            it.lowerBound > spot && it.calibratedScore >= MIN_PRIMARY_SCORE
        }
        val validR1 = rawR1?.takeIf { it.widthPoints <= HARD_MAX_WIDTH }

        val rawR2 = if (validR1 != null) {
            resistanceCandidates.firstOrNull { c ->
                c.calibratedScore >= MIN_SECONDARY_SCORE &&
                    c.lowerBound >= validR1.upperBound + MIN_ZONE_SEPARATION &&
                    c.coreStrike > validR1.coreStrike
            }
        } else null
        val validR2 = rawR2?.takeIf { it.widthPoints <= HARD_MAX_WIDTH }

        val s1 = toFootprintZone(ZoneRole.S1, rawS1, spot, dte)
        val s2 = toFootprintZone(ZoneRole.S2, rawS2, spot, dte)
        val r1 = toFootprintZone(ZoneRole.R1, rawR1, spot, dte)
        val r2 = toFootprintZone(ZoneRole.R2, rawR2, spot, dte)

        val established = listOfNotNull(
            validS1?.let { ZoneRole.S1 to it },
            validS2?.let { ZoneRole.S2 to it },
            validR1?.let { ZoneRole.R1 to it },
            validR2?.let { ZoneRole.R2 to it }
        )

        val strongest = established.maxByOrNull { it.second.calibratedScore }
        val weakest = established.minByOrNull { it.second.calibratedScore }

        val strongestSummary = strongest?.let { (role, c) ->
            "${role.code} (Core ${c.coreStrike.toInt()}, Zone ${formatRange(c.lowerBound, c.upperBound)}, " +
                "${String.format("%.0f", c.widthPoints)} pts, IPRR ${String.format("%.3f", c.avgIprr)}) — ${c.strength.label} evidence (${c.totalOiLots} lots OI)"
        } ?: "None — insufficient active seller concentration within 50-point precision ceiling"

        val weakestSummary = weakest?.let { (role, c) ->
            "${role.code} (Core ${c.coreStrike.toInt()}, Zone ${formatRange(c.lowerBound, c.upperBound)}) — ${c.strength.label}"
        } ?: "None established"

        val persistentStrikes = discovery.session22Metrics
            .filter { it.activeDefenseSessions >= 2 }
            .sortedByDescending { it.activeDefenseSessions }
            .take(4)
            .map { "${it.strikePrice.toInt()} ${it.optionType} (${it.activeDefenseSessions} defended sessions)" }
        val persistentSummary = if (persistentStrikes.isNotEmpty()) {
            persistentStrikes.joinToString(", ")
        } else {
            "No strikes maintained multi-session active seller defense with positive retention."
        }

        val freshFootprints = discovery.session22Metrics
            .filter { it.zDeltaOi >= 1.35 && it.positionalRetentionRatio >= 0.15 && it.activeDefenseSessions == 0 }
            .sortedByDescending { it.iprr }
            .take(3)
            .map { "${it.strikePrice.toInt()} ${it.optionType} (+${it.deltaOiLots.toLong()} lots, IPRR ${String.format("%.3f", it.iprr)})" }
        val newlyDetectedSummary = if (freshFootprints.isNotEmpty()) {
            freshFootprints.joinToString(", ")
        } else {
            "No isolated fresh Session 22 outlier additions outside established seller walls."
        }

        val rollClusters = established.mapNotNull { (role, c) ->
            when {
                c.rollSourceStrike != null ->
                    "${role.code}: vertical roll from ${c.rollSourceStrike.toInt()} ${c.side} into ${c.coreStrike.toInt()} ${c.side}"
                c.isCrossExpiryRolled ->
                    "${role.code}: cross-expiry calendar roll detected at ${c.coreStrike.toInt()} ${c.side}"
                else -> null
            }
        }
        val shiftingSummary = if (rollClusters.isNotEmpty()) {
            rollClusters.joinToString("; ")
        } else {
            "No vertical or cross-expiry defensive roll detected on Session 22."
        }

        return DerivedZonesBundle(
            s1 = s1,
            s2 = s2,
            r1 = r1,
            r2 = r2,
            strongestZoneSummary = strongestSummary,
            weakestZoneSummary = weakestSummary,
            persistentAreasSummary = persistentSummary,
            newlyDetectedSummary = newlyDetectedSummary,
            shiftingSummary = shiftingSummary
        )
    }

    private fun hasConflictingSellerSignals(
        m: StrikeMicrostructureMetrics,
        metricByKey: Map<Pair<OptionSide, Double>, StrikeMicrostructureMetrics>,
        matchByKey: Map<Pair<OptionSide, Double>, PatternDiscoveryEngine.StrikePatternMatch>
    ): Boolean {
        if (!m.isCrossExpiryRoll && m.deltaOiLots < 0.0 && m.excessPriceDecay > 2.0) {
            return true
        }
        val oppositeSide = if (m.optionType == OptionSide.PE) OptionSide.CE else OptionSide.PE
        val oppMetric = metricByKey[Pair(oppositeSide, m.strikePrice)] ?: return false
        val oppMatch = matchByKey[Pair(oppositeSide, m.strikePrice)]
        if (oppMatch != null && oppMatch.hasVetoSignal) return false
        return oppMetric.zDeltaOi >= 1.25 && oppMetric.positionalRetentionRatio >= 0.14
    }

    private fun hasActiveSellerEvidence(
        m: StrikeMicrostructureMetrics,
        match: PatternDiscoveryEngine.StrikePatternMatch
    ): Boolean {
        val activePatterns = match.matchedPatterns.filter {
            it != PatternId.P6_SHORT_COVERING_CAPITULATION && it != PatternId.P7_TAIL_WING_MARGIN_HEDGE
        }
        if (activePatterns.isNotEmpty()) return true
        val hasImpulseAndRetention = m.zDeltaOi >= 1.25 && m.positionalRetentionRatio >= 0.14 && m.iprr >= 0.035
        val hasActiveDefense = m.activeDefenseSessions >= 2 && m.deltaOiLots > 0.0 && m.excessPriceDecay <= 0.0
        return hasImpulseAndRetention || hasActiveDefense
    }

    private fun buildNarrowCandidates(
        orderedMetrics: List<StrikeMicrostructureMetrics>,
        side: OptionSide,
        spot: Double,
        dte: Long,
        matchByKey: Map<Pair<OptionSide, Double>, PatternDiscoveryEngine.StrikePatternMatch>,
        featureModel: PatternDiscoveryEngine.CalibratedFeatureModel,
        proNetShortDelta: Long?,
        fiiNetShortDelta: Long?,
        proShortVol: Long?,
        fiiShortVol: Long?
    ): List<NarrowZoneCandidate> {
        if (orderedMetrics.isEmpty()) return emptyList()

        val scoredStrikes = orderedMetrics.mapNotNull { m ->
            val match = matchByKey[Pair(m.optionType, m.strikePrice)] ?: return@mapNotNull null
            val score = scoreIndividualStrike(
                metric = m,
                match = match,
                featureModel = featureModel,
                proNetShortDelta = proNetShortDelta,
                fiiNetShortDelta = fiiNetShortDelta
            )
            if (score < MIN_PRIMARY_SCORE) return@mapNotNull null

            val singleZone = buildSingleStrikeZone(
                metric = m,
                match = match,
                side = side,
                spot = spot,
                dte = dte,
                calibratedScore = score,
                proNetShortDelta = proNetShortDelta,
                fiiNetShortDelta = fiiNetShortDelta,
                proShortVol = proShortVol,
                fiiShortVol = fiiShortVol
            )
            ScoredStrikeCandidate(m, match, score, singleZone)
        }

        if (scoredStrikes.isEmpty()) return emptyList()

        val resultClusters = mutableListOf<NarrowZoneCandidate>()
        var idx = 0
        while (idx < scoredStrikes.size) {
            val run = mutableListOf(scoredStrikes[idx])
            var nextIdx = idx + 1
            while (nextIdx < scoredStrikes.size &&
                areBalancedAdjacentStrikes(run.last(), scoredStrikes[nextIdx])
            ) {
                run.add(scoredStrikes[nextIdx])
                nextIdx++
            }

            when (run.size) {
                1 -> resultClusters.add(run[0].singleStrikeZone)
                2 -> {
                    val merged = tryBuildBalancedTwoStrikeZone(
                        first = run[0],
                        second = run[1],
                        side = side,
                        spot = spot,
                        proNetShortDelta = proNetShortDelta,
                        fiiNetShortDelta = fiiNetShortDelta,
                        proShortVol = proShortVol,
                        fiiShortVol = fiiShortVol
                    )
                    if (merged != null) {
                        resultClusters.add(merged)
                    } else {
                        resultClusters.add(run[0].singleStrikeZone)
                        resultClusters.add(run[1].singleStrikeZone)
                    }
                }
                else -> {
                    resultClusters.add(
                        buildOversizedMultiStrikeCluster(
                            run = run,
                            side = side,
                            proNetShortDelta = proNetShortDelta,
                            fiiNetShortDelta = fiiNetShortDelta,
                            proShortVol = proShortVol,
                            fiiShortVol = fiiShortVol
                        )
                    )
                }
            }
            idx = nextIdx
        }

        return if (side == OptionSide.PE) {
            resultClusters.sortedByDescending { it.coreStrike }
        } else {
            resultClusters.sortedBy { it.coreStrike }
        }
    }

    private fun areBalancedAdjacentStrikes(
        first: ScoredStrikeCandidate,
        second: ScoredStrikeCandidate
    ): Boolean {
        if (abs(first.metric.strikePrice - second.metric.strikePrice) != 50.0) return false
        val m1 = first.metric
        val m2 = second.metric
        if (m1.zDeltaOi < 1.15 || m2.zDeltaOi < 1.15) return false
        if (m1.positionalRetentionRatio < 0.14 || m2.positionalRetentionRatio < 0.14) return false
        val maxScore = max(first.calibratedScore, second.calibratedScore)
        val minScore = min(first.calibratedScore, second.calibratedScore)
        return maxScore > 0.0 && minScore >= 0.78 * maxScore
    }

    private fun scoreIndividualStrike(
        metric: StrikeMicrostructureMetrics,
        match: PatternDiscoveryEngine.StrikePatternMatch,
        featureModel: PatternDiscoveryEngine.CalibratedFeatureModel,
        proNetShortDelta: Long?,
        fiiNetShortDelta: Long?
    ): Double {
        val features = PatternDiscoveryEngine.extractNormalizedFeatures(metric)
        var featureScore = 0.0
        for ((fname, fval) in features) {
            val w = featureModel.weights[fname] ?: 0.0
            featureScore += w * fval
        }

        val activePatterns = match.matchedPatterns.filter {
            it != PatternId.P6_SHORT_COVERING_CAPITULATION && it != PatternId.P7_TAIL_WING_MARGIN_HEDGE
        }
        val patternBoost = min(0.32, activePatterns.size * 0.11)
        val historicalBoost = match.historicalDefenseRate?.let { rate ->
            if (match.historicalSampleCount >= 3) (rate - 0.50).coerceIn(-0.20, 0.25) else 0.0
        } ?: 0.0

        val excessDistPct = max(0.0, abs(metric.distanceFromSpotPct) - 2.5)
        val distancePenalty = min(0.18, excessDistPct * 0.05)

        val participantBonus = if ((proNetShortDelta != null && proNetShortDelta > 0L) ||
            (fiiNetShortDelta != null && fiiNetShortDelta > 0L)
        ) {
            0.05
        } else {
            0.0
        }

        return (featureScore + patternBoost + historicalBoost + participantBonus - distancePenalty - match.falsePositivePenalty)
            .coerceIn(0.0, 1.5)
    }

    private fun buildSingleStrikeZone(
        metric: StrikeMicrostructureMetrics,
        match: PatternDiscoveryEngine.StrikePatternMatch,
        side: OptionSide,
        spot: Double,
        dte: Long,
        calibratedScore: Double,
        proNetShortDelta: Long?,
        fiiNetShortDelta: Long?,
        proShortVol: Long?,
        fiiShortVol: Long?
    ): NarrowZoneCandidate {
        val core = metric.strikePrice
        val dteScale = when {
            dte <= 1L -> 0.35
            dte <= 3L -> 0.28
            else -> 0.22
        }
        val rawCushion = metric.settlePrice * dteScale
        val backCushion = if (rawCushion <= HARD_MAX_WIDTH) {
            roundToFive(rawCushion.coerceIn(10.0, 25.0))
        } else {
            roundToFive(rawCushion)
        }
        val frontWickBuffer = if (PatternId.P3_INTRADAY_REJECTION_OI in match.matchedPatterns) 5.0 else 0.0

        var lower: Double
        var upper: Double
        if (side == OptionSide.PE) {
            upper = min(core + frontWickBuffer, roundToQuarter(spot - 5.0))
            upper = max(upper, core)
            lower = core - backCushion
        } else {
            lower = max(core - frontWickBuffer, roundToQuarter(spot + 5.0))
            lower = min(lower, core)
            upper = core + backCushion
        }

        var width = upper - lower
        if (width < PREFERRED_MIN_WIDTH) {
            if (side == OptionSide.PE) lower = upper - PREFERRED_MIN_WIDTH else upper = lower + PREFERRED_MIN_WIDTH
            width = upper - lower
        }

        val patterns = match.matchedPatterns.filter {
            it != PatternId.P6_SHORT_COVERING_CAPITULATION && it != PatternId.P7_TAIL_WING_MARGIN_HEDGE
        }.toSet()

        val (confScore, confBasis) = PatternDiscoveryEngine.computeStatisticallyJustifiedConfidence(
            trials = match.historicalSampleCount,
            defendedRate = match.historicalDefenseRate
        )

        return NarrowZoneCandidate(
            side = side,
            coreStrike = core,
            strikes = listOf(metric),
            matchedPatternIds = patterns,
            lowerBound = lower,
            upperBound = upper,
            widthPoints = width,
            calibratedScore = calibratedScore,
            strength = classifyStrength(calibratedScore, patterns.size, metric.activeDefenseSessions),
            confidenceScore = confScore,
            confidenceBasis = confBasis,
            historicalValidationText = formatHistoricalValidation(match),
            totalOiLots = max(0.0, metric.oiLots).toLong(),
            deltaOiLots = metric.deltaOiLots.toLong(),
            avgSettlePrice = metric.settlePrice,
            avgPrr = metric.positionalRetentionRatio,
            avgIprr = metric.iprr,
            avgFuturesAlignment = metric.futuresAlignment,
            maxContradictionScore = metric.contradictionScore,
            maxAtsRatio = metric.atsRatioToMedian,
            maxPersistence = metric.activeDefenseSessions,
            participantAlignmentNote = buildParticipantNote(side, proNetShortDelta, fiiNetShortDelta, proShortVol, fiiShortVol),
            rollSourceStrike = metric.oppositeRollSourceStrike,
            isCrossExpiryRolled = metric.isCrossExpiryRoll
        )
    }

    private fun tryBuildBalancedTwoStrikeZone(
        first: ScoredStrikeCandidate,
        second: ScoredStrikeCandidate,
        side: OptionSide,
        spot: Double,
        proNetShortDelta: Long?,
        fiiNetShortDelta: Long?,
        proShortVol: Long?,
        fiiShortVol: Long?
    ): NarrowZoneCandidate? {
        if (!areBalancedAdjacentStrikes(first, second)) return null

        val m1 = first.metric
        val m2 = second.metric
        val dominant = if (first.calibratedScore >= second.calibratedScore) first else second
        val highStrike = max(m1.strikePrice, m2.strikePrice)
        val lowStrike = min(m1.strikePrice, m2.strikePrice)

        if (side == OptionSide.PE && highStrike >= spot) return null
        if (side == OptionSide.CE && lowStrike <= spot) return null

        val width = highStrike - lowStrike
        if (width > HARD_MAX_WIDTH) return null

        val combinedPatterns = (first.match.matchedPatterns + second.match.matchedPatterns).filter {
            it != PatternId.P6_SHORT_COVERING_CAPITULATION && it != PatternId.P7_TAIL_WING_MARGIN_HEDGE
        }.toSet()

        val combinedScore = ((first.calibratedScore + second.calibratedScore) / 2.0 + 0.06).coerceIn(0.0, 1.5)
        val (confScore, confBasis) = PatternDiscoveryEngine.computeStatisticallyJustifiedConfidence(
            trials = dominant.match.historicalSampleCount,
            defendedRate = dominant.match.historicalDefenseRate
        )

        return NarrowZoneCandidate(
            side = side,
            coreStrike = dominant.metric.strikePrice,
            strikes = listOf(m1, m2).sortedBy { it.strikePrice },
            matchedPatternIds = combinedPatterns,
            lowerBound = lowStrike,
            upperBound = highStrike,
            widthPoints = width,
            calibratedScore = combinedScore,
            strength = classifyStrength(
                combinedScore,
                combinedPatterns.size,
                max(m1.activeDefenseSessions, m2.activeDefenseSessions)
            ),
            confidenceScore = confScore,
            confidenceBasis = confBasis,
            historicalValidationText = formatHistoricalValidation(dominant.match),
            totalOiLots = (max(0.0, m1.oiLots) + max(0.0, m2.oiLots)).toLong(),
            deltaOiLots = (m1.deltaOiLots + m2.deltaOiLots).toLong(),
            avgSettlePrice = (m1.settlePrice + m2.settlePrice) / 2.0,
            avgPrr = (m1.positionalRetentionRatio + m2.positionalRetentionRatio) / 2.0,
            avgIprr = (m1.iprr + m2.iprr) / 2.0,
            avgFuturesAlignment = (m1.futuresAlignment + m2.futuresAlignment) / 2.0,
            maxContradictionScore = max(m1.contradictionScore, m2.contradictionScore),
            maxAtsRatio = max(m1.atsRatioToMedian, m2.atsRatioToMedian),
            maxPersistence = max(m1.activeDefenseSessions, m2.activeDefenseSessions),
            participantAlignmentNote = buildParticipantNote(side, proNetShortDelta, fiiNetShortDelta, proShortVol, fiiShortVol),
            rollSourceStrike = dominant.metric.oppositeRollSourceStrike,
            isCrossExpiryRolled = m1.isCrossExpiryRoll || m2.isCrossExpiryRoll
        )
    }

    private fun buildOversizedMultiStrikeCluster(
        run: List<ScoredStrikeCandidate>,
        side: OptionSide,
        proNetShortDelta: Long?,
        fiiNetShortDelta: Long?,
        proShortVol: Long?,
        fiiShortVol: Long?
    ): NarrowZoneCandidate {
        val dominant = run.maxBy { it.calibratedScore }
        val sortedMetrics = run.map { it.metric }.sortedBy { it.strikePrice }
        val lowStrike = sortedMetrics.first().strikePrice
        val highStrike = sortedMetrics.last().strikePrice
        val combinedPatterns = run.flatMap { it.match.matchedPatterns }.filter {
            it != PatternId.P6_SHORT_COVERING_CAPITULATION && it != PatternId.P7_TAIL_WING_MARGIN_HEDGE
        }.toSet()

        return NarrowZoneCandidate(
            side = side,
            coreStrike = dominant.metric.strikePrice,
            strikes = sortedMetrics,
            matchedPatternIds = combinedPatterns,
            lowerBound = lowStrike,
            upperBound = highStrike,
            widthPoints = highStrike - lowStrike,
            calibratedScore = run.map { it.calibratedScore }.average(),
            strength = ZoneEvidenceStrength.INCONCLUSIVE,
            confidenceScore = null,
            confidenceBasis = "Rejected — oversized multi-strike cluster (${String.format("%.1f", highStrike - lowStrike)} pts > 50.0 pt ceiling)",
            historicalValidationText = formatHistoricalValidation(dominant.match),
            totalOiLots = sortedMetrics.sumOf { max(0.0, it.oiLots).toLong() },
            deltaOiLots = sortedMetrics.sumOf { it.deltaOiLots.toLong() },
            avgSettlePrice = sortedMetrics.map { it.settlePrice }.average(),
            avgPrr = sortedMetrics.map { it.positionalRetentionRatio }.average(),
            avgIprr = sortedMetrics.map { it.iprr }.average(),
            avgFuturesAlignment = sortedMetrics.map { it.futuresAlignment }.average(),
            maxContradictionScore = sortedMetrics.maxOf { it.contradictionScore },
            maxAtsRatio = sortedMetrics.maxOf { it.atsRatioToMedian },
            maxPersistence = sortedMetrics.maxOf { it.activeDefenseSessions },
            participantAlignmentNote = buildParticipantNote(side, proNetShortDelta, fiiNetShortDelta, proShortVol, fiiShortVol),
            rollSourceStrike = dominant.metric.oppositeRollSourceStrike,
            isCrossExpiryRolled = sortedMetrics.any { it.isCrossExpiryRoll }
        )
    }

    private fun classifyStrength(
        score: Double,
        patternCount: Int,
        activeDefenseSessions: Int
    ): ZoneEvidenceStrength = when {
        score >= 0.72 && (patternCount >= 2 || activeDefenseSessions >= 2) -> ZoneEvidenceStrength.STRONG
        score >= 0.50 -> ZoneEvidenceStrength.MODERATE
        else -> ZoneEvidenceStrength.WEAK
    }

    private fun formatHistoricalValidation(match: PatternDiscoveryEngine.StrikePatternMatch): String {
        val n = match.historicalSampleCount
        val h = match.historicalHoldRate
        val r = match.historicalReversalRate
        val b = match.historicalBreakRate
        if (n <= 0 || h == null || r == null || b == null) {
            return "No prior triggers for this exact pattern combination in the 22-session window."
        }
        return "Across $n distinct historical observation(s): held ${(h * 100).toInt()}%, probed & reversed ${(r * 100).toInt()}%, broke ${(b * 100).toInt()}%."
    }

    private fun toFootprintZone(
        role: ZoneRole,
        candidate: NarrowZoneCandidate?,
        spot: Double,
        dte: Long
    ): FootprintZone {
        val sideText = if (role == ZoneRole.S1 || role == ZoneRole.S2) "below" else "above"
        val defaultSide = if (role == ZoneRole.S1 || role == ZoneRole.S2) OptionSide.PE else OptionSide.CE

        if (candidate != null && candidate.widthPoints > HARD_MAX_WIDTH) {
            val strikesStr = candidate.strikes.joinToString(" + ") { "${it.strikePrice.toInt()} ${it.optionType}" }
            val patternNames = if (candidate.matchedPatternIds.isNotEmpty()) {
                candidate.matchedPatternIds.map { "${it.code}: ${it.title}" }
            } else {
                listOf("Active Normalized OI Impulse & IPRR")
            }
            val rejectionExplanation = "Rejected oversized candidate anchored near ${candidate.coreStrike.toInt()} ($strikesStr): " +
                "calculated natural width ${String.format("%.1f", candidate.widthPoints)} pts " +
                "(${formatRange(candidate.lowerBound, candidate.upperBound)}) exceeds strict 50.0-point precision ceiling; " +
                "not artificially shrunk."

            return FootprintZone(
                role = role,
                isEstablished = false,
                coreStrike = null,
                lowerBoundary = null,
                upperBoundary = null,
                widthPoints = null,
                strength = ZoneEvidenceStrength.INCONCLUSIVE,
                confidenceScore = null,
                confidenceBasis = "Inconclusive — candidate width ${String.format("%.1f", candidate.widthPoints)} pts exceeded 50.0-point hard cap (not shrunk)",
                relevantStrikes = candidate.strikes.map { it.strikePrice },
                optionSide = candidate.side,
                supportingPatterns = patternNames,
                sellerFootprintExplanation = rejectionExplanation,
                historicalValidationResult = candidate.historicalValidationText,
                totalOiLots = candidate.totalOiLots,
                deltaOiLots = candidate.deltaOiLots,
                settlePriceAvg = candidate.avgSettlePrice,
                positionalRetentionRatio = candidate.avgPrr,
                atsRatio = candidate.maxAtsRatio,
                persistenceSessions = candidate.maxPersistence,
                participantAlignment = candidate.participantAlignmentNote,
                validationSummary = rejectionExplanation,
                session22TestResult = "INCONCLUSIVE — oversized candidate (${String.format("%.1f", candidate.widthPoints)} pts > 50.0 pts) rejected without artificial shrinking.",
                selectionReason = rejectionExplanation,
                iprr = candidate.avgIprr,
                futuresAlignment = candidate.avgFuturesAlignment,
                contradictionScore = candidate.maxContradictionScore
            )
        }

        if (candidate == null) {
            return FootprintZone(
                role = role,
                isEstablished = false,
                coreStrike = null,
                lowerBoundary = null,
                upperBoundary = null,
                widthPoints = null,
                strength = ZoneEvidenceStrength.INCONCLUSIVE,
                confidenceScore = null,
                confidenceBasis = "Inconclusive — no statistically justified zone within 50-point limit",
                relevantStrikes = emptyList(),
                optionSide = defaultSide,
                supportingPatterns = emptyList(),
                sellerFootprintExplanation = "No concentrated seller footprint satisfied narrow-zone (<= 50 pts) evidence criteria $sideText NIFTY close (${String.format("%.2f", spot)}).",
                historicalValidationResult = "Not applicable — zone marked INCONCLUSIVE rather than forcing an unsubstantiated or overly wide range.",
                totalOiLots = 0L,
                deltaOiLots = 0L,
                settlePriceAvg = 0.0,
                positionalRetentionRatio = 0.0,
                atsRatio = 0.0,
                persistenceSessions = 0,
                participantAlignment = "Insufficient strike concentration $sideText NIFTY close (${String.format("%.2f", spot)}).",
                validationSummary = "No validated pattern cluster satisfied narrow-zone precision rules for ${role.code}.",
                session22TestResult = "INCONCLUSIVE — not forced.",
                selectionReason = "No credible seller footprint cluster could be isolated within 50 points $sideText ${String.format("%.2f", spot)}."
            )
        }

        val strikesStr = candidate.strikes.joinToString(" + ") { "${it.strikePrice.toInt()} ${it.optionType}" }
        val patternNames = if (candidate.matchedPatternIds.isNotEmpty()) {
            candidate.matchedPatternIds.map { "${it.code}: ${it.title}" }
        } else {
            listOf("Active Normalized OI Impulse (Z >= 1.25) & IPRR >= 0.035")
        }

        val footprintExplanation = buildString {
            append("${candidate.side} seller concentration anchored at core strike ${candidate.coreStrike.toInt()} ")
            append("(${candidate.totalOiLots} lots OI, ΔOI ${if (candidate.deltaOiLots >= 0) "+${candidate.deltaOiLots}" else "${candidate.deltaOiLots}"} lots, ")
            append("IPRR ${String.format("%.3f", candidate.avgIprr)}, PRR ${String.format("%.2f", candidate.avgPrr)}, ATS ${String.format("%.2f", candidate.maxAtsRatio)}x")
            if (candidate.maxPersistence > 0) {
                append(", defended across ${candidate.maxPersistence} prior session(s)")
            }
            append(").")
        }

        val directionLabel = if (candidate.side == OptionSide.PE) "support" else "resistance"
        val orderLabel = if (role == ZoneRole.S1 || role == ZoneRole.R1) "Nearest" else "Secondary distinct"

        return FootprintZone(
            role = role,
            isEstablished = true,
            coreStrike = candidate.coreStrike,
            lowerBoundary = candidate.lowerBound,
            upperBoundary = candidate.upperBound,
            widthPoints = candidate.widthPoints,
            strength = candidate.strength,
            confidenceScore = candidate.confidenceScore,
            confidenceBasis = candidate.confidenceBasis,
            relevantStrikes = candidate.strikes.map { it.strikePrice },
            optionSide = candidate.side,
            supportingPatterns = patternNames,
            sellerFootprintExplanation = footprintExplanation,
            historicalValidationResult = candidate.historicalValidationText,
            totalOiLots = candidate.totalOiLots,
            deltaOiLots = candidate.deltaOiLots,
            settlePriceAvg = candidate.avgSettlePrice,
            positionalRetentionRatio = candidate.avgPrr,
            atsRatio = candidate.maxAtsRatio,
            persistenceSessions = candidate.maxPersistence,
            participantAlignment = candidate.participantAlignmentNote,
            validationSummary = candidate.historicalValidationText,
            session22TestResult = "Established on Session 22 (Contradiction ${String.format("%.2f", candidate.maxContradictionScore)} < 0.55; DTE = ${dte}d).",
            selectionReason = "$orderLabel credible $directionLabel zone relative to NIFTY close (${String.format("%.2f", spot)}), " +
                "anchored at core ${candidate.coreStrike.toInt()} ($strikesStr) with ${String.format("%.0f", candidate.widthPoints)}-pt width.",
            iprr = candidate.avgIprr,
            futuresAlignment = candidate.avgFuturesAlignment,
            contradictionScore = candidate.maxContradictionScore
        )
    }

    private fun netShortDelta(
        cur: SessionMarketData,
        prev: SessionMarketData,
        clientType: String,
        isCall: Boolean
    ): Long? {
        val cRec = cur.participantOi.firstOrNull { it.clientType.equals(clientType, ignoreCase = true) } ?: return null
        val pRec = prev.participantOi.firstOrNull { it.clientType.equals(clientType, ignoreCase = true) } ?: return null
        return if (isCall) {
            cRec.optionIndexCallShort - pRec.optionIndexCallShort
        } else {
            cRec.optionIndexPutShort - pRec.optionIndexPutShort
        }
    }

    private fun participantShortVol(
        cur: SessionMarketData,
        clientType: String,
        isCall: Boolean
    ): Long? {
        val vRec = cur.participantVolume.firstOrNull { it.clientType.equals(clientType, ignoreCase = true) } ?: return null
        return if (isCall) vRec.optionIndexCallShort else vRec.optionIndexPutShort
    }

    private fun buildParticipantNote(
        side: OptionSide,
        proDelta: Long?,
        fiiDelta: Long?,
        proShortVol: Long?,
        fiiShortVol: Long?
    ): String {
        val leg = if (side == OptionSide.CE) "Index Call Short" else "Index Put Short"
        if (proDelta == null && fiiDelta == null) {
            return "Participant-Wise OI/Volume unavailable; evaluated strictly from strike microstructure."
        }
        val proRet = if (proDelta != null && proShortVol != null && proShortVol > 0L) {
            " (ret ${(proDelta.toDouble() / proShortVol * 100).toInt()}% of vol)"
        } else ""
        val fiiRet = if (fiiDelta != null && fiiShortVol != null && fiiShortVol > 0L) {
            " (ret ${(fiiDelta.toDouble() / fiiShortVol * 100).toInt()}% of vol)"
        } else ""
        val proText = proDelta?.let { "Pro $leg: ${if (it >= 0) "+$it" else "$it"}$proRet" } ?: "Pro: N/A"
        val fiiText = fiiDelta?.let { "FII $leg: ${if (it >= 0) "+$it" else "$it"}$fiiRet" } ?: "FII: N/A"
        return "$proText | $fiiText"
    }

    private fun roundToQuarter(value: Double): Double = round(value * 4.0) / 4.0
    private fun roundToFive(value: Double): Double = round(value / 5.0) * 5.0

    private fun formatRange(low: Double, high: Double): String =
        "${String.format("%.0f", low)}–${String.format("%.0f", high)}"
}
