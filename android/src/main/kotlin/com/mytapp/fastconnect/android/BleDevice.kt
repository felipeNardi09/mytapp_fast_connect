package com.mytapp.fastconnect.android

/**
 * A BLE device surfaced during scanning.
 *
 * Ported from the app's `BleDeviceUi`, renamed to drop the UI connotation since this library
 * has no UI.
 */
public data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int? = null,
)
