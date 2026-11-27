package com.nifty.sellerfootprint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nifty.sellerfootprint.ui.MainScreen
import com.nifty.sellerfootprint.ui.MainViewModel
import com.nifty.sellerfootprint.ui.theme.NiftySellerFootprintTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val uiState by vm.uiState.collectAsState()
            NiftySellerFootprintTheme(themeMode = uiState.themeMode) {
                MainScreen(viewModel = vm)
            }
        }
    }
}
