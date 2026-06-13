package com.messenger.app

import android.os.Build
import androidx.annotation.RequiresApi

object RtcConnectionManager {
    // We store the connection as Any? to avoid API level issues on pre-O devices
    var activeConnection: Any? = null
    var callStartedAt: Long = 0L

    @RequiresApi(Build.VERSION_CODES.O)
    fun getConnection(): RtcConnection? {
        return activeConnection as? RtcConnection
    }

    fun clear() {
        activeConnection = null
        callStartedAt = 0L
    }
}
