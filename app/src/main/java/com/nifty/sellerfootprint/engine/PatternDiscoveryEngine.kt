package com.nifty.sellerfootprint.engine

import com.nifty.sellerfootprint.model.DiscoveredPatternRecord
import com.nifty.sellerfootprint.model.FeaturePredictiveContribution
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.PatternId
import com.nifty.sellerfootprint.model.PatternValidationStatus
import com.nifty.sellerfootprint.model.SessionMarketData
import com.nifty.sellerfootprint.model.StrikeBelt
import com.nifty.sellerfootprint.model.StrikeMicrostructureMetrics
import com.nifty.sellerfootprint.model.ZoneOutcomeClass
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object PatternDiscoveryEngine {

    data class DistinctValidationObservation(
        val sessionDate: LocalDate,
        val strikePrice: Double,
        val optionType: OptionSide,
        val matchedDirectionalPatterns: Set<PatternId>,
        val outcome: ZoneOutcomeClass,
        val isOutOfSample: Boolean
    )

    data class StrikePatternMatch(
        val strikePrice: Double,
        val optionType: OptionSide,
        val matchedPatterns: List<PatternId>,
        val hasVetoSignal: Boolean,
        val isFilteredTailWing: Boolean,
        val historicalSampleCount: Int,
        val historicalDefenseRate: Double?,
        val historicalHoldRate: Double?,
        val historicalReversalRate: Double?,
        val historicalBreakRate: Double?,
        val falsePositivePenalty: Double
    )

    data class CalibratedFeatureModel(
        val weights: Map<String, Double>,
        val contributions: List<FeaturePredictiveContribution>,
        val baselineDefenseRate: Double,
        val totalValidationTrials: Int
    )

    data class TwentyTwoSessionDiscoveryOutcome(
        val phase1LedgerA: List<DiscoveredPatternRecord>,
        val phase2ValidationNotes: List<String>,
        val phase2NovelLedgerC: List<DiscoveredPatternRecord>,
        val combinedLedgerD: List<DiscoveredPatternRecord>,
        val distinctValidationObservations: List<DistinctValidationObservation>,
        val calibratedFeatureModel: CalibratedFeatureModel,
        val session22Metrics: List<StrikeMicrostructureMetrics>,
        val session22Envelope: MultiStrikeAnalyzer.ChainEnvelopeResult,
        val session22StrikeMatches: List<StrikePatternMatch>,
        val session22MatchedCodes: List<String>,
        val session22UnvalidatedObservations: List<String>
    )

    private data class PatternOutcomeStat(
        val triggers: Int,
        val holds: Int,
        val reversals: Int,
        val breaks: Int
    ) {
        val evaluatedCount: Int get() = holds + reversals + breaks
        val defenseRate: Double get() = if (evaluatedCount > 0) (holds + reversals).toDouble() / evaluatedCount else 0.0
        val holdRate: Double get() = if (evaluatedCount > 0) holds.toDouble() / evaluatedCount else 0.0
        val reversalRate: Double get() = if (evaluatedCount > 0) reversals.toDouble() / evaluatedCount else 0.0
        val breakRate: Double get() = if (evaluatedCount > 0) breaks.toDouble() / evaluatedCount else 0.0
    }

    private data class FeatureTrial(
        val features: Map<String, Double>,
        val defended: Boolean
    )

    fun runTwentyTwoSessionPipeline(
        sessions: List<SessionMarketData>,
        targetExpiry: LocalDate
    ): TwentyTwoSessionDiscoveryOutcome {
        require(sessions.size >= 22) { "Expected 22 sessions, got ${sessions.size}" }

        val session22 = sessions[21]

        val sessionMetricsMap = sessions.mapIndexed { idx, s ->
            val prior = sessions.subList(0, idx)
            val activeExp = if (idx == 21) {
                targetExpiry
            } else {
                s.contracts.map { it.expiryDate }
                    .filter { !it.isBefore(s.tradeDate) }
                    .minOrNull() ?: targetExpiry
            }
            MultiStrikeAnalyzer.analyzeSessionChain(s, activeExp, prior)
        }

        val observationByKey = LinkedHashMap<Triple<LocalDate, Double, OptionSide>, DistinctValidationObservation>()

        // --- PHASE A: Sessions 1..17 Discovery (Ledger A) ---
        val phase1Trials = mutableListOf<FeatureTrial>()
        val phase1Stats = evaluatePatternsOverWindow(
            windowIndices = 0..16,
            maxOutcomeSessionIndex = 16,
            isOutOfSampleWindow = false,
            sessions = sessions,
            metricsPerSession = sessionMetricsMap,
            candidatePatterns = listOf(
                PatternId.P1_ABSORPTION_WRITING,
                PatternId.P2_MULTI_SESSION_ANCHOR,
                PatternId.P3_INTRADAY_REJECTION_OI,
                PatternId.P4_STRANGLE_SKEW_PIN,
                PatternId.P5_VERTICAL_DEFENSIVE_ROLL,
                PatternId.P6_SHORT_COVERING_CAPITULATION,
                PatternId.P7_TAIL_WING_MARGIN_HEDGE
            ),
            trialCollector = phase1Trials,
            observationStore = observationByKey
        )

        val ledgerA = phase1Stats.map { (patternId, stat) ->
            val status = when (patternId) {
                PatternId.P7_TAIL_WING_MARGIN_HEDGE -> PatternValidationStatus.FILTER_RULE
                PatternId.P6_SHORT_COVERING_CAPITULATION -> PatternValidationStatus.VETO_RULE
                else -> PatternValidationStatus.VALIDATED_TIER_1
            }
            DiscoveredPatternRecord(
                patternId = patternId,
                occurrencesInDiscovery = stat.triggers,
                inSampleHoldRate = stat.holdRate,
                inSampleReversalRate = stat.reversalRate,
                outOfSampleOccurrences = 0,
                outOfSampleHoldRate = null,
                outOfSampleReversalRate = null,
                outOfSampleBreakRate = null,
                status = status,
                evidenceSummary = "Sessions 1–17: ${stat.triggers} hit(s); narrow-zone held ${(stat.holdRate * 100).toInt()}%, " +
                    "probed & reversed ${(stat.reversalRate * 100).toInt()}%, broke ${(stat.breakRate * 100).toInt()}%."
            )
        }.filter { it.occurrencesInDiscovery > 0 }

        // --- PHASE B (Track 2A): Out-of-Sample Held/Reversed/Broke Validation on Sessions 18..21 ---
        val phase2Trials = mutableListOf<FeatureTrial>()
        val phase2ValidationStats = evaluatePatternsOverWindow(
            windowIndices = 17..20,
            maxOutcomeSessionIndex = 20,
            isOutOfSampleWindow = true,
            sessions = sessions,
            metricsPerSession = sessionMetricsMap,
            candidatePatterns = ledgerA.map { it.patternId },
            trialCollector = phase2Trials,
            observationStore = observationByKey
        )

        val validationNotes = mutableListOf<String>()
        val reconciledFromPhase1 = ledgerA.map { record ->
            val oos = phase2ValidationStats[record.patternId]
            val oosCount = oos?.evaluatedCount ?: 0
            val oosHold = if (oosCount > 0) oos!!.holdRate else null
            val oosRev = if (oosCount > 0) oos!!.reversalRate else null
            val oosBreak = if (oosCount > 0) oos!!.breakRate else null

            val finalStatus = when {
                record.patternId == PatternId.P7_TAIL_WING_MARGIN_HEDGE -> PatternValidationStatus.FILTER_RULE
                record.patternId == PatternId.P6_SHORT_COVERING_CAPITULATION -> PatternValidationStatus.VETO_RULE
                oosCount >= 2 && oosBreak != null && oosBreak > 0.45 -> PatternValidationStatus.REJECTED_OVERFITTED
                else -> PatternValidationStatus.VALIDATED_TIER_1
            }

            val note = when {
                record.patternId == PatternId.P7_TAIL_WING_MARGIN_HEDGE ->
                    "P7 (Tail-Wing Margin Hedge): Noise filter active ($oosCount tail-wing hits excluded in Sessions 18–21)."
                record.patternId == PatternId.P6_SHORT_COVERING_CAPITULATION ->
                    "P6 (Short-Covering Capitulation): Contradiction veto active ($oosCount true unwind breaches flagged in Sessions 18–21; cross-expiry rolls protected)."
                finalStatus == PatternValidationStatus.REJECTED_OVERFITTED ->
                    "${record.patternId.code} (${record.patternId.title}): REJECTED — out-of-sample break rate reached ${(oosBreak!! * 100).toInt()}% across $oosCount tests."
                oosCount > 0 ->
                    "${record.patternId.code} (${record.patternId.title}): VALIDATED — held ${(oosHold!! * 100).toInt()}%, reversed ${(oosRev!! * 100).toInt()}%, broke ${(oosBreak!! * 100).toInt()}% ($oosCount OOS tests)."
                else ->
                    "${record.patternId.code} (${record.patternId.title}): CONDITIONAL — 0 triggers in Sessions 18–21; retained with shrinkage from Phase 1 (${record.occurrencesInDiscovery} hits)."
            }
            validationNotes.add(note)

            record.copy(
                outOfSampleOccurrences = oosCount,
                outOfSampleHoldRate = oosHold,
                outOfSampleReversalRate = oosRev,
                outOfSampleBreakRate = oosBreak,
                status = finalStatus
            )
        }

        // --- PHASE B (Track 2B): Independent Novel Discovery on Sessions 18..21 (Ledger C) ---
        val novelCandidates = listOf(
            PatternId.NP1_CALENDAR_ROLLOVER_WALL,
            PatternId.NP2_VOL_EXPANSION_HARVEST,
            PatternId.NP3_GAMMA_PIN_MAGNET
        )
        val novelStats = evaluatePatternsOverWindow(
            windowIndices = 17..20,
            maxOutcomeSessionIndex = 20,
            isOutOfSampleWindow = true,
            sessions = sessions,
            metricsPerSession = sessionMetricsMap,
            candidatePatterns = novelCandidates,
            trialCollector = null,
            observationStore = observationByKey
        )

        val ledgerC = novelCandidates.mapNotNull { pid ->
            val st = novelStats[pid] ?: return@mapNotNull null
            if (st.evaluatedCount <= 0 || st.breakRate > 0.45) return@mapNotNull null
            DiscoveredPatternRecord(
                patternId = pid,
                occurrencesInDiscovery = st.triggers,
                inSampleHoldRate = st.holdRate,
                inSampleReversalRate = st.reversalRate,
                outOfSampleOccurrences = st.evaluatedCount,
                outOfSampleHoldRate = st.holdRate,
                outOfSampleReversalRate = st.reversalRate,
                outOfSampleBreakRate = st.breakRate,
                status = PatternValidationStatus.NOVEL_PHASE_B,
                evidenceSummary = "Sessions 18–21 regime signal (${st.evaluatedCount} test(s)): held ${(st.holdRate * 100).toInt()}%, " +
                    "reversed ${(st.reversalRate * 100).toInt()}%, broke ${(st.breakRate * 100).toInt()}%."
            )
        }

        // --- PHASE C: Combined Pattern Set (Ledger D) & Feature Predictive Calibration ---
        val combinedLedgerD = (
            reconciledFromPhase1.filter { it.status != PatternValidationStatus.REJECTED_OVERFITTED } +
                ledgerC
            )
        val activePatternIds = combinedLedgerD.map { it.patternId }.toSet()
        val activeDirectionalPatternIds = activePatternIds.filter {
            it != PatternId.P6_SHORT_COVERING_CAPITULATION && it != PatternId.P7_TAIL_WING_MARGIN_HEDGE
        }.toSet()

        val eligibleDistinctObservations = observationByKey.values.mapNotNull { obs ->
            val eligibleMatched = obs.matchedDirectionalPatterns.intersect(activeDirectionalPatternIds)
            if (eligibleMatched.isEmpty()) null else obs.copy(matchedDirectionalPatterns = eligibleMatched)
        }

        val featureModel = calibrateFeaturePredictiveWeights(
            inSampleTrials = phase1Trials,
            outOfSampleTrials = phase2Trials
        )

        // --- PHASE D: Session 22 Final EOD Test ---
        val s22Envelope = sessionMetricsMap[21]
        val s22Metrics = s22Envelope.metrics
        val s22MatchedCodes = linkedSetOf<String>()
        val s22UnvalidatedObservations = mutableListOf<String>()

        val strikeMatches = s22Metrics.map { m ->
            val matched = detectPatternsForStrike(
                metric = m,
                session = session22,
                allSessionMetrics = s22Metrics,
                prevSession = sessions[20]
            ).filter { it in activePatternIds }

            matched.forEach { s22MatchedCodes.add("${it.code}: ${it.title}") }

            val hasVeto = PatternId.P6_SHORT_COVERING_CAPITULATION in matched
            val isTailWing = PatternId.P7_TAIL_WING_MARGIN_HEDGE in matched

            val directionalMatchedSet = matched.filter { it in activeDirectionalPatternIds }.toSet()

            val matchingDistinctObs = if (directionalMatchedSet.isNotEmpty()) {
                eligibleDistinctObservations.filter { obs ->
                    obs.matchedDirectionalPatterns.any { it in directionalMatchedSet }
                }
            } else {
                emptyList()
            }

            val totalTrials = matchingDistinctObs.size
            val holdCount = matchingDistinctObs.count { it.outcome == ZoneOutcomeClass.HELD }
            val revCount = matchingDistinctObs.count { it.outcome == ZoneOutcomeClass.REVERSED }
            val breakCount = matchingDistinctObs.count { it.outcome == ZoneOutcomeClass.BROKE }

            val defenseRate = if (totalTrials > 0) ((holdCount + revCount).toDouble() / totalTrials).coerceIn(0.0, 1.0) else null
            val holdRate = if (totalTrials > 0) (holdCount.toDouble() / totalTrials).coerceIn(0.0, 1.0) else null
            val revRate = if (totalTrials > 0) (revCount.toDouble() / totalTrials).coerceIn(0.0, 1.0) else null
            val breakRate = if (totalTrials > 0) (breakCount.toDouble() / totalTrials).coerceIn(0.0, 1.0) else null

            var fpPenalty = 0.0
            if (breakRate != null && breakRate > 0.25) {
                fpPenalty += (breakRate - 0.25) * 1.5
            }
            if (m.zDeltaOi < 0.75 && m.excessPriceDecay >= 0.0 && !m.isCrossExpiryRoll) {
                fpPenalty += 0.35
            }
            fpPenalty += m.contradictionScore * 0.45

            if (matched.isEmpty() && abs(m.zDeltaOi) >= 2.2 && m.belt != StrikeBelt.TAIL_HEDGE) {
                s22UnvalidatedObservations.add(
                    "Unmatched Session 22 anomaly at ${m.strikePrice.toInt()} ${m.optionType}: " +
                        "Z(ΔOI)=${String.format("%.2f", m.zDeltaOi)}, IPRR=${String.format("%.3f", m.iprr)}, " +
                        "ΔOI=${m.deltaOiLots.toLong()} lots (logged separately; not treated as validated)."
                )
            }

            StrikePatternMatch(
                strikePrice = m.strikePrice,
                optionType = m.optionType,
                matchedPatterns = matched,
                hasVetoSignal = hasVeto,
                isFilteredTailWing = isTailWing,
                historicalSampleCount = totalTrials,
                historicalDefenseRate = defenseRate,
                historicalHoldRate = holdRate,
                historicalReversalRate = revRate,
                historicalBreakRate = breakRate,
                falsePositivePenalty = fpPenalty
            )
        }

        return TwentyTwoSessionDiscoveryOutcome(
            phase1LedgerA = ledgerA,
            phase2ValidationNotes = validationNotes,
            phase2NovelLedgerC = ledgerC,
            combinedLedgerD = combinedLedgerD,
            distinctValidationObservations = eligibleDistinctObservations,
            calibratedFeatureModel = featureModel,
            session22Metrics = s22Metrics,
            session22Envelope = s22Envelope,
            session22StrikeMatches = strikeMatches,
            session22MatchedCodes = s22MatchedCodes.toList(),
            session22UnvalidatedObservations = s22UnvalidatedObservations.take(5)
        )
    }

    private fun evaluatePatternsOverWindow(
        windowIndices: IntRange,
        maxOutcomeSessionIndex: Int,
        isOutOfSampleWindow: Boolean,
        sessions: List<SessionMarketData>,
        metricsPerSession: List<MultiStrikeAnalyzer.ChainEnvelopeResult>,
        candidatePatterns: List<PatternId>,
        trialCollector: MutableList<FeatureTrial>?,
        observationStore: MutableMap<Triple<LocalDate, Double, OptionSide>, DistinctValidationObservation>
    ): Map<PatternId, PatternOutcomeStat> {
        val triggersMap = mutableMapOf<PatternId, Int>()
        val holdsMap = mutableMapOf<PatternId, Int>()
        val reversalsMap = mutableMapOf<PatternId, Int>()
        val breaksMap = mutableMapOf<PatternId, Int>()

        for (idx in windowIndices) {
            val currentSession = sessions[idx]
            val prevSession = if (idx > 0) sessions[idx - 1] else null
            val nextSession = if (idx + 1 <= maxOutcomeSessionIndex && idx + 1 < sessions.size) sessions[idx + 1] else null
            val currentMetrics = metricsPerSession[idx].metrics

            for (m in currentMetrics) {
                val spot = currentSession.underlyingClose
                val relevantSide = when (m.optionType) {
                    OptionSide.CE -> m.strikePrice >= spot - 50.0
                    OptionSide.PE -> m.strikePrice <= spot + 50.0
                }
                if (!relevantSide) continue

                val detected = detectPatternsForStrike(m, currentSession, currentMetrics, prevSession)
                val outcome = if (nextSession != null) classifyNarrowZoneOutcome(m, nextSession) else null

                if (outcome != null && trialCollector != null && m.belt != StrikeBelt.TAIL_HEDGE &&
                    (m.zDeltaOi >= 0.85 || m.activeDefenseSessions >= 2 || m.isCrossExpiryRoll)
                ) {
                    trialCollector.add(
                        FeatureTrial(
                            features = extractNormalizedFeatures(m),
                            defended = outcome != ZoneOutcomeClass.BROKE
                        )
                    )
                }

                val directionalDetected = detected.filter {
                    it in candidatePatterns &&
                        it != PatternId.P6_SHORT_COVERING_CAPITULATION &&
                        it != PatternId.P7_TAIL_WING_MARGIN_HEDGE
                }.toSet()

                if (outcome != null && directionalDetected.isNotEmpty()) {
                    val obsKey = Triple(currentSession.tradeDate, m.strikePrice, m.optionType)
                    val existing = observationStore[obsKey]
                    observationStore[obsKey] = if (existing == null) {
                        DistinctValidationObservation(
                            sessionDate = currentSession.tradeDate,
                            strikePrice = m.strikePrice,
                            optionType = m.optionType,
                            matchedDirectionalPatterns = directionalDetected,
                            outcome = outcome,
                            isOutOfSample = isOutOfSampleWindow
                        )
                    } else {
                        existing.copy(
                            matchedDirectionalPatterns = existing.matchedDirectionalPatterns + directionalDetected
                        )
                    }
                }

                for (pid in detected) {
                    if (pid !in candidatePatterns) continue
                    triggersMap[pid] = (triggersMap[pid] ?: 0) + 1
                    when (outcome) {
                        ZoneOutcomeClass.HELD -> holdsMap[pid] = (holdsMap[pid] ?: 0) + 1
                        ZoneOutcomeClass.REVERSED -> reversalsMap[pid] = (reversalsMap[pid] ?: 0) + 1
                        ZoneOutcomeClass.BROKE -> breaksMap[pid] = (breaksMap[pid] ?: 0) + 1
                        null -> Unit
                    }
                }
            }
        }

        return candidatePatterns.associateWith { pid ->
            PatternOutcomeStat(
                triggers = triggersMap[pid] ?: 0,
                holds = holdsMap[pid] ?: 0,
                reversals = reversalsMap[pid] ?: 0,
                breaks = breaksMap[pid] ?: 0
            )
        }
    }

    fun classifyNarrowZoneOutcome(
        metric: StrikeMicrostructureMetrics,
        nextSession: SessionMarketData
    ): ZoneOutcomeClass {
        val narrowBuffer = min(25.0, max(10.0, metric.settlePrice * 0.35))
        return when (metric.optionType) {
            OptionSide.PE -> {
                val lowerLimit = metric.strikePrice - narrowBuffer
                when {
                    nextSession.underlyingClose < lowerLimit -> ZoneOutcomeClass.BROKE
                    nextSession.underlyingLow <= metric.strikePrice -> ZoneOutcomeClass.REVERSED
                    else -> ZoneOutcomeClass.HELD
                }
            }
            OptionSide.CE -> {
                val upperLimit = metric.strikePrice + narrowBuffer
                when {
                    nextSession.underlyingClose > upperLimit -> ZoneOutcomeClass.BROKE
                    nextSession.underlyingHigh >= metric.strikePrice -> ZoneOutcomeClass.REVERSED
                    else -> ZoneOutcomeClass.HELD
                }
            }
        }
    }

    fun extractNormalizedFeatures(m: StrikeMicrostructureMetrics): Map<String, Double> {
        val iprrNorm = (max(0.0, m.iprr) / 0.25).coerceIn(0.0, 1.0)
        val zImp = (m.zDeltaOi.coerceIn(0.0, 4.0)) / 4.0
        val absDecay = max(0.0, -m.excessPriceDecay)
        val denom = max(10.0, m.settlePrice * 0.35)
        val absNorm = (absDecay / denom).coerceIn(0.0, 1.0)
        val futNorm = ((m.futuresAlignment + 1.0) / 2.0).coerceIn(0.0, 1.0)
        val persistNorm = (m.activeDefenseSessions.coerceIn(0, 4)) / 4.0
        val atsNorm = ((m.atsRatioToMedian - 1.0).coerceIn(0.0, 1.5)) / 1.5
        val rollNorm = if (m.isPairedWithOppositeRoll || m.isCrossExpiryRoll) 1.0 else 0.0

        return mapOf(
            "Impulse Retention (IPRR)" to iprrNorm,
            "OI Impulse Z-Score" to zImp,
            "Price-OI Absorption" to absNorm,
            "Futures Flow Alignment" to futNorm,
            "Active Session Persistence" to persistNorm,
            "Institutional Block ATS" to atsNorm,
            "Vertical / Cross-Expiry Roll" to rollNorm
        )
    }

    private fun calibrateFeaturePredictiveWeights(
        inSampleTrials: List<FeatureTrial>,
        outOfSampleTrials: List<FeatureTrial>
    ): CalibratedFeatureModel {
        val domainPriors = mapOf(
            "Impulse Retention (IPRR)" to 0.24,
            "OI Impulse Z-Score" to 0.18,
            "Price-OI Absorption" to 0.19,
            "Futures Flow Alignment" to 0.12,
            "Active Session Persistence" to 0.12,
            "Institutional Block ATS" to 0.08,
            "Vertical / Cross-Expiry Roll" to 0.07
        )

        val weightedTrials = inSampleTrials.map { it to 1.0 } + outOfSampleTrials.map { it to 2.0 }
        val totalWeight = weightedTrials.sumOf { it.second }
        val baselineRate = if (totalWeight > 0.0) {
            weightedTrials.filter { it.first.defended }.sumOf { it.second } / totalWeight
        } else {
            0.5
        }

        val rawWeights = mutableMapOf<String, Double>()
        val contributions = mutableListOf<FeaturePredictiveContribution>()

        for ((fname, prior) in domainPriors) {
            var activeW = 0.0
            var activeDefW = 0.0
            var obsCount = 0

            for ((trial, w) in weightedTrials) {
                val fVal = trial.features[fname] ?: 0.0
                if (fVal >= 0.35) {
                    activeW += w
                    if (trial.defended) activeDefW += w
                    obsCount++
                }
            }

            val kappa = 8.0
            val empiricalRate = if (activeW > 0.0) activeDefW / activeW else baselineRate
            val lift = empiricalRate - baselineRate
            val shrunkWeight = (prior * kappa + max(0.02, prior + 0.5 * lift) * activeW) / (kappa + activeW)

            rawWeights[fname] = shrunkWeight
            contributions.add(
                FeaturePredictiveContribution(
                    featureName = fname,
                    calibratedWeight = shrunkWeight,
                    empiricalDefenseLift = lift,
                    sampleObservations = obsCount
                )
            )
        }

        val sumW = rawWeights.values.sum().let { if (it <= 0.0) 1.0 else it }
        val normalizedWeights = rawWeights.mapValues { it.value / sumW }
        val normalizedContributions = contributions.map {
            it.copy(calibratedWeight = normalizedWeights[it.featureName] ?: it.calibratedWeight)
        }.sortedByDescending { it.calibratedWeight }

        return CalibratedFeatureModel(
            weights = normalizedWeights,
            contributions = normalizedContributions,
            baselineDefenseRate = baselineRate,
            totalValidationTrials = inSampleTrials.size + outOfSampleTrials.size
        )
    }

    /**
     * Computes Wilson 90% one-sided lower confidence bound in [0.0, 0.99] from distinct,
     * eligible narrow-zone validation observations.
     * Strictly returns null when N < 5 so sparse data is always marked Unrated / Inconclusive.
     */
    fun computeStatisticallyJustifiedConfidence(
        trials: Int,
        defendedRate: Double?
    ): Pair<Double?, String> {
        if (trials < 5 || defendedRate == null) {
            return Pair(
                null,
                "Unrated — insufficient distinct validation sample (N = $trials < 5 required; Wilson 90% lower bound)"
            )
        }
        val n = trials.toDouble()
        val p = defendedRate.coerceIn(0.0, 1.0)
        val z = 1.2816
        val z2 = z * z
        val denom = 1.0 + z2 / n
        val center = p + z2 / (2.0 * n)
        val radius = z * sqrt((p * (1.0 - p) + z2 / (4.0 * n)) / n)
        val lowerBound = ((center - radius) / denom).coerceIn(0.0, 0.99)
        return Pair(
            lowerBound,
            "Wilson 90% lower bound = ${String.format("%.3f", lowerBound)} across N = $trials distinct narrow-zone validation observation(s) (${(p * 100).toInt()}% held/reversed)"
        )
    }

    fun detectPatternsForStrike(
        metric: StrikeMicrostructureMetrics,
        session: SessionMarketData,
        allSessionMetrics: List<StrikeMicrostructureMetrics>,
        prevSession: SessionMarketData?
    ): List<PatternId> {
        val found = mutableListOf<PatternId>()
        val spot = session.underlyingClose
        val spotDelta = if (prevSession != null) spot - prevSession.underlyingClose else 0.0

        // P7: Tail-Wing Margin Hedge (Noise Filter)
        if (metric.belt == StrikeBelt.TAIL_HEDGE && metric.settlePrice < 18.0 && metric.oiLots > 0.0) {
            found.add(PatternId.P7_TAIL_WING_MARGIN_HEDGE)
            return found
        }

        // P6: Short-Covering Capitulation (Veto Signal)
        // v3.0 Guard: Cross-expiry calendar rolls (isCrossExpiryRoll == true) are NOT capitulation vetoes!
        val spotBreachedStrike = when (metric.optionType) {
            OptionSide.CE -> spot > metric.strikePrice
            OptionSide.PE -> spot < metric.strikePrice
        }
        if (spotBreachedStrike && !metric.isCrossExpiryRoll && metric.zDeltaOi <= -1.35 && metric.priceChange > 0.0) {
            found.add(PatternId.P6_SHORT_COVERING_CAPITULATION)
        }

        // P1: Absorption Writing (Price-OI Divergence + IPRR confirmation)
        val movedTowardStrike = when (metric.optionType) {
            OptionSide.CE -> spotDelta > 0.0 && metric.strikePrice >= spot - 50.0
            OptionSide.PE -> spotDelta < 0.0 && metric.strikePrice <= spot + 50.0
        }
        if (metric.zDeltaOi >= 1.15 &&
            metric.positionalRetentionRatio >= 0.15 &&
            metric.iprr >= 0.035 &&
            ((movedTowardStrike && metric.excessPriceDecay < -1.0) || metric.priceChange <= 0.0)
        ) {
            found.add(PatternId.P1_ABSORPTION_WRITING)
        }

        // P2: Multi-Session Active Anchor Wall
        if (metric.activeDefenseSessions >= 2 &&
            metric.deltaOiLots >= 0.0 &&
            metric.positionalRetentionRatio >= 0.12 &&
            metric.oiLots > 0.0
        ) {
            found.add(PatternId.P2_MULTI_SESSION_ANCHOR)
        }

        // P3: Intraday Rejection Wick + OI Addition
        val intradayProbedAndRejected = when (metric.optionType) {
            OptionSide.CE -> session.underlyingHigh >= metric.strikePrice - (spot * 0.0020) &&
                spot < metric.strikePrice
            OptionSide.PE -> session.underlyingLow <= metric.strikePrice + (spot * 0.0020) &&
                spot > metric.strikePrice
        }
        if (intradayProbedAndRejected && metric.zDeltaOi >= 0.95 && metric.atsRatioToMedian >= 1.15) {
            found.add(PatternId.P3_INTRADAY_REJECTION_OI)
        }

        // P4: Asymmetric Strangle Skew Pin
        val hasOppositeStrongWriter = allSessionMetrics.any { other ->
            other.optionType != metric.optionType &&
                abs(other.distanceFromSpotPct) <= 2.5 &&
                other.zDeltaOi >= 1.20 &&
                other.positionalRetentionRatio >= 0.14
        }
        if (metric.zDeltaOi >= 1.20 &&
            metric.positionalRetentionRatio >= 0.14 &&
            abs(metric.distanceFromSpotPct) <= 2.5 &&
            hasOppositeStrongWriter
        ) {
            found.add(PatternId.P4_STRANGLE_SKEW_PIN)
        }

        // P5: Vertical or Cross-Expiry Defensive Roll
        if ((metric.isPairedWithOppositeRoll && metric.zDeltaOi >= 1.20) || metric.isCrossExpiryRoll) {
            found.add(PatternId.P5_VERTICAL_DEFENSIVE_ROLL)
        }

        // NP1: Monthly-to-Weekly Calendar Rollover Wall
        val dte = ChronoUnit.DAYS.between(session.tradeDate, metric.expiryDate)
        if (dte in 0..2 && ((metric.zDeltaOi >= 1.30 && metric.atsRatioToMedian >= 1.35) || metric.isCrossExpiryRoll)) {
            found.add(PatternId.NP1_CALENDAR_ROLLOVER_WALL)
        }

        // NP2: Volatility-Expansion Premium Harvest
        val curVol = session.volatilityRecord?.dailyVolatility
        val prevVol = prevSession?.volatilityRecord?.dailyVolatility
        if (curVol != null && prevVol != null && prevVol > 0.0 &&
            (curVol - prevVol) / prevVol >= 0.10 &&
            metric.belt == StrikeBelt.PRIMARY_WALL &&
            metric.zDeltaOi >= 1.10 &&
            metric.positionalRetentionRatio >= 0.14
        ) {
            found.add(PatternId.NP2_VOL_EXPANSION_HARVEST)
        }

        // NP3: Near-Expiry ATM Gamma-Pin Magnet
        if (dte in 0..1 && abs(metric.distanceFromSpotPct) <= 0.30) {
            val pairedOppositeAtSameStrike = allSessionMetrics.firstOrNull {
                it.strikePrice == metric.strikePrice && it.optionType != metric.optionType
            }
            if (pairedOppositeAtSameStrike != null &&
                metric.oiLots > 0.0 &&
                pairedOppositeAtSameStrike.oiLots > 0.0 &&
                metric.priceChange <= 0.0 &&
                pairedOppositeAtSameStrike.priceChange <= 0.0
            ) {
                found.add(PatternId.NP3_GAMMA_PIN_MAGNET)
            }
        }

        return found
    }
}
