package com.messenger.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebChromeClient

class MainActivity : BridgeActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val CALL_CHANNEL_ID = "messenger_calls_fullscreen"
        private const val OVERLAY_PERMISSION_REQ_CODE = 5469
        private const val MEDIA_PERMISSION_REQ_CODE = 5470
        private const val NOTIFICATION_PERMISSION_REQ_CODE = 5471

        @JvmField
        var isAppVisible = false
        @JvmField
        var activeChatId: String? = null
    }

    private var callBannerView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ChatHeadPlugin::class.java)
        super.onCreate(savedInstanceState)

        val appBgColor = Color.parseColor("#0b0614")
        window.statusBarColor = appBgColor
        window.navigationBarColor = appBgColor

        applyDarkSystemBarAppearance()

        allowWebViewMediaPermissions()
        requestMediaPermissionsIfNeeded()
        requestNotificationPermissionIfNeeded()
        createNotificationChannels()
        handleIntent(intent)
        checkOverlayPermission()
        checkFullScreenCallPermission()
    }

    private fun requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (!cameraGranted || !audioGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                MEDIA_PERMISSION_REQ_CODE
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQ_CODE
                )
            }
        }
    }

    private fun allowWebViewMediaPermissions() {
        val b = bridge ?: return
        val web = b.webView ?: return

        web.webChromeClient = object : BridgeWebChromeClient(b) {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                try {
                    request.grant(request.resources)
                } catch (error: Exception) {
                    Log.w(TAG, "WebView media permission request failed: ${error.message}")
                }
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
                Toast.makeText(this, "Kérlek engedélyezd a 'Megjelenítés más alkalmazások felett' opciót a Chat fejekhez!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkFullScreenCallPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val manager = getSystemService(NotificationManager::class.java)
                if (manager != null && !manager.canUseFullScreenIntent()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Engedelyezd a teljes kepernyos hivasertesiteseket, hogy zarolt kijelzon is megjelenjen a hivo.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Full-screen intent settings could not be opened: ${error.message}")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "onActivityResult: Overlay permission granted.")
                    showActiveCallReturnSurface()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isAppVisible = true
        Log.d(TAG, "onResume: App is visible. Keeping active chat heads alive.")
        ChatHeadService.setAppVisible(true)
        
        MyFirebaseMessagingService.stopRingtone()
        ChatHeadPlugin.sendStopRingtoneEvent()
        BadgeHelper.clearBadge(this)

        val hideOverlayIntent = Intent(this, FloatingCallOverlayService::class.java).apply {
            action = "com.messenger.app.FLOATING_CALL_HIDE_OVERLAY_ONLY"
        }
        try {
            startService(hideOverlayIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not hide floating call overlay on resume: ${e.message}")
        }

        ChatHeadPlugin.triggerWebEvent("appResume")

        if (!ChatHeadPlugin.hasActiveOngoingCall()) {
            clearFullScreenCallWindow()
        }

        updateOngoingCallBanner()
    }

    private fun applyDarkSystemBarAppearance() {
        val decorView = window.decorView
        val compatController = WindowInsetsControllerCompat(window, decorView)
        compatController.isAppearanceLightStatusBars = false
        compatController.isAppearanceLightNavigationBars = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            controller?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            var visibility = decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            visibility = visibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                visibility = visibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = visibility
        }
    }

    override fun onPause() {
        super.onPause()
        isAppVisible = false
        ChatHeadService.setAppVisible(false)
        Log.d(TAG, "onPause: App is in background.")
        showActiveCallReturnSurface()

        callBannerView?.let { view ->
            val root = findViewById<FrameLayout>(android.R.id.content)
            root.removeView(view)
            callBannerView = null
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ChatHeadPlugin.hasActiveOngoingCall()) {
            showActiveCallReturnSurface()
        }
    }

    private fun showActiveCallReturnSurface() {
        if (ChatHeadPlugin.hasActiveOngoingCall()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                checkOverlayPermission()
                return
            }
            ChatHeadPlugin.showActiveCallOverlayFromNative(this)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null) {
            MyFirebaseMessagingService.stopRingtone()

            if (intent.hasExtra("chatId")) {
                val chatId = intent.getStringExtra("chatId")
                Log.d(TAG, "handleIntent: Received intent with chatId: $chatId")
                
                if ("like" == intent.getStringExtra("messageAction")) {
                    Log.d(TAG, "handleIntent: Received like action")
                    ChatHeadPlugin.pendingMessageAction = "like"
                    ChatHeadPlugin.pendingMessageActionChatId = chatId
                    ChatHeadPlugin.sendLikeActionEvent(chatId ?: "")
                } else if (!chatId.isNullOrEmpty()) {
                    activeChatId = chatId
                    ChatHeadPlugin.pendingChatId = chatId
                    ChatHeadPlugin.sendChatHeadTappedEvent(chatId)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.cancel(chatId.hashCode())
                }
            }

            if (intent.hasExtra("callId") && intent.hasExtra("callAction")) {
                val callId = intent.getStringExtra("callId")
                val callAction = intent.getStringExtra("callAction")
                val chatId = intent.getStringExtra("chatId")
                val callerName = if (intent.hasExtra("callerName")) intent.getStringExtra("callerName") else ""
                val callType = if (intent.hasExtra("callType")) intent.getStringExtra("callType") else ""
                val avatarUrl = if (intent.hasExtra("avatarUrl")) intent.getStringExtra("avatarUrl") else ""
                val callStartedAt = intent.getLongExtra("callStartedAt", 0L)
                Log.d(TAG, "handleIntent: Received call action: $callAction for callId: $callId")

                prepareFullScreenCallWindow()
                if (callStartedAt > 0L) {
                    ChatHeadPlugin.activeCallStartedAt = callStartedAt
                }
                
                if ("accept" == callAction || "maximize" == callAction) {
                    val hideOverlayIntent = Intent(this, FloatingCallOverlayService::class.java).apply {
                        action = FloatingCallOverlayService.ACTION_HIDE_OVERLAY_ONLY
                    }
                    try {
                        startService(hideOverlayIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not hide call overlay only: ${e.message}")
                    }
                } else {
                    FloatingCallOverlayService.stop(this, callId)
                }

                ChatHeadPlugin.pendingCallId = callId
                ChatHeadPlugin.pendingCallAction = callAction
                ChatHeadPlugin.pendingCallChatId = chatId
                ChatHeadPlugin.pendingCallerName = callerName
                ChatHeadPlugin.pendingCallType = callType
                ChatHeadPlugin.pendingAvatarUrl = avatarUrl
                ChatHeadPlugin.sendCallActionEvent(callId, callAction, chatId, callerName, callType, avatarUrl)
                
                if ("accept" != callAction && "maximize" != callAction) {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.let {
                        it.cancel(7777)
                        it.cancel(8888)
                    }
                }
                updateOngoingCallBanner()
            }
        }
    }

    private fun prepareFullScreenCallWindow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                controller?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not prepare full-screen call window: ${error.message}")
        }
    }

    private fun clearFullScreenCallWindow() {
        try {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                controller?.let {
                    it.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
                }
            } else {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                flags = flags and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
                @Suppress("DEPRECATION")
                flags = flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
                @Suppress("DEPRECATION")
                flags = flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not clear full-screen call window: ${error.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.let {
                it.deleteNotificationChannel("messenger_calls")
                it.deleteNotificationChannel(CALL_CHANNEL_ID)
            }

            val chatChannel = NotificationChannel(
                "messenger_chat",
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Chat messages and bubbles"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
            }

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Calls full screen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call notifications"
                enableVibration(true)
                setSound(null, null)
            }

            manager?.let {
                it.createNotificationChannel(chatChannel)
                it.createNotificationChannel(callChannel)
            }
        }
    }

    fun updateOngoingCallBanner() {
        runOnUiThread {
            val root = findViewById<FrameLayout>(android.R.id.content)
            callBannerView?.let { view ->
                root.removeView(view)
                callBannerView = null
            }

            // Always returning directly on Android because React handles the active call card inside the sidebar
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BASE) {
                return@runOnUiThread
            }
        }
    }
}
