package com.nifty.sellerfootprint.data

import java.io.IOException
import java.time.LocalDate

interface NseReportFetcher {
    fun downloadUdiffBhavcopyCsv(tradeDate: LocalDate): Result<String>
    fun downloadParticipantOiCsv(tradeDate: LocalDate): Result<String>
    fun downloadIndexSnapshotCsv(tradeDate: LocalDate): Result<String>
    fun downloadFoVolatilityCsv(tradeDate: LocalDate): Result<String>

    /**
     * Stream 5: Participant-Wise Trading Volume (`fao_participant_vol_DDMMYYYY.csv`).
     * Default failure implementation preserves backward compatibility with existing test doubles.
     */
    fun downloadParticipantVolumeCsv(tradeDate: LocalDate): Result<String> {
        return Result.failure(IOException("Participant-Wise Trading Volume endpoint not configured"))
    }
}
