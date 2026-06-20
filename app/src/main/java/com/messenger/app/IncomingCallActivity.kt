package com.messenger.app

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

class IncomingCallActivity : Activity() {
    private var callId: String? = null
    private var chatId: String? = null
    private var callerName: String? = null
    private var callType: String? = null
    private var avatarUrl: String? = null

    companion object {
        const val ACTION_ACCEPT_CALL = "ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "ACTION_DECLINE_CALL"
        private var instanceRef = WeakReference<IncomingCallActivity>(null)

        @JvmStatic
        fun dismissCall(incomingCallId: String?) {
            val instance = instanceRef.get()
            if (instance != null && (incomingCallId == null || incomingCallId == instance.callId)) {
                instance.finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceRef = WeakReference(this)
        configureLockScreenWindow()

        handleIntentAction(intent)
        if (isFinishing) return

        val currentIntent = intent
        callId = currentIntent.getStringExtra("callId")
        chatId = currentIntent.getStringExtra("chatId")

        if (NotificationReceiver.isCallDismissed(this, callId)) {
            MyFirebaseMessagingService.stopRingtone()
            cancelCallNotification()
            finish()
            return
        }

        callerName = currentIntent.getStringExtra("callerName")
        callType = currentIntent.getStringExtra("callType")
        avatarUrl = currentIntent.getStringExtra("avatarUrl")
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked ?: false

        window.setBackgroundDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#020617"))
            )
        )

        setContentView(buildCallScreen(callerName, callType, avatarUrl))

        // Hide floating calling overlay while the incoming call screen is open
        val hideOverlayIntent = Intent(this, FloatingCallOverlayService::class.java).apply {
            action = FloatingCallOverlayService.ACTION_HIDE_OVERLAY_ONLY
        }
        try {
            startService(hideOverlayIntent)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: Intent?) {
        if (intent == null) return
        callId = intent.getStringExtra("callId")
        chatId = intent.getStringExtra("chatId")
        val action = intent.action
        if (ACTION_ACCEPT_CALL == action) acceptCall()
        else if (ACTION_DECLINE_CALL == action || "ACTION_DECLINE_CALL" == action) declineCall()
    }

    @Suppress("DEPRECATION")
    private fun configureLockScreenWindow() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        showFloatingCallOverlay()
    }

    private fun showFloatingCallOverlay() {
        if (isFinishing || NotificationReceiver.isCallDismissed(this, callId)) return
        FloatingCallOverlayService.show(this, callerName, callId, chatId, callType, avatarUrl)
    }

