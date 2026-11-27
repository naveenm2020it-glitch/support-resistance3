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
