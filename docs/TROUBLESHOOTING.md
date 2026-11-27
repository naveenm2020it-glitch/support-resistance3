# Troubleshooting Guide

## 1. "Cannot analyse YYYY-MM-DD: F&O Bhavcopy unavailable"
- **Pre-Market / Intraday Selection:** NSE publishes EOD F&O Bhavcopy, Participant OI, and Index Snapshot files **after market close (typically between 6:00 PM and 7:30 PM IST)**. If you select today's date before EOD files are uploaded, select the previous completed trading session.
- **Unscheduled Exchange Holiday:** While `TradingCalendarEngine` includes scheduled 2024–2026 NSE holidays, ad-hoc exchange closures return HTTP 404 for the target date. Select a valid completed trading day.
- **Historical Archive Gaps:** Prior sessions (`Sessions 1–21`) automatically skip unscheduled holidays by scanning up to 34 backward candidate weekdays to assemble 22 valid sessions.

## 2. NSE Network Rate Limiting or HTTP 403
- `NseNetworkClient` warms up session cookies against `https://www.nseindia.com/` and includes browser-compatible `User-Agent` and `Referer` headers before querying `nsearchives.nseindia.com`.
- If corporate Wi-Fi or a VPN blocks `nseindia.com`, switch to mobile data or a standard residential network and tap **Analyse** again. Previously downloaded sessions remain cached locally so progress resumes without re-fetching completed dates.

## 3. One or More Zones (`S1`, `S2`, `R1`, `R2`) Show "Inconclusive"
- By design, the engine **never fabricates arbitrary support/resistance levels**.
- A zone is marked `Inconclusive` when:
  - No strike cluster on that side of spot passed `Ledger D` pattern criteria or minimum normalized OI impulse (`Z >= 1.25`), **or**
  - Nearby strikes triggered `P6` (Short-Covering Capitulation veto) because spot breached the strike while OI unwound sharply.

## 4. Gradle / Android Studio Build Issues
- **JDK Version Mismatch:** Ensure Android Studio uses **JDK 17** (`Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK = 17`).
- **Missing `gradle-wrapper.jar` in Offline Environments:** If building offline without internet access to download Gradle 8.7, run `gradle wrapper --gradle-version 8.7` using a locally installed Gradle binary, or open the project directly in Android Studio which provisions the Gradle distribution automatically.
