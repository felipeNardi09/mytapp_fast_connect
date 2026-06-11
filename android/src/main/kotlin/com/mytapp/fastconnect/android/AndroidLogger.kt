package com.mytapp.fastconnect.android

import android.util.Log
import com.mytapp.fastconnect.core.Logger

/**
 * [Logger] backed by `android.util.Log`. Pass an instance to [com.mytapp.fastconnect.core.MyTappFastConnectClient]
 * to route core logs to Logcat.
 */
public class AndroidLogger(private val tag: String = "MyTappFastConnect") : Logger {
    override fun debug(message: String) {
        Log.d(tag, message)
    }

    override fun warn(message: String) {
        Log.w(tag, message)
    }

    override fun error(message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
