package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BotAccessibilitySvc"
        private const val TAP_DURATION_MS = 45L
        private const val JOYSTICK_STEP_DURATION_MS = 120L
        private const val KEY_BOX_WAIT_MS = 3200L

        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }

    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null
    private var lastJoystickX: Float = 0f
    private var lastJoystickY: Float = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "BotAccessibilityService conectado y listo para dispatchGesture.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gestures are dispatched on demand by FloatingUIService.
    }

    override fun onInterrupt() {
        activeJoystickStroke = null
        Log.w(TAG, "BotAccessibilityService interrumpido.")
    }

    override fun onDestroy() {
        activeJoystickStroke = null
        instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    suspend fun releaseContinuousJoystick() {
        val currentStroke = activeJoystickStroke ?: return
        activeJoystickStroke = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val releasePath = Path().apply {
                    moveTo(lastJoystickX, lastJoystickY)
                    lineTo(lastJoystickX, lastJoystickY)
                }
                val endStroke = currentStroke.continueStroke(releasePath, 0L, 20L, false)
                dispatchGestureSuspend(GestureDescription.Builder().addStroke(endStroke).build())
            } catch (e: Exception) {
                Log.w(TAG, "Error liberando joystick continuo: ${e.message}")
            }
        }
    }

    suspend fun executeDecision(
        decision: BotDecision,
        calibration: HudCalibration,
    ): Long {
        if (decision.releaseJoystick) {
            releaseContinuousJoystick()
        }

        return when (decision.accion) {
            "cambiar_config", "fin_combate" -> {
                tapProportional(calibration.configButtonX, calibration.configButtonY)
                0L
            }

            "evadir", "roam", "kitear" -> {
                updateContinuousJoystick(
                    centerXRatio = calibration.joystickCenterX,
                    centerYRatio = calibration.joystickCenterY,
                    dx = decision.dx,
                    dy = decision.dy,
                    willContinue = decision.willContinue,
                )
                0L
            }

            "atacar_y_kitear" -> {
                // Ensure any previous continuous stroke is released before single-tap attack
                releaseContinuousJoystick()
                if (decision.tapAttackOnce) {
                    tapProportional(calibration.attackButtonX, calibration.attackButtonY)
                }
                // Immediately engage continuous kiting stroke (willContinue = true)
                updateContinuousJoystick(
                    centerXRatio = calibration.joystickCenterX,
                    centerYRatio = calibration.joystickCenterY,
                    dx = decision.dx,
                    dy = decision.dy,
                    willContinue = true,
                )
                0L
            }

            "recoger_caja_libre" -> {
                val tx = decision.tapX ?: calibration.collectButtonX
                val ty = decision.tapY ?: calibration.collectButtonY
                tapProportional(tx, ty)
                0L
            }

            "recoger_caja_llave" -> {
                val tx = decision.tapX ?: calibration.collectButtonX
                val ty = decision.tapY ?: calibration.collectButtonY
                tapProportional(tx, ty)
                if (decision.esperarCajaLlave) KEY_BOX_WAIT_MS else 0L
            }

            else -> {
                Log.w(TAG, "Acción no reconocida: ${decision.accion}")
                0L
            }
        }
    }

    suspend fun tapProportional(xRatio: Float, yRatio: Float): Boolean {
        val metrics = resources.displayMetrics
        val x = (metrics.widthPixels * xRatio.coerceIn(0.01f, 0.99f))
        val y = (metrics.heightPixels * yRatio.coerceIn(0.01f, 0.99f))

        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
    }

    suspend fun updateContinuousJoystick(
        centerXRatio: Float,
        centerYRatio: Float,
        dx: Int,
        dy: Int,
        willContinue: Boolean,
        durationMs: Long = JOYSTICK_STEP_DURATION_MS,
    ): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val centerX = width * centerXRatio.coerceIn(0.05f, 0.95f)
        val centerY = height * centerYRatio.coerceIn(0.05f, 0.95f)

        val maxRadiusPx = minOf(width, height) * 0.14f
        val deltaX = (dx.coerceIn(-100, 100) / 100f) * maxRadiusPx
        val deltaY = (dy.coerceIn(-100, 100) / 100f) * maxRadiusPx

        val targetX = (centerX + deltaX).coerceIn(1f, width - 1f)
        val targetY = (centerY + deltaY).coerceIn(1f, height - 1f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val previousStroke = activeJoystickStroke
            val nextStroke: GestureDescription.StrokeDescription = if (previousStroke != null) {
                try {
                    val movePath = Path().apply {
                        moveTo(lastJoystickX, lastJoystickY)
                        lineTo(targetX, targetY)
                    }
                    previousStroke.continueStroke(movePath, 0L, durationMs, willContinue)
                } catch (e: Exception) {
                    Log.w(TAG, "Reiniciando stroke continuo tras interrupción: ${e.message}")
                    val freshPath = Path().apply {
                        moveTo(centerX, centerY)
                        lineTo(targetX, targetY)
                    }
                    GestureDescription.StrokeDescription(freshPath, 0L, durationMs, willContinue)
                }
            } else {
                val startPath = Path().apply {
                    moveTo(centerX, centerY)
                    lineTo(targetX, targetY)
                }
                GestureDescription.StrokeDescription(startPath, 0L, durationMs, willContinue)
            }

            lastJoystickX = targetX
            lastJoystickY = targetY
            activeJoystickStroke = if (willContinue) nextStroke else null

            val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
            val ok = dispatchGestureSuspend(gesture)
            if (!ok) {
                activeJoystickStroke = null
            }
            return ok
        } else {
            val fallbackPath = Path().apply {
                moveTo(centerX, centerY)
                lineTo(targetX, targetY)
            }
            val stroke = GestureDescription.StrokeDescription(fallbackPath, 0L, durationMs)
            return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
        }
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean {
        val resultDeferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    resultDeferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesto cancelado por el sistema.")
                    resultDeferred.complete(false)
                }
            },
            null,
        )
        if (!dispatched) {
            Log.e(TAG, "No se pudo despachar el gesto de accesibilidad.")
            return false
        }
        return resultDeferred.await()
    }
}
