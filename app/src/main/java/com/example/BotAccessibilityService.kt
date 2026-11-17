package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BotAccessibilitySvc"
        private const val TAP_DURATION_MS = 65L
        private const val JOYSTICK_SWIPE_DURATION_MS = 320L
        private const val ORBIT_SWIPE_DURATION_MS = 460L
        private const val KEY_BOX_WAIT_MS = 3200L

        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "BotAccessibilityService conectado y listo para dispatchGesture.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event inspection needed; gestures are dispatched on demand by FloatingUIService.
    }

    override fun onInterrupt() {
        Log.w(TAG, "BotAccessibilityService interrumpido.")
    }

    override fun onDestroy() {
        instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    suspend fun executeDecision(
        decision: BotDecision,
        calibration: HudCalibration,
    ): Long {
        var extraDelayMs = 0L
        when (decision.accion) {
            "cambiar_config" -> {
                tapProportional(calibration.configButtonX, calibration.configButtonY)
            }
            "atacar" -> {
                tapProportional(calibration.attackButtonX, calibration.attackButtonY)
                // Execute orbital kiting joystick vector if provided during combat
                if (decision.dx != 0 || decision.dy != 0) {
                    dragJoystick(
                        centerXRatio = calibration.joystickCenterX,
                        centerYRatio = calibration.joystickCenterY,
                        dx = decision.dx,
                        dy = decision.dy,
                        durationMs = ORBIT_SWIPE_DURATION_MS,
                    )
                }
            }
            "recoger" -> {
                tapProportional(calibration.collectButtonX, calibration.collectButtonY)
                if (decision.esperarCajaLlave) {
                    extraDelayMs = KEY_BOX_WAIT_MS
                }
            }
            "mover" -> {
                if (decision.atacar) {
                    tapProportional(calibration.attackButtonX, calibration.attackButtonY)
                }
                dragJoystick(
                    centerXRatio = calibration.joystickCenterX,
                    centerYRatio = calibration.joystickCenterY,
                    dx = decision.dx,
                    dy = decision.dy,
                    durationMs = if (decision.atacar) ORBIT_SWIPE_DURATION_MS else JOYSTICK_SWIPE_DURATION_MS,
                )
            }
            else -> {
                Log.w(TAG, "Acción no reconocida: ${decision.accion}")
            }
        }
        return extraDelayMs
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

    suspend fun dragJoystick(
        centerXRatio: Float,
        centerYRatio: Float,
        dx: Int,
        dy: Int,
        durationMs: Long = JOYSTICK_SWIPE_DURATION_MS,
    ): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val startX = width * centerXRatio.coerceIn(0.05f, 0.95f)
        val startY = height * centerYRatio.coerceIn(0.05f, 0.95f)

        // Scale normalized -100..100 offset to a responsive joystick throw radius (~14% of min dimension)
        val maxRadiusPx = minOf(width, height) * 0.14f
        val deltaX = (dx.coerceIn(-100, 100) / 100f) * maxRadiusPx
        val deltaY = (dy.coerceIn(-100, 100) / 100f) * maxRadiusPx

        val endX = (startX + deltaX).coerceIn(1f, width - 1f)
        val endY = (startY + deltaY).coerceIn(1f, height - 1f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
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
