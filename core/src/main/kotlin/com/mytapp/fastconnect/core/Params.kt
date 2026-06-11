package com.mytapp.fastconnect.core

/**
 * Parameters for the `CONFIG` message.
 *
 * Neutral by design — this deliberately does NOT reference the app's `TapInfoResponse` or any
 * other app model. Hosts map their own holders onto this class.
 *
 * @property volumeShown displayed volume
 * @property stepsForward motor steps forward
 * @property stepsBackward motor steps backward
 * @property tick sensor tick
 * @property adjustSeconds adjustment in seconds; serialized as `round(adjustSeconds * 1000)` ms
 * @property cupDist cup distance
 * @property ledColor LED color in any accepted form (see [MessageBuilder]); normalized to `RRGGBB`
 * @property clientTimeout client timeout
 * @property useHallPositioning whether hall-effect positioning is used; serialized as `1`/`0`
 */
public data class ConfigParams(
    val volumeShown: Int,
    val stepsForward: Int,
    val stepsBackward: Int,
    val tick: Int,
    val adjustSeconds: Double,
    val cupDist: Int,
    val ledColor: String,
    val clientTimeout: Int,
    val useHallPositioning: Boolean,
)

/**
 * Parameters for the `INIT_SERVING` message.
 *
 * @property liquid liquid volume
 * @property foam foam volume
 * @property foamDecayMinutes minutes until foam decays
 * @property foamMsAfterDecay milliseconds of foam dispensing after decay
 */
public data class ServingParams(
    val liquid: Int,
    val foam: Int,
    val foamDecayMinutes: Int,
    val foamMsAfterDecay: Int,
)
