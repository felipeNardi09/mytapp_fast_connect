package com.mytapp.fastconnect.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Handles BLE scanning, connection, MTU negotiation, service discovery, the write queue and
 * notification subscriptions.
 *
 * Ported from the app's `BleManager.kt` with neutral types ([BleDevice], [BleConnectionState]).
 * Behavior is preserved verbatim: MTU 512, chunk size = MTU − 3, write queue draining one
 * operation at a time, ≤10 retries with 300 ms backoff, CCCD subscription, and both the API-33+
 * and legacy callback paths.
 *
 * This class has no UI and does not request runtime permissions — the host app must hold
 * `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (API 31+) or `ACCESS_FINE_LOCATION` (API 24-30) before
 * calling [startScan] / [connect]. Calls without the required permission are no-ops that log a
 * warning. For coroutine/Flow-based use, wrap this in [BleTransport].
 */
public class BleConnectionManager(
    private val context: Context,
) {
    public companion object {
        private const val TAG = "BleConnectionManager"
        private const val REQUESTED_MTU = 512

        // Standard Bluetooth SIG services use the base UUID pattern 0000XXXX-0000-1000-8000-00805f9b34fb.
        // The data channel lives in a vendor-specific service, so we skip these when
        // selecting the writable/notifiable characteristic.
        private val STANDARD_BLE_UUID_REGEX = Regex(
            "^0000[0-9a-f]{4}-0000-1000-8000-00805f9b34fb\$",
            RegexOption.IGNORE_CASE,
        )

        // Client Characteristic Configuration Descriptor — written to enable notifications/indications.
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    public val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var writableCharacteristic: BluetoothGattCharacteristic? = null

    // Negotiated ATT MTU. Default is 23 bytes; payload = mtu - 3.
    @Volatile
    private var negotiatedMtu: Int = 23

    // Guards against discoverServices being called twice on the same connection
    // (can happen when requestMtu returns false AND onMtuChanged still fires).
    @Volatile
    private var discoverServicesStarted = false

    // Write queue — BLE WRITE_TYPE_DEFAULT requires waiting for onCharacteristicWrite before
    // the next write. Messages are enqueued and drained one at a time.
    private val writeQueue = ArrayDeque<ByteArray>()

    @Volatile
    private var isWritePending = false

    // Tracks whether a CCCD descriptor write is in progress. Characteristic writes must wait
    // until this completes, otherwise writeCharacteristic returns false because only one
    // outstanding GATT operation is allowed at a time.
    @Volatile
    private var isDescriptorWritePending = false

    // Maximum number of consecutive retries for a single write before giving up
    private var writeRetryCount = 0
    private val maxWriteRetries = 10

    private var onDeviceFound: ((BleDevice) -> Unit)? = null
    private var onConnectionStateChanged: ((BleConnectionState) -> Unit)? = null
    private var onServicesDiscovered: ((List<BluetoothGattService>) -> Unit)? = null
    private var onScanStopped: (() -> Unit)? = null
    private var onDataReceived: ((String) -> Unit)? = null

    private var isScanning = false

    /** True once service discovery has selected a writable characteristic. */
    public val isConnected: Boolean
        get() = bluetoothGatt != null && writableCharacteristic != null

    // ScanCallback runs on the main thread by default — no posting needed
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            Log.d(TAG, "onScanResult: $result")
            onDeviceFound?.invoke(
                BleDevice(
                    name = safeDeviceName(device),
                    address = address,
                    rssi = result.rssi,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: errorCode=$errorCode")
            isScanning = false
            mainHandler.post { onScanStopped?.invoke() }
        }
    }

    // BluetoothGattCallback runs on a Binder thread — must post to main thread before
    // touching any state observed by the UI/consumer
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "onConnectionStateChange: STATE_CONNECTED, address=${gatt.device.address}")
                    val deviceName = safeDeviceName(gatt.device)
                    val deviceAddress = gatt.device.address
                    mainHandler.post {
                        onConnectionStateChanged?.invoke(
                            BleConnectionState.Connected(name = deviceName, address = deviceAddress),
                        )
                    }
                    // Request a larger MTU before discovering services so that messages longer
                    // than the default 20-byte payload can be sent in a single write.
                    // Service discovery is triggered from onMtuChanged once MTU is negotiated.
                    try {
                        Log.d(TAG, "onConnectionStateChange: requesting MTU=$REQUESTED_MTU")
                        val mtuRequested = gatt.requestMtu(REQUESTED_MTU)
                        if (!mtuRequested) {
                            Log.w(TAG, "onConnectionStateChange: requestMtu returned false, falling back to discoverServices")
                            if (!discoverServicesStarted) {
                                discoverServicesStarted = true
                                gatt.discoverServices()
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "onConnectionStateChange: SecurityException on requestMtu: ${e.message}")
                        mainHandler.post {
                            onConnectionStateChanged?.invoke(
                                BleConnectionState.Error("Permission denied requesting MTU: ${e.message}"),
                            )
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "onConnectionStateChange: STATE_DISCONNECTED, address=${gatt.device.address}")
                    mainHandler.post {
                        onConnectionStateChanged?.invoke(BleConnectionState.Disconnected)
                        closeGatt()
                    }
                }

                else -> {
                    Log.w(TAG, "onConnectionStateChange: unexpected state status=$status, newState=$newState")
                    mainHandler.post {
                        onConnectionStateChanged?.invoke(
                            BleConnectionState.Error("Unexpected state change: status=$status, newState=$newState"),
                        )
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu status=$status (requested=$REQUESTED_MTU)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.d(TAG, "onMtuChanged: stored negotiatedMtu=$negotiatedMtu (payload=${mtu - 3} bytes)")
            }
            // Proceed with service discovery regardless of whether the full MTU was granted.
            // The negotiated MTU is still larger than the default 23 bytes in most cases.
            if (discoverServicesStarted) {
                Log.d(TAG, "onMtuChanged: discoverServices already started, skipping duplicate call")
                return
            }
            discoverServicesStarted = true
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                Log.e(TAG, "onMtuChanged: SecurityException on discoverServices: ${e.message}")
                mainHandler.post {
                    onConnectionStateChanged?.invoke(
                        BleConnectionState.Error("Permission denied discovering services: ${e.message}"),
                    )
                }
            }
        }

        // Android 13+ delivers the value directly; older versions read it from characteristic.value
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val message = value.toString(Charsets.UTF_8)
            Log.d(TAG, "onCharacteristicChanged (API33+): uuid=${characteristic.uuid} data='$message'")
            mainHandler.post { onDataReceived?.invoke(message) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val message = characteristic.value?.toString(Charsets.UTF_8) ?: return
            Log.d(TAG, "onCharacteristicChanged (legacy): uuid=${characteristic.uuid} data='$message'")
            mainHandler.post { onDataReceived?.invoke(message) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.uuid} status=$status char=${descriptor.characteristic?.uuid}")
            // CCCD write completed — GATT is now free for characteristic writes.
            if (isDescriptorWritePending) {
                isDescriptorWritePending = false
                Log.d(TAG, "onDescriptorWrite: descriptor write completed, draining write queue")
                mainHandler.post { drainWriteQueue(gatt) }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.d(TAG, "onCharacteristicWrite: status=$status uuid=${characteristic.uuid}")
            isWritePending = false
            mainHandler.post { drainWriteQueue(gatt) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services ?: emptyList()
                Log.d(TAG, "onServicesDiscovered: success, ${services.size} service(s) found")

                services.forEachIndexed { si, service ->
                    Log.d(TAG, "onServicesDiscovered: service[$si] uuid=${service.uuid} type=${service.type}")
                    service.characteristics.forEachIndexed { ci, char ->
                        val props = buildString {
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("READ ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("WRITE ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WRITE_NO_RESPONSE ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("NOTIFY ")
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("INDICATE ")
                        }.trim()
                        Log.d(TAG, "onServicesDiscovered:   char[$ci] uuid=${char.uuid} properties=[$props]")
                    }
                }

                // Find the first writable characteristic in a vendor-specific service.
                // Standard BLE services (Generic Access 0x1800, Generic Attribute 0x1801, etc.)
                // are skipped — their writable characteristics (e.g. 0x2A00 Device Name) are
                // not the data channel.
                // Prefer WRITE_NO_RESPONSE (no per-packet ACK) over WRITE for UART-style protocols.
                val vendorServices = services.filter {
                    !STANDARD_BLE_UUID_REGEX.matches(it.uuid.toString())
                }
                Log.d(TAG, "onServicesDiscovered: ${vendorServices.size} vendor service(s) after filtering standard UUIDs")

                writableCharacteristic = vendorServices
                    .flatMap { it.characteristics }
                    .firstOrNull { char ->
                        char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                    } ?: vendorServices
                    .flatMap { it.characteristics }
                    .firstOrNull { char ->
                        char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    }
                Log.d(TAG, "onServicesDiscovered: auto-selected writableCharacteristic=${writableCharacteristic?.uuid}")

                // Subscribe to all notifiable/indicatable characteristics in vendor services
                // so the device can send data back.
                vendorServices
                    .flatMap { it.characteristics }
                    .filter { char ->
                        char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    }
                    .forEach { char ->
                        try {
                            val enabled = gatt.setCharacteristicNotification(char, true)
                            Log.d(TAG, "onServicesDiscovered: setCharacteristicNotification uuid=${char.uuid} enabled=$enabled")
                            val cccd = char.getDescriptor(UUID.fromString(CCCD_UUID))
                            if (cccd != null) {
                                val value =
                                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    } else {
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    }
                                isDescriptorWritePending = true
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(cccd, value)
                                } else {
                                    @Suppress("DEPRECATION")
                                    cccd.value = value
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(cccd)
                                }
                                Log.d(TAG, "onServicesDiscovered: wrote CCCD for uuid=${char.uuid}")
                            } else {
                                Log.w(TAG, "onServicesDiscovered: no CCCD descriptor for uuid=${char.uuid}")
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "onServicesDiscovered: SecurityException subscribing to ${char.uuid}: ${e.message}")
                        }
                    }

                mainHandler.post { onServicesDiscovered?.invoke(services) }
            } else {
                Log.e(TAG, "onServicesDiscovered: failed with status=$status")
                mainHandler.post {
                    onConnectionStateChanged?.invoke(
                        BleConnectionState.Error("Failed to discover services: $status"),
                    )
                }
            }
        }
    }

    /** Registers callbacks. Pass `{}` for any you don't need. */
    public fun setCallbacks(
        onDeviceFound: (BleDevice) -> Unit = {},
        onConnectionStateChanged: (BleConnectionState) -> Unit = {},
        onServicesDiscovered: (List<BluetoothGattService>) -> Unit = {},
        onScanStopped: () -> Unit = {},
        onDataReceived: (String) -> Unit = {},
    ) {
        this.onDeviceFound = onDeviceFound
        this.onConnectionStateChanged = onConnectionStateChanged
        this.onServicesDiscovered = onServicesDiscovered
        this.onScanStopped = onScanStopped
        this.onDataReceived = onDataReceived
    }

    public fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    public fun hasBleFeature(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    public fun startScan(scanPeriodMs: Long = 10_000L) {
        if (!hasScanPermission()) {
            Log.w(TAG, "startScan: missing BLUETOOTH_SCAN permission, aborting")
            return
        }
        if (!isBluetoothEnabled()) {
            Log.w(TAG, "startScan: Bluetooth is not enabled, aborting")
            return
        }
        if (isScanning) {
            Log.d(TAG, "startScan: already scanning, ignoring")
            return
        }

        val bleScanner = scanner ?: run {
            Log.e(TAG, "startScan: BluetoothLeScanner is null, aborting")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        try {
            bleScanner.startScan(null, settings, scanCallback)
            Log.d(TAG, "startScan: scan started, will auto-stop after ${scanPeriodMs}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan: SecurityException: ${e.message}")
            isScanning = false
            return
        }

        mainHandler.postDelayed({
            Log.d(TAG, "startScan: scan period elapsed, stopping scan")
            stopScan()
        }, scanPeriodMs)
    }

    public fun stopScan() {
        if (!hasScanPermission()) {
            Log.w(TAG, "stopScan: missing BLUETOOTH_SCAN permission, aborting")
            return
        }
        if (!isScanning) {
            Log.d(TAG, "stopScan: not scanning, ignoring")
            return
        }

        try {
            scanner?.stopScan(scanCallback)
            Log.d(TAG, "stopScan: scan stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "stopScan: SecurityException: ${e.message}")
        }
        isScanning = false
        onScanStopped?.invoke()
    }

    public fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            Log.w(TAG, "connect: missing BLUETOOTH_CONNECT permission, aborting")
            return
        }

        Log.d(TAG, "connect: connecting to address=${device.address}")
        stopScan()

        onConnectionStateChanged?.invoke(BleConnectionState.Connecting(device.address))

        closeGatt()
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            Log.d(TAG, "connect: connectGatt called for address=${device.address}")
        } catch (e: SecurityException) {
            Log.e(TAG, "connect: SecurityException: ${e.message}")
            onConnectionStateChanged?.invoke(
                BleConnectionState.Error("Permission denied connecting: ${e.message}"),
            )
        }
    }

    /** Connects to a device by MAC address using the system adapter's remote-device lookup. */
    public fun connect(address: String) {
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "connect: bluetoothAdapter is null, aborting")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "connect: invalid address '$address': ${e.message}")
            onConnectionStateChanged?.invoke(BleConnectionState.Error("Invalid address: $address"))
            return
        }
        connect(device)
    }

    public fun disconnect() {
        if (!hasConnectPermission()) {
            Log.w(TAG, "disconnect: missing BLUETOOTH_CONNECT permission, aborting")
            return
        }
        try {
            bluetoothGatt?.disconnect()
            Log.d(TAG, "disconnect: disconnect called")
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnect: SecurityException: ${e.message}")
        }
    }

    /**
     * Sends a UTF-8 string to the connected device via the first writable characteristic found
     * during service discovery. Returns true if the write was submitted (enqueued).
     *
     * Messages longer than the negotiated MTU payload (MTU − 3) are split into chunks.
     */
    public fun sendString(data: String): Boolean {
        Log.d(TAG, "sendString: called with data='$data' (${data.length} chars, ${data.toByteArray(Charsets.UTF_8).size} bytes)")

        if (bluetoothGatt == null) {
            Log.w(TAG, "sendString: no active GATT connection, aborting")
            return false
        }
        if (writableCharacteristic == null) {
            Log.w(TAG, "sendString: writableCharacteristic is null — services may not have been discovered yet, aborting")
            return false
        }
        if (!hasConnectPermission()) {
            Log.w(TAG, "sendString: missing BLUETOOTH_CONNECT permission, aborting")
            return false
        }

        val bytes = data.toByteArray(Charsets.UTF_8)
        val chunkSize = negotiatedMtu - 3
        if (bytes.size <= chunkSize) {
            writeQueue.addLast(bytes)
            Log.d(TAG, "sendString: enqueued ${bytes.size} bytes as single chunk")
        } else {
            var offset = 0
            var chunkIndex = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                writeQueue.addLast(bytes.copyOfRange(offset, end))
                Log.d(TAG, "sendString: enqueued chunk[$chunkIndex] bytes $offset..$end")
                offset = end
                chunkIndex++
            }
        }
        Log.d(TAG, "sendString: queue size=${writeQueue.size} chunkSize=$chunkSize")

        bluetoothGatt?.let { drainWriteQueue(it) }
        return true
    }

    private fun drainWriteQueue(gatt: BluetoothGatt) {
        if (isWritePending || isDescriptorWritePending || writeQueue.isEmpty()) {
            if (isDescriptorWritePending && writeQueue.isNotEmpty()) {
                Log.d(TAG, "drainWriteQueue: waiting for CCCD descriptor write to complete before writing characteristic")
            }
            return
        }

        val char = writableCharacteristic ?: run {
            Log.w(TAG, "drainWriteQueue: writableCharacteristic is null, clearing queue")
            writeQueue.clear()
            return
        }
        if (!hasConnectPermission()) {
            Log.w(TAG, "drainWriteQueue: missing BLUETOOTH_CONNECT permission, clearing queue")
            writeQueue.clear()
            return
        }

        val bytes = writeQueue.removeFirst()
        val writeType =
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        Log.d(TAG, "drainWriteQueue: writing ${bytes.size} bytes uuid=${char.uuid} writeType=$writeType remaining=${writeQueue.size}")

        try {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(char, bytes, writeType)
                Log.d(TAG, "drainWriteQueue: writeCharacteristic result=$result")
                result == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }

            if (success) {
                writeRetryCount = 0
                // For WRITE_TYPE_DEFAULT, wait for onCharacteristicWrite before sending next.
                // For WRITE_TYPE_NO_RESPONSE, no callback comes — drain immediately.
                if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                    isWritePending = true
                } else {
                    // Small delay between no-response writes to avoid flooding the GATT
                    mainHandler.postDelayed({ drainWriteQueue(gatt) }, 10L)
                }
                Log.d(TAG, "drainWriteQueue: write submitted successfully")
            } else {
                writeRetryCount++
                if (writeRetryCount > maxWriteRetries) {
                    Log.e(TAG, "drainWriteQueue: write failed after $maxWriteRetries retries, dropping ${bytes.size} bytes and clearing queue")
                    writeQueue.clear()
                    writeRetryCount = 0
                } else {
                    Log.w(TAG, "drainWriteQueue: write failed (attempt $writeRetryCount/$maxWriteRetries), scheduling retry in 300ms")
                    writeQueue.addFirst(bytes)
                    mainHandler.postDelayed({ drainWriteQueue(gatt) }, 300L)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "drainWriteQueue: SecurityException: ${e.message}", e)
            writeRetryCount++
            if (writeRetryCount > maxWriteRetries) {
                Log.e(TAG, "drainWriteQueue: SecurityException persisted after $maxWriteRetries retries, clearing queue")
                writeQueue.clear()
                writeRetryCount = 0
            } else {
                writeQueue.addFirst(bytes)
                mainHandler.postDelayed({ drainWriteQueue(gatt) }, 300L)
            }
        }
    }

    public fun release() {
        Log.d(TAG, "release: releasing resources")
        stopScan()
        closeGatt()
    }

    private fun closeGatt() {
        try {
            if (bluetoothGatt != null) {
                bluetoothGatt?.close()
                Log.d(TAG, "closeGatt: GATT closed")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "closeGatt: SecurityException: ${e.message}")
        }
        bluetoothGatt = null
        writableCharacteristic = null
        negotiatedMtu = 23
        discoverServicesStarted = false
        writeQueue.clear()
        isWritePending = false
        isDescriptorWritePending = false
        writeRetryCount = 0
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun safeDeviceName(device: BluetoothDevice): String? {
        if (!hasConnectPermission()) return null
        return try {
            device.name
        } catch (e: SecurityException) {
            null
        }
    }

    private fun hasScanPermission(): Boolean {
        return when {
            // Android 12+: requires BLUETOOTH_SCAN runtime permission
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) Log.w(TAG, "hasScanPermission: BLUETOOTH_SCAN not granted")
                granted
            }
            // Android 6–11: requires ACCESS_FINE_LOCATION to receive BLE scan results
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) Log.w(TAG, "hasScanPermission: ACCESS_FINE_LOCATION not granted (required for BLE on Android 6-11)")
                granted
            }
            // Android 5 and below: normal BLUETOOTH permission is auto-granted
            else -> true
        }
    }

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT is a runtime permission only on Android 12+
        // On older versions the legacy BLUETOOTH normal permission covers connecting and is auto-granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) Log.w(TAG, "hasConnectPermission: BLUETOOTH_CONNECT not granted")
        return granted
    }
}
