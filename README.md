# NIFTY Seller Footprint

An Android native application (Kotlin + Jetpack Compose + Material 3) that identifies **narrow, evidence-backed NIFTY 50 option-seller support and resistance zones (`S1`, `S2`, `R1`, `R2`)** exclusively from official, free **National Stock Exchange of India (NSE)** End-of-Day (EOD) public archives across a rolling **22-trading-session research window**.

---

## Core Capabilities

- **Official Free NSE EOD Ingestion (6-Stream Pipeline, No Paid APIs):** Downloads and parses official NSE public archives:
  1. F&O UDiFF Common Bhavcopy (`BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip`, with legacy fallback) — NIFTY Options
  2. Participant-Wise Open Interest (`fao_participant_oi_DDMMYYYY.csv`)
  3. Daily Index Snapshot (`ind_close_all_DDMMYYYY.csv`)
  4. F&O Daily Volatility (`FOVOLT_DDMMYYYY.csv`)
  5. Participant-Wise Trading Volume (`fao_participant_vol_DDMMYYYY.csv`)
  6. NIFTY Futures contracts extracted from F&O Bhavcopy
- **Trading Date & Expiry Engine:** Validates NSE trading days (rejecting weekends, scheduled holidays, and future dates), auto-resolves actual listed NIFTY option expiries from Bhavcopy contracts, selects the nearest expiry by default, and allows manual expiry switching without re-downloading cached session files.
- **Verified SEBI/NSE Lot-Size Normalization:** Resolves lot sizes from Bhavcopy `NewBrdLotQty` first, then verified SEBI/NSE circulars, and explicitly marks unverified post-2026 lot sizes as `UNAVAILABLE` (`lotSize = 0`) without fabricating `NaN` or assuming stale lot sizes.
- **Dynamic Multi-Strike Chain Envelope:** Evaluates all strikes capturing **95% of cumulative CE + PE Open Interest mass** bounded by a **2.5-sigma volatility envelope**—never artificially restricted to ATM or `ATM ± 1`.
- **4-Phase 22-Session Discovery & Validation Pipeline:**
  - **Phase A (Sessions 1–17):** Discovers empirical option-seller microstructure footprints (`Ledger A`).
  - **Phase B (Sessions 18–21):** Performs out-of-sample validation on `Ledger A` (`Ledger B`) and independent regime discovery (`Ledger C`).
  - **Phase C:** Synthesizes validated patterns (`Ledger D`), rejects overfitted signals, and activates `P6` short-covering capitulation vetoes and `P7` tail-wing margin filters.
  - **Phase D (Session 22):** Applies `Ledger D` blindly to the selected trading session to derive narrow premium-cushioned `S1`, `S2`, `R1`, and `R2` zones.
- **Single-Screen Architecture & Safe Temporary Storage:** All controls, market summary, `S1`/`S2`/`R1`/`R2` cards, expandable audit trail, locale-safe saved analysis history (`analysis_history_v1.tsv`), settings/methodology guide, and one-tap **Delete Temporary Files** controls with tracked deletion verification live on one clean Material 3 screen.

---

## Project Structure

```text
NiftySellerFootprint/
├── .github/workflows/build-apk.yml          # GitHub Actions CI (JUnit 4 tests + Debug APK)
├── .gitignore                               # Git ignore rules
├── build.gradle.kts                         # Root Gradle plugin configuration (AGP 8.5.2, Kotlin 1.9.24)
├── settings.gradle.kts                      # Repository & module configuration
├── gradle.properties                        # JVM & AndroidX flags
├── gradlew / gradlew.bat                    # Gradle wrapper scripts (Gradle 8.7)
├── gradle/wrapper/gradle-wrapper.properties # Gradle distribution metadata
├── docs/
│   ├── RESEARCH_METHODOLOGY.md              # 22-session pattern discovery & zone derivation math
│   ├── NSE_DATA_SOURCES.md                  # Official NSE archive URLs, CSV schemas & caching rules
│   ├── TESTING_REPORT.md                    # Verification results, regression audit & test matrix
│   ├── TROUBLESHOOTING.md                   # Network, holiday, EOD timing & build troubleshooting
│   └── PROJECT_INTEGRITY_CHECKLIST.md       # Recursive file, dependency & build readiness audit
└── app/
    ├── build.gradle.kts                     # App module config (minSdk 26, targetSdk 34, Compose, OkHttp)
    ├── proguard-rules.pro                   # ProGuard / R8 rules
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/nifty/sellerfootprint/
        │   │   ├── MainActivity.kt
        │   │   ├── model/DomainModels.kt
        │   │   ├── data/
        │   │   │   ├── NiftyLotSizeResolver.kt
        │   │   │   ├── TradingCalendarEngine.kt
        │   │   │   ├── ExpiryResolver.kt
        │   │   │   ├── NseReportFetcher.kt
        │   │   │   ├── NseNetworkClient.kt
        │   │   │   ├── NseReportParsers.kt
        │   │   │   ├── TempStorageManager.kt
        │   │   │   ├── NseDataRepository.kt
        │   │   │   └── AnalysisHistoryStore.kt
        │   │   ├── engine/
        │   │   │   ├── MultiStrikeAnalyzer.kt
        │   │   │   ├── PatternDiscoveryEngine.kt
        │   │   │   ├── ZoneDerivationEngine.kt
        │   │   │   └── AnalysisOrchestrator.kt
        │   │   └── ui/
        │   │       ├── MainViewModel.kt
        │   │       ├── MainScreen.kt
        │   │       └── theme/Theme.kt
        │   └── res/
        │       ├── drawable/ic_launcher_foreground.xml
        │       ├── drawable/ic_launcher_background.xml
        │       ├── mipmap-anydpi-v26/ic_launcher.xml
        │       ├── mipmap-anydpi-v26/ic_launcher_round.xml
        │       └── values/{strings.xml,themes.xml}
        └── test/java/com/nifty/sellerfootprint/
            ├── TradingCalendarAndExpiryTest.kt
            ├── NseParsersStorageAndHistoryTest.kt
            ├── TwentyTwoSessionAnalyticalEngineTest.kt
            └── RegressionFixesVerificationTest.kt
```

---

## Building the Android APK & Running Tests

### Option 1: GitHub Actions (Cloud Build)
1. Push this repository to GitHub (`main` or `master` branch).
2. Open the **Actions** tab and run **Build Android Debug APK & Run Unit Tests**.
3. Download `NiftySellerFootprint-debug-apk` from the workflow artifacts.

### Option 2: Android Studio / Local Command Line
1. Ensure **JDK 17** and **Android SDK (API 34)** are installed.
2. Open the root directory in **Android Studio Ladybug / Koala+** or run:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
