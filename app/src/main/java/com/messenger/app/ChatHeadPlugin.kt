package com.messenger.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowInsetsController
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "ChatHead")
class ChatHeadPlugin : Plugin() {
    companion object {
        private const val TAG = "ChatHeadPlugin"
        private const val PREFS_NAME = "messenger_notification_prefs"
        private var instance: ChatHeadPlugin? = null

        @JvmField
        var pendingChatId: String? = null
        @JvmField
        var pendingCallId: String? = null
        @JvmField
        var pendingCallAction: String? = null
        @JvmField
        var pendingCallChatId: String? = null
        @JvmField
        var pendingCallerName: String? = null
        @JvmField
        var pendingCallType: String? = null
        @JvmField
        var pendingAvatarUrl: String? = null
        @JvmField
        var pendingMessageAction: String? = null
        @JvmField
        var pendingMessageActionChatId: String? = null

        @JvmField
        var activeOngoingCall = false
        @JvmField
        var activeCallId: String? = null
        @JvmField
        var activeCallerName: String? = null
        @JvmField
        var activeCallType: String? = null
        @JvmField
        var activeAvatarUrl: String? = null
        @JvmField
        var activeChatId: String? = null
        @JvmField
        var activeCallStartedAt = 0L

        @JvmStatic
        fun sendChatHeadTappedEvent(chatId: String) {
            val inst = instance
            if (inst != null) {
                Log.d(TAG, "sendChatHeadTappedEvent: Dispatching chatHeadTapped to JS with chatId: $chatId")
                val data = JSObject()
                data.put("chatId", chatId)
                inst.notifyListeners("chatHeadTapped", data)
            } else {
                Log.w(TAG, "sendChatHeadTappedEvent: Plugin instance not loaded yet. Event queued.")
            }
        }

        @JvmStatic
        fun sendCallActionEvent(callId: String?, action: String?, chatId: String?, callerName: String?, callType: String?, avatarUrl: String?) {
            pendingCallId = callId
            pendingCallAction = action
            pendingCallChatId = chatId
            pendingCallerName = callerName
            pendingCallType = callType
            pendingAvatarUrl = avatarUrl

            val inst = instance
            if (inst != null) {
                inst.context
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("pendingCallId", callId)
                    .putString("pendingCallAction", action)
                    .putString("pendingCallChatId", chatId)
                    .putString("pendingCallerName", callerName)
                    .putString("pendingCallType", callType)
                    .putString("pendingAvatarUrl", avatarUrl)
                    .apply()

                Log.d(TAG, "sendCallActionEvent: Dispatching callAction to JS")
                val data = JSObject()
                data.put("callId", callId)
                data.put("action", action)
                data.put("chatId", chatId)
                data.put("callerName", callerName)
                data.put("callType", callType)
                data.put("avatarUrl", avatarUrl)
                inst.notifyListeners("callAction", data)
            }
        }

        @JvmStatic
        fun queueCallAction(context: Context?, callId: String?, action: String?, chatId: String?, callerName: String?, callType: String?, avatarUrl: String?) {
            pendingCallId = callId
            pendingCallAction = action
            pendingCallChatId = chatId
            pendingCallerName = callerName
            pendingCallType = callType
            pendingAvatarUrl = avatarUrl

            if (context != null) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("pendingCallId", callId)
                    .putString("pendingCallAction", action)
                    .putString("pendingCallChatId", chatId)
                    .putString("pendingCallerName", callerName)
                    .putString("pendingCallType", callType)
                    .putString("pendingAvatarUrl", avatarUrl)
                    .apply()
            }

            sendCallActionEvent(callId, action, chatId, callerName, callType, avatarUrl)
        }

        @JvmStatic
        fun sendLikeActionEvent(chatId: String) {
            val inst = instance
            if (inst != null) {
                Log.d(TAG, "sendLikeActionEvent: Dispatching messageAction to JS")
                val data = JSObject()
                data.put("action", "like")
                data.put("chatId", chatId)
                inst.notifyListeners("messageAction", data)
            }
        }

        @JvmStatic
        fun sendStopRingtoneEvent() {
            val inst = instance
            if (inst != null) {
                Log.d(TAG, "sendStopRingtoneEvent: Dispatching stopRingtone to JS")
                val data = JSObject()
                inst.notifyListeners("stopRingtone", data)
            }
        }

