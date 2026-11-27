package com.nifty.sellerfootprint

import com.nifty.sellerfootprint.data.AnalysisHistoryStore
import com.nifty.sellerfootprint.data.NseDataRepository
import com.nifty.sellerfootprint.data.NseReportFetcher
import com.nifty.sellerfootprint.data.NseReportParsers
import com.nifty.sellerfootprint.data.TempStorageManager
import com.nifty.sellerfootprint.engine.AnalysisOrchestrator
import com.nifty.sellerfootprint.model.OptionSide
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NseParsersStorageAndHistoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseFoBhavcopyCsv_parsesUdiffWithBomAndFiltersOnlyNiftyOptions() {
        val tradeDate = LocalDate.of(2026, 3, 9)
        val udiffCsv = "\uFEFF" + """
            TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,SsnId,NewBrdLotQty,Rmks,Rsvd1,Rsvd2,Rsvd3,Rsvd4
            2026-03-09,2026-03-09,FO,NSE,IDO,101,,NIFTY,,2026-03-10,2026-03-10,24000,PE,NIFTY2631024000PE,85.0,92.0,68.0,70.0,70.0,88.0,24150.5,70.0,650000,130000,1300000,91000000,4000,F1,65,,,,,
            2026-03-09,2026-03-09,FO,NSE,IDO,102,,NIFTY,,2026-03-10,2026-03-10,24300,CE,NIFTY2631024300CE,78.0,84.0,60.0,62.0,62.0,80.0,24150.5,62.0,780000,195000,1625000,100750000,5000,F1,65,,,,,
            2026-03-09,2026-03-09,FO,NSE,IDO,103,,BANKNIFTY,,2026-03-11,2026-03-11,51000,CE,BANKNIFTY,120.0,130.0,110.0,115.0,115.0,125.0,51200.0,115.0,300000,50000,500000,57500000,2000,F1,30,,,,,
        """.trimIndent()

        val zipBytes = createZipBytes("BhavCopy_NSE_FO_0_0_0_20260309_F_0000.csv", udiffCsv)
        val extracted = NseReportParsers.extractCsvFromZip(ByteArrayInputStream(zipBytes))
        assertTrue(extracted.isSuccess)

        val parsed = NseReportParsers.parseFoBhavcopyCsv(extracted.getOrThrow(), tradeDate)
        assertTrue(parsed.isSuccess)
        val records = parsed.getOrThrow()
        assertEquals(2, records.size)
        assertEquals(24000.0, records[0].strikePrice, 0.001)
        assertEquals(OptionSide.PE, records[0].optionType)
        assertEquals(10000.0, records[0].openInterestLots, 0.001)
        assertEquals(2000.0, records[0].changeInOiLots, 0.001)
    }

    @Test
    fun parseSecondaryReports_parsesParticipantIndexAndVolatility() {
        val tradeDate = LocalDate.of(2026, 3, 9)

        val participantCsv = """
            Participant wise Open Interest (as on 09-Mar-2026)
            Client Type,Future Index Long,Future Index Short,Future Stock Long,Future Stock Short,Option Index Call Long,Option Index Put Long,Option Index Call Short,Option Index Put Short,Option Stock Call Long,Option Stock Put Long,Option Stock Call Short,Option Stock Put Short,Total Long Contracts,Total Short Contracts
            Client,100,120,200,180,1500000,1400000,1350000,1250000,0,0,0,0,3200300,2900300
            FII,80,90,150,140,450000,520000,490000,560000,0,0,0,0,1050230,1140230
            Pro,50,45,90,95,620000,580000,760000,790000,0,0,0,0,1250140,1595140
        """.trimIndent()
        val pList = NseReportParsers.parseParticipantOiCsv(participantCsv, tradeDate)
        assertEquals(3, pList.size)
        assertEquals(760000L, pList.first { it.clientType == "Pro" }.optionIndexCallShort)

        val indexCsv = """
            Index Name,Index Date,Open Index Value,High Index Value,Low Index Value,Closing Index Value,Points Change,Change(%),Volume,Turnover (Rs. Cr.),P/E,P/B,Div Yield
            Nifty 50,09-03-2026,24080.00,24210.50,24040.25,24150.50,95.25,0.40,250000000,28500.00,21.5,3.4,1.25
            India VIX,09-03-2026,13.80,14.20,13.10,13.45,-0.35,-2.54,-,-,-,-,-
        """.trimIndent()
        val snap = NseReportParsers.parseIndexSnapshotCsv(indexCsv, tradeDate)
        assertNotNull(snap)
        assertEquals(24150.50, snap!!.niftyClose, 0.001)
        assertEquals(13.45, snap.indiaVixClose!!, 0.001)

        val voltCsv = """
            Date,Symbol,Underlying Close Price (A),Underlying Previous Day Close Price (B),Underlying Log Returns (C) = LN(A/B),Previous Day Underlying Volatility (D),Current Day Underlying Daily Volatility (E),Applicable Annualised Volatility (F) = E * Sqrt(365)
            09-Mar-2026,NIFTY,24150.50,24055.25,0.00395,0.0082,0.0085,0.1624
        """.trimIndent()
        val volt = NseReportParsers.parseFoVolatilityCsv(voltCsv, tradeDate)
        assertNotNull(volt)
        assertEquals(0.0085, volt!!.dailyVolatility, 0.00001)
    }

    @Test
    fun tempStorageAndHistoryStore_deletesOnlyTempReportsWhilePreservingHistory() = runTest {
        val cacheDir = tempFolder.newFolder("cache")
        val filesDir = tempFolder.newFolder("files")
        val storage = TempStorageManager(cacheDir)
        val history = AnalysisHistoryStore(filesDir)

        val targetDate = LocalDate.of(2026, 3, 9)
        val expiryDate = LocalDate.of(2026, 3, 10)
        val fakeFetcher = SyntheticNseFetcher(targetDate, expiryDate)
        val repo = NseDataRepository(fakeFetcher, storage)
        val orchestrator = AnalysisOrchestrator(repo)

        val result = orchestrator.executeAnalysis(targetDate, onProgress = {}).getOrThrow()
        history.recordAnalysis(result)

        val statsBefore = storage.getStorageStats()
        assertTrue(statsBefore.fileCount >= 22)
        assertEquals(1, history.loadHistory().size)

        // Delete temporary NSE reports
        val delOutcome = storage.deleteAllTemporaryReports()
        assertTrue(delOutcome.success)
        assertEquals(0, storage.getStorageStats().fileCount)
        assertEquals(0L, storage.getStorageStats().totalBytes)

        // Analysis history in filesDir remains intact
        val savedHistory = history.loadHistory()
        assertEquals(1, savedHistory.size)
        assertEquals(targetDate, savedHistory[0].tradingDate)
        assertNull(storage.readSessionReportIfCached(targetDate, TempStorageManager.BHAVCOPY_FILE))
    }

    private fun createZipBytes(entryName: String, content: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private class SyntheticNseFetcher(
        private val targetDate: LocalDate,
        private val expiryDate: LocalDate
    ) : NseReportFetcher {

        private val validDates: Set<LocalDate> = run {
            val list = mutableListOf<LocalDate>()
            var d = targetDate
            while (list.size < 22) {
                if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d != LocalDate.of(2026, 3, 3)) {
                    list.add(d)
                }
                d = d.minusDays(1)
            }
            list.toSet()
        }

        override fun downloadUdiffBhavcopyCsv(tradeDate: LocalDate): Result<String> {
            if (tradeDate !in validDates) return Result.failure(IOException("404 Holiday"))
            val sb = StringBuilder(
                "TradDt,FinInstrmTp,TckrSymb,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,OpnPric,HghPric,LwPric,ClsPric,PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,NewBrdLotQty\n"
            )
            val strikes = listOf(23800, 23900, 24000, 24100, 24200, 24300, 24400)
            for (k in strikes) {
                val peOi = if (k == 24000) 900000L else 250000L
                val peChg = if (k == 24000) 195000L else 26000L
                val ceOi = if (k == 24300) 950000L else 260000L
                val ceChg = if (k == 24300) 227500L else 32500L
                sb.append("$tradeDate,IDO,NIFTY,$expiryDate,$expiryDate,$k,PE,75,80,60,62,74,24150.0,62,$peOi,$peChg,975000,60450000,2500,65\n")
                sb.append("$tradeDate,IDO,NIFTY,$expiryDate,$expiryDate,$k,CE,72,78,58,60,71,24150.0,60,$ceOi,$ceChg,975000,58500000,2500,65\n")
            }
            return Result.success(sb.toString())
        }

        override fun downloadParticipantOiCsv(tradeDate: LocalDate): Result<String> {
            if (tradeDate !in validDates) return Result.failure(IOException("404"))
            val mult = if (tradeDate == targetDate) 10000L else 0L
            return Result.success(
                "Client Type,Option Index Call Long,Option Index Put Long,Option Index Call Short,Option Index Put Short\n" +
                    "FII,100000,100000,${150000 + mult},${160000 + mult}\n" +
                    "Pro,200000,200000,${250000 + mult},${260000 + mult}\n"
            )
        }

        override fun downloadIndexSnapshotCsv(tradeDate: LocalDate): Result<String> {
            if (tradeDate !in validDates) return Result.failure(IOException("404"))
            return Result.success(
                "Index Name,Open Index Value,High Index Value,Low Index Value,Closing Index Value,Points Change,Change(%)\n" +
                    "Nifty 50,24100.0,24190.0,24080.0,24150.0,45.0,0.19\n" +
                    "India VIX,13.5,13.9,13.1,13.3,-0.2,-1.48\n"
            )
        }

        override fun downloadFoVolatilityCsv(tradeDate: LocalDate): Result<String> {
            if (tradeDate !in validDates) return Result.failure(IOException("404"))
            return Result.success(
                "Symbol,Underlying Close Price (A),Current Day Underlying Daily Volatility (E),Applicable Annualised Volatility (F) = E * Sqrt(365)\n" +
                    "NIFTY,24150.0,0.0085,0.1624\n"
            )
        }
    }
}
