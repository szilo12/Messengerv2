package com.messenger.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class FloatingCallOverlayService : Service() {
    companion object {
        private const val TAG = "FloatingCallOverlay"
        const val ACTION_SHOW = "com.messenger.app.FLOATING_CALL_SHOW"
        const val ACTION_SHOW_NOTIFICATION_ONLY = "com.messenger.app.FLOATING_CALL_SHOW_NOTIFICATION_ONLY"
        const val ACTION_HIDE_OVERLAY_ONLY = "com.messenger.app.FLOATING_CALL_HIDE_OVERLAY_ONLY"
        const val ACTION_STOP = "com.messenger.app.FLOATING_CALL_STOP"
        const val CHANNEL_ID = "messenger_calls_background_v4"
        const val CALL_NOTIFICATION_ID = 7777

        @JvmStatic
        @JvmOverloads
        fun show(context: Context, callerName: String?, callId: String?, chatId: String?, callType: String?, avatarUrl: String?, isOngoingOrOutgoing: Boolean = false) {
            val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra("callerName", callerName)
                putExtra("callId", callId)
                putExtra("chatId", chatId)
                putExtra("callType", callType)
                putExtra("avatarUrl", avatarUrl)
                putExtra("isOngoingOrOutgoing", isOngoingOrOutgoing)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.e(TAG, "show failed: ${error.message}. Falling back to direct Activity launch.")
                try {
                    val fallbackIntent = Intent(context, IncomingCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("callId", callId)
                        putExtra("chatId", chatId)
                        putExtra("callerName", callerName)
                        putExtra("callType", callType)
                        putExtra("avatarUrl", avatarUrl)
                    }
                    context.startActivity(fallbackIntent)
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "Activity fallback also failed: ${fallbackError.message}")
                }
            }
        }

        @JvmStatic
        fun stop(context: Context?, callId: String? = null) {
            if (context == null) return
            try {
                val intent = Intent(context, FloatingCallOverlayService::class.java).apply {
                    action = ACTION_STOP
                    putExtra("callId", callId)
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
            try {
                context.stopService(Intent(context, FloatingCallOverlayService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var equalizerRunnable: Runnable? = null

    private var callId: String? = null
    private var chatId: String? = null
    private var callerName: String = "Messenger hívás"
    private var callType: String? = null
    private var avatarUrl: String? = null
    private var statusText: String? = null
    private var isOngoingOrOutgoing = false
    private var forceShowOverlay = false
    private var overlayHiddenByUser = false
    private var callStartedAt: Long
        get() = RtcConnectionManager.callStartedAt
        set(value) {
            RtcConnectionManager.callStartedAt = value
        }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ensureCallChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (intent == null) {
            removeOverlayView()
            stopForegroundCompat(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (ACTION_STOP == action) {
            removeOverlayView()
            stopForegroundCompat(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (ACTION_HIDE_OVERLAY_ONLY == action) {
            overlayHiddenByUser = true
            removeOverlayView()
            return START_STICKY
        }

        callId = intent?.getStringExtra("callId") ?: callId
        chatId = intent?.getStringExtra("chatId") ?: chatId
        callerName = intent?.getStringExtra("callerName")?.takeIf { it.isNotBlank() } ?: callerName
        callType = intent?.getStringExtra("callType") ?: callType
        avatarUrl = intent?.getStringExtra("avatarUrl") ?: avatarUrl
        statusText = intent?.getStringExtra("statusText") ?: statusText
        isOngoingOrOutgoing = intent?.getBooleanExtra("isOngoingOrOutgoing", isOngoingOrOutgoing) ?: isOngoingOrOutgoing
        forceShowOverlay = intent?.getBooleanExtra("forceShowOverlay", false) ?: false

        if (!isOngoingOrOutgoing && callId.isNullOrBlank()) {
            Log.w(TAG, "Ignoring incoming floating call without a valid callId.")
            removeOverlayView()
            stopForegroundCompat(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val callStartedAtExtra = intent?.getLongExtra("callStartedAt", 0L) ?: 0L
        if (callStartedAtExtra > 0L) {
            callStartedAt = callStartedAtExtra
        } else if (isOngoingOrOutgoing && callStartedAt == 0L) {
            callStartedAt = System.currentTimeMillis()
        }

        if (ACTION_SHOW == action) {
            if (forceShowOverlay || isOngoingOrOutgoing) {
                overlayHiddenByUser = false
            }
        }

        startForegroundCompat(buildCallNotification(), isOngoingOrOutgoing)

        if (ACTION_SHOW_NOTIFICATION_ONLY != action && !overlayHiddenByUser) {
            showOverlay()
        }

        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification, isOngoing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (isOngoing) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            try {
                startForeground(CALL_NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground with types failed: ${e.message}, static fallback")
                try {
                    startForeground(CALL_NOTIFICATION_ID, notification)
                } catch (e2: Exception) {
                    Log.e(TAG, "FGS fallback failed: ${e2.message}")
                }
            }
        } else {
            startForeground(CALL_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        removeOverlayView()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isOngoingOrOutgoing && callId?.isNotBlank() == true) {
            overlayHiddenByUser = false
            forceShowOverlay = true
            startForegroundCompat(buildCallNotification(), isOngoingOrOutgoing)
            showOverlay()
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission missing; keeping notification instead of unlocked full-screen popup.")
            if (!isOngoingOrOutgoing && shouldUseFullScreenIncomingCallUi()) {
                try {
                    val intent = Intent(this, IncomingCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("callId", callId)
                        putExtra("chatId", chatId)
                        putExtra("callerName", callerName)
                        putExtra("callType", callType)
                        putExtra("avatarUrl", avatarUrl)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch full-screen incoming call: ${e.message}")
                }
            } else if (isOngoingOrOutgoing && shouldUseFullScreenIncomingCallUi()) {
                try {
                    val intent = Intent(this, ActiveCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("callId", callId)
                        putExtra("chatId", chatId)
                        putExtra("callerName", callerName)
                        putExtra("callType", callType)
                        putExtra("avatarUrl", avatarUrl)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch active call activity: ${e.message}")
                }
            }
            return
        }
        if (overlayView != null || windowManager == null) return

        if (isOngoingOrOutgoing) {
            showOngoingMiniOverlay()
            return
        }

        // Layout parameters matching Ujmes-main layout
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(16)
        }
        overlayParams = params

        // Root layout to support drag margins
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // Capsule glassmorphic card container (match Ujmes-main 1:1)
        val cardContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), 0, dp(12), 0)
            }

            // Light Glassmorphism style background: light sky blue with sky edge glow
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#E6F0F9FF")) // Translucent sky-50 (90% opacity)
                setStroke(dp(2), Color.parseColor("#8038BDF8")) // Smooth sky-400 edge glow
            }

            elevation = dp(10).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                translationZ = dp(4).toFloat()
            }
        }

        // Content layout inside card
        val innerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Left Avatar Layout
        val avatarSize = dp(48)
        val avatarFrameLayout = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                rightMargin = dp(12)
            }
        }

        // Dynamic background color from name hash
        val colors = listOf("#3B82F6", "#6366F1", "#EC4899", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6")
        val colorIndex = kotlin.math.abs(callerName.hashCode() % colors.size)
        val avatarBgColor = Color.parseColor(colors[colorIndex])

        val avatarCircle = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarBgColor)
            }
        }

        val initialsText = TextView(this).apply {
            text = if (callerName.isNotEmpty()) callerName.substring(0, 1).uppercase() else "?"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        avatarCircle.addView(initialsText)

        // Actual avatar image loaded if url exists
        val avatarImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
        }
        avatarCircle.addView(avatarImage)
        loadAvatar(avatarUrl, avatarImage)

        avatarFrameLayout.addView(avatarCircle)

        // Small active call badge in bottom right
        val badgeSize = dp(16)
        val badgeFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                gravity = Gravity.BOTTOM or Gravity.RIGHT
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
                setStroke(dp(1), Color.parseColor("#0F172A"))
            }
        }
        val badgeIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_call_accept)
            setColorFilter(Color.WHITE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        badgeFrame.addView(badgeIcon)
        avatarFrameLayout.addView(badgeFrame)

        innerContent.addView(avatarFrameLayout)

        // 2. Middle Text details + soundwaves
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }

        val nameView = TextView(this).apply {
            text = callerName
            setTextColor(Color.rgb(15, 23, 42)) // Slate-900
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textColumn.addView(nameView)

        val subtitleView = TextView(this).apply {
            text = if ("video" == callType) "Bejövő videóhívás" else "Bejövő hanghívás"
            setTextColor(Color.rgb(71, 85, 105)) // Slate-600
            textSize = 11f
            maxLines = 1
        }
        textColumn.addView(subtitleView)

        // 14-bar dynamic audio wave equalizer
        val visualizerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(16)
            ).apply {
                topMargin = dp(4)
            }
        }

        val barCount = 14
        val barViews = ArrayList<View>()
        val barWidth = dp(2)
        val barSpacing = dp(2)

        for (i in 0 until barCount) {
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(barWidth, dp(4)).apply {
                    rightMargin = barSpacing
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(1).toFloat()
                    setColor(Color.parseColor("#0284C7")) // sky-600
                }
            }
            visualizerContainer.addView(bar)
            barViews.add(bar)
        }
        textColumn.addView(visualizerContainer)
        innerContent.addView(textColumn)

        // Equaliizer animations runnable
        equalizerRunnable = object : Runnable {
            override fun run() {
                for (i in 0 until barCount) {
                    val bar = barViews[i]
                    val lParams = bar.layoutParams as LinearLayout.LayoutParams
                    val heightDp = when (i % 4) {
                        0 -> (3 + Math.random() * 11).toInt()
                        1 -> (2 + Math.random() * 8).toInt()
                        2 -> (4 + Math.random() * 12).toInt()
                        else -> (3 + Math.random() * 7).toInt()
                    }
                    lParams.height = dp(heightDp)
                    bar.layoutParams = lParams
                }
                handler.postDelayed(this, 120)
            }
        }
        handler.post(equalizerRunnable!!)

        // 3. Right side action buttons
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnChat = roundIconButton(R.drawable.ic_call_chat, Color.parseColor("#1A0F172A"), Color.rgb(15, 23, 42)) {
            openFullScreen(false)
        }
        val btnDecline = roundIconButton(R.drawable.ic_call_decline, Color.parseColor("#EF4444")) {
            declineOrEndCall()
        }
        val btnAccept = roundIconButton(R.drawable.ic_call_accept, Color.parseColor("#22C55E")) {
            openFullScreen(true)
        }

        buttonsRow.addView(btnChat, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        buttonsRow.addView(btnDecline, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        buttonsRow.addView(btnAccept, LinearLayout.LayoutParams(dp(44), dp(44)))

        innerContent.addView(buttonsRow)
        cardContainer.addView(innerContent)
        cardContainer.setOnClickListener {
            openFullScreen(false)
        }
        rootLayout.addView(cardContainer)

        overlayView = rootLayout
        attachDragToHide(rootLayout, listOf(btnChat, btnDecline, btnAccept))

        try {
            windowManager?.addView(rootLayout, params)
            Log.d(TAG, "Floating calling overlay displayed successfully!")
        } catch (error: Exception) {
            Log.e(TAG, "add overlay failed: ${error.message}")
            overlayView = null
        }
    }

    private fun shouldUseFullScreenIncomingCallUi(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        val isDeviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceLocked == true
        } else {
            isKeyguardLocked
        }
        val isInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager?.isInteractive != false
        } else {
            @Suppress("DEPRECATION")
            powerManager?.isScreenOn != false
        }

        return isKeyguardLocked || isDeviceLocked || !isInteractive
    }

    private fun isTouchOnView(root: View, view: View?, touchX: Float, touchY: Float): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        var left = view.left
        var top = view.top
        var p = view.parent
        while (p != null && p !== root && p is View) {
            left += p.left
            top += p.top
            p = p.parent
        }
        val right = left + view.width
        val bottom = top + view.height
        return touchX >= left && touchX <= right && touchY >= top && touchY <= bottom
    }

    private fun attachDragToHide(view: View, clickableViews: List<View>) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = overlayParams ?: return false
                val x = event.rawX
                val y = event.rawY

                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    for (cv in clickableViews) {
                        if (isTouchOnView(v, cv, event.x, event.y)) {
                            return false
                        }
                    }
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = x
                        downY = y
                        startX = params.x
                        startY = params.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = x - downX
                        val dy = y - downY
                        if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) dragging = true
                        if (dragging) {
                            params.x = startX + dx.toInt()
                            params.y = maxOf(dp(4), startY + dy.toInt())
                            try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!dragging) {
                            v.performClick()
                            return true
                        }
                        if (kotlin.math.abs(params.x) > dp(110) || params.y < dp(10)) {
                            hideOverlayOnly()
                        } else {
                            animateBackToTop()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun animateBackToTop() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val fromX = params.x
        val fromY = params.y
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 180
        animator.addUpdateListener {
            val t = it.animatedValue as Float
            params.x = (fromX + (0 - fromX) * t).toInt()
            params.y = (fromY + (dp(16) - fromY) * t).toInt()
            try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
        }
        animator.start()
    }

    private fun hideOverlayOnly() {
        overlayHiddenByUser = true
        overlayView?.animate()
            ?.alpha(0f)
            ?.translationY(-dp(36).toFloat())
            ?.setDuration(150)
            ?.withEndAction { removeOverlayView() }
            ?.start()
    }

    private fun attachFreeDrag(view: View, clickableViews: List<View>) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = overlayParams ?: return false
                val x = event.rawX
                val y = event.rawY

                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    for (cv in clickableViews) {
                        if (isTouchOnView(v, cv, event.x, event.y)) {
                            return false
                        }
                    }
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = x
                        downY = y
                        startX = params.x
                        startY = params.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = x - downX
                        val dy = y - downY
                        if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                            dragging = true
                        }
                        if (dragging) {
                            val displayWidth = v.resources.displayMetrics.widthPixels
                            val displayHeight = v.resources.displayMetrics.heightPixels
                            val elementWidth = v.width
                            val elementHeight = v.height

                            val rawNewX = startX - dx.toInt()
                            val rawNewY = startY - dy.toInt()

                            params.x = maxOf(dp(4), minOf(displayWidth - elementWidth - dp(4), rawNewX))
                            params.y = maxOf(dp(4), minOf(displayHeight - elementHeight - dp(4), rawNewY))
                            try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!dragging) {
                            v.performClick()
                            return true
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun symbolButton(symbol: String, size: Float, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            gravity = Gravity.CENTER
            textSize = size
            setTextColor(Color.WHITE)
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(95, 0, 0, 0))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(42, 255, 255, 255))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            setOnClickListener {
                triggerFeedback()
                action()
            }
        }
    }

    private fun roundIconButton(iconRes: Int, color: Int, iconColor: Int = Color.WHITE, paddingDp: Int = 9, action: () -> Unit): FrameLayout {
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                triggerFeedback()
                action()
            }
            addView(ImageView(this@FloatingCallOverlayService).apply {
                setImageResource(iconRes)
                setColorFilter(iconColor)
                setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    @Suppress("DEPRECATION")
    private fun showOngoingMiniOverlay() {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val width = dp(152)
        val height = dp(60)

        val params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = dp(16)
            y = dp(120)
        }
        overlayParams = params

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#E6F0F9FF"))
                setStroke(dp(1), Color.parseColor("#8038BDF8"))
            }
            isClickable = true
            setOnClickListener { openFullScreen(false) }
        }

        val avatarSize = dp(36)
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_avatar_placeholder)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(224, 242, 254))
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        root.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
            rightMargin = dp(8)
        })
        loadAvatar(avatarUrl, avatar)

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                rightMargin = dp(6)
            }
        }

        val nameText = TextView(this).apply {
            text = callerName
            setTextColor(Color.rgb(15, 23, 42)) // Slate-900
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoLayout.addView(nameText)

        val chronometer = android.widget.Chronometer(this).apply {
            val elapsedMs = System.currentTimeMillis() - if (callStartedAt > 0L) callStartedAt else System.currentTimeMillis()
            base = android.os.SystemClock.elapsedRealtime() - elapsedMs
            setTextColor(Color.parseColor("#475569")) // Slate-600
            textSize = 10f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            start()
        }
        infoLayout.addView(chronometer)
        root.addView(infoLayout)

        val btnDeclineMini = roundIconButton(
            iconRes = R.drawable.ic_call_decline,
            color = Color.rgb(239, 68, 68),
            iconColor = Color.WHITE,
            paddingDp = 6
        ) {
            declineOrEndCall()
        }
        root.addView(btnDeclineMini, LinearLayout.LayoutParams(dp(28), dp(28)))

        attachFreeDrag(root, listOf(btnDeclineMini))
        overlayView = root
        try {
            windowManager?.addView(root, params)
        } catch (error: Exception) {
            Log.e(TAG, "add ongoing mini overlay failed: ${error.message}")
            overlayView = null
        }
    }

    private fun openFullScreen(acceptImmediately: Boolean) {
        removeOverlayView()
        overlayHiddenByUser = true

        if (acceptImmediately) {
            val broadcastIntent = Intent(this, NotificationReceiver::class.java).apply {
                action = NotificationReceiver.ACTION_ACCEPT_CALL
                putExtra("callId", callId)
                putExtra("chatId", chatId)
                putExtra("callerName", callerName)
                putExtra("callType", callType)
                putExtra("avatarUrl", avatarUrl)
            }
            sendBroadcast(broadcastIntent)
            return
        }

        if (isOngoingOrOutgoing && !acceptImmediately) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("chatId", chatId)
                putExtra("callId", callId)
                putExtra("callAction", "maximize")
            }
            startActivity(mainIntent)

            // Start the beautiful full-screen native ActiveCallActivity
            val activeCallIntent = Intent(this, ActiveCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("chatId", chatId)
                putExtra("callId", callId)
                putExtra("callerName", callerName)
                putExtra("callType", callType)
                putExtra("avatarUrl", avatarUrl)
                putExtra("callStartedAt", callStartedAt)
            }
            startActivity(activeCallIntent)
            return
        }

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chatId", chatId)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        startActivity(intent)
    }

    private fun declineOrEndCall() {
        removeOverlayView()
        overlayHiddenByUser = true
        val actionString = if (isOngoingOrOutgoing) NotificationReceiver.ACTION_END_CALL else NotificationReceiver.ACTION_DECLINE_CALL
        val broadcastIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = actionString
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        sendBroadcast(broadcastIntent)
    }

    private fun buildCallNotification(): Notification {
        if (isOngoingOrOutgoing) return buildOngoingCallNotification()
        return buildIncomingCallNotification()
    }

    private fun buildIncomingCallNotification(): Notification {
        val callerAvatar = downloadBitmap(avatarUrl)
        return CallNotificationHelper.buildIncomingCallNotification(
            this,
            callerName,
            callId,
            chatId,
            callType,
            avatarUrl,
            callerAvatar
        )
    }

    private fun buildOngoingCallNotification(): Notification {
        val callerAvatar = downloadBitmap(avatarUrl)
        val contentText = statusText?.takeIf { it.isNotBlank() }
            ?: if ("video" == callType) "Videóhívás folyamatban" else "Hanghívás folyamatban"

        val contentIntent = Intent(this, ActiveCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chatId", chatId)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
            putExtra("callStartedAt", RtcConnectionManager.callStartedAt)
        }
        val contentPending = PendingIntent.getActivity(
            this,
            stableCallRequestCode(callId, 8899),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_END_CALL
            putExtra("chatId", chatId)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        val endPending = PendingIntent.getBroadcast(
            this,
            stableCallRequestCode(callId, 8888),
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerBuilder = Person.Builder().setName(callerName).setImportant(true)
        if (callerAvatar != null) callerBuilder.setIcon(IconCompat.createWithBitmap(callerAvatar))
        val caller = callerBuilder.build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(callerName)
            .setContentText(contentText)
            .setSubText("Hívás...")
            .setSmallIcon(R.drawable.ic_call_chat)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPending)
            .setColor(Color.rgb(24, 119, 242))
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setWhen(if (callStartedAt > 0L) callStartedAt else System.currentTimeMillis())
            .setUsesChronometer(true)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, endPending))
            .apply {
                if (callerAvatar != null) setLargeIcon(callerAvatar)
            }
            .build()
    }

    private fun ensureCallChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Hívások", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Bejövő hívások és lebegő hívásablak"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun removeOverlayView() {
        equalizerRunnable?.let {
            handler.removeCallbacks(it)
            equalizerRunnable = null
        }
        val view = overlayView ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        overlayView = null
    }

    @Suppress("DEPRECATION")
    private fun triggerFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {
        }
    }

    private fun stableCallRequestCode(callId: String?, salt: Int): Int {
        return salt + kotlin.math.abs((callId?.hashCode() ?: 0) % 100000)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun loadAvatar(url: String?, target: ImageView) {
        if (url.isNullOrBlank() || url.startsWith("preset:")) return
        Thread {
            val bitmap = downloadBitmap(url)
            if (bitmap != null) target.post { target.setImageBitmap(bitmap) }
        }.start()
    }

    private fun downloadBitmap(urlString: String?): Bitmap? {
        if (urlString.isNullOrBlank() || urlString.startsWith("preset:")) return null
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.connect()
            input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (error: Exception) {
            Log.e(TAG, "download avatar failed: ${error.message}")
            null
        } finally {
            try { input?.close(); connection?.disconnect() } catch (_: Exception) {}
        }
    }
}