        @JvmStatic
        fun triggerWebEvent(eventName: String) {
            val inst = instance
            if (inst != null) {
                inst.activity.runOnUiThread {
                    try {
                        inst.bridge.webView.evaluateJavascript(
                            "window.dispatchEvent(new Event('$eventName'))",
                            null
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "triggerWebEvent failed: ${e.message}")
                    }
                }
            }
        }

        @JvmStatic
        fun hasActiveOngoingCall(): Boolean {
            return activeOngoingCall
        }

        @JvmStatic
        fun showActiveCallOverlayFromNative(context: Context) {
            if (activeOngoingCall && activeCallId != null) {
                if (activeCallStartedAt <= 0L) {
                    activeCallStartedAt = System.currentTimeMillis()
                }
                val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
                    action = FloatingCallOverlayService.ACTION_SHOW
                    putExtra("callId", activeCallId)
                    putExtra("chatId", activeChatId)
                    putExtra("callerName", activeCallerName)
                    putExtra("callType", activeCallType)
                    putExtra("avatarUrl", activeAvatarUrl)
                    putExtra("statusText", "Hívás...")
                    putExtra("isOngoingOrOutgoing", true)
                    putExtra("forceShowOverlay", true)
                    putExtra("callStartedAt", activeCallStartedAt)
                }
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    Log.e(TAG, "showActiveCallOverlayFromNative failed: ${e.message}")
                }
            }
        }
    }

    override fun load() {
        super.load()
        instance = this
        Log.d(TAG, "ChatHeadPlugin loaded")
    }

    override fun handleOnDestroy() {
        instance = null
        super.handleOnDestroy()
    }

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        val ret = JSObject()
        var granted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = Settings.canDrawOverlays(context)
        }
        ret.put("granted", granted)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        val ret = JSObject()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
                activity.startActivity(intent)
                ret.put("requested", true)
                ret.put("granted", false)
            } else {
                ret.put("requested", false)
                ret.put("granted", true)
            }
        } else {
            ret.put("requested", false)
            ret.put("granted", true)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun show(call: PluginCall) {
        val senderName = call.getString("senderName", "Messenger")
        val messageText = call.getString("messageText", "")
        val avatarUrl = call.getString("avatarUrl", "")
        val chatId = call.getString("chatId", "")

        Log.d(TAG, "show: Showing chat head from JS side. Sender: $senderName")

        val intent = Intent(context, ChatHeadService::class.java).apply {
            putExtra("senderName", senderName)
            putExtra("messageText", messageText)
            putExtra("avatarUrl", avatarUrl)
            putExtra("chatId", chatId)
            putExtra("isNewMessage", true)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
            call.resolve()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ChatHeadService from plugin: ${e.message}")
            call.reject("Failed to start service: " + e.message)
        }
    }

    @PluginMethod
    fun hide(call: PluginCall) {
        Log.d(TAG, "hide: Hiding chat head from JS side")
        val intent = Intent(context, ChatHeadService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun setActiveChat(call: PluginCall) {
        MainActivity.activeChatId = call.getString("chatId", null)
        MainActivity.activeChatId?.let { cid ->
            if (cid.isNotEmpty()) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancel(cid.hashCode())
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun setAppIcon(call: PluginCall) {
        val requestedIcon = call.getString("icon", "crystal")
        val selectedIcon = if ("heart" == requestedIcon || "star" == requestedIcon || "flower" == requestedIcon) {
            requestedIcon
        } else {
            "crystal"
        }

        activity.runOnUiThread {
            try {
                val packageManager = context.packageManager
                val packageName = context.packageName
                val classPackage = ChatHeadPlugin::class.java.getPackage()?.name ?: "com.messenger.app"
                val aliases = arrayOf("CrystalIcon", "HeartIcon", "StarIcon", "FlowerIcon")
                val selectedAlias = when (selectedIcon) {
                    "heart" -> "HeartIcon"
                    "star" -> "StarIcon"
                    "flower" -> "FlowerIcon"
                    else -> "CrystalIcon"
                }

                packageManager.setComponentEnabledSetting(
                    ComponentName(packageName, "$classPackage.$selectedAlias"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )

                for (alias in aliases) {
                    if (alias == selectedAlias) continue
                    packageManager.setComponentEnabledSetting(
                        ComponentName(packageName, "$classPackage.$alias"),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }

                context.getSharedPreferences("messenger_app_preferences", Context.MODE_PRIVATE)
                    .edit()
                    .putString("appIcon", selectedIcon)
                    .apply()

                val result = JSObject()
                result.put("icon", selectedIcon)
                call.resolve(result)
            } catch (error: Exception) {
                call.reject("Failed to change app icon: " + error.message)
            }
        }
    }

    @PluginMethod
    fun getAppIcon(call: PluginCall) {
        var selectedIcon = context
            .getSharedPreferences("messenger_app_preferences", Context.MODE_PRIVATE)
            .getString("appIcon", "crystal") ?: "crystal"
        if ("crystal" != selectedIcon && "heart" != selectedIcon &&
            "star" != selectedIcon && "flower" != selectedIcon) {
            selectedIcon = "crystal"
        }
        val result = JSObject()
        result.put("icon", selectedIcon)
        call.resolve(result)
    }

    @PluginMethod
    fun setNotificationPreferences(call: PluginCall) {
        val soundEnabled = call.getBoolean("soundEnabled", true) ?: true
        val vibrationEnabled = call.getBoolean("vibrationEnabled", true) ?: true
        val messageSound = call.getString("messageSound", "default")

        context.getSharedPreferences("messenger_notification_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("soundEnabled", soundEnabled)
            .putBoolean("vibrationEnabled", vibrationEnabled)
            .putString("messageSound", messageSound)
            .apply()

        call.resolve()
    }

    @PluginMethod
    fun setBackendUrl(call: PluginCall) {
        val url = call.getString("url", "")
        Log.d(TAG, "setBackendUrl: Saving backend URL: $url")
        context.getSharedPreferences("messenger_notification_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("backendUrl", url)
            .apply()
        call.resolve()
    }

    @PluginMethod
    fun stopRingtone(call: PluginCall) {
        Log.d(TAG, "stopRingtone: Stopping ringtone from JS side")
        MyFirebaseMessagingService.stopRingtone()
        IncomingCallActivity.dismissCall(null)

        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(8888)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel call notification from plugin: " + e.message)
        }

        call.resolve()
    }

    @PluginMethod
    fun showOngoingCallNotification(call: PluginCall) {
        val callId = call.getString("callId", "")
        val callerName = call.getString("callerName", "Messenger")
        val callType = call.getString("callType", "audio")
        val avatarUrl = call.getString("avatarUrl", "")
        val chatId = call.getString("chatId", "")
        val callStatus = call.getString("callStatus", "ringing") ?: "ringing"
        RtcConnectionManager.callStatus = callStatus
        var callStartedAt = call.getLong("callStartedAt", 0L) ?: 0L
        if (callStatus == "accepted" && callStartedAt <= 0L) {
            callStartedAt = System.currentTimeMillis()
        } else if (callStatus != "accepted") {
            callStartedAt = 0L
            RtcConnectionManager.callStartedAt = 0L
        }

        activeOngoingCall = true
        activeCallId = callId
        activeCallerName = callerName
        activeCallType = callType
        activeAvatarUrl = avatarUrl
        activeChatId = chatId
        activeCallStartedAt = callStartedAt

        val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
            action = FloatingCallOverlayService.ACTION_SHOW_NOTIFICATION_ONLY
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
            putExtra("statusText", call.getString("statusText", ""))
            putExtra("isOngoingOrOutgoing", true)
            putExtra("callStartedAt", callStartedAt)
            putExtra("callStatus", callStatus)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "showOngoingCallNotification failed: ${e.message}")
        }

        val act = activity
        if (act is MainActivity) {
            act.updateOngoingCallBanner()
        }

        call.resolve()
    }

    @PluginMethod
    fun dismissOngoingCallNotification(call: PluginCall) {
        activeOngoingCall = false
        activeCallId = null
        activeCallerName = null
        activeCallType = null
        activeAvatarUrl = null
        activeChatId = null
        activeCallStartedAt = 0L
        
        IncomingCallActivity.dismissCall(null)
        ActiveCallActivity.dismissActiveCall(null)
        FloatingCallOverlayService.stop(context, null)

        val act = activity
        if (act is MainActivity) {
            act.updateOngoingCallBanner()
        }

        call.resolve()
    }

    @PluginMethod
    fun showFloatingCallOverlay(call: PluginCall) {
        val callId = call.getString("callId", "")
        val callerName = call.getString("callerName", "Messenger")
        val callType = call.getString("callType", "audio")
        val avatarUrl = call.getString("avatarUrl", "")
        val chatId = call.getString("chatId", "")
        val callStatus = call.getString("callStatus", "ringing") ?: "ringing"
        RtcConnectionManager.callStatus = callStatus
        var callStartedAt = call.getLong("callStartedAt", 0L) ?: 0L
        if (callStatus == "accepted" && callStartedAt <= 0L) {
            callStartedAt = System.currentTimeMillis()
        } else if (callStatus != "accepted") {
            callStartedAt = 0L
            RtcConnectionManager.callStartedAt = 0L
        }

        activeOngoingCall = true
        activeCallId = callId
        activeCallerName = callerName
        activeCallType = callType
        activeAvatarUrl = avatarUrl
        activeChatId = chatId
        activeCallStartedAt = callStartedAt

        val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
            action = FloatingCallOverlayService.ACTION_SHOW
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
            putExtra("statusText", call.getString("statusText", ""))
            putExtra("isOngoingOrOutgoing", true)
            putExtra("forceShowOverlay", true)
            putExtra("callStartedAt", callStartedAt)
            putExtra("callStatus", callStatus)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingCallOverlay failed: ${e.message}")
        }

        val act = activity
        if (act is MainActivity) {
            act.updateOngoingCallBanner()
        }

        call.resolve()
    }

    @PluginMethod
    fun dismissFloatingCallOverlay(call: PluginCall) {
        val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
            action = FloatingCallOverlayService.ACTION_HIDE_OVERLAY_ONLY
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "dismissFloatingCallOverlay failed: ${e.message}")
        }
        call.resolve()
    }

    @PluginMethod
    fun showIncomingCallNotification(call: PluginCall) {
        val callId = call.getString("callId", "")
        val callerName = call.getString("callerName", "Messenger")
        val callType = call.getString("callType", "audio")
        val avatarUrl = call.getString("avatarUrl", "")
        val chatId = call.getString("chatId", "")

        val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
            action = FloatingCallOverlayService.ACTION_SHOW
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
            putExtra("isOngoingOrOutgoing", false)
            putExtra("forceShowOverlay", true)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "showIncomingCallNotification failed: ${e.message}")
        }
        call.resolve()
    }

    @PluginMethod
    fun getPendingChatId(call: PluginCall) {
        val ret = JSObject()
        ret.put("chatId", pendingChatId)
        call.resolve(ret)
        pendingChatId = null
    }

    @PluginMethod
    fun getPendingCall(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (pendingCallId == null) {
            pendingCallId = prefs.getString("pendingCallId", null)
            pendingCallAction = prefs.getString("pendingCallAction", null)
            pendingCallChatId = prefs.getString("pendingCallChatId", null)
            pendingCallerName = prefs.getString("pendingCallerName", null)
            pendingCallType = prefs.getString("pendingCallType", null)
            pendingAvatarUrl = prefs.getString("pendingAvatarUrl", null)
        }

        val ret = JSObject()
        ret.put("callId", pendingCallId)
        ret.put("action", pendingCallAction)
        ret.put("chatId", pendingCallChatId)
        ret.put("callerName", pendingCallerName)
        ret.put("callType", pendingCallType)
        ret.put("avatarUrl", pendingAvatarUrl)
        call.resolve(ret)
        
        pendingCallId = null
        pendingCallAction = null
        pendingCallChatId = null
        pendingCallerName = null
        pendingCallType = null
        pendingAvatarUrl = null
        prefs.edit()
            .remove("pendingCallId")
            .remove("pendingCallAction")
            .remove("pendingCallChatId")
            .remove("pendingCallerName")
            .remove("pendingCallType")
            .remove("pendingAvatarUrl")
            .apply()
    }

    @PluginMethod
    fun getActiveCall(call: PluginCall) {
        val ret = JSObject()
        ret.put("active", activeOngoingCall)
        ret.put("callId", activeCallId)
        ret.put("chatId", activeChatId)
        ret.put("callerName", activeCallerName)
        ret.put("callType", activeCallType)
        ret.put("avatarUrl", activeAvatarUrl)
        call.resolve(ret)
    }

    @PluginMethod
    fun getPendingMessageAction(call: PluginCall) {
        val ret = JSObject()
        ret.put("action", pendingMessageAction)
        ret.put("chatId", pendingMessageActionChatId)
        call.resolve(ret)
        
        pendingMessageAction = null
        pendingMessageActionChatId = null
    }

    @PluginMethod
    fun minimizeCallToPip(call: PluginCall) {
        activity.runOnUiThread {
            try {
                if (activeOngoingCall) {
                    val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
                        action = FloatingCallOverlayService.ACTION_SHOW
                        putExtra("callId", activeCallId)
                        putExtra("chatId", activeChatId)
                        putExtra("callerName", activeCallerName)
                        putExtra("callType", activeCallType)
                        putExtra("avatarUrl", activeAvatarUrl)
                        putExtra("statusText", "Hívás...")
                        putExtra("isOngoingOrOutgoing", true)
                        putExtra("forceShowOverlay", true)
                        putExtra("callStartedAt", if (RtcConnectionManager.callStatus == "accepted" && activeCallStartedAt > 0L) activeCallStartedAt else 0L)
                        putExtra("callStatus", RtcConnectionManager.callStatus)
                    }
                    ContextCompat.startForegroundService(context, intent)
                }
                activity.moveTaskToBack(true)
                call.resolve()
            } catch (e: Exception) {
                call.reject("Failed to minimize: " + e.message)
            }
        }
    }

    @PluginMethod
    fun setSystemBars(call: PluginCall) {
        val statusColorHex = call.getString("statusBarColor")
        val navColorHex = call.getString("navigationBarColor")
        val isLightStatus = call.getBoolean("isLightStatus")
        val isLightNav = call.getBoolean("isLightNav")

        activity.runOnUiThread {
            try {
                if (!statusColorHex.isNullOrEmpty()) {
                    activity.window.statusBarColor = Color.parseColor(statusColorHex)
                }
                if (!navColorHex.isNullOrEmpty()) {
                    activity.window.navigationBarColor = Color.parseColor(navColorHex)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    activity.window.isStatusBarContrastEnforced = false
                    activity.window.isNavigationBarContrastEnforced = false
                }
                val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                if (isLightStatus != null) {
                    insetsController.isAppearanceLightStatusBars = isLightStatus
                }
                if (isLightNav != null) {
                    insetsController.isAppearanceLightNavigationBars = isLightNav
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val platformController = activity.window.insetsController
                    if (platformController != null) {
                        var appearance = 0
                        var mask = 0
                        if (isLightStatus != null) {
                            mask = mask or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            if (isLightStatus) {
                                appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            }
                        }
                        if (isLightNav != null) {
                            mask = mask or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                            if (isLightNav) {
                                appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                            }
                        }
                        platformController.setSystemBarsAppearance(appearance, mask)
                    }
                }
                call.resolve()
            } catch (e: Exception) {
                call.reject("Failed to set system bars: " + e.message)
            }
        }
    }

    @PluginMethod
    fun updateCallStatus(call: PluginCall) {
        val status = call.getString("status", "ringing") ?: "ringing"
        val callStartedAt = call.getLong("callStartedAt", 0L) ?: 0L
        Log.d(TAG, "updateCallStatus: status=$status, callStartedAt=$callStartedAt")

        RtcConnectionManager.callStatus = status
        if (status == "accepted" && callStartedAt > 0L) {
            RtcConnectionManager.callStartedAt = callStartedAt
        } else if (status == "ringing" || status == "initiating" || status == "connecting") {
            RtcConnectionManager.callStartedAt = 0L
        }

        if ("declined" == status || "cancelled" == status || "ended" == status || "missed" == status) {
            MyFirebaseMessagingService.stopRingtone()
            IncomingCallActivity.dismissCall(null)
            ActiveCallActivity.dismissActiveCall(null)
            FloatingCallOverlayService.stop(context, null)
            activeOngoingCall = false
            activeCallId = null
            activeChatId = null
            activeCallerName = null
            activeCallType = null
            activeAvatarUrl = null
            activeCallStartedAt = 0L
            RtcConnectionManager.clear()
        }

        call.resolve()
    }
}
