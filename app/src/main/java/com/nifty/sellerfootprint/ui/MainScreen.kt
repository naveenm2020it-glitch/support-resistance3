package com.nifty.sellerfootprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nifty.sellerfootprint.data.AnalysisHistoryItem
import com.nifty.sellerfootprint.model.AnalysisResult
import com.nifty.sellerfootprint.model.ExpiryOption
import com.nifty.sellerfootprint.model.FootprintZone
import com.nifty.sellerfootprint.model.TempStorageStats
import com.nifty.sellerfootprint.model.ZoneEvidenceStrength
import com.nifty.sellerfootprint.ui.theme.AppThemeMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val initialMillis = state.selectedTradingDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            viewModel.onTradingDateSelected(picked)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("Select Date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Delete Temporary NSE Reports?",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "This will permanently delete ${state.storageStats.fileCount} downloaded temporary " +
                        "NSE EOD report file(s) (${state.storageStats.formattedSize}) from app-private cache storage.\n\n" +
                        "App code, settings, saved analysis history, and unrelated Android files will NOT be touched. " +
                        "If you run a future analysis, the app will automatically download any needed NSE reports again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteTemporaryFiles()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete Reports")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NIFTY Seller Footprint",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "22-Session NSE EOD Zone Discovery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. TOP SECTION — ANALYSIS SELECTION
            item {
                AnalysisSelectionCard(
                    selectedDate = state.selectedTradingDate,
                    isDateValid = state.isDateValid,
                    dateValidationError = state.dateValidationError,
                    availableExpiries = state.availableExpiries,
                    selectedExpiry = state.selectedExpiry,
                    isManualOverride = state.isManualExpiryOverride,
                    isLoading = state.isLoading,
                    progressMessage = state.progressMessage,
                    onOpenDatePicker = { showDatePicker = true },
                    onExpirySelected = { viewModel.onExpiryManuallyChanged(it) },
                    onAnalyseClick = { viewModel.runAnalysis() }
                )
            }

            // Status / Error Banners
            state.errorMessage?.let { err ->
                item {
                    StatusAlertCard(
                        title = "NSE Data / Validation Notice",
                        message = err,
                        isError = true
                    )
                }
            }

            state.infoBannerMessage?.let { info ->
                item {
                    StatusAlertCard(
                        title = "Storage Updated",
                        message = info,
                        isError = false,
                        onDismiss = { viewModel.dismissInfoBanner() }
                    )
                }
            }

            // 2. NIFTY MARKET INFORMATION & FOUR ZONES
            state.analysisResult?.let { result ->
                item {
                    NiftyMarketInfoCard(result = result)
                }

                // 3. SUPPORT ZONES (S1 & S2)
                item {
                    SectionLabel(title = "SUPPORT ZONES (PUT SELLER FOOTPRINTS)")
                }
                item {
                    FootprintZoneCard(zone = result.s1, isSupport = true)
                }
                item {
                    FootprintZoneCard(zone = result.s2, isSupport = true)
                }

                // 4. RESISTANCE ZONES (R1 & R2)
                item {
                    SectionLabel(title = "RESISTANCE ZONES (CALL SELLER FOOTPRINTS)")
                }
                item {
                    FootprintZoneCard(zone = result.r1, isSupport = false)
                }
                item {
                    FootprintZoneCard(zone = result.r2, isSupport = false)
                }

                // 5. EXPANDABLE 22-SESSION RESEARCH & AUDIT TRAIL
                item {
                    ResearchAuditCard(result = result)
                }
            }

            // 6. EXPANDABLE RECENT ANALYSIS HISTORY (Same-Screen)
            if (state.historyItems.isNotEmpty()) {
                item {
                    AnalysisHistoryCard(
                        items = state.historyItems,
                        isBusy = state.isLoading,
                        onSelectHistoryItem = { viewModel.loadFromHistoryItem(it) }
                    )
                }
            }

            // 7. EXPANDABLE SETTINGS & METHODOLOGY GUIDE (Same-Screen)
            item {
                SettingsAndMethodologyCard(
                    currentTheme = state.themeMode,
                    onThemeSelected = { viewModel.onThemeModeChanged(it) }
                )
            }

            // 8. TEMPORARY NSE REPORT STORAGE & DELETE BUTTON
            item {
                TempStorageCard(
                    stats = state.storageStats,
                    isBusy = state.isLoading,
                    onDeleteRequest = { showDeleteDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisSelectionCard(
    selectedDate: LocalDate,
    isDateValid: Boolean,
    dateValidationError: String?,
    availableExpiries: List<ExpiryOption>,
    selectedExpiry: ExpiryOption?,
    isManualOverride: Boolean,
    isLoading: Boolean,
    progressMessage: String,
    onOpenDatePicker: () -> Unit,
    onExpirySelected: (ExpiryOption) -> Unit,
    onAnalyseClick: () -> Unit
) {
    var expiryMenuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Analysis Selection",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedButton(
                onClick = onOpenDatePicker,
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = "Select Trading Date",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Trading Date (Session 22)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = selectedDate.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (!isDateValid && dateValidationError != null) {
                Text(
                    text = dateValidationError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (isDateValid && availableExpiries.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = expiryMenuExpanded,
                    onExpandedChange = {
                        if (!isLoading) expiryMenuExpanded = !expiryMenuExpanded
                    }
                ) {
                    OutlinedTextField(
                        value = selectedExpiry?.displayLabel ?: "Auto-selecting nearest expiry...",
                        onValueChange = {},
                        readOnly = true,
                        enabled = !isLoading,
                        label = {
                            Text(
                                if (isManualOverride) "NIFTY Expiry (Manual Override)"
                                else "NIFTY Expiry (Auto-Selected Nearest)"
                            )
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expiryMenuExpanded)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    ExposedDropdownMenu(
                        expanded = expiryMenuExpanded,
                        onDismissRequest = { expiryMenuExpanded = false }
                    ) {
                        availableExpiries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.displayLabel,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                onClick = {
                                    expiryMenuExpanded = false
                                    onExpirySelected(option)
                                }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onAnalyseClick,
                enabled = isDateValid && !isLoading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Analysing NSE Data...")
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Analyse",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            if (isLoading && progressMessage.isNotBlank()) {
                Text(
                    text = progressMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun NiftyMarketInfoCard(result: AnalysisResult) {
    val isPositive = result.niftyPointsChange >= 0.0
    val changeColor = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)
    val sign = if (isPositive) "+" else ""

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NIFTY 50 EOD CLOSE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format("%.2f", result.niftyClose),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = changeColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "$sign${String.format("%.2f", result.niftyPointsChange)} ($sign${String.format("%.2f", result.niftyPercentChange)}%)",
                        style = MaterialTheme.typography.labelMedium,
                        color = changeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            InfoRow("Selected Trading Date", result.selectedTradingDate.toString())
            InfoRow("Active Expiry", result.selectedExpiry.displayLabel)
            InfoRow(
                "Intraday High / Low",
                "${String.format("%.2f", result.niftyHigh)} / ${String.format("%.2f", result.niftyLow)}"
            )
            result.indiaVix?.let { vix ->
                InfoRow("India VIX Close", String.format("%.2f", vix))
            }
            InfoRow(
                "Last Analysed (IST)",
                result.lastAnalyzedTimestamp.format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = result.dataStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FootprintZoneCard(
    zone: FootprintZone,
    isSupport: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    val badgeColor = when (zone.strength) {
        ZoneEvidenceStrength.STRONG -> if (isSupport) Color(0xFF10B981) else Color(0xFFF59E0B)
        ZoneEvidenceStrength.MODERATE -> Color(0xFF0284C7)
        ZoneEvidenceStrength.WEAK -> Color(0xFF64748B)
        ZoneEvidenceStrength.INCONCLUSIVE -> MaterialTheme.colorScheme.error
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = zone.role.code,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = zone.role.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse details" else "Expand details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (zone.isEstablished && zone.lowerBoundary != null && zone.upperBoundary != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${String.format("%.2f", zone.lowerBoundary)} – ${String.format("%.2f", zone.upperBoundary)}",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        val strikeLabel = zone.relevantStrikes.joinToString(", ") {
                            "${it.toInt()} ${zone.optionSide?.name.orEmpty()}"
                        }
                        Text(
                            text = "Strikes: $strikeLabel • Width: ${String.format("%.1f", zone.widthPoints ?: 0.0)} pts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = zone.strength.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = "Inconclusive / No Credible Zone",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = zone.selectionReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                if (zone.isEstablished) {
                    InfoRow("Open Interest (Lots)", "${zone.totalOiLots}")
                    InfoRow(
                        "Session 22 ΔOI (Lots)",
                        if (zone.deltaOiLots >= 0) "+${zone.deltaOiLots}" else "${zone.deltaOiLots}"
                    )
                    InfoRow("Avg Settle Price", String.format("%.2f", zone.settlePriceAvg))
                    InfoRow(
                        "Retention / ATS Ratio",
                        "PRR ${String.format("%.2f", zone.positionalRetentionRatio)} • ATS ${String.format("%.2f", zone.atsRatio)}x"
                    )
                    InfoRow("Historical Persistence", "${zone.persistenceSessions} prior session(s)")
                    InfoRow(
                        "Statistical Confidence (90% LB)",
                        zone.confidenceScore?.let { "${String.format("%.1f", it * 100.0)}%" }
                            ?: "Unrated (Sample < 5)"
                    )
                    DetailBlock("Confidence Basis", zone.confidenceBasis)
                    DetailBlock("Supporting Patterns", zone.supportingPatterns.joinToString("\n• ", prefix = "• "))
                    DetailBlock("Participant Alignment", zone.participantAlignment)
                    DetailBlock("Out-of-Sample Validation", zone.validationSummary)
                    DetailBlock("Session 22 EOD Test", zone.session22TestResult)
                    DetailBlock("Why Selected", zone.selectionReason)
                } else {
                    DetailBlock("Diagnostic Details", zone.validationSummary)
                    DetailBlock("Session 22 Status", zone.session22TestResult)
                }
            }
        }
    }
}

@Composable
private fun ResearchAuditCard(result: AnalysisResult) {
    var expanded by remember { mutableStateOf(false) }
    val audit = result.auditSummary

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "22-Session Research & Audit Trail",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Active Chain Envelope: ${audit.activeStrikeRangeMin.toInt()} – ${audit.activeStrikeRangeMax.toInt()} " +
                    "(${audit.strikesRetainedInEnvelope} of ${audit.totalStrikesScanned} strikes retained)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                DetailBlock("Strongest Footprint Zone", audit.strongestZoneSummary)
                DetailBlock("Weakest Footprint Zone", audit.weakestZoneSummary)
                DetailBlock("Persistent Multi-Session Areas", audit.persistentAreasSummary)
                DetailBlock("Newly Detected Session 22 Footprints", audit.newlyDetectedFootprintsSummary)
                DetailBlock("Positioning Shifts / Rolls", audit.shiftingPositioningSummary)
                DetailBlock(
                    "Phase A (Sessions 1–17) Discovered Patterns",
                    audit.phase1Patterns.joinToString("\n") {
                        "• ${it.patternId.code}: ${it.evidenceSummary}"
                    }.ifBlank { "None triggered in window." }
                )
                DetailBlock(
                    "Phase B (Sessions 18–21) Validation Audit",
                    audit.phase2ValidationNotes.joinToString("\n") { "• $it" }
                )
                DetailBlock(
                    "Phase B (Sessions 18–21) Novel Regime Patterns",
                    audit.phase2NewPatterns.joinToString("\n") {
                        "• ${it.patternId.code}: ${it.evidenceSummary}"
                    }.ifBlank { "No novel regime-only patterns triggered in Sessions 18–21." }
                )
                DetailBlock(
                    "Calibrated Feature Weights (L2-Regularized)",
                    audit.featureContributions.joinToString("\n") {
                        "• ${it.featureName}: weight ${String.format("%.2f", it.calibratedWeight)} " +
                            "(lift ${if (it.empiricalDefenseLift >= 0) "+" else ""}${String.format("%.1f", it.empiricalDefenseLift * 100.0)}%, n=${it.sampleObservations})"
                    }.ifBlank { "Default domain feature priors active." }
                )
                DetailBlock(
                    "Combined Active Ledger D Rules",
                    audit.combinedPatternSet.joinToString("\n") {
                        "• ${it.patternId.code} [${it.status.label}]"
                    }.ifBlank { "No active Ledger D patterns." }
                )
                DetailBlock(
                    "Session 22 Matched Combined Patterns",
                    audit.session22MatchedPatterns.joinToString("\n") { "• $it" }
                        .ifBlank { "No combined patterns matched on Session 22." }
                )
                if (audit.session22UnvalidatedObservations.isNotEmpty()) {
                    DetailBlock(
                        "Session 22 Unvalidated Observations (Logged Separately)",
                        audit.session22UnvalidatedObservations.joinToString("\n") { "• $it" }
                    )
                }
                DetailBlock(
                    "Methodology Limitations",
                    audit.limitationsSummary.joinToString("\n") { "• $it" }
                )
            }
        }
    }
}

@Composable
private fun AnalysisHistoryCard(
    items: List<AnalysisHistoryItem>,
    isBusy: Boolean,
    onSelectHistoryItem: (AnalysisHistoryItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recent Analysis History (${items.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!expanded) {
                Text(
                    text = "Tap to view saved date/expiry runs (preserved even if temporary NSE files are deleted).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                items.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isBusy) { onSelectHistoryItem(item) }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${item.tradingDate} • Exp ${item.expiryDate}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "Close ${String.format("%.2f", item.niftyClose)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "S1: ${item.s1Summary} | S2: ${item.s2Summary} | R1: ${item.r1Summary} | R2: ${item.r2Summary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAndMethodologyCard(
    currentTheme: AppThemeMode,
    onThemeSelected: (AppThemeMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings & Methodology Guide",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!expanded) {
                Text(
                    text = "Appearance theme & 22-session NSE EOD analytical rules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Text(
                    text = "Appearance Theme",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = currentTheme == mode,
                            onClick = { onThemeSelected(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }

                DetailBlock(
                    "1. Official Free NSE EOD Sources",
                    "• F&O UDiFF Common Bhavcopy (BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip)\n" +
                        "• Participant-Wise Open Interest (fao_participant_oi_DDMMYYYY.csv)\n" +
                        "• Daily Index Snapshot (ind_close_all_DDMMYYYY.csv)\n" +
                        "• F&O Daily Volatility (FOVOLT_DDMMYYYY.csv)"
                )
                DetailBlock(
                    "2. Dynamic Multi-Strike Envelope",
                    "Evaluates all strikes capturing 95% of cumulative CE+PE Open Interest mass bounded by a 2.5-sigma volatility envelope—never restricted to ATM or ATM ±1."
                )
                DetailBlock(
                    "3. 22-Session Discovery & Validation Split",
                    "• Sessions 1–17: Independent discovery of seller microstructure patterns (Ledger A).\n" +
                        "• Sessions 18–21: Out-of-sample validation of Ledger A + independent discovery of late-cycle patterns (Ledger C).\n" +
                        "• Combined Set (Ledger D): Retains validated signals, applies P6 short-covering veto and P7 tail-wing filter.\n" +
                        "• Session 22: Blind EOD test deriving narrow premium-cushioned S1, S2, R1, and R2 zones."
                )
            }
        }
    }
}

@Composable
private fun TempStorageCard(
    stats: TempStorageStats,
    isBusy: Boolean,
    onDeleteRequest: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Temporary NSE Report Storage",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Text(
                text = "Downloaded NSE EOD reports are stored temporarily in app-private cache storage " +
                    "(${stats.storagePathDisplay}) so switching expiries does not re-download files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            InfoRow("Temporary Files Stored", "${stats.fileCount} file(s) across ${stats.sessionFoldersCount} session(s)")
            InfoRow("Total Temporary Storage", stats.formattedSize)

            OutlinedButton(
                onClick = onDeleteRequest,
                enabled = !isBusy && stats.fileCount > 0,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (stats.fileCount > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Delete Temporary Files",
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
private fun StatusAlertCard(
    title: String,
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)? = null
) {
    val accent = if (isError) MaterialTheme.colorScheme.error else Color(0xFF10B981)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text("OK", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DetailBlock(heading: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
