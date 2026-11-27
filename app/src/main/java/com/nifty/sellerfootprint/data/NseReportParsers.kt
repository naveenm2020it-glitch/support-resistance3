package com.nifty.sellerfootprint.data

import com.nifty.sellerfootprint.model.DailyVolatilityRecord
import com.nifty.sellerfootprint.model.FuturesContractRecord
import com.nifty.sellerfootprint.model.IndexSnapshotRecord
import com.nifty.sellerfootprint.model.OptionContractRecord
import com.nifty.sellerfootprint.model.OptionSide
import com.nifty.sellerfootprint.model.ParticipantOiRecord
import com.nifty.sellerfootprint.model.ParticipantVolumeRecord
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.zip.ZipInputStream

object NseReportParsers {

    data class ParsedBhavcopyBundle(
        val options: List<OptionContractRecord>,
        val futures: List<FuturesContractRecord>
    )

    private val FLEXIBLE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        .appendOptional(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH))
        .appendOptional(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        .appendOptional(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        .appendOptional(DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH))
        .toFormatter(Locale.ENGLISH)

    fun parseFlexibleDate(raw: String): LocalDate? {
        val cleaned = raw.trim().removeSurrounding("\"")
        if (cleaned.isEmpty()) return null
        return try {
            LocalDate.parse(cleaned, FLEXIBLE_DATE_FORMATTER)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the first CSV entry from an NSE Bhavcopy ZIP archive into UTF-8 text.
     */
    fun extractCsvFromZip(zipStream: InputStream): Result<String> {
        return try {
            ZipInputStream(zipStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase(Locale.ENGLISH).endsWith(".csv")) {
                        val content = BufferedReader(InputStreamReader(zis, StandardCharsets.UTF_8)).readText()
                        if (content.isBlank()) {
                            return Result.failure(IllegalStateException("Extracted CSV '${entry.name}' is empty."))
                        }
                        return Result.success(content)
                    }
                    entry = zis.nextEntry
                }
            }
            Result.failure(IllegalStateException("ZIP archive contained no CSV file."))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to extract NSE ZIP archive: ${e.message}", e))
        }
    }

    /**
     * Parses both NIFTY Index Options (`IDO` / `OPTIDX`) and NIFTY Index Futures (`IDF` / `FUTIDX`)
     * from the same NSE UDiFF or Legacy F&O Bhavcopy CSV (Streams 1 & 6).
     */
    fun parseFoBhavcopyBundleCsv(csvText: String, expectedTradeDate: LocalDate): Result<ParsedBhavcopyBundle> {
        if (csvText.isBlank()) {
            return Result.failure(IllegalArgumentException("Bhavcopy CSV is empty."))
        }
        if (csvText.trimStart().startsWith("<")) {
            return Result.failure(IllegalStateException("NSE returned HTML instead of CSV (file unavailable or blocked)."))
        }

        val lines = csvText.trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) {
            return Result.failure(IllegalStateException("Bhavcopy CSV has no data rows."))
        }

        val headers = splitCsvLine(lines[0]).map { it.trim().removeSurrounding("\"").uppercase(Locale.ENGLISH) }
        val colIndex = headers.withIndex().associate { it.value to it.index }

        val isUdiff = colIndex.containsKey("TCKRSYMB") && (colIndex.containsKey("STRKPRIC") || colIndex.containsKey("FININSTRMTP"))
        val isLegacy = colIndex.containsKey("SYMBOL") && (colIndex.containsKey("STRIKE_PR") || colIndex.containsKey("INSTRUMENT"))

        if (!isUdiff && !isLegacy) {
            return Result.failure(
                IllegalStateException("Unrecognized F&O Bhavcopy schema. Found headers: ${headers.take(8).joinToString()}")
            )
        }

        val optionRecords = ArrayList<OptionContractRecord>(512)
        val futuresRecords = ArrayList<FuturesContractRecord>(16)

        for (i in 1 until lines.size) {
            val cols = splitCsvLine(lines[i])
            if (cols.size < 10) continue

            if (isUdiff) {
                parseUdiffOptionRow(cols, colIndex, expectedTradeDate)?.let { optionRecords.add(it) }
                parseUdiffFuturesRow(cols, colIndex, expectedTradeDate)?.let { futuresRecords.add(it) }
            } else {
                parseLegacyOptionRow(cols, colIndex, expectedTradeDate)?.let { optionRecords.add(it) }
                parseLegacyFuturesRow(cols, colIndex, expectedTradeDate)?.let { futuresRecords.add(it) }
            }
        }

        if (optionRecords.isEmpty()) {
            return Result.failure(
                IllegalStateException("No valid NIFTY index option rows found for $expectedTradeDate in Bhavcopy.")
            )
        }

        return Result.success(
            ParsedBhavcopyBundle(
                options = optionRecords,
                futures = futuresRecords.sortedBy { it.expiryDate }
            )
        )
    }

    /**
     * Backward-compatible entry point returning parsed NIFTY option contracts.
     */
    fun parseFoBhavcopyCsv(csvText: String, expectedTradeDate: LocalDate): Result<List<OptionContractRecord>> {
        return parseFoBhavcopyBundleCsv(csvText, expectedTradeDate).map { it.options }
    }

    private fun parseUdiffOptionRow(
        cols: List<String>,
        idx: Map<String, Int>,
        expectedDate: LocalDate
    ): OptionContractRecord? {
        val symbol = cols.getStr(idx, "TCKRSYMB")
        if (symbol != "NIFTY") return null

        val instType = cols.getStr(idx, "FININSTRMTP")
        if (instType != "IDO" && instType != "OPTIDX") return null

        val optTypeRaw = cols.getStr(idx, "OPTNTP")
        val side = when (optTypeRaw) {
            "CE" -> OptionSide.CE
            "PE" -> OptionSide.PE
            else -> return null
        }

        val parsedTradeDate = parseFlexibleDate(cols.getStr(idx, "TRADDT")) ?: expectedDate
        if (parsedTradeDate != expectedDate) return null

        val actlExpiry = parseFlexibleDate(cols.getStr(idx, "FININSTRMACTLXPRYDT"))
            ?: parseFlexibleDate(cols.getStr(idx, "XPRYDT"))
            ?: return null

        val strike = cols.getDouble(idx, "STRKPRIC")
        if (strike <= 0.0) return null

        val lotResolution = NiftyLotSizeResolver.resolve(cols.getInt(idx, "NEWBRDLOTQTY"), parsedTradeDate)

        return OptionContractRecord(
            tradeDate = parsedTradeDate,
            expiryDate = actlExpiry,
            strikePrice = strike,
            optionType = side,
            openPrice = cols.getDouble(idx, "OPNPRIC"),
            highPrice = cols.getDouble(idx, "HGHPRIC"),
            lowPrice = cols.getDouble(idx, "LWPRIC"),
            closePrice = cols.getDouble(idx, "CLSPRIC"),
            prevClosePrice = cols.getDouble(idx, "PRVSCLSGPRIC"),
            settlePrice = cols.getDouble(idx, "STTLMPRIC"),
            underlyingPrice = cols.getDouble(idx, "UNDRLYGPRIC"),
            openInterestUnits = cols.getLong(idx, "OPNINTRST"),
            changeInOiUnits = cols.getLong(idx, "CHNGINOPNINTRST"),
            totalTradedVolumeUnits = cols.getLong(idx, "TTLTRADGVOL"),
            totalTurnoverValue = cols.getDouble(idx, "TTLTRFVAL"),
            totalTradesExecuted = cols.getLong(idx, "TTLNBOFTXSEXCTD"),
            lotSize = lotResolution.lotSize,
            lotSizeSource = lotResolution.source
        )
    }

    private fun parseUdiffFuturesRow(
        cols: List<String>,
        idx: Map<String, Int>,
        expectedDate: LocalDate
    ): FuturesContractRecord? {
        val symbol = cols.getStr(idx, "TCKRSYMB")
        if (symbol != "NIFTY") return null

        val instType = cols.getStr(idx, "FININSTRMTP")
        if (instType != "IDF" && instType != "FUTIDX") return null

        val parsedTradeDate = parseFlexibleDate(cols.getStr(idx, "TRADDT")) ?: expectedDate
        if (parsedTradeDate != expectedDate) return null

        val actlExpiry = parseFlexibleDate(cols.getStr(idx, "FININSTRMACTLXPRYDT"))
            ?: parseFlexibleDate(cols.getStr(idx, "XPRYDT"))
            ?: return null

        val lotResolution = NiftyLotSizeResolver.resolve(cols.getInt(idx, "NEWBRDLOTQTY"), parsedTradeDate)

        return FuturesContractRecord(
            tradeDate = parsedTradeDate,
            expiryDate = actlExpiry,
            openPrice = cols.getDouble(idx, "OPNPRIC"),
            highPrice = cols.getDouble(idx, "HGHPRIC"),
            lowPrice = cols.getDouble(idx, "LWPRIC"),
            closePrice = cols.getDouble(idx, "CLSPRIC"),
            prevClosePrice = cols.getDouble(idx, "PRVSCLSGPRIC"),
            settlePrice = cols.getDouble(idx, "STTLMPRIC"),
            underlyingPrice = cols.getDouble(idx, "UNDRLYGPRIC"),
            openInterestUnits = cols.getLong(idx, "OPNINTRST"),
            changeInOiUnits = cols.getLong(idx, "CHNGINOPNINTRST"),
            totalTradedVolumeUnits = cols.getLong(idx, "TTLTRADGVOL"),
            totalTradesExecuted = cols.getLong(idx, "TTLNBOFTXSEXCTD"),
            lotSize = lotResolution.lotSize,
            lotSizeSource = lotResolution.source
        )
    }

    private fun parseLegacyOptionRow(
        cols: List<String>,
        idx: Map<String, Int>,
        expectedDate: LocalDate
    ): OptionContractRecord? {
        val symbol = cols.getStr(idx, "SYMBOL")
        if (symbol != "NIFTY") return null

        val instrument = cols.getStr(idx, "INSTRUMENT")
        if (instrument != "OPTIDX") return null

        val side = when (cols.getStr(idx, "OPTION_TYP")) {
            "CE" -> OptionSide.CE
            "PE" -> OptionSide.PE
            else -> return null
        }

        val parsedDate = parseFlexibleDate(cols.getStr(idx, "TIMESTAMP")) ?: expectedDate
        if (parsedDate != expectedDate) return null

        val expiry = parseFlexibleDate(cols.getStr(idx, "EXPIRY_DT")) ?: return null
        val strike = cols.getDouble(idx, "STRIKE_PR")
        if (strike <= 0.0) return null

        val contractsLots = cols.getLong(idx, "CONTRACTS")
        val lotResolution = NiftyLotSizeResolver.resolve(0, parsedDate)
        val volumeUnits = if (lotResolution.isAvailable) contractsLots * lotResolution.lotSize else 0L

        return OptionContractRecord(
            tradeDate = parsedDate,
            expiryDate = expiry,
            strikePrice = strike,
            optionType = side,
            openPrice = cols.getDouble(idx, "OPEN"),
            highPrice = cols.getDouble(idx, "HIGH"),
            lowPrice = cols.getDouble(idx, "LOW"),
            closePrice = cols.getDouble(idx, "CLOSE"),
            prevClosePrice = cols.getDouble(idx, "CLOSE"),
            settlePrice = cols.getDouble(idx, "SETTLE_PR"),
            underlyingPrice = 0.0,
            openInterestUnits = cols.getLong(idx, "OPEN_INT"),
            changeInOiUnits = cols.getLong(idx, "CHG_IN_OI"),
            totalTradedVolumeUnits = volumeUnits,
            totalTurnoverValue = cols.getDouble(idx, "VAL_INLAKH") * 100_000.0,
            totalTradesExecuted = contractsLots,
            lotSize = lotResolution.lotSize,
            lotSizeSource = lotResolution.source
        )
    }

    private fun parseLegacyFuturesRow(
        cols: List<String>,
        idx: Map<String, Int>,
        expectedDate: LocalDate
    ): FuturesContractRecord? {
        val symbol = cols.getStr(idx, "SYMBOL")
        if (symbol != "NIFTY") return null

        val instrument = cols.getStr(idx, "INSTRUMENT")
        if (instrument != "FUTIDX") return null

        val parsedDate = parseFlexibleDate(cols.getStr(idx, "TIMESTAMP")) ?: expectedDate
        if (parsedDate != expectedDate) return null

        val expiry = parseFlexibleDate(cols.getStr(idx, "EXPIRY_DT")) ?: return null
        val contractsLots = cols.getLong(idx, "CONTRACTS")
        val lotResolution = NiftyLotSizeResolver.resolve(0, parsedDate)
        val volumeUnits = if (lotResolution.isAvailable) contractsLots * lotResolution.lotSize else 0L

        return FuturesContractRecord(
            tradeDate = parsedDate,
            expiryDate = expiry,
            openPrice = cols.getDouble(idx, "OPEN"),
            highPrice = cols.getDouble(idx, "HIGH"),
            lowPrice = cols.getDouble(idx, "LOW"),
            closePrice = cols.getDouble(idx, "CLOSE"),
            prevClosePrice = cols.getDouble(idx, "CLOSE"),
            settlePrice = cols.getDouble(idx, "SETTLE_PR"),
            underlyingPrice = 0.0,
            openInterestUnits = cols.getLong(idx, "OPEN_INT"),
            changeInOiUnits = cols.getLong(idx, "CHG_IN_OI"),
            totalTradedVolumeUnits = volumeUnits,
            totalTradesExecuted = contractsLots,
            lotSize = lotResolution.lotSize,
            lotSizeSource = lotResolution.source
        )
    }

    /**
     * Stream 2: Parses `fao_participant_oi_DDMMYYYY.csv`.
     */
    fun parseParticipantOiCsv(csvText: String, tradeDate: LocalDate): List<ParticipantOiRecord> {
        if (csvText.isBlank() || csvText.trimStart().startsWith("<")) return emptyList()
        val lines = csvText.trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.toList()
        val headerIdx = lines.indexOfFirst { it.uppercase(Locale.ENGLISH).contains("CLIENT TYPE") }
        if (headerIdx < 0 || headerIdx + 1 >= lines.size) return emptyList()

        val headers = splitCsvLine(lines[headerIdx]).map { it.trim().removeSurrounding("\"").uppercase(Locale.ENGLISH) }
        val idx = headers.withIndex().associate { it.value to it.index }
        if (!idx.containsKey("CLIENT TYPE") ||
            (!idx.containsKey("OPTION INDEX CALL SHORT") && !idx.containsKey("OPTION INDEX PUT SHORT"))
        ) {
            return emptyList()
        }

        val out = mutableListOf<ParticipantOiRecord>()
        for (i in (headerIdx + 1) until lines.size) {
            val cols = splitCsvLine(lines[i])
            if (cols.size < 5) continue
            val clientType = cols.getStr(idx, "CLIENT TYPE")
            if (clientType.isBlank()) continue
            out.add(
                ParticipantOiRecord(
                    tradeDate = tradeDate,
                    clientType = clientType,
                    optionIndexCallLong = cols.getLong(idx, "OPTION INDEX CALL LONG"),
                    optionIndexPutLong = cols.getLong(idx, "OPTION INDEX PUT LONG"),
                    optionIndexCallShort = cols.getLong(idx, "OPTION INDEX CALL SHORT"),
                    optionIndexPutShort = cols.getLong(idx, "OPTION INDEX PUT SHORT"),
                    futureIndexLong = cols.getLong(idx, "FUTURE INDEX LONG"),
                    futureIndexShort = cols.getLong(idx, "FUTURE INDEX SHORT")
                )
            )
        }
        return out
    }

    /**
     * Stream 5: Parses `fao_participant_vol_DDMMYYYY.csv` (Participant-Wise Trading Volume in Contracts).
     */
    fun parseParticipantVolumeCsv(csvText: String, tradeDate: LocalDate): List<ParticipantVolumeRecord> {
        if (csvText.isBlank() || csvText.trimStart().startsWith("<")) return emptyList()
        val lines = csvText.trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.toList()
        val headerIdx = lines.indexOfFirst { it.uppercase(Locale.ENGLISH).contains("CLIENT TYPE") }
        if (headerIdx < 0 || headerIdx + 1 >= lines.size) return emptyList()

        val headers = splitCsvLine(lines[headerIdx]).map { it.trim().removeSurrounding("\"").uppercase(Locale.ENGLISH) }
        val idx = headers.withIndex().associate { it.value to it.index }
        if (!idx.containsKey("CLIENT TYPE") ||
            (!idx.containsKey("OPTION INDEX CALL SHORT") && !idx.containsKey("FUTURE INDEX LONG"))
        ) {
            return emptyList()
        }

        val out = mutableListOf<ParticipantVolumeRecord>()
        for (i in (headerIdx + 1) until lines.size) {
            val cols = splitCsvLine(lines[i])
            if (cols.size < 5) continue
            val clientType = cols.getStr(idx, "CLIENT TYPE")
            if (clientType.isBlank()) continue
            out.add(
                ParticipantVolumeRecord(
                    tradeDate = tradeDate,
                    clientType = clientType,
                    futureIndexLong = cols.getLong(idx, "FUTURE INDEX LONG"),
                    futureIndexShort = cols.getLong(idx, "FUTURE INDEX SHORT"),
                    optionIndexCallLong = cols.getLong(idx, "OPTION INDEX CALL LONG"),
                    optionIndexPutLong = cols.getLong(idx, "OPTION INDEX PUT LONG"),
                    optionIndexCallShort = cols.getLong(idx, "OPTION INDEX CALL SHORT"),
                    optionIndexPutShort = cols.getLong(idx, "OPTION INDEX PUT SHORT"),
                    totalLongContracts = cols.getLong(idx, "TOTAL LONG CONTRACTS"),
                    totalShortContracts = cols.getLong(idx, "TOTAL SHORT CONTRACTS")
                )
            )
        }
        return out
    }

    /**
     * Stream 3: Parses `ind_close_all_DDMMYYYY.csv` for `Nifty 50` and `India VIX`.
     */
    fun parseIndexSnapshotCsv(csvText: String, tradeDate: LocalDate): IndexSnapshotRecord? {
        if (csvText.isBlank() || csvText.trimStart().startsWith("<")) return null
        val lines = csvText.trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null

        val headers = splitCsvLine(lines[0]).map { it.trim().removeSurrounding("\"").uppercase(Locale.ENGLISH) }
        val idx = headers.withIndex().associate { it.value to it.index }

        var niftyOpen = 0.0
        var niftyHigh = 0.0
        var niftyLow = 0.0
        var niftyClose = 0.0
        var ptsChange = 0.0
        var pctChange = 0.0
        var vixClose: Double? = null

        for (i in 1 until lines.size) {
            val cols = splitCsvLine(lines[i])
            if (cols.size < 6) continue
            val name = cols.getStr(idx, "INDEX NAME").uppercase(Locale.ENGLISH)
            if (name == "NIFTY 50" || name == "CNX NIFTY") {
                niftyOpen = cols.getDouble(idx, "OPEN INDEX VALUE")
                niftyHigh = cols.getDouble(idx, "HIGH INDEX VALUE")
                niftyLow = cols.getDouble(idx, "LOW INDEX VALUE")
                niftyClose = cols.getDouble(idx, "CLOSING INDEX VALUE")
                ptsChange = cols.getDouble(idx, "POINTS CHANGE")
                pctChange = cols.getDouble(idx, "CHANGE(%)")
            } else if (name == "INDIA VIX") {
                vixClose = cols.getDouble(idx, "CLOSING INDEX VALUE").takeIf { it > 0.0 }
            }
        }

        if (niftyClose <= 0.0) return null
        return IndexSnapshotRecord(
            tradeDate = tradeDate,
            niftyOpen = niftyOpen,
            niftyHigh = niftyHigh,
            niftyLow = niftyLow,
            niftyClose = niftyClose,
            pointsChange = ptsChange,
            percentChange = pctChange,
            indiaVixClose = vixClose
        )
    }

    /**
     * Stream 4: Parses `FOVOLT_DDMMYYYY.csv` for NIFTY underlying daily volatility.
     */
    fun parseFoVolatilityCsv(csvText: String, tradeDate: LocalDate): DailyVolatilityRecord? {
        if (csvText.isBlank() || csvText.trimStart().startsWith("<")) return null
        val lines = csvText.trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null

        val headers = splitCsvLine(lines[0]).map { it.trim().removeSurrounding("\"").uppercase(Locale.ENGLISH) }
        val idx = headers.withIndex().associate { it.value to it.index }

        for (i in 1 until lines.size) {
            val cols = splitCsvLine(lines[i])
            if (cols.size < 6) continue
            val sym = cols.getStr(idx, "SYMBOL")
            if (sym == "NIFTY") {
                val close = cols.getDouble(idx, "UNDERLYING CLOSE PRICE (A)")
                val dailyVol = cols.getDouble(idx, "CURRENT DAY UNDERLYING DAILY VOLATILITY (E)")
                val annVol = cols.getDouble(idx, "APPLICABLE ANNUALISED VOLATILITY (F) = E * SQRT(365)")
                    .let { if (it > 0.0) it else cols.getDoubleByPrefix(idx, "APPLICABLE ANNUALISED VOLATILITY") }
                if (dailyVol > 0.0) {
                    return DailyVolatilityRecord(
                        tradeDate = tradeDate,
                        underlyingClose = close,
                        dailyVolatility = dailyVol,
                        annualizedVolatility = annVol
                    )
                }
            }
        }
        return null
    }

    fun splitCsvLine(line: String): List<String> {
        val result = ArrayList<String>(24)
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun List<String>.getStr(idx: Map<String, Int>, key: String): String {
        val pos = idx[key] ?: return ""
        return if (pos in indices) this[pos].trim().removeSurrounding("\"") else ""
    }

    private fun List<String>.getDouble(idx: Map<String, Int>, key: String): Double {
        val raw = getStr(idx, key)
        if (raw.isEmpty() || raw == "-" || raw.equals("NA", ignoreCase = true)) return 0.0
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    }

    private fun List<String>.getDoubleByPrefix(idx: Map<String, Int>, prefix: String): Double {
        val matchedKey = idx.keys.firstOrNull { it.startsWith(prefix) } ?: return 0.0
        return getDouble(idx, matchedKey)
    }

    private fun List<String>.getLong(idx: Map<String, Int>, key: String): Long {
        val raw = getStr(idx, key)
        if (raw.isEmpty() || raw == "-" || raw.equals("NA", ignoreCase = true)) return 0L
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: 0L
    }

    private fun List<String>.getInt(idx: Map<String, Int>, key: String): Int {
        val raw = getStr(idx, key)
        if (raw.isEmpty() || raw == "-" || raw.equals("NA", ignoreCase = true)) return 0
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt() ?: 0
    }
}
