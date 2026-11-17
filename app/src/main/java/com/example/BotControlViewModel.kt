package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PermissionStatus(
    val overlayGranted: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val notificationGranted: Boolean = true,
)

class BotControlViewModel(application: Application) : AndroidViewModel(application) {

    val botState: StateFlow<BotRuntimeState> = BotStateRepository.state
    val accessibilityConnected: StateFlow<Boolean> = BotAccessibilityService.isConnected

    private val _permissions = MutableStateFlow(PermissionStatus())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    init {
        BotStateRepository.loadPreferences(application)
        refreshPermissions(application)
    }

    fun refreshPermissions(context: Context) {
        val overlayOk = Settings.canDrawOverlays(context)
        val accessibilityOk = BotAccessibilityService.instance != null
        val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _permissions.update {
            PermissionStatus(
                overlayGranted = overlayOk,
                accessibilityConnected = accessibilityOk,
                notificationGranted = notificationsOk,
            )
        }
    }

    fun setTargetMap(map: String) {
        BotStateRepository.setTargetMap(getApplication(), map)
    }

    fun setTargetAlien(alien: String) {
        BotStateRepository.setTargetAlien(getApplication(), alien)
    }

    fun setCollectKeyBoxes(enabled: Boolean) {
        BotStateRepository.setCollectKeyBoxes(getApplication(), enabled)
    }

    fun setAttackAliens(enabled: Boolean) {
        BotStateRepository.setAttackAliens(getApplication(), enabled)
    }

    fun updateCalibration(calibration: HudCalibration) {
        BotStateRepository.updateCalibration(getApplication(), calibration)
    }

    fun resetCalibration() {
        BotStateRepository.updateCalibration(getApplication(), HudCalibration())
    }

    fun stopFloatingOverlay(context: Context) {
        val intent = Intent(context, FloatingUIService::class.java).apply {
            action = FloatingUIService.ACTION_STOP
        }
        context.startService(intent)
    }
}
