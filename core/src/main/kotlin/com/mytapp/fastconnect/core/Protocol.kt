package com.mytapp.fastconnect.core

/**
 * Constants describing the mytapp_fast_connect wire protocol.
 *
 * The firmware and this library evolve together — [VERSION] is bumped whenever the set of
 * messages or their field layout changes in a way that is not backward compatible. It is NOT
 * the library's Maven version (see CHANGELOG.md / the semantic-versioning policy).
 */
public object Protocol {
    /**
     * Protocol version. Increment when the wire format changes incompatibly.
     *
     * v1 — CONFIG / INIT_SERVING / MANAGER_START / MANAGER_STOP, UTF-8, `&`-terminated.
     */
    public const val VERSION: Int = 1

    /** Every outgoing message is terminated by this byte. */
    public const val TERMINATOR: Char = '&'

    /** Fallback LED color used when an input cannot be parsed. */
    public const val DEFAULT_LED_COLOR: String = "FFFFFF"
}
