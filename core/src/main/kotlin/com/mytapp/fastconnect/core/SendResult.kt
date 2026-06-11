package com.mytapp.fastconnect.core

/**
 * The single result shape returned by every [MyTappFastConnectClient] send method.
 *
 * It is intentionally small and flat so it maps cleanly onto Dart, TypeScript and a plain map
 * across the Flutter / React Native bridges.
 */
public sealed class SendResult {
    /** The message was built and accepted by the transport for delivery. */
    public object Success : SendResult()

    /** The transport had no live connection; nothing was sent. */
    public object NotConnected : SendResult()

    /**
     * A parameter failed validation; nothing was sent.
     *
     * @property field the offending field name (e.g. `"liquid"`, `"volumeShown"`)
     */
    public data class InvalidPayload(val field: String) : SendResult()

    /**
     * The transport rejected the (valid) message.
     *
     * @property reason a human-readable explanation
     */
    public data class TransportError(val reason: String) : SendResult()
}
