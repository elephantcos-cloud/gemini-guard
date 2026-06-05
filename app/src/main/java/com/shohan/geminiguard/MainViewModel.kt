package com.shohan.geminiguard

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppState(
    val hasOverlayPermission: Boolean = false,
    val isServiceRunning: Boolean    = false,
    val isGuardActive: Boolean       = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun refresh() {
        _state.value = AppState(
            hasOverlayPermission = Settings.canDrawOverlays(getApplication()),
            isServiceRunning     = FloatingOverlayService.isRunning,
            isGuardActive        = FloatingOverlayService.isGuardActive
        )
    }
}
