package com.nifty.sellerfootprint.data

import com.nifty.sellerfootprint.model.SessionMarketData
import com.nifty.sellerfootprint.model.TempStorageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class NseDataRepository(
    private val networkClient: NseReportFetcher,
    private val storageManager: TempStorageManager
) {

    companion object {
        const val REQUIRED_PRIOR_SESSIONS = 21
        const val REQUIRED_TOTAL_SESSIONS = 22
        const val MAX_CANDIDATE_LOOKBACK_SESSIONS = 90
    }

    data class TwentyTwoSessionDataset(
        val sessions: List<SessionMarketData>,
        val cachedSessionsUsed: Int,
        val freshlyDownloadedSessions: Int,
        val skippedNonTradingDates: List<LocalDate>
    )

    private data class LoadedSession(
        val session: SessionMarketData,
        val loadedBhavcopyFromCache: Boolean
    )

    fun getStorageStats(): TempStorageStats = storageManager.getStorageStats()

    fun deleteTemporaryFiles(): TempStorageManager.DeletionOutcome =
        storageManager.deleteAllTemporaryReports()

    suspend fun fetchTwentyTwoSessionDataset(
        selectedTradingDate: LocalDate,
        onProgress: (String) -> Unit
    ): Result<TwentyTwoSessionDataset> = withContext(Dispatchers.IO) {
        onProgress("Validating target trading date $selectedTradingDate...")
        val targetValidation = TradingCalendarEngine.validateTradingDate(selectedTradingDate)
        if (targetValidation is TradingCalendarEngine.DateValidationResult.Invalid) {
            return@withContext Result.failure(IllegalArgumentException(targetValidation.reason))
        }

        onProgress("Fetching Session 22 (6-stream NSE EOD reports) for $selectedTradingDate...")
        val targetSessionResult = loadOrFetchSingleSession(selectedTradingDate, sessionIndex = REQUIRED_TOTAL_SESSIONS)
        if (targetSessionResult.isFailure) {
            val reason = targetSessionResult.exceptionOrNull()?.message
                ?: "Required NSE F&O Bhavcopy is missing for $selectedTradingDate."
            return@withContext Result.failure(
                IllegalStateException(
                    "Cannot analyse $selectedTradingDate: $reason " +
                        "(Verify the date is not a market holiday and EOD reports have been published by NSE after 6:00 PM IST)."
                )
            )
        }

        val targetLoaded = targetSessionResult.getOrThrow()
        val candidates = TradingCalendarEngine.generateCandidateBackwardSessions(
            endDate = selectedTradingDate.minusDays(1),
            targetCount = MAX_CANDIDATE_LOOKBACK_SESSIONS
        ).reversed()

        val collectedPrior = mutableListOf<SessionMarketData>()
        val skippedDates = mutableListOf<LocalDate>()
        var cachedCount = if (targetLoaded.loadedBhavcopyFromCache) 1 else 0
        var downloadedCount = if (targetLoaded.loadedBhavcopyFromCache) 0 else 1

        for (candidateDate in candidates) {
            if (collectedPrior.size >= REQUIRED_PRIOR_SESSIONS) break
            onProgress("Loading historical session ${REQUIRED_PRIOR_SESSIONS - collectedPrior.size}/$REQUIRED_PRIOR_SESSIONS ($candidateDate)...")
            val res = loadOrFetchSingleSession(candidateDate, sessionIndex = 0)
            if (res.isSuccess) {
                val loaded = res.getOrThrow()
                collectedPrior.add(loaded.session)
                if (loaded.loadedBhavcopyFromCache) cachedCount++ else downloadedCount++
            } else {
                skippedDates.add(candidateDate)
            }
        }

        if (collectedPrior.size < REQUIRED_PRIOR_SESSIONS) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Incomplete 22-session historical window: retrieved ${collectedPrior.size + 1} of $REQUIRED_TOTAL_SESSIONS " +
                        "required genuine NSE sessions across ${candidates.size} candidate trading dates. " +
                        "Check your internet connection or verify NSE archive availability."
                )
            )
        }

        val chronological = (collectedPrior.reversed() + targetLoaded.session)
            .mapIndexed { idx, session -> session.copy(sessionIndex = idx + 1) }

        Result.success(
            TwentyTwoSessionDataset(
                sessions = chronological,
                cachedSessionsUsed = cachedCount,
                freshlyDownloadedSessions = downloadedCount,
                skippedNonTradingDates = skippedDates
            )
        )
    }

    private fun loadOrFetchSingleSession(
        tradeDate: LocalDate,
        sessionIndex: Int
    ): Result<LoadedSession> {
        val missingOptional = mutableListOf<String>()

        // Stream 1 & Stream 6: Options + Futures from UDiFF/Legacy F&O Bhavcopy
        // Validate CSV parsing BEFORE caching to prevent cache poisoning.
        var loadedBhavcopyFromCache = false
        val cachedBhavCsv = storageManager.readSessionReportIfCached(tradeDate, TempStorageManager.BHAVCOPY_FILE)
        val cachedBundle = cachedBhavCsv?.let {
            NseReportParsers.parseFoBhavcopyBundleCsv(it, tradeDate).getOrNull()
        }

        val bundle = if (cachedBundle != null) {
            loadedBhavcopyFromCache = true
            cachedBundle
        } else {
            val dl = networkClient.downloadUdiffBhavcopyCsv(tradeDate)
            if (dl.isFailure) {
                return Result.failure(dl.exceptionOrNull()!!)
            }
            val downloadedText = dl.getOrThrow()
            val parsedResult = NseReportParsers.parseFoBhavcopyBundleCsv(downloadedText, tradeDate)
            if (parsedResult.isFailure) {
                return Result.failure(parsedResult.exceptionOrNull()!!)
            }
            storageManager.saveSessionReport(tradeDate, TempStorageManager.BHAVCOPY_FILE, downloadedText)
            parsedResult.getOrThrow()
        }

        val contracts = bundle.options
        val futures = bundle.futures
        if (futures.isEmpty()) {
            missingOptional.add("NIFTY Futures (Bhavcopy IDF)")
        }

        // Stream 2: Participant-Wise Open Interest (validate before caching)
        val participantRecords = loadOptionalReport(
            tradeDate = tradeDate,
            cacheFileName = TempStorageManager.PARTICIPANT_OI_FILE,
            download = { networkClient.downloadParticipantOiCsv(tradeDate) },
            parse = { csv -> NseReportParsers.parseParticipantOiCsv(csv, tradeDate) },
            isValid = { it.isNotEmpty() }
        ) ?: run {
            missingOptional.add("Participant-Wise OI")
            emptyList()
        }

        // Stream 5: Participant-Wise Trading Volume (validate before caching)
        val participantVolRecords = loadOptionalReport(
            tradeDate = tradeDate,
            cacheFileName = TempStorageManager.PARTICIPANT_VOL_FILE,
            download = { networkClient.downloadParticipantVolumeCsv(tradeDate) },
            parse = { csv -> NseReportParsers.parseParticipantVolumeCsv(csv, tradeDate) },
            isValid = { it.isNotEmpty() }
        ) ?: run {
            missingOptional.add("Participant-Wise Trading Volume")
            emptyList()
        }

        // Stream 3: Daily Index Snapshot (validate before caching)
        val indexSnapshot = loadOptionalReport(
            tradeDate = tradeDate,
            cacheFileName = TempStorageManager.INDEX_CLOSE_FILE,
            download = { networkClient.downloadIndexSnapshotCsv(tradeDate) },
            parse = { csv -> NseReportParsers.parseIndexSnapshotCsv(csv, tradeDate) },
            isValid = { snap -> snap != null && snap.niftyClose.isFinite() && snap.niftyClose > 0.0 }
        )
        if (indexSnapshot == null) {
            missingOptional.add("Daily Index Snapshot")
        }

        // Stream 4: F&O Daily Volatility (validate before caching)
        val voltRecord = loadOptionalReport(
            tradeDate = tradeDate,
            cacheFileName = TempStorageManager.FOVOLT_FILE,
            download = { networkClient.downloadFoVolatilityCsv(tradeDate) },
            parse = { csv -> NseReportParsers.parseFoVolatilityCsv(csv, tradeDate) },
            isValid = { rec -> rec != null && rec.dailyVolatility.isFinite() && rec.dailyVolatility > 0.0 }
        )
        if (voltRecord == null) {
            missingOptional.add("F&O Daily Volatility")
        }

        val session = SessionMarketData(
            sessionIndex = sessionIndex,
            tradeDate = tradeDate,
            contracts = contracts,
            participantOi = participantRecords,
            indexSnapshot = indexSnapshot,
            volatilityRecord = voltRecord,
            missingOptionalReports = missingOptional,
            participantVolume = participantVolRecords,
            futuresContracts = futures
        )

        if (!session.underlyingClose.isFinite() || session.underlyingClose <= 0.0) {
            return Result.failure(
                IllegalStateException(
                    "NIFTY closing price missing or invalid in Index Snapshot, Options Bhavcopy, Futures Bhavcopy, and F&O Volatility report for $tradeDate."
                )
            )
        }

        return Result.success(
            LoadedSession(
                session = session,
                loadedBhavcopyFromCache = loadedBhavcopyFromCache
            )
        )
    }

    /**
     * Loads and validates an optional NSE report.
     * - If a cached copy parses validly, returns it immediately without network access.
     * - Otherwise downloads fresh text, parses it first, and caches ONLY if valid.
     * - Returns null when neither cache nor network yields a valid parsed report.
     */
    private fun <T> loadOptionalReport(
        tradeDate: LocalDate,
        cacheFileName: String,
        download: () -> Result<String>,
        parse: (String) -> T,
        isValid: (T) -> Boolean
    ): T? {
        val cached = storageManager.readSessionReportIfCached(tradeDate, cacheFileName)
        if (cached != null) {
            val parsedCached = runCatching { parse(cached) }.getOrNull()
            if (parsedCached != null && isValid(parsedCached)) {
                return parsedCached
            }
        }

        val downloaded = download().getOrNull() ?: return null
        val parsedFresh = runCatching { parse(downloaded) }.getOrNull() ?: return null
        if (!isValid(parsedFresh)) {
            return null
        }

        storageManager.saveSessionReport(tradeDate, cacheFileName, downloaded)
        return parsedFresh
    }
}
