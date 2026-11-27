package com.nifty.sellerfootprint.engine

import com.nifty.sellerfootprint.data.ExpiryResolver
import com.nifty.sellerfootprint.data.NseDataRepository
import com.nifty.sellerfootprint.data.TradingCalendarEngine
import com.nifty.sellerfootprint.model.AnalysisResult
import com.nifty.sellerfootprint.model.ExpiryOption
import com.nifty.sellerfootprint.model.ResearchAuditSummary
import java.time.LocalDate
import java.time.LocalDateTime

class AnalysisOrchestrator(
    private val repository: NseDataRepository
) {
    suspend fun executeAnalysis(
        selectedTradingDate: LocalDate,
        manualExpiryOverride: LocalDate? = null,
        onProgress: (String) -> Unit
    ): Result<AnalysisResult> {
        val datasetResult = repository.fetchTwentyTwoSessionDataset(selectedTradingDate, onProgress)
        if (datasetResult.isFailure) {
            return Result.failure(datasetResult.exceptionOrNull()!!)
        }

        val dataset = datasetResult.getOrThrow()
        val sessions = dataset.sessions
        val session22 = sessions.last()
        val session21 = sessions[sessions.size - 2]

        onProgress("Resolving actual NSE expiries from Session 22 Bhavcopy...")
        val availableExpiries = ExpiryResolver.resolveFromContracts(
            selectedTradingDate = selectedTradingDate,
            contracts = session22.contracts
        )
        if (availableExpiries.isEmpty()) {
            return Result.failure(
                IllegalStateException("No active NIFTY option expiries found in NSE Bhavcopy for $selectedTradingDate.")
            )
        }

        val chosenExpiry: ExpiryOption = if (manualExpiryOverride != null) {
            availableExpiries.firstOrNull { it.expiryDate == manualExpiryOverride }
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Selected expiry $manualExpiryOverride is not listed in NSE contracts for $selectedTradingDate."
                    )
                )
        } else {
            ExpiryResolver.selectNearestExpiry(availableExpiries)!!
        }

        onProgress("Running 22-session pattern discovery & out-of-sample validation (${chosenExpiry.expiryDate})...")
        val discovery = PatternDiscoveryEngine.runTwentyTwoSessionPipeline(
            sessions = sessions,
            targetExpiry = chosenExpiry.expiryDate
        )

        onProgress("Deriving core-anchored narrow S1, S2, R1, R2 zones (<= 50 pts)...")
        val zones = ZoneDerivationEngine.deriveNarrowZones(
            session22 = session22,
            prevSession21 = session21,
            targetExpiry = chosenExpiry.expiryDate,
            discovery = discovery
        )

        val prevClose = session21.underlyingClose
        val close = session22.underlyingClose
        val ptsChange = session22.indexSnapshot?.pointsChange
            ?: if (prevClose > 0.0) close - prevClose else 0.0
        val pctChange = session22.indexSnapshot?.percentChange
            ?: if (prevClose > 0.0) (ptsChange / prevClose) * 100.0 else 0.0

        val nearFut = session22.nearMonthFutures
        val futClose = nearFut?.effectivePrice
        val futBasis = if (futClose != null && close > 0.0) futClose - close else null
        val futOiLots = nearFut?.openInterestLotsOrNull?.toLong()

        val activeStreams = (6 - session22.missingOptionalReports.distinct().size).coerceIn(0, 6)
        val missingOptionalNote = if (session22.missingOptionalReports.isNotEmpty()) {
            " • Optional missing on Session 22: ${session22.missingOptionalReports.joinToString()}"
        } else {
            " • All 6 NSE EOD streams verified"
        }
        val statusMsg = "Verified 22 NSE sessions (${dataset.freshlyDownloadedSessions} downloaded, " +
            "${dataset.cachedSessionsUsed} cached)$missingOptionalNote"

        val lotSources = session22.contracts.map { "${it.lotSize} (${it.lotSizeSource})" }.distinct().joinToString(", ")
        val sixStreamAudit = "Streams active on Session 22: $activeStreams/6 " +
            "[1: Options Bhavcopy (${session22.contracts.size} contracts), " +
            "2: Participant OI (${session22.participantOi.size} rows), " +
            "3: Index Snapshot (${if (session22.indexSnapshot != null) "OK" else "Fallback"}), " +
            "4: FOVOLT (${if (session22.volatilityRecord != null) "OK" else "Realized SD"}), " +
            "5: Participant Volume (${session22.participantVolume.size} rows), " +
            "6: NIFTY Futures (${session22.futuresContracts.size} contracts)]"

        val audit = ResearchAuditSummary(
            sessionWindowDates = sessions.map { it.tradeDate },
            activeStrikeRangeMin = discovery.session22Envelope.envelopeMinStrike,
            activeStrikeRangeMax = discovery.session22Envelope.envelopeMaxStrike,
            totalStrikesScanned = discovery.session22Envelope.totalStrikesInExpiry,
            strikesRetainedInEnvelope = discovery.session22Envelope.retainedStrikesCount,
            featureContributions = discovery.calibratedFeatureModel.contributions,
            phase1Patterns = discovery.phase1LedgerA,
            phase2ValidationNotes = discovery.phase2ValidationNotes,
            phase2NewPatterns = discovery.phase2NovelLedgerC,
            combinedPatternSet = discovery.combinedLedgerD,
            session22MatchedPatterns = discovery.session22MatchedCodes,
            session22UnvalidatedObservations = discovery.session22UnvalidatedObservations,
            strongestZoneSummary = zones.strongestZoneSummary,
            weakestZoneSummary = zones.weakestZoneSummary,
            persistentAreasSummary = zones.persistentAreasSummary,
            newlyDetectedFootprintsSummary = zones.newlyDetectedSummary,
            shiftingPositioningSummary = zones.shiftingSummary,
            limitationsSummary = listOf(
                "Public NSE EOD reports measure net end-of-day open interest and settlement prices, not intraday order-book aggressor flags.",
                "Participant-Wise OI & Volume aggregate all index options (NIFTY, BankNifty, etc.), providing regime context rather than strike-specific attribution.",
                "Wilson 90% confidence lower bounds are only displayed when >= 5 distinct narrow-zone validation trials exist in the 22-session window; otherwise zones are marked Unrated.",
                "Near-expiry gamma acceleration (DTE <= 1) can compress intraday hold rates during macro event shocks."
            ),
            sixStreamStatusSummary = sixStreamAudit,
            lotSizeAuditSummary = "Lot size resolution: $lotSources (never hardcoded; contracts without verified lot size are excluded)."
        )

        return Result.success(
            AnalysisResult(
                selectedTradingDate = selectedTradingDate,
                selectedExpiry = chosenExpiry,
                availableExpiries = availableExpiries,
                niftyClose = close,
                niftyPointsChange = ptsChange,
                niftyPercentChange = pctChange,
                niftyHigh = session22.underlyingHigh,
                niftyLow = session22.underlyingLow,
                indiaVix = session22.indexSnapshot?.indiaVixClose,
                dataStatusMessage = statusMsg,
                lastAnalyzedTimestamp = LocalDateTime.now(TradingCalendarEngine.IST_ZONE),
                s1 = zones.s1,
                s2 = zones.s2,
                r1 = zones.r1,
                r2 = zones.r2,
                auditSummary = audit,
                futuresClose = futClose,
                futuresBasis = futBasis,
                futuresOiLots = futOiLots,
                activeStreamsCount = activeStreams
            )
        )
    }
}