    // ─── Main call screen builder ──────────────────────────────────────────────
    private fun buildCallScreen(callerName: String?, callType: String?, avatarUrl: String?): View {
        val safeName = if (callerName.isNullOrBlank()) "Messenger hívás" else callerName
        val isVideo = "video" == callType

        val root = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Content column ──────────────────────────────────────────────────────
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(24), dp(72), dp(24), dp(48))
        }

        // ── Top pill label ──────────────────────────────────────────────────────
        val pillText = if (isVideo) "BEJÖVŐ VIDEÓHÍVÁS" else "BEJÖVŐ HANGHÍVÁS"
        val pill = TextView(this).apply {
            text = pillText
            setTextColor(Color.parseColor("#38BDF8")) // sky-400
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(35, 56, 189, 248)) // Translucent sky-400
                setStroke(dp(1), Color.argb(80, 56, 189, 248))
            }
            background = bg
        }
        col.addView(pill, wrapContent().apply { topMargin = dp(12) })

        // ── Pulsing avatar ──────────────────────────────────────────────────────
        val avatarContainer = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150)).apply {
                topMargin = dp(40)
            }
        }

        // Ring 1 – outermost (slow pulse)
        val ring1 = View(this).apply {
            val rlp = RelativeLayout.LayoutParams(dp(150), dp(150))
            rlp.addRule(RelativeLayout.CENTER_IN_PARENT)
            layoutParams = rlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, 14, 165, 233))
            }
            alpha = 0f
        }
        // Ring 2
        val ring2 = View(this).apply {
            val rlp = RelativeLayout.LayoutParams(dp(120), dp(120))
            rlp.addRule(RelativeLayout.CENTER_IN_PARENT)
            layoutParams = rlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(60, 14, 165, 233))
            }
            alpha = 0f
        }
        // Avatar image
        val avatarSize = dp(96)
        val avatarView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_avatar_placeholder)
            val rlp = RelativeLayout.LayoutParams(avatarSize, avatarSize)
            rlp.addRule(RelativeLayout.CENTER_IN_PARENT)
            layoutParams = rlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(224, 242, 254)) // sky-100
                setStroke(dp(3), Color.argb(120, 14, 165, 233))
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        avatarContainer.addView(ring1)
        avatarContainer.addView(ring2)
        avatarContainer.addView(avatarView)
        col.addView(avatarContainer)

        // Animate rings
        val handler = Handler(Looper.getMainLooper())
        val pingRings = object : Runnable {
            override fun run() {
                animatePing(ring1, 0)
                animatePing(ring2, 300)
                handler.postDelayed(this, 1800)
            }
        }
        handler.postDelayed(pingRings, 200)

        // Load avatar
        loadAvatar(avatarUrl, avatarView)

        // ── Caller name ──────────────────────────────────────────────────────────
        val nameText = TextView(this).apply {
            text = safeName
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(dp(8).toFloat(), 0f, dp(4).toFloat(), Color.argb(40, 56, 189, 248))
        }
        col.addView(nameText, wrapContent().apply { topMargin = dp(20) })

        // ── Subtitle ─────────────────────────────────────────────────────────────
        val subtitle = TextView(this).apply {
            text = if (isVideo) "Videóhívásra hív téged" else "Hanghívásra hív téged"
            setTextColor(Color.parseColor("#94A3B8")) // slate-400
            textSize = 14f
            gravity = Gravity.CENTER
        }
        col.addView(subtitle, wrapContent().apply { topMargin = dp(8) })

        // ── Wave bars (decorative) ─────────────────────────────────────────────
        val waveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply { topMargin = dp(20) }
        }
        val barHeights = intArrayOf(12, 20, 14, 24, 16, 20, 12)
        barHeights.forEach { h ->
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.rgb(2, 132, 199))
                }
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(h)).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            }
            waveRow.addView(bar)
        }
        col.addView(waveRow)

        // ── Spacer ────────────────────────────────────────────────────────────
        col.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // ── Action buttons ────────────────────────────────────────────────────
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttonRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // Decline button
        val declineBtn = buildRoundButton(R.drawable.ic_call_decline, Color.rgb(239, 68, 68), Color.rgb(153, 27, 27))
        declineBtn.setOnClickListener { declineCall() }
        buttonRow.addView(declineBtn, LinearLayout.LayoutParams(dp(80), dp(80)))

        buttonRow.addView(View(this), LinearLayout.LayoutParams(dp(60), 0))

        // Accept button
        val acceptIconRes = if (isVideo) R.drawable.ic_video_on else R.drawable.ic_call_accept
        val acceptBtn = buildRoundButton(acceptIconRes, Color.rgb(34, 197, 94), Color.rgb(21, 128, 61))
        acceptBtn.setOnClickListener { acceptCall() }
        buttonRow.addView(acceptBtn, LinearLayout.LayoutParams(dp(80), dp(80)))

        buttonRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        val buttonSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        buttonSection.addView(buttonRow)

        // Labels under buttons
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        labelRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val declineLabel = TextView(this).apply {
            text = "Elutasítás"
            setTextColor(Color.parseColor("#94A3B8")) // slate-400
            textSize = 13f
            gravity = Gravity.CENTER
        }
        labelRow.addView(declineLabel, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))

        labelRow.addView(View(this), LinearLayout.LayoutParams(dp(60), 0))

        val acceptLabel = TextView(this).apply {
            text = "Fogadás"
            setTextColor(Color.parseColor("#94A3B8")) // slate-400
            textSize = 13f
            gravity = Gravity.CENTER
        }
        labelRow.addView(acceptLabel, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))

        labelRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        buttonSection.addView(labelRow)

        col.addView(buttonSection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        root.addView(col)
        return root
    }

    private fun buildRoundButton(iconResId: Int, colorTop: Int, colorBottom: Int): FrameLayout {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colorTop, colorBottom)
            ).apply {
                shape = GradientDrawable.OVAL
            }
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
        }
        val img = ImageView(this).apply {
            setImageResource(iconResId)
            setColorFilter(Color.WHITE)
            setPadding(dp(22), dp(22), dp(22), dp(22))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(img)
        return root
    }

    private fun animatePing(view: View, delayMs: Long) {
        view.alpha = 0.7f
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.animate()
            .alpha(0f)
            .scaleX(1.4f)
            .scaleY(1.4f)
            .setStartDelay(delayMs)
            .setDuration(900)
            .start()
    }

    private fun wrapContent() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun acceptCall() {
        MyFirebaseMessagingService.stopRingtone()
        FloatingCallOverlayService.stop(this, callId)
        cancelCallNotification()
        ChatHeadPlugin.queueCallAction(this, callId, "accept", chatId, callerName ?: "", callType ?: "", avatarUrl ?: "")
        NotificationReceiver.reportAcceptToBackend(this, callId)
        val appIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chatId", chatId)
            putExtra("callId", callId)
            putExtra("callAction", "accept")
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        startActivity(appIntent)

        val activeCallIntent = Intent(this, ActiveCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chatId", chatId)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
            putExtra("callStartedAt", if (RtcConnectionManager.callStartedAt > 0L) RtcConnectionManager.callStartedAt else System.currentTimeMillis())
        }
        startActivity(activeCallIntent)
        finish()
    }

    private fun declineCall() {
        MyFirebaseMessagingService.stopRingtone()
        FloatingCallOverlayService.stop(this, callId)
        cancelCallNotification()
        dismissCall(callId)
        NotificationReceiver.markCallDismissed(this, callId)
        ChatHeadPlugin.queueCallAction(this, callId, "decline", chatId, callerName ?: "", callType ?: "", avatarUrl ?: "")
        NotificationReceiver.reportDeclineToBackend(this, callId)
        finish()
    }

    private fun cancelCallNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(7777)
    }

    private fun loadAvatar(avatarUrl: String?, avatar: ImageView) {
        if (avatarUrl.isNullOrBlank() || avatarUrl.startsWith("preset:")) return
        Thread {
            var connection: HttpURLConnection? = null
            var input: InputStream? = null
            try {
                val url = URL(avatarUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                input = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    val circleBmp = toCircle(bitmap)
                    Handler(Looper.getMainLooper()).post { avatar.setImageBitmap(circleBmp) }
                }
            } catch (ignored: Exception) {
            } finally {
                try { input?.close(); connection?.disconnect() } catch (ignored: Exception) {}
            }
        }.start()
    }

    private fun toCircle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawOval(rect, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val dx = ((size - src.width) / 2).toFloat()
        val dy = ((size - src.height) / 2).toFloat()
        canvas.drawBitmap(src, dx, dy, paint)
        return output
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        val instance = instanceRef.get()
        if (instance === this) instanceRef.clear()
    }
}
