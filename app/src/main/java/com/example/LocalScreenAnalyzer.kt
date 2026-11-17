package com.example

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class LocalScreenAnalyzer {

    companion object {
        // Sampling strides for fast frame analysis (<5ms on device)
        private const val MINIMAP_STEP = 3
        private const val PLAYFIELD_STEP = 8

        // Minimap ROI (top-right corner: x 78%..98%, y 4%..28%)
        private const val MINIMAP_LEFT = 0.78f
        private const val MINIMAP_RIGHT = 0.98f
        private const val MINIMAP_TOP = 0.04f
        private const val MINIMAP_BOTTOM = 0.28f

        // Playfield ROI (exclude edge HUD bars)
        private const val FIELD_LEFT = 0.12f
        private const val FIELD_RIGHT = 0.88f
        private const val FIELD_TOP = 0.12f
        private const val FIELD_BOTTOM = 0.86f

        // Minimap danger radius threshold from center or red pixels around player
        private const val MINIMAP_DANGER_RADIUS = 0.34f
        private const val IDEAL_ORBIT_RADIUS = 0.20f
    }

    private data class PointCluster(
        val normX: Float,
        val normY: Float,
        val pixelHits: Int,
    )

    var hasAttacked: Boolean = false
        private set

    var inCombat: Boolean = false
        private set

    fun resetState() {
        hasAttacked = false
        inCombat = false
    }

    fun analyzeFrame(
        frame: Bitmap,
        targetMap: String,
        targetAlien: String,
        collectKeyBoxes: Boolean,
        attackAliens: Boolean,
        currentConfig: Int,
    ): BotDecision {
        val width = frame.width
        val height = frame.height
        if (width < 32 || height < 32) {
            return BotDecision(
                accion = "roam",
                config = currentConfig,
                dx = 0,
                dy = 0,
                willContinue = false,
                razon = "Frame too small for local pixel scan",
            )
        }

        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)

        // =========================================================================
        // PRIORITY 1: PREDICTIVE EVASION (Absolute Override)
        // =========================================================================
        val pvpDangerRadius = if (targetMap.contains("PvP", ignoreCase = true)) {
            MINIMAP_DANGER_RADIUS - 0.04f
        } else {
            MINIMAP_DANGER_RADIUS
        }
        val evasionVector = detectMinimapPredictiveEvasion(pixels, width, height, pvpDangerRadius)
        if (evasionVector != null) {
            // Cancel everything else immediately
            resetState()
            if (currentConfig != 1) {
                return BotDecision(
                    accion = "cambiar_config",
                    config = 1,
                    dx = evasionVector.first,
                    dy = evasionVector.second,
                    willContinue = false,
                    releaseJoystick = true,
                    razon = "EVASION OVERRIDE: Red border near minimap center -> Config 1 (Speed)",
                )
            }
            return BotDecision(
                accion = "evadir",
                config = 1,
                dx = evasionVector.first,
                dy = evasionVector.second,
                willContinue = true,
                razon = "EVASION OVERRIDE: Steering away from red radiation border (dx=${evasionVector.first}, dy=${evasionVector.second})",
            )
        }

        // =========================================================================
        // PRIORITY 2: FREE BONUS BOXES (Pause kiting/combat & tap immediately)
        // =========================================================================
        val bonusBoxCluster = detectBonusBox(pixels, width, height)
        if (bonusBoxCluster != null) {
            // Pause kiting/combat cleanly so the tap registers on the box coordinate
            if (inCombat) {
                resetState()
            }
            return BotDecision(
                accion = "recoger_caja_libre",
                config = currentConfig,
                tapX = bonusBoxCluster.normX,
                tapY = bonusBoxCluster.normY,
                willContinue = false,
                releaseJoystick = true,
                esperarCajaLlave = false,
                razon = "FREE BOX: Tapping bonus box at (${(bonusBoxCluster.normX * 100).roundToInt()}%, ${(bonusBoxCluster.normY * 100).roundToInt()}%)",
            )
        }

        // =========================================================================
        // PRIORITY 3: COMBAT (Only if "Attack Aliens" checkbox == TRUE)
        // =========================================================================
        val alienCluster = if (attackAliens) detectTargetAlien(pixels, width, height, targetAlien) else null
        val triangleCluster = if (attackAliens) detectTargetingTriangle(pixels, width, height) else null
        val enemyCluster = alienCluster ?: triangleCluster

        if (attackAliens && enemyCluster != null) {
            // 1. Switch to Config 2 (Shields) before engaging
            if (currentConfig != 2) {
                return BotDecision(
                    accion = "cambiar_config",
                    config = 2,
                    willContinue = false,
                    releaseJoystick = true,
                    razon = "COMBAT: Target detected -> switching to Config 2 (Shields)",
                )
            }

            // Compute circular time-based sine/cosine kiting offsets around enemy
            val relX = enemyCluster.normX - 0.5f
            val relY = enemyCluster.normY - 0.5f
            val distance = hypot(relX, relY)
            val (kiteDx, kiteDy) = computeOrbitalKiteOffset(relX, relY, distance)

            // 2. Single-Tap Attack ONCE (never spam) + Start continuous kiting
            if (!hasAttacked) {
                hasAttacked = true
                inCombat = true
                return BotDecision(
                    accion = "atacar_y_kitear",
                    config = 2,
                    dx = kiteDx,
                    dy = kiteDy,
                    tapAttackOnce = true,
                    willContinue = true,
                    razon = "COMBAT START: Single-tap Attack on '$targetAlien' + orbital kiting (dx=$kiteDx, dy=$kiteDy)",
                )
            }

            // 3. Continuous Kiting while enemy stays alive
            inCombat = true
            return BotDecision(
                accion = "kitear",
                config = 2,
                dx = kiteDx,
                dy = kiteDy,
                tapAttackOnce = false,
                willContinue = true,
                razon = "KITING '$targetAlien' in Config 2 (dx=$kiteDx, dy=$kiteDy)",
            )
        } else if (inCombat || hasAttacked) {
            // 4. Enemy died (or Attack Aliens toggled off): reset state, release joystick, return to Config 1
            resetState()
            return BotDecision(
                accion = "fin_combate",
                config = 1,
                willContinue = false,
                releaseJoystick = true,
                razon = "COMBAT ENDED: Reset attack lock, release joystick & return to Config 1 (Speed)",
            )
        }

        // =========================================================================
        // PRIORITY 4: KEY BOXES (Only if "Collect Key Boxes" checkbox == TRUE)
        // =========================================================================
        if (collectKeyBoxes) {
            val keyBoxCluster = detectKeyLootBox(pixels, width, height)
            if (keyBoxCluster != null) {
                return BotDecision(
                    accion = "recoger_caja_llave",
                    config = currentConfig,
                    tapX = keyBoxCluster.normX,
                    tapY = keyBoxCluster.normY,
                    willContinue = false,
                    releaseJoystick = true,
                    esperarCajaLlave = true,
                    razon = "KEY BOX: Tapping at (${(keyBoxCluster.normX * 100).roundToInt()}%, ${(keyBoxCluster.normY * 100).roundToInt()}%) & pausing 3.2s",
                )
            }
        }

        // =========================================================================
        // DEFAULT STATE: ROAMING (Continuous exploration in Config 1)
        // =========================================================================
        if (currentConfig != 1) {
            return BotDecision(
                accion = "cambiar_config",
                config = 1,
                willContinue = false,
                releaseJoystick = true,
                razon = "ROAMING: Switching to Config 1 (Speed)",
            )
        }

        val roamAngleRad = ((System.currentTimeMillis() % 7000L) / 7000.0) * (Math.PI * 2.0)
        val roamDx = (cos(roamAngleRad) * 85.0).roundToInt().coerceIn(-100, 100)
        val roamDy = (sin(roamAngleRad) * 85.0).roundToInt().coerceIn(-100, 100)
        return BotDecision(
            accion = "roam",
            config = 1,
            dx = roamDx,
            dy = roamDy,
            willContinue = true,
            razon = "ROAMING [$targetMap]: Exploring continuously for boxes (dx=$roamDx, dy=$roamDy)",
        )
    }

    private fun detectMinimapPredictiveEvasion(
        pixels: IntArray,
        width: Int,
        height: Int,
        dangerRadiusThreshold: Float,
    ): Pair<Int, Int>? {
        val xStart = (width * MINIMAP_LEFT).toInt().coerceIn(0, width - 1)
        val xEnd = (width * MINIMAP_RIGHT).toInt().coerceIn(xStart + 1, width)
        val yStart = (height * MINIMAP_TOP).toInt().coerceIn(0, height - 1)
        val yEnd = (height * MINIMAP_BOTTOM).toInt().coerceIn(yStart + 1, height)

        val mapW = (xEnd - xStart).toFloat()
        val mapH = (yEnd - yStart).toFloat()

        var redDangerSumX = 0f
        var redDangerSumY = 0f
        var redDangerHits = 0

        var shipSumX = 0f
        var shipSumY = 0f
        var shipCount = 0

        for (y in yStart until yEnd step MINIMAP_STEP) {
            val rowOffset = y * width
            for (x in xStart until xEnd step MINIMAP_STEP) {
                val pixel = pixels[rowOffset + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val normX = (x - xStart) / mapW
                val normY = (y - yStart) / mapH

                // Player blip on minimap (bright white dot)
                if (r > 225 && g > 225 && b > 225) {
                    shipSumX += normX
                    shipSumY += normY
                    shipCount++
                }

                // Red radiation border pixels
                if (r > 190 && g < 75 && b < 75) {
                    val distToCenter = hypot(normX - 0.5f, normY - 0.5f)
                    if (distToCenter < dangerRadiusThreshold) {
                        redDangerSumX += normX
                        redDangerSumY += normY
                        redDangerHits++
                    }
                }
            }
        }

        // Trigger predictive evasion if red border pixels encroach near minimap center
        if (redDangerHits >= 8) {
            val redCx = redDangerSumX / redDangerHits
            val redCy = redDangerSumY / redDangerHits
            // Push strictly AWAY from red pixels toward safe center (0.5, 0.5)
            return vectorAwayFrom(redCx, redCy, 0.5f, 0.5f)
        }

        // Also evade if the player blip itself is near the minimap edge
        if (shipCount > 0) {
            val shipNormX = shipSumX / shipCount
            val shipNormY = shipSumY / shipCount
            val shipDistFromCenter = hypot(shipNormX - 0.5f, shipNormY - 0.5f)
            if (shipDistFromCenter > 0.36f) {
                return vectorToward(shipNormX, shipNormY, 0.5f, 0.5f)
            }
        }

        return null
    }

    private fun detectTargetAlien(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetAlien: String,
    ): PointCluster? {
        val xStart = (width * FIELD_LEFT).toInt().coerceIn(0, width - 1)
        val xEnd = (width * FIELD_RIGHT).toInt().coerceIn(xStart + 1, width)
        val yStart = (height * FIELD_TOP).toInt().coerceIn(0, height - 1)
        val yEnd = (height * FIELD_BOTTOM).toInt().coerceIn(yStart + 1, height)

        var sumX = 0L
        var sumY = 0L
        var count = 0

        for (y in yStart until yEnd step PLAYFIELD_STEP) {
            val rowOffset = y * width
            for (x in xStart until xEnd step PLAYFIELD_STEP) {
                val nx = x.toFloat() / width
                val ny = y.toFloat() / height
                if (nx in 0.46f..0.54f && ny in 0.45f..0.55f) continue

                val p = pixels[rowOffset + x]
                if (matchesAlienColorProfile(p, targetAlien)) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }

        if (count < 8) return null
        return PointCluster(
            normX = (sumX.toFloat() / count) / width,
            normY = (sumY.toFloat() / count) / height,
            pixelHits = count,
        )
    }

    private fun detectTargetingTriangle(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): PointCluster? {
        val xStart = (width * FIELD_LEFT).toInt().coerceIn(0, width - 1)
        val xEnd = (width * FIELD_RIGHT).toInt().coerceIn(xStart + 1, width)
        val yStart = (height * FIELD_TOP).toInt().coerceIn(0, height - 1)
        val yEnd = (height * FIELD_BOTTOM).toInt().coerceIn(yStart + 1, height)

        var sumX = 0L
        var sumY = 0L
        var count = 0

        for (y in yStart until yEnd step PLAYFIELD_STEP) {
            val rowOffset = y * width
            for (x in xStart until xEnd step PLAYFIELD_STEP) {
                val p = pixels[rowOffset + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                // Bright red/crimson lock-on targeting triangle around an engaged enemy
                if (r > 235 && g < 45 && b < 45) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }

        if (count < 4) return null
        return PointCluster(
            normX = (sumX.toFloat() / count) / width,
            normY = (sumY.toFloat() / count) / height,
            pixelHits = count,
        )
    }

    private fun matchesAlienColorProfile(pixel: Int, targetAlien: String): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        return when (targetAlien) {
            "Cube" -> b > 195 && g in 140..240 && r < 95
            "Mini" -> r > 205 && g in 45..110 && b < 85
            "Zyrex" -> g > 195 && r < 110 && b < 110
            "Kyrex" -> r > 175 && b > 175 && g < 95
            "Zorax" -> r > 215 && g in 95..165 && b < 60
            "Nexor" -> r > 195 && g < 55 && b in 60..135
            "Pyramid" -> r > 210 && g in 150..215 && b < 70
            "Spider" -> b > 155 && r in 100..185 && g < 70
            "Zerathis" -> r > 210 && b > 120 && g < 70
            else -> {
                (r > 200 && g < 85 && b < 95) ||
                    (g > 195 && r < 105 && b < 105) ||
                    (r > 180 && b > 175 && g < 90) ||
                    (r > 215 && g in 95..160 && b < 60)
            }
        }
    }

    private fun detectBonusBox(pixels: IntArray, width: Int, height: Int): PointCluster? {
        val xStart = (width * FIELD_LEFT).toInt().coerceIn(0, width - 1)
        val xEnd = (width * FIELD_RIGHT).toInt().coerceIn(xStart + 1, width)
        val yStart = (height * FIELD_TOP).toInt().coerceIn(0, height - 1)
        val yEnd = (height * FIELD_BOTTOM).toInt().coerceIn(yStart + 1, height)

        var sumX = 0L
        var sumY = 0L
        var count = 0

        for (y in yStart until yEnd step PLAYFIELD_STEP) {
            val rowOffset = y * width
            for (x in xStart until xEnd step PLAYFIELD_STEP) {
                val p = pixels[rowOffset + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val isBonusYellow = r > 220 && g > 200 && b < 80
                val isMineralAqua = g > 215 && b > 220 && r < 80
                if (isBonusYellow || isMineralAqua) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }

        if (count < 5) return null
        return PointCluster(
            normX = (sumX.toFloat() / count) / width,
            normY = (sumY.toFloat() / count) / height,
            pixelHits = count,
        )
    }

    private fun detectKeyLootBox(pixels: IntArray, width: Int, height: Int): PointCluster? {
        val xStart = (width * FIELD_LEFT).toInt().coerceIn(0, width - 1)
        val xEnd = (width * FIELD_RIGHT).toInt().coerceIn(xStart + 1, width)
        val yStart = (height * FIELD_TOP).toInt().coerceIn(0, height - 1)
        val yEnd = (height * FIELD_BOTTOM).toInt().coerceIn(yStart + 1, height)

        var sumX = 0L
        var sumY = 0L
        var count = 0

        for (y in yStart until yEnd step PLAYFIELD_STEP) {
            val rowOffset = y * width
            for (x in xStart until xEnd step PLAYFIELD_STEP) {
                val p = pixels[rowOffset + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val isKeyLootGlow = (r > 195 && g in 140..190 && b in 180..255)
                if (isKeyLootGlow) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }

        if (count < 5) return null
        return PointCluster(
            normX = (sumX.toFloat() / count) / width,
            normY = (sumY.toFloat() / count) / height,
            pixelHits = count,
        )
    }

    private fun computeOrbitalKiteOffset(
        relX: Float,
        relY: Float,
        distance: Float,
    ): Pair<Int, Int> {
        val orbitPeriodMs = 3600L
        val timePhaseRad = ((System.currentTimeMillis() % orbitPeriodMs) / orbitPeriodMs.toDouble()) * (Math.PI * 2.0)

        if (distance < 0.001f) {
            val dx = (cos(timePhaseRad) * 92.0).roundToInt().coerceIn(-100, 100)
            val dy = (sin(timePhaseRad) * 92.0).roundToInt().coerceIn(-100, 100)
            return dx to dy
        }

        val angleToEnemy = atan2(relY.toDouble(), relX.toDouble())
        val radialError = ((distance - IDEAL_ORBIT_RADIUS) / 0.15f).coerceIn(-1f, 1f)
        val tangentAngle = angleToEnemy + (Math.PI / 2.0) - (radialError * 0.45) + (sin(timePhaseRad) * 0.25)

        val dx = (cos(tangentAngle) * 92.0).roundToInt().coerceIn(-100, 100)
        val dy = (sin(tangentAngle) * 92.0).roundToInt().coerceIn(-100, 100)
        return dx to dy
    }

    private fun vectorAwayFrom(
        dangerX: Float,
        dangerY: Float,
        safeCenterX: Float,
        safeCenterY: Float,
    ): Pair<Int, Int> {
        val angle = atan2((safeCenterY - dangerY).toDouble(), (safeCenterX - dangerX).toDouble())
        val dx = (cos(angle) * 100.0).roundToInt().coerceIn(-100, 100)
        val dy = (sin(angle) * 100.0).roundToInt().coerceIn(-100, 100)
        return dx to dy
    }

    private fun vectorToward(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
    ): Pair<Int, Int> {
        val angle = atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        val dx = (cos(angle) * 90.0).roundToInt().coerceIn(-100, 100)
        val dy = (sin(angle) * 90.0).roundToInt().coerceIn(-100, 100)
        return dx to dy
    }
}
