package com.example

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class GeminiApiClient {

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val MODEL_NAME = "gemini-3.8-flash"
        private const val MAX_FRAME_WIDTH = 1024
        private const val INFERENCE_TIMEOUT_MS = 30_000L
    }

    private val decisionSchema = Schema.obj(
        properties = mapOf(
            "accion" to Schema.enumeration(
                values = listOf("mover", "atacar", "recoger", "cambiar_config"),
                description = "Acción principal a ejecutar en el juego"
            ),
            "config" to Schema.integer(
                description = "Configuración deseada de la nave: 1 (Alta velocidad) o 2 (Escudos)"
            ),
            "dx" to Schema.integer(
                description = "Desplazamiento horizontal del joystick (-100 a 100)"
            ),
            "dy" to Schema.integer(
                description = "Desplazamiento vertical del joystick (-100 a 100)"
            ),
            "atacar" to Schema.boolean(
                description = "true si se debe pulsar el botón ATACAR"
            ),
            "recoger" to Schema.boolean(
                description = "true si se debe pulsar el botón recoger"
            ),
            "esperar_caja_llave" to Schema.boolean(
                description = "true si es una caja de botín con llave y requiere esperar 3.2s"
            ),
            "razon" to Schema.string(
                description = "Explicación de la táctica usada"
            ),
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = decisionSchema
                temperature = 0.2f
            },
        )
    }

    fun buildTacticalPrompt(targetAlien: String, collectKeyBoxes: Boolean): String {
        val alienSelectionRule = if (targetAlien == "Any") {
            "Any hostile alien visible on screen or minimap."
        } else {
            "Target ONLY '$targetAlien' (prioritize searching for '$targetAlien')."
        }

        return """
            You are the autonomous tactical vision agent for the mobile game "Pixel Universe: Novaborn".
            Current UI Settings:
            - Target Alien: $targetAlien ($alienSelectionRule)
            - Key Boxes Checkbox: $collectKeyBoxes

            Analyze the screen capture and follow these strict rules:
            1. Minimap (top right): The ship must never touch the outer red border (radiation zone). Move towards the center if close.
            2. Configurations: Use Config 1 (High Speed) to travel. You MUST switch to Config 2 (Shields) to attack.
            3. Combat & Kiting: If you detect the target alien selected in the UI, press the ATTACK button. STRICT EVASION RULE: While in combat, calculate `dx` and `dy` joystick offsets to constantly orbit (kite) the alien in circles, maintaining a safe radius to deal damage without taking hits.
            4. Collection: Always collect floating bonus boxes and minerals. If the 'Key Boxes' checkbox is false, ignore loot boxes. If true, collect them and wait.
            5. Output Format: You must return EXCLUSIVELY this pure JSON (no markdown tags):
            {
              "accion": "mover" | "atacar" | "recoger" | "cambiar_config",
              "config": 1 | 2,
              "dx": X joystick offset in pixels (-100 to 100),
              "dy": Y joystick offset in pixels (-100 to 100),
              "atacar": boolean,
              "recoger": boolean,
              "esperar_caja_llave": boolean,
              "razon": "tactical explanation"
            }
        """.trimIndent()
    }

    suspend fun analyzeFrame(
        frame: Bitmap,
        targetAlien: String,
        collectKeyBoxes: Boolean,
    ): Result<BotDecision> {
        val scaledBitmap = scaleBitmapForInference(frame)
        return try {
            val prompt = buildTacticalPrompt(targetAlien, collectKeyBoxes)
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                model.generateContent(
                    content {
                        image(scaledBitmap)
                        text(prompt)
                    }
                )
            }

            val jsonText = response.text
            if (jsonText.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Gemini returned an empty response."))
            }

            val json = JSONObject(jsonText)
            val decision = BotDecision(
                accion = json.optString("accion", "mover"),
                config = json.optInt("config", 1).coerceIn(1, 2),
                dx = json.optInt("dx", 0).coerceIn(-100, 100),
                dy = json.optInt("dy", 0).coerceIn(-100, 100),
                atacar = json.optBoolean("atacar", false),
                recoger = json.optBoolean("recoger", false),
                esperarCajaLlave = json.optBoolean("esperar_caja_llave", false),
                razon = json.optString("razon", "No tactical explanation provided"),
            )
            Result.success(decision)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini AI Logic inference timed out", e)
            Result.failure(IllegalStateException("Gemini request timed out after 30s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini AI Logic inference failed", e)
            Result.failure(e)
        } finally {
            if (scaledBitmap !== frame && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }

    private fun scaleBitmapForInference(source: Bitmap): Bitmap {
        if (source.width <= MAX_FRAME_WIDTH) return source
        val aspectRatio = source.height.toFloat() / source.width.toFloat()
        val targetHeight = (MAX_FRAME_WIDTH * aspectRatio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, MAX_FRAME_WIDTH, targetHeight, true)
    }
}
