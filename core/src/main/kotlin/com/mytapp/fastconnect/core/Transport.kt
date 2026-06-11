package com.mytapp.fastconnect.core

import kotlinx.coroutines.flow.Flow

/**
 * The seam between the protocol layer ([MyTappFastConnectClient]) and whatever actually moves
 * bytes — BLE on Android, a fake in tests, a socket on the JVM, etc.
 *
 * Implementations are responsible only for transport concerns (connection liveness, delivery,
 * receiving). They know nothing about the mytapp protocol; the client builds protocol strings
 * and hands them to [send].
 */
public interface Transport {
    /** Whether the transport currently has a live link to a device. */
    public val isConnected: Boolean

    /**
     * Raw inbound messages from the device, already decoded to UTF-8 strings (e.g.
     * `STATUS:IDLE`, `SERVING:...`, `FINISH_SERVING:...`). Emitted in arrival order.
     */
    public val incomingMessages: Flow<String>

    /**
     * Sends an already-built protocol string. Returns true if the transport accepted it for
     * delivery (e.g. enqueued on the BLE write queue), false if it could not be submitted.
     */
    public suspend fun send(message: String): Boolean
}
