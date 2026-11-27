package com.nifty.sellerfootprint.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NseNetworkClient : NseReportFetcher {

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = cookieStore.getOrPut(host) { mutableListOf() }
            synchronized(existing) {
                val names = cookies.map { it.name }.toSet()
                existing.removeAll { it.name in names }
                existing.addAll(cookies)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val merged = mutableListOf<Cookie>()
            cookieStore.forEach { (host, list) ->
                if (url.host.endsWith(host) || host.endsWith("nseindia.com")) {
                    synchronized(list) {
                        list.removeAll { it.expiresAt < now }
                        merged.addAll(list)
                    }
                }
            }
            return merged
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var sessionWarmedUp = false

    private fun ensureNseSessionCookies() {
        if (sessionWarmedUp) return
        try {
            val warmupReq = Request.Builder()
                .url("https://www.nseindia.com/")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(warmupReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    sessionWarmedUp = true
                }
            }
        } catch (_: Exception) {
            // Archive endpoints (nsearchives.nseindia.com) often respond directly even if homepage warmup times out
        }
    }

    override fun downloadUdiffBhavcopyCsv(tradeDate: LocalDate): Result<String> {
        ensureNseSessionCookies()
        val yyyymmdd = tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE)
        val ddMMMyyyy = tradeDate.format(DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH))
            .uppercase(Locale.ENGLISH)
        val yyyy = tradeDate.year.toString()
        val mmm = tradeDate.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)).uppercase(Locale.ENGLISH)

        val candidateUrls = listOf(
            "https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_${yyyymmdd}_F_0000.csv.zip",
            "https://archives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_${yyyymmdd}_F_0000.csv.zip",
            "https://nsearchives.nseindia.com/content/historical/DERIVATIVES/$yyyy/$mmm/fo${ddMMMyyyy}bhav.csv.zip"
        )

        var lastError = "HTTP 404 Not Found"
        for (url in candidateUrls) {
            val bytesResult = fetchBytes(url)
            if (bytesResult.isSuccess) {
                val bytes = bytesResult.getOrThrow()
                val extracted = NseReportParsers.extractCsvFromZip(ByteArrayInputStream(bytes))
                if (extracted.isSuccess) {
                    return extracted
                } else {
                    lastError = extracted.exceptionOrNull()?.message ?: "ZIP extraction failed"
                }
            } else {
                lastError = bytesResult.exceptionOrNull()?.message ?: "Download failed"
            }
        }
        return Result.failure(
            IOException("F&O Bhavcopy unavailable for $tradeDate ($lastError). Confirm NSE EOD files are published for this date.")
        )
    }

    override fun downloadParticipantOiCsv(tradeDate: LocalDate): Result<String> {
        ensureNseSessionCookies()
        val ddmmyyyy = tradeDate.format(DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ENGLISH))
        val urls = listOf(
            "https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_$ddmmyyyy.csv",
            "https://archives.nseindia.com/content/nsccl/fao_participant_oi_$ddmmyyyy.csv"
        )
        return fetchFirstText(urls, "Participant-Wise OI ($ddmmyyyy)")
    }

    override fun downloadParticipantVolumeCsv(tradeDate: LocalDate): Result<String> {
        ensureNseSessionCookies()
        val ddmmyyyy = tradeDate.format(DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ENGLISH))
        val urls = listOf(
            "https://nsearchives.nseindia.com/content/nsccl/fao_participant_vol_$ddmmyyyy.csv",
            "https://archives.nseindia.com/content/nsccl/fao_participant_vol_$ddmmyyyy.csv"
        )
        return fetchFirstText(urls, "Participant-Wise Trading Volume ($ddmmyyyy)")
    }

    override fun downloadIndexSnapshotCsv(tradeDate: LocalDate): Result<String> {
        ensureNseSessionCookies()
        val ddmmyyyy = tradeDate.format(DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ENGLISH))
        val urls = listOf(
            "https://nsearchives.nseindia.com/content/indices/ind_close_all_$ddmmyyyy.csv",
            "https://archives.nseindia.com/content/indices/ind_close_all_$ddmmyyyy.csv"
        )
        return fetchFirstText(urls, "Daily Index Snapshot ($ddmmyyyy)")
    }

    override fun downloadFoVolatilityCsv(tradeDate: LocalDate): Result<String> {
        ensureNseSessionCookies()
        val ddmmyyyy = tradeDate.format(DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ENGLISH))
        val urls = listOf(
            "https://nsearchives.nseindia.com/archives/nsccl/volt/FOVOLT_$ddmmyyyy.csv",
            "https://archives.nseindia.com/archives/nsccl/volt/FOVOLT_$ddmmyyyy.csv"
        )
        return fetchFirstText(urls, "F&O Daily Volatility ($ddmmyyyy)")
    }

    private fun fetchFirstText(urls: List<String>, label: String): Result<String> {
        var lastErr = "Not found"
        for (url in urls) {
            val res = fetchBytes(url)
            if (res.isSuccess) {
                val text = res.getOrThrow().toString(Charsets.UTF_8)
                if (text.isNotBlank() && !text.trimStart().startsWith("<")) {
                    return Result.success(text)
                }
                lastErr = "Received HTML/empty payload"
            } else {
                lastErr = res.exceptionOrNull()?.message ?: "HTTP error"
            }
        }
        return Result.failure(IOException("$label unavailable ($lastErr)"))
    }

    private fun fetchBytes(url: String): Result<ByteArray> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.nseindia.com/all-reports")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body ?: return Result.failure(IOException("Empty response body"))
                val bytes = body.bytes()
                if (bytes.isEmpty()) {
                    return Result.failure(IOException("0-byte file"))
                }
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(IOException(e.message ?: "Network connection failure", e))
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
