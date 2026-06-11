package com.mytapp.fastconnect.android

import android.bluetooth.BluetoothDevice
import com.mytapp.fastconnect.core.Transport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Adapts [BleConnectionManager] to the core [Transport] interface so a
 * [com.mytapp.fastconnect.core.MyTappFastConnectClient] can drive it.
 *
 * Wiring:
 * - [isConnected] reflects [BleConnectionManager.isConnected] (connected AND a writable
 *   characteristic selected during service discovery — i.e. actually able to send).
 * - [send] delegates to the manager's write queue (chunking + retries handled there).
 * - [incomingMessages] re-exposes the notification callback as a [SharedFlow].
 *
 * It also surfaces [connectionState] and [discoveredDevices] for scanning/connection UI.
 *
 * Constructing a `BleTransport` installs callbacks on the manager (it owns them), so create one
 * per manager and route all scan/connection UI through the flows exposed here.
 */
public class BleTransport(
    private val manager: BleConnectionManager,
) : Transport {

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incomingMessages: SharedFlow<String> = _incoming.asSharedFlow()

    private val _connectionState =
        MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)

    /** Latest connection lifecycle state. */
    public val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableSharedFlow<BleDevice>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** Devices reported during scanning. */
    public val discoveredDevices: SharedFlow<BleDevice> = _discoveredDevices.asSharedFlow()

    init {
        manager.setCallbacks(
            onDeviceFound = { device -> _discoveredDevices.tryEmit(device) },
            onConnectionStateChanged = { state -> _connectionState.value = state },
            onServicesDiscovered = { /* connection readiness is observed via isConnected */ },
            onScanStopped = { /* no-op; scan timeout is internal to the manager */ },
            onDataReceived = { message -> _incoming.tryEmit(message) },
        )
    }

    override val isConnected: Boolean
        get() = manager.isConnected

    override suspend fun send(message: String): Boolean = manager.sendString(message)

    // ---- passthrough lifecycle controls ----

    public fun startScan(scanPeriodMs: Long = 10_000L): Unit = manager.startScan(scanPeriodMs)

    public fun stopScan(): Unit = manager.stopScan()

    public fun connect(device: BluetoothDevice): Unit = manager.connect(device)

    public fun connect(address: String): Unit = manager.connect(address)

    public fun disconnect(): Unit = manager.disconnect()

    public fun release(): Unit = manager.release()

    public fun isBluetoothEnabled(): Boolean = manager.isBluetoothEnabled()

    public fun hasBleFeature(): Boolean = manager.hasBleFeature()
}
