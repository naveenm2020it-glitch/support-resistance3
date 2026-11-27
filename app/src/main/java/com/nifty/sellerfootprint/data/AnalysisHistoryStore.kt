package com.nifty.sellerfootprint.data

import com.nifty.sellerfootprint.model.AnalysisResult
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AnalysisHistoryItem(
    val id: String,
    val tradingDate: LocalDate,
    val expiryDate: LocalDate,
    val niftyClose: Double,
    val s1Summary: String,
    val s2Summary: String,
    val r1Summary: String,
    val r2Summary: String,
    val analyzedAtLabel: String
)

/**
 * Lightweight, dependency-free persistence for recent analysis summaries in app-private filesDir.
 * Kept strictly separate from cacheDir/nse_temp_reports so deleting temporary NSE report files
 * never deletes saved analysis history records.
 */
class AnalysisHistoryStore(private val appFilesDir: File) {

    private val historyFile: File
        get() = File(appFilesDir, "analysis_history_v1.tsv")

    fun loadHistory(): List<AnalysisHistoryItem> {
        val file = historyFile
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size < 9) return@mapNotNull null
                    val tDate = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return@mapNotNull null
                    val eDate = runCatching { LocalDate.parse(parts[2]) }.getOrNull() ?: return@mapNotNull null
                    val rawClose = parts[3].trim()
                    val close = (rawClose.toDoubleOrNull() ?: rawClose.replace(',', '.').toDoubleOrNull())
                        ?.takeIf { it.isFinite() }
                        ?: return@mapNotNull null
                    AnalysisHistoryItem(
                        id = parts[0],
                        tradingDate = tDate,
                        expiryDate = eDate,
                        niftyClose = close,
                        s1Summary = parts[4],
                        s2Summary = parts[5],
                        r1Summary = parts[6],
                        r2Summary = parts[7],
                        analyzedAtLabel = parts[8]
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordAnalysis(result: AnalysisResult): List<AnalysisHistoryItem> {
        val key = "${result.selectedTradingDate}_${result.selectedExpiry.expiryDate}"
        val item = AnalysisHistoryItem(
            id = key,
            tradingDate = result.selectedTradingDate,
            expiryDate = result.selectedExpiry.expiryDate,
            niftyClose = result.niftyClose,
            s1Summary = formatZoneShort(result.s1.lowerBoundary, result.s1.upperBoundary),
            s2Summary = formatZoneShort(result.s2.lowerBoundary, result.s2.upperBoundary),
            r1Summary = formatZoneShort(result.r1.lowerBoundary, result.r1.upperBoundary),
            r2Summary = formatZoneShort(result.r2.lowerBoundary, result.r2.upperBoundary),
            analyzedAtLabel = result.lastAnalyzedTimestamp.format(DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH))
        )

        val updated = (listOf(item) + loadHistory().filterNot { it.id == key }).take(12)
        try {
            if (!appFilesDir.exists()) appFilesDir.mkdirs()
            val serialized = updated.joinToString("\n") { h ->
                listOf(
                    h.id,
                    h.tradingDate.toString(),
                    h.expiryDate.toString(),
                    String.format(Locale.US, "%.2f", h.niftyClose),
                    h.s1Summary,
                    h.s2Summary,
                    h.r1Summary,
                    h.r2Summary,
                    h.analyzedAtLabel
                ).joinToString("\t")
            }
            historyFile.writeText(serialized, Charsets.UTF_8)
        } catch (_: Exception) {
            // Non-fatal if storage write is restricted
        }
        return updated
    }

    private fun formatZoneShort(low: Double?, high: Double?): String {
        return if (low != null && high != null) {
            "${low.toInt()}–${high.toInt()}"
        } else {
            "Inconclusive"
        }
    }
}
