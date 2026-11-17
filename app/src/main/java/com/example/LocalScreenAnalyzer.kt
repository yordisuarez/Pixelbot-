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
        // Sampling strides for sub-5ms frame analysis on mobile CPUs
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

        // Ideal normalized combat orbit radius from screen center (ship position)
        private const val IDEAL_ORBIT_RADIUS = 0.20f
        private const val ATTACK_RANGE_RADIUS = 0.36f
        private const val COLLECT_RANGE_RADIUS = 0.24f
    }

    private data class PointCluster(
        val normX: Float,
        val normY: Float,
        val pixelHits: Int,
    )

    private var patrolAngleRad = 0.0
    private var combatOrbitPhaseRad = 0.0

    fun analyzeFrame(
        frame: Bitmap,
        targetMap: String,
        targetAlien: String,
        collectKeyBoxes: Boolean,
        currentConfig: Int,
    ): BotDecision {
        val width = frame.width
        val height = frame.height
        if (width < 32 || height < 32) {
            return BotDecision(
                accion = "mover",
                config = currentConfig,
                dx = 0,
                dy = 0,
                atacar = false,
                recoger = false,
                esperarCajaLlave = false,
                razon = "Frame too small for local pixel scan",
            )
        }

        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Check minimap (top-right) for red radiation border proximity
        val safeMargin = if (targetMap.contains("PvP", ignoreCase = true)) 0.22f else 0.18f
        val radiationVector = detectMinimapRadiationEvasion(pixels, width, height, safeMargin)
        if (radiationVector != null) {
            if (currentConfig != 1) {
                return BotDecision(
                    accion = "cambiar_config",
                    config = 1,
                    dx = radiationVector.first,
                    dy = radiationVector.second,
                    atacar = false,
                    recoger = false,
                    esperarCajaLlave = false,
                    razon = "Radiation alert on minimap: switching to Config 1 (Speed) to escape",
                )
            }
            return BotDecision(
                accion = "mover",
                config = 1,
                dx = radiationVector.first,
                dy = radiationVector.second,
                atacar = false,
                recoger = false,
                esperarCajaLlave = false,
                razon = "Minimap red radiation zone detected: steering toward safe center",
            )
        }

        // 2. Scan playfield for Target Alien, Bonus Boxes, and Key Loot Boxes
        val alienCluster = detectTargetAlien(pixels, width, height, targetAlien)
        val bonusBoxCluster = detectBonusBox(pixels, width, height)
        val keyBoxCluster = if (collectKeyBoxes) detectKeyLootBox(pixels, width, height) else null

        // 3. Priority: Loot Collection if nearby & not actively engaged in close combat
        if (keyBoxCluster != null) {
            val dist = hypot(keyBoxCluster.normX - 0.5f, keyBoxCluster.normY - 0.5f)
            if (dist <= COLLECT_RANGE_RADIUS) {
                return BotDecision(
                    accion = "recoger",
                    config = 1,
                    dx = 0,
                    dy = 0,
                    atacar = false,
                    recoger = true,
                    esperarCajaLlave = true,
                    razon = "Key loot box within range (${keyBoxCluster.pixelHits} px): collecting & waiting 3.2s",
                )
            }
        }

        if (bonusBoxCluster != null && alienCluster == null) {
            val dist = hypot(bonusBoxCluster.normX - 0.5f, bonusBoxCluster.normY - 0.5f)
            if (dist <= COLLECT_RANGE_RADIUS) {
                return BotDecision(
                    accion = "recoger",
                    config = 1,
                    dx = 0,
                    dy = 0,
                    atacar = false,
                    recoger = true,
                    esperarCajaLlave = false,
                    razon = "Bonus box/mineral in range: collecting",
                )
            }
            val (dx, dy) = vectorToward(0.5f, 0.5f, bonusBoxCluster.normX, bonusBoxCluster.normY)
            return if (currentConfig != 1) {
                BotDecision(
                    accion = "cambiar_config",
                    config = 1,
                    dx = dx,
                    dy = dy,
                    atacar = false,
                    recoger = false,
                    esperarCajaLlave = false,
                    razon = "Switching to Config 1 (Speed) to reach bonus box",
                )
            } else {
                BotDecision(
                    accion = "mover",
                    config = 1,
                    dx = dx,
                    dy = dy,
                    atacar = false,
                    recoger = false,
                    esperarCajaLlave = false,
                    razon = "Moving toward detected bonus box/mineral",
                )
            }
        }

        // 4. Combat & Orbital Kiting when Target Alien is detected
        if (alienCluster != null) {
            val relX = alienCluster.normX - 0.5f
            val relY = alienCluster.normY - 0.5f
            val distance = hypot(relX, relY)

            if (distance <= ATTACK_RANGE_RADIUS) {
                if (currentConfig != 2) {
                    return BotDecision(
                        accion = "cambiar_config",
                        config = 2,
                        dx = 0,
                        dy = 0,
                        atacar = false,
                        recoger = false,
                        esperarCajaLlave = false,
                        razon = "Alien '$targetAlien' in combat range: switching to Config 2 (Shields)",
                    )
                }

                // Calculate tangential circular orbit vector (kite) + safe radius correction
                val (dx, dy) = computeOrbitalKiteOffset(relX, relY, distance)
                return BotDecision(
                    accion = "atacar",
                    config = 2,
                    dx = dx,
                    dy = dy,
                    atacar = true,
                    recoger = false,
                    esperarCajaLlave = false,
                    razon = "Orbiting '$targetAlien' in Config 2 (dx=$dx, dy=$dy) while firing",
                )
            } else {
                // Approach distant target alien using Config 1 (High Speed)
                val (dx, dy) = vectorToward(0.5f, 0.5f, alienCluster.normX, alienCluster.normY)
                if (currentConfig != 1) {
                    return BotDecision(
                        accion = "cambiar_config",
                        config = 1,
                        dx = dx,
                        dy = dy,
                        atacar = false,
                        recoger = false,
                        esperarCajaLlave = false,
                        razon = "Target '$targetAlien' distant: switching to Config 1 (Speed) to close gap",
                    )
                }
                return BotDecision(
                    accion = "mover",
                    config = 1,
                    dx = dx,
                    dy = dy,
                    atacar = false,
                    recoger = false,
                    esperarCajaLlave = false,
                    razon = "Approaching '$targetAlien' (${alienCluster.pixelHits} px match)",
                )
            }
        }

        // 5. Key Loot Box approach if visible outside immediate range
        if (keyBoxCluster != null) {
            val (dx, dy) = vectorToward(0.5f, 0.5f, keyBoxCluster.normX, keyBoxCluster.normY)
            if (currentConfig != 1) {
                return BotDecision(
                    accion = "cambiar_config",
                    config = 1,
                    dx = dx,
                    dy = dy,
                    atacar = false,
                    recoger = false,
                    esperarCajaLlave = false,
                    razon = "Switching to Config 1 to approach key loot box",
                )
            }
            return BotDecision(
                accion = "mover",
                config = 1,
                dx = dx,
                dy = dy,
                atacar = false,
                recoger = false,
                esperarCajaLlave = false,
                razon = "Approaching key loot box",
            )
        }

        // 6. Default Patrol in Config 1 (smooth sweeping search pattern)
        if (currentConfig != 1) {
            return BotDecision(
                accion = "cambiar_config",
                config = 1,
                dx = 0,
                dy = 0,
                atacar = false,
                recoger = false,
                esperarCajaLlave = false,
                razon = "Area clear: returning to Config 1 (High Speed) for patrol",
            )
        }

        patrolAngleRad = (patrolAngleRad + 0.14) % (Math.PI * 2.0)
        val patrolDx = (cos(patrolAngleRad) * 80.0).roundToInt().coerceIn(-100, 100)
        val patrolDy = (sin(patrolAngleRad) * 80.0).roundToInt().coerceIn(-100, 100)
        return BotDecision(
            accion = "mover",
            config = 1,
            dx = patrolDx,
            dy = patrolDy,
            atacar = false,
            recoger = false,
            esperarCajaLlave = false,
            razon = "Patrolling [$targetMap] in Config 1 searching for '$targetAlien'",
        )
    }

    private fun detectMinimapRadiationEvasion(
        pixels: IntArray,
        width: Int,
        height: Int,
        edgeMargin: Float,
    ): Pair<Int, Int>? {
        val xStart = (width * MINIMAP_LEFT).toInt().coerceIn(0, width - 1)
        val xEnd = (width * MINIMAP_RIGHT).toInt().coerceIn(xStart + 1, width)
        val yStart = (height * MINIMAP_TOP).toInt().coerceIn(0, height - 1)
        val yEnd = (height * MINIMAP_BOTTOM).toInt().coerceIn(yStart + 1, height)

        val mapW = (xEnd - xStart).toFloat()
        val mapH = (yEnd - yStart).toFloat()

        var shipSumX = 0f
        var shipSumY = 0f
        var shipCount = 0

        var redBorderNearShip = 0

        for (y in yStart until yEnd step MINIMAP_STEP) {
            val rowOffset = y * width
            for (x in xStart until xEnd step MINIMAP_STEP) {
                val pixel = pixels[rowOffset + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Player blip on minimap (bright white / cyan dot)
                if (r > 225 && g > 225 && b > 225) {
                    shipSumX += (x - xStart) / mapW
                    shipSumY += (y - yStart) / mapH
                    shipCount++
                }
            }
        }

        if (shipCount == 0) return null

        val shipNormX = shipSumX / shipCount
        val shipNormY = shipSumY / shipCount

        // Check if ship is inside outer margin of minimap OR adjacent to red radiation pixels
        val nearEdge = shipNormX < edgeMargin ||
            shipNormX > (1f - edgeMargin) ||
            shipNormY < edgeMargin ||
            shipNormY > (1f - edgeMargin)
        if (!nearEdge) {
            // Sample small window around ship blip for red radiation border pixels
            val shipPxX = xStart + (shipNormX * mapW).toInt()
            val shipPxY = yStart + (shipNormY * mapH).toInt()
            val radius = (minOf(mapW, mapH) * 0.14f).toInt().coerceAtLeast(6)
            for (y in (shipPxY - radius).coerceAtLeast(yStart) until (shipPxY + radius).coerceAtMost(yEnd) step 2) {
                val rowOffset = y * width
                for (x in (shipPxX - radius).coerceAtLeast(xStart) until (shipPxX + radius).coerceAtMost(xEnd) step 2) {
                    val p = pixels[rowOffset + x]
                    if (Color.red(p) > 195 && Color.green(p) < 70 && Color.blue(p) < 70) {
                        redBorderNearShip++
                    }
                }
            }
        }

        if (nearEdge || redBorderNearShip >= 6) {
            // Vector pointing from ship's minimap position straight back to minimap center (0.5, 0.5)
            return vectorToward(shipNormX, shipNormY, 0.5f, 0.5f)
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
                // Skip center 6% box where player ship sits
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

    private fun matchesAlienColorProfile(pixel: Int, targetAlien: String): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        return when (targetAlien) {
            // Cube: bright cyan/ice-blue geometric hulls
            "Cube" -> b > 195 && g in 140..240 && r < 95
            // Mini: small crimson/coral drones
            "Mini" -> r > 205 && g in 45..110 && b < 85
            // Zyrex: toxic neon green ships
            "Zyrex" -> g > 195 && r < 110 && b < 110
            // Kyrex: electric magenta/violet hulls
            "Kyrex" -> r > 175 && b > 175 && g < 95
            // Zorax: molten orange/amber predators
            "Zorax" -> r > 215 && g in 95..165 && b < 60
            // Nexor: deep crimson-crimson/red heavy units
            "Nexor" -> r > 195 && g < 55 && b in 60..135
            // Pyramid: golden-amber geometric bosses
            "Pyramid" -> r > 210 && g in 150..215 && b < 70
            // Spider: dark violet/indigo with bright red/purple accents
            "Spider" -> b > 155 && r in 100..185 && g < 70
            // Zerathis: intense neon crimson-violet boss signature
            "Zerathis" -> r > 210 && b > 120 && g < 70
            // Any: match common hostile alien hues (red/orange/neon-green/magenta)
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
                // Bright yellow/gold bonus boxes & glowing cyan mineral crystals
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
                // Key loot boxes: glowing emerald-gold or royal-purple locked chest trim
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
        // Advance circular kiting phase angle so the ship sweeps smoothly in circles
        combatOrbitPhaseRad = (combatOrbitPhaseRad + 0.35) % (Math.PI * 2.0)

        if (distance < 0.001f) {
            val dx = (cos(combatOrbitPhaseRad) * 90.0).roundToInt().coerceIn(-100, 100)
            val dy = (sin(combatOrbitPhaseRad) * 90.0).roundToInt().coerceIn(-100, 100)
            return dx to dy
        }

        // Base angle from ship toward enemy; add +90° (PI/2) for perpendicular circular orbit
        // plus a slight sine/cosine wobble and radial distance correction to hold safe firing radius
        val angleToEnemy = atan2(relY.toDouble(), relX.toDouble())
        val radialError = ((distance - IDEAL_ORBIT_RADIUS) / 0.15f).coerceIn(-1f, 1f)
        val tangentAngle = angleToEnemy + (Math.PI / 2.0) - (radialError * 0.45) + (sin(combatOrbitPhaseRad) * 0.12)

        val dx = (cos(tangentAngle) * 92.0).roundToInt().coerceIn(-100, 100)
        val dy = (sin(tangentAngle) * 92.0).roundToInt().coerceIn(-100, 100)
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
