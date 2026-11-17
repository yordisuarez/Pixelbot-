package com.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

val ALIEN_TARGETS = listOf(
    "Any",
    "Cube",
    "Mini",
    "Zyrex",
    "Kyrex",
    "Zorax",
    "Nexor",
    "Pyramid",
    "Spider",
    "Zerathis",
)

data class BotDecision(
    val accion: String,
    val config: Int,
    val dx: Int,
    val dy: Int,
    val atacar: Boolean,
    val recoger: Boolean,
    val esperarCajaLlave: Boolean,
    val razon: String,
)

data class HudCalibration(
    val joystickCenterX: Float = 0.16f,
    val joystickCenterY: Float = 0.72f,
    val attackButtonX: Float = 0.87f,
    val attackButtonY: Float = 0.72f,
    val collectButtonX: Float = 0.74f,
    val collectButtonY: Float = 0.80f,
    val configButtonX: Float = 0.12f,
    val configButtonY: Float = 0.18f,
)

data class BotRuntimeState(
    val targetAlien: String = ALIEN_TARGETS.first(),
    val collectKeyBoxes: Boolean = false,
    val isOverlayRunning: Boolean = false,
    val isBotActive: Boolean = false,
    val hasMediaProjection: Boolean = false,
    val statusMessage: String = "Status: Idle (Ready)",
    val lastError: String? = null,
    val lastDecision: BotDecision? = null,
    val calibration: HudCalibration = HudCalibration(),
    val framesAnalyzed: Int = 0,
)

object BotStateRepository {
    private const val PREFS_NAME = "novaborn_bot_prefs"
    private const val KEY_TARGET_ALIEN = "target_alien"
    private const val KEY_COLLECT_KEY_BOXES = "collect_key_boxes"
    private const val KEY_JOYSTICK_X = "cal_joystick_x"
    private const val KEY_JOYSTICK_Y = "cal_joystick_y"
    private const val KEY_ATTACK_X = "cal_attack_x"
    private const val KEY_ATTACK_Y = "cal_attack_y"
    private const val KEY_COLLECT_X = "cal_collect_x"
    private const val KEY_COLLECT_Y = "cal_collect_y"
    private const val KEY_CONFIG_X = "cal_config_x"
    private const val KEY_CONFIG_Y = "cal_config_y"

    private val _state = MutableStateFlow(BotRuntimeState())
    val state: StateFlow<BotRuntimeState> = _state.asStateFlow()

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alien = prefs.getString(KEY_TARGET_ALIEN, ALIEN_TARGETS.first()) ?: ALIEN_TARGETS.first()
        val collectKey = prefs.getBoolean(KEY_COLLECT_KEY_BOXES, false)
        val cal = HudCalibration(
            joystickCenterX = prefs.getFloat(KEY_JOYSTICK_X, 0.16f),
            joystickCenterY = prefs.getFloat(KEY_JOYSTICK_Y, 0.72f),
            attackButtonX = prefs.getFloat(KEY_ATTACK_X, 0.87f),
            attackButtonY = prefs.getFloat(KEY_ATTACK_Y, 0.72f),
            collectButtonX = prefs.getFloat(KEY_COLLECT_X, 0.74f),
            collectButtonY = prefs.getFloat(KEY_COLLECT_Y, 0.80f),
            configButtonX = prefs.getFloat(KEY_CONFIG_X, 0.12f),
            configButtonY = prefs.getFloat(KEY_CONFIG_Y, 0.18f),
        )
        _state.update {
            it.copy(
                targetAlien = if (alien in ALIEN_TARGETS) alien else ALIEN_TARGETS.first(),
                collectKeyBoxes = collectKey,
                calibration = cal,
            )
        }
    }

    fun setTargetAlien(context: Context, alien: String) {
        val valid = if (alien in ALIEN_TARGETS) alien else ALIEN_TARGETS.first()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_ALIEN, valid)
            .apply()
        _state.update { it.copy(targetAlien = valid) }
    }

    fun setCollectKeyBoxes(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COLLECT_KEY_BOXES, enabled)
            .apply()
        _state.update { it.copy(collectKeyBoxes = enabled) }
    }

    fun updateCalibration(context: Context, calibration: HudCalibration) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_JOYSTICK_X, calibration.joystickCenterX)
            .putFloat(KEY_JOYSTICK_Y, calibration.joystickCenterY)
            .putFloat(KEY_ATTACK_X, calibration.attackButtonX)
            .putFloat(KEY_ATTACK_Y, calibration.attackButtonY)
            .putFloat(KEY_COLLECT_X, calibration.collectButtonX)
            .putFloat(KEY_COLLECT_Y, calibration.collectButtonY)
            .putFloat(KEY_CONFIG_X, calibration.configButtonX)
            .putFloat(KEY_CONFIG_Y, calibration.configButtonY)
            .apply()
        _state.update { it.copy(calibration = calibration) }
    }

    fun setOverlayState(running: Boolean, hasProjection: Boolean) {
        _state.update {
            it.copy(
                isOverlayRunning = running,
                hasMediaProjection = hasProjection,
                isBotActive = if (!running) false else it.isBotActive,
            )
        }
    }

    fun setBotActive(active: Boolean, statusMessage: String) {
        _state.update {
            it.copy(
                isBotActive = active,
                statusMessage = statusMessage,
                lastError = if (active) null else it.lastError,
            )
        }
    }

    fun updateStatus(message: String, error: String? = null) {
        _state.update {
            it.copy(
                statusMessage = message,
                lastError = error,
            )
        }
    }

    fun recordDecision(decision: BotDecision) {
        _state.update {
            it.copy(
                lastDecision = decision,
                lastError = null,
                framesAnalyzed = it.framesAnalyzed + 1,
                statusMessage = "Action: ${decision.accion.uppercase()} (Cfg ${decision.config}) - ${decision.razon}",
            )
        }
    }
}
