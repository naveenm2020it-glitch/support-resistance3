package com.nifty.sellerfootprint.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nifty.sellerfootprint.data.AnalysisHistoryItem
import com.nifty.sellerfootprint.data.AnalysisHistoryStore
import com.nifty.sellerfootprint.data.ExpiryResolver
import com.nifty.sellerfootprint.data.NseDataRepository
import com.nifty.sellerfootprint.data.NseNetworkClient
import com.nifty.sellerfootprint.data.TempStorageManager
import com.nifty.sellerfootprint.data.TradingCalendarEngine
import com.nifty.sellerfootprint.engine.AnalysisOrchestrator
import com.nifty.sellerfootprint.model.AnalysisResult
import com.nifty.sellerfootprint.model.ExpiryOption
import com.nifty.sellerfootprint.model.TempStorageStats
import com.nifty.sellerfootprint.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MainUiState(
    val selectedTradingDate: LocalDate,
    val isDateValid: Boolean,
    val dateValidationError: String? = null,
    val availableExpiries: List<ExpiryOption> = emptyList(),
    val selectedExpiry: ExpiryOption? = null,
    val isManualExpiryOverride: Boolean = false,
    val isLoading: Boolean = false,
    val progressMessage: String = "",
    val errorMessage: String? = null,
    val infoBannerMessage: String? = null,
    val analysisResult: AnalysisResult? = null,
    val historyItems: List<AnalysisHistoryItem> = emptyList(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val storageStats: TempStorageStats
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val storageManager = TempStorageManager(application.cacheDir)
    private val historyStore = AnalysisHistoryStore(application.filesDir)
    private val repository = NseDataRepository(NseNetworkClient(), storageManager)
    private val orchestrator = AnalysisOrchestrator(repository)

    private val _uiState: MutableStateFlow<MainUiState>
    val uiState: StateFlow<MainUiState>

    init {
        val initialDate = TradingCalendarEngine.mostRecentProbableTradingDay()
        val initialExpiries = ExpiryResolver.previewCalendarExpiries(initialDate)
        val nearestExpiry = ExpiryResolver.selectNearestExpiry(initialExpiries)
        _uiState = MutableStateFlow(
            MainUiState(
                selectedTradingDate = initialDate,
                isDateValid = true,
                availableExpiries = initialExpiries,
                selectedExpiry = nearestExpiry,
                historyItems = historyStore.loadHistory(),
                storageStats = repository.getStorageStats()
            )
        )
        uiState = _uiState.asStateFlow()
    }

    fun onThemeModeChanged(mode: AppThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun onTradingDateSelected(newDate: LocalDate) {
        val validation = TradingCalendarEngine.validateTradingDate(newDate)
        when (validation) {
            is TradingCalendarEngine.DateValidationResult.Invalid -> {
                _uiState.update {
                    it.copy(
                        selectedTradingDate = newDate,
                        isDateValid = false,
                        dateValidationError = validation.reason,
                        availableExpiries = emptyList(),
                        selectedExpiry = null,
                        isManualExpiryOverride = false,
                        errorMessage = null,
                        infoBannerMessage = null,
                        analysisResult = null
                    )
                }
            }
            is TradingCalendarEngine.DateValidationResult.Valid -> {
                val previewExpiries = ExpiryResolver.previewCalendarExpiries(newDate)
                val nearest = ExpiryResolver.selectNearestExpiry(previewExpiries)
                _uiState.update {
                    it.copy(
                        selectedTradingDate = newDate,
                        isDateValid = true,
                        dateValidationError = null,
                        availableExpiries = previewExpiries,
                        selectedExpiry = nearest,
                        isManualExpiryOverride = false,
                        errorMessage = null,
                        infoBannerMessage = null,
                        analysisResult = null
                    )
                }
            }
        }
    }

    fun onExpiryManuallyChanged(expiry: ExpiryOption) {
        val currentState = _uiState.value
        if (currentState.selectedExpiry?.expiryDate == expiry.expiryDate) return

        _uiState.update {
            it.copy(
                selectedExpiry = expiry,
                isManualExpiryOverride = true,
                errorMessage = null
            )
        }

        if (currentState.isDateValid && currentState.analysisResult != null) {
            runAnalysis(explicitExpiry = expiry.expiryDate)
        }
    }

    fun loadFromHistoryItem(item: AnalysisHistoryItem) {
        onTradingDateSelected(item.tradingDate)
        val matched = _uiState.value.availableExpiries.firstOrNull { it.expiryDate == item.expiryDate }
        if (matched != null) {
            _uiState.update {
                it.copy(
                    selectedExpiry = matched,
                    isManualExpiryOverride = true
                )
            }
        }
        runAnalysis(explicitExpiry = item.expiryDate)
    }

    fun runAnalysis(explicitExpiry: LocalDate? = null) {
        val state = _uiState.value
        if (!state.isDateValid) {
            _uiState.update {
                it.copy(
                    errorMessage = state.dateValidationError
                        ?: "Select a valid NSE trading date before running analysis."
                )
            }
            return
        }

        val targetExpiryOverride = explicitExpiry
            ?: if (state.isManualExpiryOverride) state.selectedExpiry?.expiryDate else null

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    progressMessage = "Connecting to NSE EOD archives...",
                    errorMessage = null,
                    infoBannerMessage = null
                )
            }

            val outcome = orchestrator.executeAnalysis(
                selectedTradingDate = state.selectedTradingDate,
                manualExpiryOverride = targetExpiryOverride,
                onProgress = { stepMsg ->
                    _uiState.update { it.copy(progressMessage = stepMsg) }
                }
            )

            outcome.fold(
                onSuccess = { result ->
                    val updatedHistory = historyStore.recordAnalysis(result)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = null,
                            analysisResult = result,
                            availableExpiries = result.availableExpiries,
                            selectedExpiry = result.selectedExpiry,
                            historyItems = updatedHistory,
                            storageStats = repository.getStorageStats()
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = throwable.message
                                ?: "Unable to complete NSE EOD analysis for ${state.selectedTradingDate}.",
                            analysisResult = null,
                            storageStats = repository.getStorageStats()
                        )
                    }
                }
            )
        }
    }

    fun deleteTemporaryFiles() {
        val outcome = repository.deleteTemporaryFiles()
        _uiState.update {
            it.copy(
                storageStats = repository.getStorageStats(),
                infoBannerMessage = outcome.message
            )
        }
    }

    fun dismissInfoBanner() {
        _uiState.update { it.copy(infoBannerMessage = null) }
    }
}
