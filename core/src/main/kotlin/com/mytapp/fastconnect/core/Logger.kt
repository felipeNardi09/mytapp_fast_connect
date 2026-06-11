package com.mytapp.fastconnect.core

/**
 * Minimal logging seam for the core module.
 *
 * The core is pure Kotlin/JVM and must not depend on `android.util.Log`. Hosts that want logs
 * supply an implementation (e.g. one backed by `android.util.Log` on Android). The default is
 * [NoOpLogger], which discards everything.
 */
public interface Logger {
    public fun debug(message: String)
    public fun warn(message: String)
    public fun error(message: String, throwable: Throwable? = null)
}

/** A [Logger] that discards all messages. The default used when no logger is provided. */
public object NoOpLogger : Logger {
    override fun debug(message: String) {}
    override fun warn(message: String) {}
    override fun error(message: String, throwable: Throwable?) {}
}
