package com.mytapp.fastconnect.android

import android.content.Context
import com.mytapp.fastconnect.core.Logger
import com.mytapp.fastconnect.core.MyTappFastConnectClient

/**
 * Android entry point. Bundles a [BleConnectionManager], its [BleTransport], and a
 * [MyTappFastConnectClient] so a Kotlin/Java app gets a working stack in one call.
 *
 * ```kotlin
 * val fc = MyTappFastConnect.create(context)
 * fc.transport.startScan()
 * // ...collect fc.transport.discoveredDevices, then:
 * fc.transport.connect(address)
 * // once connected:
 * val result = fc.client.sendConfig(params)
 * ```
 *
 * The host app is responsible for requesting BLE runtime permissions before scanning/connecting.
 */
public class MyTappFastConnect private constructor(
    public val manager: BleConnectionManager,
    public val transport: BleTransport,
    public val client: MyTappFastConnectClient,
) {
    public fun release(): Unit = transport.release()

    public companion object {
        /**
         * Builds the full stack for [context].
         *
         * @param logger optional [Logger] for core protocol logs (defaults to
         *   [AndroidLogger] routing to Logcat).
         */
        public fun create(
            context: Context,
            logger: Logger = AndroidLogger(),
        ): MyTappFastConnect {
            val manager = BleConnectionManager(context.applicationContext)
            val transport = BleTransport(manager)
            val client = MyTappFastConnectClient(transport, logger)
            return MyTappFastConnect(manager, transport, client)
        }
    }
}
