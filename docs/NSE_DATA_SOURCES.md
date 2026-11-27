# Official NSE End-of-Day (EOD) Data Sources & Schemas

**NIFTY Seller Footprint** uses only publicly accessible, unauthenticated EOD archive files published by the National Stock Exchange of India (`nsearchives.nseindia.com` / `archives.nseindia.com`).

---

## 1. Report Inventory & Archive URLs

| # | Report Name | Requirement | Primary & Fallback Archive URLs |
|---|---|---|---|
| 1 | **F&O UDiFF Common Bhavcopy** | **Mandatory** (all 22 sessions) | `https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip`<br>`https://archives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip`<br>Legacy: `https://nsearchives.nseindia.com/content/historical/DERIVATIVES/YYYY/MMM/foDDMMMYYYYbhav.csv.zip` |
| 2 | **Participant-Wise Open Interest** | Optional (graceful fallback) | `https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_DDMMYYYY.csv`<br>`https://archives.nseindia.com/content/nsccl/fao_participant_oi_DDMMYYYY.csv` |
| 3 | **Daily Index Snapshot** | Optional (falls back to Bhavcopy `UndrlygPric`) | `https://nsearchives.nseindia.com/content/indices/ind_close_all_DDMMYYYY.csv`<br>`https://archives.nseindia.com/content/indices/ind_close_all_DDMMYYYY.csv` |
| 4 | **F&O Daily Volatility** | Optional (falls back to realized return $\sigma$) | `https://nsearchives.nseindia.com/archives/nsccl/volt/FOVOLT_DDMMYYYY.csv`<br>`https://archives.nseindia.com/archives/nsccl/volt/FOVOLT_DDMMYYYY.csv` |

---

## 2. CSV Parsing & Normalization Rules (`NseReportParsers`)

1. **UTF-8 BOM & HTML Guard:** Leading UTF-8 Byte Order Marks (`\uFEFF`) are stripped before header mapping. Any payload starting with `<` (such as an NSE 404/maintenance HTML page) is rejected immediately.
2. **UDiFF Bhavcopy Filtering:**
   - `TckrSymb == "NIFTY"`
   - `FinInstrmTp` in `{"IDO", "OPTIDX"}`
   - `OptnTp` in `{"CE", "PE"}`
   - `FininstrmActlXpryDt` (or `XpryDt`) parsed flexibly across `yyyy-MM-dd`, `dd-MMM-yyyy`, `dd-MM-yyyy`, and `dd/MM/yyyy`.
   - Unit quantities (`OpnIntrst`, `ChngInOpnIntrst`, `TtlTradgVol`) are divided by `NewBrdLotQty` (defaulting to `65` if absent) so all comparisons across sessions use standardized contract lots.
3. **Participant-Wise OI (`fao_participant_oi`):** Locates the header row containing `CLIENT TYPE` and extracts `Option Index Call Long`, `Option Index Put Long`, `Option Index Call Short`, and `Option Index Put Short` for `Pro` and `FII`.
4. **Daily Index Snapshot (`ind_close_all`):** Extracts `Nifty 50` (or `CNX Nifty`) Open, High, Low, Close, Points Change, and `% Change`, plus `India VIX` closing value.
5. **F&O Daily Volatility (`FOVOLT`):** Extracts `Symbol == "NIFTY"` daily underlying volatility (`E`) and annualized volatility (`F`).

---

## 3. Caching & Privacy Architecture (`TempStorageManager`)

- All downloaded CSVs are stored inside app-private cache storage: `<context.cacheDir>/nse_temp_reports/<YYYY-MM-DD>/`.
- Switching between weekly and monthly expiries for the same trading date reuses the already-cached 22-session dataset in milliseconds with zero redundant network requests.
- Tapping **Delete Temporary Files** in the UI purges only `nse_temp_reports/` while preserving saved analysis history (`<context.filesDir>/analysis_history_v1.tsv`).
