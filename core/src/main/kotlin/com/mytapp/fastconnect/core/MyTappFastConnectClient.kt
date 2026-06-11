package com.mytapp.fastconnect.core

import kotlinx.coroutines.flow.Flow

/**
 * Protocol-level facade over a [Transport].
 *
 * This is the single public entry point shared across all four languages. Each send method:
 * 1. validates `transport.isConnected` **first** — returns [SendResult.NotConnected] if false;
 * 2. builds the protocol string from the typed params — returns [SendResult.InvalidPayload]
 *    naming the offending field if a builder rejects them;
 * 3. hands the string to [Transport.send] — maps a `false` return to [SendResult.TransportError].
 *
 * The client is stateless apart from its dependencies and is safe to reuse for the life of a
 * connection.
 */
public class MyTappFastConnectClient(
    private val transport: Transport,
    logger: Logger = NoOpLogger,
) {
    private val builder = MessageBuilder(logger)

    /** Raw inbound messages from the device, forwarded from the transport. */
    public val incomingMessages: Flow<String>
        get() = transport.incomingMessages

    /** Sends a `CONFIG` message built from [params]. */
    public suspend fun sendConfig(params: ConfigParams): SendResult =
        sendBuilt { builder.buildConfig(params) }

    /** Sends an `INIT_SERVING` message built from [params]. */
    public suspend fun sendInitServing(params: ServingParams): SendResult =
        sendBuilt { builder.buildInitServing(params) }

    /** Sends `MANAGER_START&`. */
    public suspend fun managerStart(): SendResult =
        sendRaw(builder.buildManagerStart())

    /** Sends `MANAGER_STOP&`. */
    public suspend fun managerStop(): SendResult =
        sendRaw(builder.buildManagerStop())

    private inline fun guardConnected(): SendResult? =
        if (!transport.isConnected) SendResult.NotConnected else null

    private suspend inline fun sendBuilt(build: () -> BuildResult): SendResult {
        guardConnected()?.let { return it }
        return when (val result = build()) {
            is BuildResult.Invalid -> SendResult.InvalidPayload(result.field)
            is BuildResult.Ok -> dispatch(result.message)
        }
    }

    private suspend fun sendRaw(message: String): SendResult {
        guardConnected()?.let { return it }
        return dispatch(message)
    }

    private suspend fun dispatch(message: String): SendResult =
        if (transport.send(message)) {
            SendResult.Success
        } else {
            SendResult.TransportError("transport rejected message")
        }
}
