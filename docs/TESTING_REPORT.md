# Testing & Verification Report

## 1. Execution Environment Summary

| Capability | Status in Packaging Sandbox | Notes |
|---|---|---|
| OpenJDK 17 (`java`) | Available (`17.0.19`) | Runtime present; `javac`, `kotlinc`, and `gradle` are **not installed** in sandbox |
| JUnit 4 Execution (`./gradlew testDebugUnitTest`) | **Not Executed in Sandbox** | Toolchain unavailable; all 4 test classes audited against actual source signatures |
| Android APK Build (`./gradlew assembleDebug`) | **Not Executed in Sandbox** | Requires Android SDK 34 + Gradle environment or GitHub Actions CI |
| Static Source & Schema Audit | **Completed & Passed** | All `.kt` imports, constructors, types, braces, and test references verified |
| Live NSE Archive HTTPS | Offline in sandbox | Tested via deterministic mock & synthetic CSV/ZIP fixtures |

---

## 2. JUnit 4 Unit Test Suite Coverage (`app/src/test/java/com/nifty/sellerfootprint/`)

Four JUnit 4 test suites are included in the repository:

1. **`TradingCalendarAndExpiryTest.kt`**
   - Rejects future dates, Saturdays, Sundays, and NSE scheduled holidays (2024–2026).
   - Verifies 22-session backward trading date generation skipping holidays and weekends.
   - Verifies NSE's September 2025 shift from Thursday to Tuesday weekly expiries and backward holiday adjustments.
   - Verifies Bhavcopy contract expiry extraction, monthly expiry tagging, and nearest-expiry auto-selection.
2. **`NseParsersStorageAndHistoryTest.kt`**
   - Verifies ZIP archive extraction (`extractCsvFromZip`), UTF-8 BOM stripping, HTML error rejection, and `NIFTY` UDiFF Bhavcopy filtering.
   - Verifies `Participant-Wise OI`, `Daily Index Snapshot` (`Nifty 50` + `India VIX`), and `FOVOLT` parsing.
   - Verifies `TempStorageManager` caching, byte/file accounting, and selective deletion.
   - Verifies `AnalysisHistoryStore` persistence across temporary cache deletions.
3. **`TwentyTwoSessionAnalyticalEngineTest.kt`**
   - Verifies `MultiStrikeAnalyzer` dynamic 95% OI mass + 2.5-sigma envelope without ATM restriction.
   - Verifies `PatternDiscoveryEngine` 4-phase pipeline (`Ledger A` → `Ledger B` out-of-sample validation → `Ledger C` novel patterns → `Ledger D` combined rules → `Session 22` blind test).
   - Verifies `P6` short-covering capitulation veto and `P7` tail-wing noise exclusion.
   - Verifies `ZoneDerivationEngine` narrow, non-overlapping `S1`, `S2`, `R1`, `R2` zone boundaries.
4. **`RegressionFixesVerificationTest.kt`** *(7 targeted regression verification tests)*
   - **Test 1 (`lotSizeAndDomainModels_returnFiniteValuesWhenLotSizeUnavailable`):** Verifies post-2026 unverified lot sizes resolve to `LotResolution(0, NiftyLotSizeResolver.SOURCE_UNAVAILABLE)`, `OptionContractRecord` convenience accessors return finite `0.0` (never `NaN`/`Infinity`), and `SessionMarketData.underlyingClose` falls back to `DailyVolatilityRecord.underlyingClose`.
   - **Test 2 (`expiryResolver_tracksUnavailableLotContractsWithoutCorruptingTotalOi`):** Verifies `ExpiryResolver.resolveFromContracts()` excludes unavailable-lot contracts from `totalOpenInterestLots` and counts them in `unavailableLotSizeContracts`.
   - **Test 3 (`analysisHistoryStore_enforcesLocaleUsAndParsesLegacyNineColumnTsv`):** Verifies `analysis_history_v1.tsv` writes `Locale.US` decimals and `Locale.ENGLISH` timestamps even when `Locale.getDefault()` is `Locale.FRANCE`, while backward-compatibly parsing legacy 9-column rows containing decimal commas.
   - **Test 4 (`tempStorageManager_reportsDeterministicDeletionFailureAndPreservesHistory`):** Uses injected `fileDeleter` in `TempStorageManager` to deterministically verify partial deletion failure reporting (`success == false`, `failedFiles >= 1`, `failedDirectories >= 1`) while leaving `AnalysisHistoryStore` intact.
   - **Test 5 (`nseReportParsers_handlesBomQuotesRejectsBadParticipantHeadersAndSanitizesNonFiniteNumbers`):** Verifies BOM + quoted header parsing, rejection of malformed participant schemas, and sanitization of `NaN`/`Infinity` numeric cells.
   - **Test 6 (`tradingCalendarEngine_scalesCandidateLookbackToNinetySessions`):** Verifies `generateCandidateBackwardSessions()` scales up to `NseDataRepository.MAX_CANDIDATE_LOOKBACK_SESSIONS` (90) without hitting the old 120-day cap and returns empty for `targetCount <= 0`.
   - **Test 7 (`repositoryAndOrchestrator_preventCachePoisoningRecoverCorruptedCacheAndBoundActiveStreams`):** Verifies `NseDataRepository` validates CSV parsing before caching, self-heals pre-seeded corrupted cache entries, and `AnalysisOrchestrator` clamps `activeStreamsCount` in `0..6`.

---

## 3. Defects Identified & Fixed Across Audits

1. **Unavailable Post-2026 Lot Sizes & Non-Finite Accessors (`NiftyLotSizeResolver.kt`, `DomainModels.kt`, `ExpiryResolver.kt`):**
   - Explicitly marks unverified post-2026 dates as `UNAVAILABLE` (`lotSize = 0`), guards all lot and price calculations with `.takeIf { it.isFinite() }`, and tracks `unavailableLotSizeContracts` per expiry.
2. **Pre-Cache Validation & Self-Healing Cache Recovery (`NseDataRepository.kt`):**
   - Validates Bhavcopy and optional reports before writing to `TempStorageManager`, preventing HTML error responses from poisoning the cache, and re-fetches whenever cached files fail parsing.
3. **Locale-Safe History & Network Formatting (`AnalysisHistoryStore.kt`, `NseNetworkClient.kt`, `DomainModels.kt`):**
   - Enforces `Locale.US` / `Locale.ENGLISH` for TSV serialization, byte formatting, and NSE archive date formatting while accepting legacy comma-decimal values in `analysis_history_v1.tsv`.
4. **Tracked Recursive Deletion & Deterministic Testability (`TempStorageManager.kt`):**
   - Recursively deletes temporary report files and directories while tracking `failedFiles` and `failedDirectories` via an injectable `fileDeleter: (File) -> Boolean = { it.delete() }`.
5. **Lookback Horizon Scaling & Bounded Active-Stream Count (`TradingCalendarEngine.kt`, `AnalysisOrchestrator.kt`):**
   - Scales lookback calendar days (`maxOf(120, targetCount * 3)`) so 90 candidate sessions are always reachable, and bounds `activeStreamsCount` to `0..6` using distinct missing optional reports.
