package com.mytapp.fastconnect.core

import kotlin.math.roundToInt

/**
 * The outcome of building a protocol string from typed parameters.
 *
 * No nulls cross this boundary: builders either produce a valid wire string or name the
 * offending field, so callers can surface a typed [SendResult.InvalidPayload].
 */
public sealed class BuildResult {
    public data class Ok(val message: String) : BuildResult()
    public data class Invalid(val field: String) : BuildResult()
}

/**
 * Pure, side-effect-free builders for every outgoing mytapp protocol message.
 *
 * Ported from the app's `FastConfigMessage.kt` and the `INIT_SERVING` / `MANAGER` call sites,
 * with all `android.util.Log` calls replaced by an injectable [Logger]. Nothing here touches
 * Android or I/O — it is exercised entirely by JVM unit tests.
 */
public class MessageBuilder(private val logger: Logger = NoOpLogger) {

    /**
     * Builds the `CONFIG` message.
     *
     * Format:
     * `CONFIG:{volumeShown},{stepsForward},{stepsBackward},{tick},{round(adjustSeconds*1000)},{cupDist},{RRGGBB},{clientTimeout},{hall 1|0}&`
     */
    public fun buildConfig(params: ConfigParams): BuildResult {
        // Physical quantities — negatives indicate a mapping error upstream.
        if (params.volumeShown < 0) return invalid("volumeShown")
        if (params.stepsForward < 0) return invalid("stepsForward")
        if (params.stepsBackward < 0) return invalid("stepsBackward")
        if (params.tick < 0) return invalid("tick")
        if (params.adjustSeconds.isNaN() || params.adjustSeconds.isInfinite()) {
            return invalid("adjustSeconds")
        }
        if (params.cupDist < 0) return invalid("cupDist")
        if (params.clientTimeout < 0) return invalid("clientTimeout")

        val adjust = (params.adjustSeconds * 1000).roundToInt()
        val ledColorNorm = normalizeLedColor(params.ledColor)
        val hallField = if (params.useHallPositioning) 1 else 0

        val message = "CONFIG:${params.volumeShown},${params.stepsForward}," +
            "${params.stepsBackward},${params.tick},$adjust,${params.cupDist}," +
            "$ledColorNorm,${params.clientTimeout},$hallField${Protocol.TERMINATOR}"

        if (message.length > 200) {
            logger.warn("CONFIG message length ${message.length} exceeds 200-byte warning threshold")
        }
        logger.debug("built CONFIG=$message")
        return BuildResult.Ok(message)
    }

    /**
     * Builds the `INIT_SERVING` message.
     *
     * Format: `INIT_SERVING:{liquid},{foam},{foamDecayMinutes},{foamMsAfterDecay}&`
     */
    public fun buildInitServing(params: ServingParams): BuildResult {
        if (params.liquid < 0) return invalid("liquid")
        if (params.foam < 0) return invalid("foam")
        if (params.foamDecayMinutes < 0) return invalid("foamDecayMinutes")
        if (params.foamMsAfterDecay < 0) return invalid("foamMsAfterDecay")

        val message = "INIT_SERVING:${params.liquid},${params.foam}," +
            "${params.foamDecayMinutes},${params.foamMsAfterDecay}${Protocol.TERMINATOR}"
        logger.debug("built INIT_SERVING=$message")
        return BuildResult.Ok(message)
    }

    /** Builds the `MANAGER_START&` message. */
    public fun buildManagerStart(): String = "MANAGER_START${Protocol.TERMINATOR}"

    /** Builds the `MANAGER_STOP&` message. */
    public fun buildManagerStop(): String = "MANAGER_STOP${Protocol.TERMINATOR}"

    /**
     * Normalizes a LED color string to 6-char uppercase hex (`RRGGBB`, no `#`).
     *
     * Accepts `RRGGBB`, `#RRGGBB`, `r,g,b`, `{r,g,b}`. Returns
     * [Protocol.DEFAULT_LED_COLOR] (`FFFFFF`) if the input cannot be parsed — color never
     * causes an [BuildResult.Invalid], matching the app's behavior.
     */
    public fun normalizeLedColor(ledColor: String): String {
        val clean = ledColor.trim().trimStart('#')

        if (clean.length == 6 && clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
            return clean.uppercase()
        }

        val csv = clean.trimStart('{').trimEnd('}')
        val parts = csv.split(',')
        if (parts.size == 3) {
            val r = parts[0].trim().toIntOrNull()
            val g = parts[1].trim().toIntOrNull()
            val b = parts[2].trim().toIntOrNull()
            if (r != null && g != null && b != null &&
                r in 0..255 && g in 0..255 && b in 0..255
            ) {
                return "%02X%02X%02X".format(r, g, b)
            }
        }

        logger.warn("normalizeLedColor: could not parse '$ledColor', falling back to ${Protocol.DEFAULT_LED_COLOR}")
        return Protocol.DEFAULT_LED_COLOR
    }

    private fun invalid(field: String): BuildResult.Invalid {
        logger.warn("CONFIG/SERVING: invalid field '$field', skipping message")
        return BuildResult.Invalid(field)
    }
}
