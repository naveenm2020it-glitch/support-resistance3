package com.nifty.sellerfootprint

import com.nifty.sellerfootprint.data.AnalysisHistoryStore
import com.nifty.sellerfootprint.data.ExpiryResolver
import com.nifty.sellerfootprint.data.NiftyLotSizeResolver
import com.nifty.sellerfootprint.data.NseDataRepository
import com.nifty.sellerfootprint.data.NseReportFetcher
import com.nifty.sellerfootprint.data.NseReportParsers
import com.nifty.sellerfootprint.data.TempStorageManager
import com.nifty.sellerfootprint.data.TradingCalendarEngine
import com.nifty.sellerfootprint.engine.AnalysisOrchestrator
import com.nifty.sellerfootprint.engine.MultiStrikeAnalyzer
import com.nifty.sellerfootprint.engine.PatternDiscoveryEngine
import com.nifty.sellerfootprint.engine.ZoneDerivationEngine
import com.nifty.sellerfootprint.model.FuturesContractRecord
import com.nifty.sellerfootprint.model.IndexSnapshotRecord
import com.nifty.sellerfootprint.model.ParticipantOiRecord
import com.nifty.sellerfootprint.model.ZoneEvidenceStrength
import com.nifty.sellerfootprint.model.DailyVolatilityRecord
import com.nifty.sellerfootprint.model.OptionContractRecord
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.SessionMarketData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class RegressionFixesVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Test 1: Unverified post-2026 lot sizes resolve to UNAVAILABLE (lotSize = 0) without assuming 65,
    // OptionContractRecord exposes finite convenience accessors (no NaN/Infinity), and
    // SessionMarketData.underlyingClose falls back cleanly to DailyVolatilityRecord when needed.
    @Test
    fun lotSizeAndDomainModels_returnFiniteValuesWhenLotSizeUnavailable() {
        val futureTradeDate = LocalDate.of(2027, 2, 10)
        val futureExpiryDate = LocalDate.of(2027, 2, 16)

        val resolution = NiftyLotSizeResolver.resolve(reportLotQty = 0, tradeDate = futureTradeDate)
        assertEquals(0, resolution.lotSize)
        assertEquals(NiftyLotSizeResolver.SOURCE_UNAVAILABLE, resolution.source)

        val contract = buildContract(
            tradeDate = futureTradeDate,
            expiryDate = futureExpiryDate,
            strike = 25000.0,
            side = OptionSide.PE,
            oiUnits = 65_000L,
            lotSize = resolution.lotSize,
            lotSizeSource = resolution.source
        )

        assertFalse(contract.isLotSizeAvailable)
        assertNull(contract.openInterestLotsOrNull)
        assertNull(contract.changeInOiLotsOrNull)
        assertNull(contract.tradedVolumeLotsOrNull)
        assertTrue(contract.openInterestLots.isFinite())
        assertTrue(contract.changeInOiLots.isFinite())
        assertTrue(contract.tradedVolumeLots.isFinite())
        assertEquals(0.0, contract.openInterestLots, 0.0)

        val sessionWithOnlyVolClose = SessionMarketData(
            sessionIndex = 1,
            tradeDate = futureTradeDate,
            contracts = emptyList(),
            participantOi = emptyList(),
            indexSnapshot = null,
            volatilityRecord = DailyVolatilityRecord(
                tradeDate = futureTradeDate,
                underlyingClose = 25120.75,
                dailyVolatility = 0.0085,
                annualizedVolatility = 0.1624
            )
        )
        assertEquals(25120.75, sessionWithOnlyVolClose.underlyingClose, 0.001)
    }

    // Test 2: ExpiryResolver.resolveFromContracts() excludes contracts without a verified lot size
    // from totalOpenInterestLots and records them in unavailableLotSizeContracts.
    @Test
    fun expiryResolver_tracksUnavailableLotContractsWithoutCorruptingTotalOi() {
        val tradeDate = LocalDate.of(2026, 3, 9)
        val expiryDate = LocalDate.of(2026, 3, 17)

        val validContract = buildContract(
            tradeDate = tradeDate,
            expiryDate = expiryDate,
            strike = 24000.0,
            side = OptionSide.PE,
            oiUnits = 65_000L,
            lotSize = 65,
            lotSizeSource = NiftyLotSizeResolver.SOURCE_REPORT
        )
        val unavailableLotContract = buildContract(
            tradeDate = tradeDate,
            expiryDate = expiryDate,
            strike = 24100.0,
            side = OptionSide.CE,
            oiUnits = 130_000L,
            lotSize = 0,
            lotSizeSource = NiftyLotSizeResolver.SOURCE_UNAVAILABLE
        )

        val resolved = ExpiryResolver.resolveFromContracts(
            selectedTradingDate = tradeDate,
            contracts = listOf(validContract, unavailableLotContract)
        )

        assertEquals(1, resolved.size)
        val option = resolved.first()
        assertEquals(2, option.totalActiveContracts)
        assertEquals(1_000L, option.totalOpenInterestLots)
        assertEquals(1, option.unavailableLotSizeContracts)
    }

    // Test 3: AnalysisHistoryStore uses analysis_history_v1.tsv with the 9-column TSV schema,
    // writes Locale.US decimals even under comma-decimal locales, and parses legacy comma decimals.
    @Test
    fun analysisHistoryStore_enforcesLocaleUsAndParsesLegacyNineColumnTsv() = runTest {
        val filesDir = tempFolder.newFolder("files_history")
        val cacheDir = tempFolder.newFolder("cache_history")
        val historyFile = File(filesDir, "analysis_history_v1.tsv")

        val legacyNineColumnLine = listOf(
            "2026-03-06_2026-03-10",
            "2026-03-06",
            "2026-03-10",
            "24088,65",
            "23900–24000",
            "23700–23800",
            "24300–24400",
            "24500–24600",
            "06 Mar 15:45"
        ).joinToString("	")
        historyFile.writeText(legacyNineColumnLine, Charsets.UTF_8)

        val store = AnalysisHistoryStore(filesDir)
        val legacyLoaded = store.loadHistory()
        assertEquals(1, legacyLoaded.size)
        assertEquals(24088.65, legacyLoaded[0].niftyClose, 0.001)

        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val targetDate = LocalDate.of(2026, 3, 9)
            val expiryDate = LocalDate.of(2026, 3, 10)
            val repo = NseDataRepository(
                networkClient = SyntheticTwentyTwoSessionFetcher(targetDate, expiryDate),
                storageManager = TempStorageManager(cacheDir)
            )
            val result = AnalysisOrchestrator(repo).executeAnalysis(targetDate, onProgress = {}).getOrThrow()
            val updated = store.recordAnalysis(result)

            assertEquals(2, updated.size)
            assertEquals(24150.50, updated[0].niftyClose, 0.001)

            val persistedLines = historyFile.readLines(Charsets.UTF_8)
            assertEquals(2, persistedLines.size)
            val firstCols = persistedLines[0].split("	")
            assertEquals(9, firstCols.size)
            assertEquals("24150.50", firstCols[3])
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    // Test 4: Deterministic deletion-failure verification via injected fileDeleter in TempStorageManager,
    // ensuring partial failure is reported accurately and AnalysisHistoryStore in filesDir is untouched.
    @Test
    fun tempStorageManager_reportsDeterministicDeletionFailureAndPreservesHistory() = runTest {
        val cacheDir = tempFolder.newFolder("cache_locked")
        val filesDir = tempFolder.newFolder("files_protected")
        val targetDate = LocalDate.of(2026, 3, 9)
        val priorDate = LocalDate.of(2026, 3, 6)
        val expiryDate = LocalDate.of(2026, 3, 10)

        val writerStorage = TempStorageManager(cacheDir)
        val historyStore = AnalysisHistoryStore(filesDir)
        val repo = NseDataRepository(SyntheticTwentyTwoSessionFetcher(targetDate, expiryDate), writerStorage)
        val result = AnalysisOrchestrator(repo).executeAnalysis(targetDate, onProgress = {}).getOrThrow()
        historyStore.recordAnalysis(result)

        val protectedFile = File(writerStorage.getSessionDirectory(priorDate), TempStorageManager.BHAVCOPY_FILE)
        assertTrue(protectedFile.exists())

        val failingStorage = TempStorageManager(
            cacheRootDir = cacheDir,
            fileDeleter = { target ->
                if (target.absolutePath == protectedFile.absolutePath) false else target.delete()
            }
        )

        val outcome = failingStorage.deleteAllTemporaryReports()
        assertFalse(outcome.success)
        assertTrue(outcome.deletedFiles >= 21)
        assertTrue(outcome.failedFiles >= 1)
        assertTrue(outcome.failedDirectories >= 1)
        assertTrue(protectedFile.exists())

        val remainingHistory = historyStore.loadHistory()
        assertEquals(1, remainingHistory.size)
        assertEquals(targetDate, remainingHistory[0].tradingDate)
    }

    // Test 5: NseReportParsers handles UTF-8 BOM + quoted headers, rejects malformed participant CSVs,
    // and sanitizes NaN/Infinity values in Bhavcopy numeric columns.
    @Test
    fun nseReportParsers_handlesBomQuotesRejectsBadParticipantHeadersAndSanitizesNonFiniteNumbers() {
        val tradeDate = LocalDate.of(2026, 3, 9)

        val bomQuotedParticipantOi = "\uFEFF" + """
            "Client Type","Future Index Long","Future Index Short","Option Index Call Long","Option Index Put Long","Option Index Call Short","Option Index Put Short"
            "FII",1000,1200,3000,3500,4100,4600
        """.trimIndent()
        val validParticipant = NseReportParsers.parseParticipantOiCsv(bomQuotedParticipantOi, tradeDate)
        assertEquals(1, validParticipant.size)
        assertEquals("FII", validParticipant[0].clientType)
        assertEquals(4100L, validParticipant[0].optionIndexCallShort)

        val missingOptionColsParticipant = """
            Client Type,ColA,ColB,ColC,ColD,ColE
            FII,10,20,30,40,50
        """.trimIndent()
        assertTrue(NseReportParsers.parseParticipantOiCsv(missingOptionColsParticipant, tradeDate).isEmpty())
        assertTrue(NseReportParsers.parseParticipantVolumeCsv(missingOptionColsParticipant, tradeDate).isEmpty())

        val nonFiniteBhavcopy = "﻿" + """
            TradDt,FinInstrmTp,TckrSymb,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,OpnPric,HghPric,LwPric,ClsPric,PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,NewBrdLotQty
            2026-03-09,IDO,NIFTY,2026-03-10,2026-03-10,24000,PE,NaN,Infinity,60.0,68.0,75.0,24150.5,NaN,650000,130000,1300000,91000000,4000,65
        """.trimIndent()
        val bundle = NseReportParsers.parseFoBhavcopyBundleCsv(nonFiniteBhavcopy, tradeDate).getOrThrow()
        assertEquals(1, bundle.options.size)
        assertTrue(bundle.options[0].openPrice.isFinite())
        assertTrue(bundle.options[0].highPrice.isFinite())
        assertEquals(68.0, bundle.options[0].effectivePrice, 0.001)
    }

    // Test 6: TradingCalendarEngine scales candidate backward lookback up to MAX_CANDIDATE_LOOKBACK_SESSIONS (90)
    // without truncating at the old 120-calendar-day limit, and returns emptyList() for non-positive counts.
    @Test
    fun tradingCalendarEngine_scalesCandidateLookbackToNinetySessions() {
        val endDate = LocalDate.of(2026, 3, 9)

        assertTrue(TradingCalendarEngine.generateCandidateBackwardSessions(endDate, 0).isEmpty())
        assertTrue(TradingCalendarEngine.generateCandidateBackwardSessions(endDate, -3).isEmpty())

        val ninetyCandidates = TradingCalendarEngine.generateCandidateBackwardSessions(
            endDate = endDate,
            targetCount = NseDataRepository.MAX_CANDIDATE_LOOKBACK_SESSIONS
        )
        assertEquals(90, ninetyCandidates.size)
        assertEquals(endDate, ninetyCandidates.last())
        assertTrue(ninetyCandidates.zipWithNext().all { (a, b) -> a.isBefore(b) })
        assertTrue(ninetyCandidates.none { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    // Test 7: NseDataRepository prevents cache poisoning on invalid downloads, recovers when pre-cached
    // Bhavcopy is corrupted, and AnalysisOrchestrator bounds activeStreamsCount cleanly in 0..6.
    @Test
    fun repositoryAndOrchestrator_preventCachePoisoningRecoverCorruptedCacheAndBoundActiveStreams() = runTest {
        val targetDate = LocalDate.of(2026, 3, 9)
        val expiryDate = LocalDate.of(2026, 3, 10)

        // Part A: Poisoned HTML responses must not be cached
        val poisonCacheDir = tempFolder.newFolder("cache_poison")
        val poisonStorage = TempStorageManager(poisonCacheDir)
        val poisonedFetcher = object : NseReportFetcher {
            override fun downloadUdiffBhavcopyCsv(tradeDate: LocalDate) = Result.success("<html>403 Forbidden</html>")
            override fun downloadParticipantOiCsv(tradeDate: LocalDate) = Result.success("<html>403</html>")
            override fun downloadIndexSnapshotCsv(tradeDate: LocalDate) = Result.success("<html>403</html>")
            override fun downloadFoVolatilityCsv(tradeDate: LocalDate) = Result.success("<html>403</html>")
        }
        val failedRun = AnalysisOrchestrator(NseDataRepository(poisonedFetcher, poisonStorage))
            .executeAnalysis(targetDate, onProgress = {})
        assertTrue(failedRun.isFailure)
        assertNull(poisonStorage.readSessionReportIfCached(targetDate, TempStorageManager.BHAVCOPY_FILE))
        assertEquals(0, poisonStorage.getStorageStats().fileCount)

        // Part B: Pre-seed corrupted Bhavcopy + invalid Participant OI in cache and verify self-healing
        val recoveryCacheDir = tempFolder.newFolder("cache_recovery")
        val recoveryStorage = TempStorageManager(recoveryCacheDir)
        recoveryStorage.saveSessionReport(targetDate, TempStorageManager.BHAVCOPY_FILE, "corrupted,not,valid,bhavcopy")
        recoveryStorage.saveSessionReport(targetDate, TempStorageManager.PARTICIPANT_OI_FILE, "<html>stale-html</html>")

        val validFetcherWithMissingOptional = SyntheticTwentyTwoSessionFetcher(
            targetDate = targetDate,
            expiryDate = expiryDate,
            failOptionalStreams = true
        )
        val recoveryResult = AnalysisOrchestrator(
            NseDataRepository(validFetcherWithMissingOptional, recoveryStorage)
        ).executeAnalysis(targetDate, onProgress = {})

        assertTrue(recoveryResult.isSuccess)
        val analysis = recoveryResult.getOrThrow()
        assertEquals(22, analysis.auditSummary.sessionWindowDates.size)
        assertEquals(24150.50, analysis.niftyClose, 0.001)
        // Only the Options Bhavcopy is available: the futures and four optional feeds are absent.
        assertEquals(1, analysis.activeStreamsCount)
        assertTrue(analysis.dataStatusMessage.contains("22 downloaded, 0 cached"))

        val healedBhavcopy = recoveryStorage.readSessionReportIfCached(targetDate, TempStorageManager.BHAVCOPY_FILE)
        assertNotNull(healedBhavcopy)
        assertTrue(healedBhavcopy!!.contains("NIFTY"))
    }

    // Test 8: Identical duplicate EOD option snapshots collapse into a single canonical row
    // without summing OI/volume/turnover/trades while preserving CE and PE separation.
    @Test
    fun identicalDuplicateOptionSnapshots_collapseWithoutSummingAndPreserveCePeSeparation() {
        val date = LocalDate.of(2026, 2, 10)
        val expiry = LocalDate.of(2026, 2, 17)
        val peRow = OptionContractRecord(
            tradeDate = date,
            expiryDate = expiry,
            strikePrice = 24000.0,
            optionType = OptionSide.PE,
            openPrice = 60.0,
            highPrice = 65.0,
            lowPrice = 48.0,
            closePrice = 50.0,
            prevClosePrice = 60.0,
            settlePrice = 50.0,
            underlyingPrice = 24100.0,
            openInterestUnits = 650_000L, // 10,000 lots
            changeInOiUnits = 130_000L, // 2,000 lots
            totalTradedVolumeUnits = 325_000L, // 5,000 lots
            totalTurnoverValue = 16_250_000.0,
            totalTradesExecuted = 1200L,
            lotSize = 65
        )
        val ceRow = peRow.copy(optionType = OptionSide.CE)

        val audit = MultiStrikeAnalyzer.deduplicateOptionContractsWithAudit(
            listOf(peRow, peRow.copy(), ceRow, ceRow.copy())
        )
        assertFalse(audit.hasConflicts)
        assertEquals(2, audit.deduplicatedContracts.size)

        val dedupPe = audit.deduplicatedContracts.first { it.optionType == OptionSide.PE }
        val dedupCe = audit.deduplicatedContracts.first { it.optionType == OptionSide.CE }
        assertEquals(650_000L, dedupPe.openInterestUnits)
        assertEquals(130_000L, dedupPe.changeInOiUnits)
        assertEquals(325_000L, dedupPe.totalTradedVolumeUnits)
        assertEquals(16_250_000.0, dedupPe.totalTurnoverValue, 1e-6)
        assertEquals(1200L, dedupPe.totalTradesExecuted)
        assertEquals(650_000L, dedupCe.openInterestUnits)

        // ExpiryResolver must also not sum identical duplicate rows
        val expiries = ExpiryResolver.resolveFromContracts(date, listOf(peRow, peRow.copy(), ceRow))
        assertEquals(1, expiries.size)
        assertEquals(2, expiries.first().totalActiveContracts)
        assertEquals(20_000L, expiries.first().totalOpenInterestLots)
    }

    // Test 9: Conflicting duplicate EOD option snapshots for the same contract key are quarantined
    // without summing, averaging, or picking by higher OI/volume/trades.
    @Test
    fun conflictingDuplicateOptionSnapshots_quarantinedWithoutSummingAveragingOrPicking() {
        val date = LocalDate.of(2026, 2, 10)
        val expiry = LocalDate.of(2026, 2, 17)
        val rowA = OptionContractRecord(
            tradeDate = date,
            expiryDate = expiry,
            strikePrice = 24000.0,
            optionType = OptionSide.PE,
            openPrice = 60.0,
            highPrice = 65.0,
            lowPrice = 48.0,
            closePrice = 50.0,
            prevClosePrice = 60.0,
            settlePrice = 50.0,
            underlyingPrice = 24100.0,
            openInterestUnits = 650_000L,
            changeInOiUnits = 130_000L,
            totalTradedVolumeUnits = 325_000L,
            totalTurnoverValue = 16_250_000.0,
            totalTradesExecuted = 1200L,
            lotSize = 65
        )
        // Conflicting duplicate row for same (tradeDate, expiryDate, strikePrice, optionType)
        val rowB = rowA.copy(
            openInterestUnits = 910_000L,
            changeInOiUnits = 260_000L,
            totalTradedVolumeUnits = 650_000L,
            totalTurnoverValue = 32_500_000.0,
            totalTradesExecuted = 2500L
        )
        val cleanCe = rowA.copy(optionType = OptionSide.CE)

        val audit = MultiStrikeAnalyzer.deduplicateOptionContractsWithAudit(listOf(rowA, rowB, cleanCe))
        assertTrue(audit.hasConflicts)
        assertEquals(1, audit.conflictingKeys.size)
        assertTrue(
            audit.conflictingKeys.contains(
                MultiStrikeAnalyzer.OptionContractKey(date, expiry, 24000.0, OptionSide.PE)
            )
        )
        // Conflicting 24000 PE is quarantined (neither picked, summed, nor averaged); clean 24000 CE remains intact
        assertEquals(1, audit.deduplicatedContracts.size)
        assertEquals(OptionSide.CE, audit.deduplicatedContracts.first().optionType)
    }

    // Test 10: Identical duplicate futures collapse without summing; conflicting futures produce
    // neutral/inconclusive 0.0 directional flow.
    @Test
    fun identicalAndConflictingFuturesSnapshots_handledSafelyWithNeutralFlowOnConflict() {
        val d1 = LocalDate.of(2026, 2, 10)
        val d2 = LocalDate.of(2026, 2, 11)
        val exp = LocalDate.of(2026, 2, 26)

        val prevFut = FuturesContractRecord(
            tradeDate = d1,
            expiryDate = exp,
            openPrice = 24000.0,
            highPrice = 24060.0,
            lowPrice = 23980.0,
            closePrice = 24050.0,
            prevClosePrice = 24000.0,
            settlePrice = 24050.0,
            underlyingPrice = 24030.0,
            openInterestUnits = 13_000_000L,
            changeInOiUnits = 325_000L,
            totalTradedVolumeUnits = 3_250_000L,
            totalTradesExecuted = 15_000L,
            lotSize = 65
        )
        val curFutA = prevFut.copy(
            tradeDate = d2,
            closePrice = 23920.0,
            prevClosePrice = 24050.0,
            settlePrice = 23920.0,
            underlyingPrice = 23910.0,
            openInterestUnits = 14_300_000L,
            changeInOiUnits = 1_300_000L
        )
        val curFutConflicting = curFutA.copy(
            openInterestUnits = 11_700_000L,
            changeInOiUnits = -1_300_000L
        )

        // Identical duplicate futures collapse without summing
        val identicalAudit = MultiStrikeAnalyzer.deduplicateFuturesContractsWithAudit(listOf(curFutA, curFutA.copy()))
        assertFalse(identicalAudit.hasConflicts)
        assertEquals(1, identicalAudit.deduplicatedContracts.size)
        assertEquals(14_300_000L, identicalAudit.deduplicatedContracts.first().openInterestUnits)
        assertEquals(1_300_000L, identicalAudit.deduplicatedContracts.first().changeInOiUnits)

        val prevSession = SessionMarketData(
            sessionIndex = 1,
            tradeDate = d1,
            contracts = emptyList(),
            participantOi = emptyList(),
            indexSnapshot = IndexSnapshotRecord(d1, 24000.0, 24060.0, 23980.0, 24030.0, 30.0, 0.12, 13.0),
            volatilityRecord = DailyVolatilityRecord(d1, 24030.0, 0.008, 0.15),
            futuresContracts = listOf(prevFut)
        )
        val cleanCurSession = prevSession.copy(
            sessionIndex = 2,
            tradeDate = d2,
            indexSnapshot = IndexSnapshotRecord(d2, 24020.0, 24030.0, 23900.0, 23910.0, -120.0, -0.50, 14.0),
            futuresContracts = listOf(curFutA, curFutA.copy())
        )
        val cleanFlow = MultiStrikeAnalyzer.computeFuturesDirectionalFlow(cleanCurSession, prevSession)
        assertTrue("Clean short-buildup futures flow should be non-zero bearish", cleanFlow < 0.0)

        // Conflicting duplicate futures snapshots must produce neutral/inconclusive 0.0 directional flow
        val conflictedCurSession = cleanCurSession.copy(
            futuresContracts = listOf(curFutA, curFutConflicting)
        )
        val conflictedFlow = MultiStrikeAnalyzer.computeFuturesDirectionalFlow(conflictedCurSession, prevSession)
        assertEquals(0.0, conflictedFlow, 1e-9)
    }

    // Test 11: Conflicting duplicate rows on Session 22 invalidate CE resistance and PE support
    // zones independently rather than poisoning the clean opposite side.
    @Test
    fun conflictingTargetExpiryZones_invalidateCeAndPeIndependently() {
        val start = LocalDate.of(2026, 2, 1)
        val expiry = LocalDate.of(2026, 3, 10)
        val sessions = (0 until 22).map { idx ->
            val date = start.plusDays(idx.toLong())
            val spot = 24100.0
            val contracts = mutableListOf<OptionContractRecord>()
            for (k in listOf(23700.0, 23900.0, 24000.0, 24100.0, 24300.0, 24500.0)) {
                val peOi = if (k == 23900.0) 14000L else if (k == 23700.0) 12500L else 2200L
                val peDelta = if (k == 23900.0) 3400L else if (k == 23700.0) 2800L else 150L
                val ceOi = if (k == 24300.0) 15000L else if (k == 24500.0) 13000L else 2100L
                val ceDelta = if (k == 24300.0) 3600L else if (k == 24500.0) 2900L else 140L

                contracts.add(
                    OptionContractRecord(
                        tradeDate = date, expiryDate = expiry, strikePrice = k, optionType = OptionSide.PE,
                        openPrice = 60.0, highPrice = 65.0, lowPrice = 45.0, closePrice = 48.0,
                        prevClosePrice = 62.0, settlePrice = 48.0, underlyingPrice = spot,
                        openInterestUnits = peOi * 65L, changeInOiUnits = peDelta * 65L,
                        totalTradedVolumeUnits = 9000L * 65L, totalTurnoverValue = 25_000_000.0,
                        totalTradesExecuted = 400L, lotSize = 65
                    )
                )
                contracts.add(
                    OptionContractRecord(
                        tradeDate = date, expiryDate = expiry, strikePrice = k, optionType = OptionSide.CE,
                        openPrice = 58.0, highPrice = 63.0, lowPrice = 44.0, closePrice = 46.0,
                        prevClosePrice = 60.0, settlePrice = 46.0, underlyingPrice = spot,
                        openInterestUnits = ceOi * 65L, changeInOiUnits = ceDelta * 65L,
                        totalTradedVolumeUnits = 9000L * 65L, totalTurnoverValue = 25_000_000.0,
                        totalTradesExecuted = 400L, lotSize = 65
                    )
                )
            }
            SessionMarketData(
                sessionIndex = idx + 1,
                tradeDate = date,
                contracts = contracts,
                participantOi = listOf(
                    ParticipantOiRecord(date, "Pro", 300_000L, 300_000L, 650_000L, 650_000L),
                    ParticipantOiRecord(date, "FII", 250_000L, 250_000L, 650_000L, 650_000L)
                ),
                indexSnapshot = IndexSnapshotRecord(date, 24060.0, 24140.0, 24040.0, spot, 35.0, 0.15, 13.4),
                volatilityRecord = DailyVolatilityRecord(date, spot, 0.0085, 0.1624)
            )
        }

        // Case A: Inject conflicting duplicate on Session 22 at 23900 PE -> S1/S2 INCONCLUSIVE, R1/R2 still established
        val s22 = sessions[21]
        val orig23900Pe = s22.contracts.first { it.strikePrice == 23900.0 && it.optionType == OptionSide.PE }
        val conflicting23900Pe = orig23900Pe.copy(
            openInterestUnits = orig23900Pe.openInterestUnits + 200_000L,
            changeInOiUnits = -100_000L
        )
        val peConflictSessions = sessions.toMutableList().apply {
            this[21] = s22.copy(contracts = s22.contracts + conflicting23900Pe)
        }
        val peDiscovery = PatternDiscoveryEngine.runTwentyTwoSessionPipeline(peConflictSessions, expiry)
        val peConflictZones = ZoneDerivationEngine.deriveNarrowZones(
            session22 = peConflictSessions[21],
            prevSession21 = peConflictSessions[20],
            targetExpiry = expiry,
            discovery = peDiscovery
        )

        assertFalse("Conflicting PE must invalidate S1", peConflictZones.s1.isEstablished)
        assertFalse("Conflicting PE must invalidate S2", peConflictZones.s2.isEstablished)
        assertEquals(ZoneEvidenceStrength.INCONCLUSIVE, peConflictZones.s1.strength)
        assertEquals(ZoneEvidenceStrength.INCONCLUSIVE, peConflictZones.s2.strength)
        assertTrue("Clean CE resistance R1 must remain independently established", peConflictZones.r1.isEstablished)
        assertTrue("Clean CE resistance R2 must remain independently established", peConflictZones.r2.isEstablished)

        // Case B: Inject conflicting duplicate on Session 22 at 24300 CE -> R1/R2 INCONCLUSIVE, S1/S2 still established
        val orig24300Ce = s22.contracts.first { it.strikePrice == 24300.0 && it.optionType == OptionSide.CE }
        val conflicting24300Ce = orig24300Ce.copy(
            openInterestUnits = orig24300Ce.openInterestUnits + 250_000L,
            changeInOiUnits = -120_000L
        )
        val ceConflictSessions = sessions.toMutableList().apply {
            this[21] = s22.copy(contracts = s22.contracts + conflicting24300Ce)
        }
        val ceDiscovery = PatternDiscoveryEngine.runTwentyTwoSessionPipeline(ceConflictSessions, expiry)
        val ceConflictZones = ZoneDerivationEngine.deriveNarrowZones(
            session22 = ceConflictSessions[21],
            prevSession21 = ceConflictSessions[20],
            targetExpiry = expiry,
            discovery = ceDiscovery
        )

        assertTrue("Clean PE support S1 must remain independently established", ceConflictZones.s1.isEstablished)
        assertTrue("Clean PE support S2 must remain independently established", ceConflictZones.s2.isEstablished)
        assertFalse("Conflicting CE must invalidate R1", ceConflictZones.r1.isEstablished)
        assertFalse("Conflicting CE must invalidate R2", ceConflictZones.r2.isEstablished)
        assertEquals(ZoneEvidenceStrength.INCONCLUSIVE, ceConflictZones.r1.strength)
        assertEquals(ZoneEvidenceStrength.INCONCLUSIVE, ceConflictZones.r2.strength)
    }

    // Test 12: Earlier-session conflicting duplicates break multi-session persistence/active-defense
    // streaks cleanly and propagate in allSessionsConflictingOptionKeys.
    @Test
    fun earlierSessionConflict_propagatesAndQuarantinesHistoricalEvidenceAcrossTwentyTwoSessions() {
        val start = LocalDate.of(2026, 2, 1)
        val expiry = LocalDate.of(2026, 3, 10)
        val sessions = (0 until 22).map { idx ->
            val date = start.plusDays(idx.toLong())
            val spot = 24100.0
            val contracts = mutableListOf<OptionContractRecord>()
            for (k in listOf(23700.0, 23900.0, 24100.0, 24300.0, 24500.0)) {
                val peOi = if (k == 23900.0) 14000L else 2200L
                val peDelta = if (k == 23900.0) 3400L else 150L
                val ceOi = if (k == 24300.0) 15000L else 2100L
                val ceDelta = if (k == 24300.0) 3600L else 140L

                contracts.add(
                    OptionContractRecord(
                        tradeDate = date, expiryDate = expiry, strikePrice = k, optionType = OptionSide.PE,
                        openPrice = 60.0, highPrice = 65.0, lowPrice = 45.0, closePrice = 48.0,
                        prevClosePrice = 62.0, settlePrice = 48.0, underlyingPrice = spot,
                        openInterestUnits = peOi * 65L, changeInOiUnits = peDelta * 65L,
                        totalTradedVolumeUnits = 9000L * 65L, totalTurnoverValue = 25_000_000.0,
                        totalTradesExecuted = 400L, lotSize = 65
                    )
                )
                contracts.add(
                    OptionContractRecord(
                        tradeDate = date, expiryDate = expiry, strikePrice = k, optionType = OptionSide.CE,
                        openPrice = 58.0, highPrice = 63.0, lowPrice = 44.0, closePrice = 46.0,
                        prevClosePrice = 60.0, settlePrice = 46.0, underlyingPrice = spot,
                        openInterestUnits = ceOi * 65L, changeInOiUnits = ceDelta * 65L,
                        totalTradedVolumeUnits = 9000L * 65L, totalTurnoverValue = 25_000_000.0,
                        totalTradesExecuted = 400L, lotSize = 65
                    )
                )
            }
            SessionMarketData(
                sessionIndex = idx + 1,
                tradeDate = date,
                contracts = contracts,
                participantOi = listOf(
                    ParticipantOiRecord(date, "Pro", 300_000L, 300_000L, 650_000L, 650_000L),
                    ParticipantOiRecord(date, "FII", 250_000L, 250_000L, 650_000L, 650_000L)
                ),
                indexSnapshot = IndexSnapshotRecord(date, 24060.0, 24140.0, 24040.0, spot, 35.0, 0.15, 13.4),
                volatilityRecord = DailyVolatilityRecord(date, spot, 0.0085, 0.1624)
            )
        }

        // Inject conflicting duplicate on Session 21 (index 20) at 23900 PE while Session 22 remains clean
        val s21 = sessions[20]
        val origS21Pe = s21.contracts.first { it.strikePrice == 23900.0 && it.optionType == OptionSide.PE }
        val conflictingS21Pe = origS21Pe.copy(
            openInterestUnits = origS21Pe.openInterestUnits + 300_000L,
            changeInOiUnits = -200_000L
        )
        val taintedHistorySessions = sessions.toMutableList().apply {
            this[20] = s21.copy(contracts = s21.contracts + conflictingS21Pe)
        }

        // Persistence & active defense on Session 22 must break at Session 21 rather than trusting conflicted Session 21 OI
        val s22Envelope = MultiStrikeAnalyzer.analyzeSessionChain(
            session = taintedHistorySessions[21],
            targetExpiry = expiry,
            priorSessions = taintedHistorySessions.take(21)
        )
        val s22Pe23900 = s22Envelope.metrics.first {
            it.strikePrice == 23900.0 && it.optionType == OptionSide.PE
        }
        assertEquals("Persistence streak must break at conflicted Session 21", 0, s22Pe23900.persistenceSessions)
        assertEquals("Active defense count must break at conflicted Session 21", 0, s22Pe23900.activeDefenseSessions)

        // PatternDiscoveryEngine must propagate earlier-session conflict in allSessionsConflictingOptionKeys
        val discovery = PatternDiscoveryEngine.runTwentyTwoSessionPipeline(taintedHistorySessions, expiry)
        assertFalse(discovery.session22Envelope.hasConflictingDuplicates)
        assertTrue(
            discovery.allSessionsConflictingOptionKeys.contains(
                MultiStrikeAnalyzer.OptionContractKey(s21.tradeDate, expiry, 23900.0, OptionSide.PE)
            )
        )
    }

    private fun buildContract(
        tradeDate: LocalDate,
        expiryDate: LocalDate,
        strike: Double,
        side: OptionSide,
        oiUnits: Long,
        lotSize: Int,
        lotSizeSource: String
    ) = OptionContractRecord(
        tradeDate = tradeDate,
        expiryDate = expiryDate,
        strikePrice = strike,
        optionType = side,
        openPrice = 75.0,
        highPrice = 82.0,
        lowPrice = 60.0,
        closePrice = 64.0,
        prevClosePrice = 76.0,
        settlePrice = 64.0,
        underlyingPrice = 24150.50,
        openInterestUnits = oiUnits,
        changeInOiUnits = 6_500L,
        totalTradedVolumeUnits = 65_000L,
        totalTurnoverValue = 4_160_000.0,
        totalTradesExecuted = 500L,
        lotSize = lotSize,
        lotSizeSource = lotSizeSource
    )

    private class SyntheticTwentyTwoSessionFetcher(
        private val targetDate: LocalDate,
        private val expiryDate: LocalDate,
        private val failOptionalStreams: Boolean = false
    ) : NseReportFetcher {

        private val validDates: Set<LocalDate> = run {
            val dates = mutableListOf<LocalDate>()
            var d = targetDate
            while (dates.size < 22) {
                if (d.dayOfWeek != DayOfWeek.SATURDAY &&
                    d.dayOfWeek != DayOfWeek.SUNDAY &&
                    d != LocalDate.of(2026, 3, 3)
                ) {
                    dates.add(d)
                }
                d = d.minusDays(1)
            }
            dates.toSet()
        }

        override fun downloadUdiffBhavcopyCsv(tradeDate: LocalDate): Result<String> {
            if (tradeDate !in validDates) return Result.failure(IOException("404 Holiday"))
            val sb = StringBuilder(
                """
                TradDt,FinInstrmTp,TckrSymb,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,OpnPric,HghPric,LwPric,ClsPric,PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,NewBrdLotQty
                """.trimIndent() + "\n"
            )
            for (k in listOf(23800, 23900, 24000, 24100, 24200, 24300, 24400)) {
                val peOi = if (k == 24000) 900_000L else 250_000L
                val peChg = if (k == 24000) 195_000L else 26_000L
                val ceOi = if (k == 24300) 950_000L else 260_000L
                val ceChg = if (k == 24300) 227_500L else 32_500L
                sb.append("$tradeDate,IDO,NIFTY,$expiryDate,$expiryDate,$k,PE,75,80,60,62,74,24150.50,62,$peOi,$peChg,975000,60450000,2500,65\n")
                sb.append("$tradeDate,IDO,NIFTY,$expiryDate,$expiryDate,$k,CE,72,78,58,60,71,24150.50,60,$ceOi,$ceChg,975000,58500000,2500,65\n")
            }
            return Result.success(sb.toString())
        }

        override fun downloadParticipantOiCsv(tradeDate: LocalDate): Result<String> {
            if (failOptionalStreams || tradeDate !in validDates) return Result.failure(IOException("404"))
            return Result.success(
                """
                Client Type,Option Index Call Long,Option Index Put Long,Option Index Call Short,Option Index Put Short
                FII,100000,100000,160000,170000
                Pro,200000,200000,260000,270000
                """.trimIndent()
            )
        }

        override fun downloadIndexSnapshotCsv(tradeDate: LocalDate): Result<String> {
            if (failOptionalStreams || tradeDate !in validDates) return Result.failure(IOException("404"))
            return Result.success(
                """
                Index Name,Open Index Value,High Index Value,Low Index Value,Closing Index Value,Points Change,Change(%)
                Nifty 50,24100.0,24190.0,24080.0,24150.50,45.0,0.19
                India VIX,13.5,13.9,13.1,13.3,-0.2,-1.48
                """.trimIndent()
            )
        }

        override fun downloadFoVolatilityCsv(tradeDate: LocalDate): Result<String> {
            if (failOptionalStreams || tradeDate !in validDates) return Result.failure(IOException("404"))
            return Result.success(
                """
                Symbol,Underlying Close Price (A),Current Day Underlying Daily Volatility (E),Applicable Annualised Volatility (F) = E * Sqrt(365)
                NIFTY,24150.50,0.0085,0.1624
                """.trimIndent()
            )
        }
    }
}
