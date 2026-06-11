package com.mytapp.fastconnect.android

/**
 * Connection lifecycle states surfaced by [BleConnectionManager].
 *
 * Ported from the app's `BleConnectionState`. Messages on [Error] are intentionally plain
 * strings so the type stays free of Android dependencies and crosses the bridges unchanged.
 */
public sealed interface BleConnectionState {
    public data object Disconnected : BleConnectionState
    public data class Connecting(val address: String) : BleConnectionState
    public data class Connected(
        val name: String?,
        val address: String,
    ) : BleConnectionState

    public data class Error(val message: String) : BleConnectionState
}
