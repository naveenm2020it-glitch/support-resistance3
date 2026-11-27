# Research Methodology: 22-Session NIFTY Option-Seller Footprint Discovery

## 1. Objective & Foundational Premise

Institutional and proprietary option sellers (`Pro` desks and `FII` derivatives participants) underwrite directional tail risk by writing out-of-the-money (OTM) Put (`PE`) and Call (`CE`) options. Because short option positions carry asymmetric downside risk, concentrated seller footprints manifest in End-of-Day (EOD) exchange data as **statistically abnormal Open Interest additions coupled with price absorption, high positional retention, and multi-session strike defense**.

Rather than relying on static single-day OI bars or arbitrary `ATM ± 1` strike windows, **NIFTY Seller Footprint** implements a rolling **22-trading-session empirical discovery and validation pipeline** ending on the user-selected trading date (`Session 22`).

---

## 2. Dynamic Multi-Strike Chain Envelope (`MultiStrikeAnalyzer`)

For each session $t$ and active expiry $E$, the engine evaluates the full NIFTY option chain without hardcoded ATM limits:

1. **95% Cumulative OI Mass Envelope:** Strikes are sorted by absolute distance $|K - S_t|$ from underlying spot $S_t$, accumulating $OI_{CE}(K) + OI_{PE}(K)$ until $\ge 95\%$ of total expiry OI is captured.
2. **2.5-Sigma Volatility Horizon Bound:** Using daily underlying volatility $\sigma_d$ from `FOVOLT` (or 15-session realized log-return volatility fallback) and calendar days to expiry $DTE = \max(1, E - t)$:
   $$\Delta_{2.5\sigma} = 2.5 \cdot S_t \cdot \sigma_d \cdot \sqrt{DTE}$$
   The retained strike universe spans $[\max(K_{\min}, \min(K_{95\%,	ext{low}}, S_t - \Delta_{2.5\sigma})), \min(K_{\max}, \max(K_{95\%,	ext{high}}, S_t + \Delta_{2.5\sigma}))]$.
3. **Structural Distance Belts:**
   - **Core Gamma Belt:** $|(K - S_t)/S_t| \le 1.2\%$
   - **Primary Seller Wall Belt:** $1.2\% < |(K - S_t)/S_t| \le 3.5\%$
   - **Tail-Hedge Margin Wing:** $|(K - S_t)/S_t| > 3.5\%$

---

## 3. Strike Microstructure Features

For every retained `(Strike, Side)` contract:
- **Normalized OI Impulse ($Z_{\Delta OI}$):** Cross-sectional Z-score of $\Delta OI_{	ext{lots}}$ across the retained chain.
- **Excess Price Decay:** $\Delta P_{	ext{actual}} - (\Delta S_t \cdot \Delta_{	ext{approx}})$, identifying options whose settlement price compressed despite underlying movement toward the strike.
- **Positional Retention Ratio ($PRR$):** $|\Delta OI_{	ext{lots}}| / 	ext{Volume}_{	ext{lots}}$, distinguishing persistent EOD writing from intraday scalping churn.
- **Average Trade Size Ratio ($ATS_{	ext{ratio}}$):** $(	ext{Volume}_{	ext{lots}} / 	ext{Trades}) / 	ext{Median}(ATS)$, detecting institutional block execution.
- **Multi-Session Persistence:** Consecutive prior sessions where the strike remained in the top OI quintile without its breakeven ($K \pm P_{	ext{settle}}$) being breached.
- **Vertical Roll Linkage:** Detects simultaneous OI unwinding ($Z_{\Delta OI} \le -1.10$) at an inner strike $50	ext{–}250$ points closer to spot paired with fresh addition ($Z_{\Delta OI} \ge 1.15$) at the outer strike.

---

## 4. Pattern Taxonomy & 4-Phase Validation Split (`PatternDiscoveryEngine`)

### Phase A: Discovery Window (`Sessions 1–17` → `Ledger A`)
Evaluates seven canonical microstructure signatures:
- **`P1` Absorption Writing:** $Z_{\Delta OI} \ge 1.15$, $PRR \ge 0.14$, and price compression / negative excess decay as spot approaches the strike.
- **`P2` Multi-Session Anchor Wall:** $\ge 2$ consecutive prior sessions in top OI quintile with no material unwinding ($\Delta OI \ge -500$ lots).
- **`P3` Intraday Rejection Wick + OI Addition:** Intraday high/low probes within $0.25\%$ of strike $K$ before closing safely OTM, accompanied by $Z_{\Delta OI} \ge 0.90$ and $ATS_{	ext{ratio}} \ge 1.15$.
- **`P4` Asymmetric Strangle Skew Pin:** Simultaneous primary-wall writing ($Z_{\Delta OI} \ge 1.15$) on both `CE` and `PE` sides.
- **`P5` Vertical Defensive Roll:** Fresh outer strike writing ($Z_{\Delta OI} \ge 1.15$) paired with inner-strike short covering ($Z_{\Delta OI} \le -1.10$).
- **`P6` Short-Covering Capitulation (Veto Rule):** Underlying spot closes past strike $K$ with sharp OI unwinding ($Z_{\Delta OI} \le -1.35$) and rising option price ($\Delta P > 0$). Immediately disqualifies the breached strike from zone construction.
- **`P7` Tail-Wing Margin Hedge (Noise Filter):** Far-OTM strikes ($>3.5\%$ from spot) trading below `18.0` points, excluded as institutional margin-reduction wings.

### Phase B: Out-of-Sample Validation & Late-Cycle Discovery (`Sessions 18–21`)
- **Track 2A (`Ledger B`):** Tests frozen `Ledger A` rules out-of-sample on `Sessions 18–21`. Any directional pattern triggering $\ge 2$ times with a next-session hold rate $< 45\%$ is tagged `REJECTED_OVERFITTED` and dropped.
- **Track 2B (`Ledger C`):** Independently scans `Sessions 18–21` for regime-specific patterns:
  - **`NP1` Calendar Rollover Wall** ($DTE \le 2$, $Z_{\Delta OI} \ge 1.30$, $ATS_{	ext{ratio}} \ge 1.35$)
  - **`NP2` Volatility-Expansion Premium Harvest** ($\ge 10\%$ daily volatility jump + primary-wall writing)
  - **`NP3` Near-Expiry ATM Gamma-Pin Magnet** ($DTE \le 1$, $|K - S_t|/S_t \le 0.35\%$, dual-sided premium decay)

### Phase C & Phase D: Combined Set (`Ledger D`) & Session 22 Blind Test
Validated patterns from `Ledger B` and regime patterns from `Ledger C` form `Ledger D`, which is applied without look-ahead to `Session 22`. Unmatched extreme OI anomalies ($|Z_{\Delta OI}| \ge 2.2$) are logged transparently in the audit trail without being treated as validated rules.

---

## 5. Narrow Zone Derivation (`ZoneDerivationEngine`)

1. Adjacent 50-point qualifying strikes (max 2 strikes per cluster) are grouped into narrow clusters.
2. **Premium-Adjusted Boundaries:** Using cushion weight $w = 1.0$ for $DTE \le 2$ and $w = 0.5$ for $DTE > 2$, with $	ext{cushion} = 	ext{clamp}(w \cdot \overline{P}_{	ext{settle}}, 8.0, 55.0)$ rounded to `0.25` tick increments:
   - **Support (`PE`):** $[K_{	ext{low}} - 	ext{cushion},\; K_{	ext{high}}]$
   - **Resistance (`CE`):** $[K_{	ext{low}},\; K_{	ext{high}} + 	ext{cushion}]$
3. **Non-Overlapping Ordering:** `S1` is the closest credible support below spot; `S2` is the next distinct support strictly below `S1`. `R1` is the closest credible resistance above spot; `R2` is the next distinct resistance strictly above `R1`. If insufficient evidence exists, the zone is explicitly reported as **Inconclusive**.
