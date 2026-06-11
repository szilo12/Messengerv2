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
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ActiveCallActivity : Activity() {

    private var callId: String? = null
    private var chatId: String? = null
    private var callerName: String? = null
    private var callType: String? = null
    private var avatarUrl: String? = null

    // Call States
    private var isMuted = false
    private var isSpeakerOn = false
    private var isCameraOn = true

    // UI Toggles
    private lateinit var micBtn: LinearLayout
    private lateinit var micIcon: ImageView
    private lateinit var micLabel: TextView

    private lateinit var speakerBtn: LinearLayout
    private lateinit var speakerIcon: ImageView
    private lateinit var speakerLabel: TextView

    private lateinit var cameraTogglePill: FrameLayout
    private lateinit var cameraToggleDot: View
    private lateinit var cameraToggleIcon: ImageView

    // Timer Variables
    private lateinit var timerText: TextView
    private var secondsElapsed = 0
    private var timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            secondsElapsed++
            val minutes = secondsElapsed / 60
            val seconds = secondsElapsed % 60
            timerText.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val TAG = "ActiveCallActivity"
        private var instanceRef = WeakReference<ActiveCallActivity>(null)

        @JvmStatic
        fun dismissActiveCall(targetCallId: String?) {
            val instance = instanceRef.get()
            if (instance != null && (targetCallId == null || targetCallId == instance.callId)) {
                instance.finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceRef = WeakReference(this)
        configureWindow()

        callId = intent.getStringExtra("callId")
        chatId = intent.getStringExtra("chatId")
        callerName = intent.getStringExtra("callerName")
        callType = intent.getStringExtra("callType")
        avatarUrl = intent.getStringExtra("avatarUrl")

        // Initial Audio State Sync
        syncAudioInitialState()

        // Visual Gradient Background (Translucent & Immersive Slate/Midnight Blue Gradient)
        window.setBackgroundDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(15, 23, 42), Color.rgb(8, 47, 73), Color.rgb(15, 23, 42))
            )
        )

        setContentView(buildActiveCallScreen())

        // Start duration timer
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    private fun configureWindow() {
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
    }

    private fun syncAudioInitialState() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                isMuted = audioManager.isMicrophoneMute
                isSpeakerOn = audioManager.isSpeakerphoneOn
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing initial audio states: ${e.message}")
        }
    }

    private fun buildActiveCallScreen(): View {
        val root = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── TOP ACTION BAR ──────────────────────────────────────────────────────
        val topBar = RelativeLayout(this).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            ).apply {
                topMargin = dp(24)
            }
            setPadding(dp(16), 0, dp(16), 0)
        }

        // Left: Chevron Down Minimize Button
        val minimizeBtn = RelativeLayout(this).apply {
            val lp = RelativeLayout.LayoutParams(dp(48), dp(48)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, 255, 255, 255))
            }

            val icon = ImageView(this@ActiveCallActivity).apply {
                val iconLp = RelativeLayout.LayoutParams(dp(24), dp(24)).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
                layoutParams = iconLp
                setImageResource(R.drawable.ic_chevron_down)
                setColorFilter(Color.WHITE)
            }
            addView(icon)

            setOnClickListener {
                minimizeToOverlay()
            }
        }
        topBar.addView(minimizeBtn)

        // Right: Slide Toggle camera switch or standard cam toggle representing high fidelity video switch
        val camContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            layoutParams = lp
        }

        cameraTogglePill = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(36))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(80, 79, 70, 229)) // Indigo-600 translucent glow
                setStroke(dp(1), Color.argb(120, 129, 140, 248))
            }

            cameraToggleDot = View(this@ActiveCallActivity).apply {
                val dotLp = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                    rightMargin = dp(4)
                }
                layoutParams = dotLp
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                elevation = dp(2).toFloat()
            }

            cameraToggleIcon = ImageView(this@ActiveCallActivity).apply {
                val iconLp = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                    gravity = Gravity.CENTER
                }
                layoutParams = iconLp
                setImageResource(R.drawable.ic_video_on)
                setColorFilter(Color.rgb(79, 70, 229))
            }

            addView(cameraToggleDot)
            addView(cameraToggleIcon)

            setOnClickListener {
                toggleCamera()
            }
        }
        camContainer.addView(cameraTogglePill)
        topBar.addView(camContainer)

        root.addView(topBar)

        // ── MIDDLE CALL DETAIL SECTION ──────────────────────────────────────────
        val middleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.BELOW, topBar.id)
                topMargin = dp(48)
            }
            layoutParams = lp
        }

        // Pulsing circular avatar container
        val avatarContainer = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Subtle slow ring glows
        val ring1 = View(this).apply {
            val rlp = RelativeLayout.LayoutParams(dp(160), dp(160)).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
            layoutParams = rlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(20, 129, 140, 248))
            }
        }
        val ring2 = View(this).apply {
            val rlp = RelativeLayout.LayoutParams(dp(130), dp(130)).createRule(RelativeLayout.CENTER_IN_PARENT)
            layoutParams = rlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(35, 129, 140, 248))
            }
        }
        val avatarView = ImageView(this).apply {
            id = View.generateViewId()
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_avatar_placeholder)
            val rlp = RelativeLayout.LayoutParams(dp(100), dp(100)).createRule(RelativeLayout.CENTER_IN_PARENT)
            layoutParams = rlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(30, 41, 59))
                setStroke(dp(3), Color.WHITE)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        avatarContainer.addView(ring1)
        avatarContainer.addView(ring2)
        avatarContainer.addView(avatarView)
        middleContainer.addView(avatarContainer)

        // Load image async
        loadAvatar(avatarUrl, avatarView)

        // Pulsing animation loops to match Messenger style aesthetics
        val loopsHandler = Handler(Looper.getMainLooper())
        val pulses = object : Runnable {
            override fun run() {
                animatePulse(ring1, 0)
                animatePulse(ring2, 250)
                loopsHandler.postDelayed(this, 1800)
            }
        }
        loopsHandler.postDelayed(pulses, 100)

        // Caller name
        val nameText = TextView(this).apply {
            text = if (callerName.isNullOrBlank()) "Ivy Xu" else callerName
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(dp(6).toFloat(), 0f, dp(3).toFloat(), Color.argb(40, 0, 0, 0))
        }
        middleContainer.addView(nameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(24)
        })

        // Timer counter
        timerText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.rgb(148, 163, 184)) // slate-400
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        middleContainer.addView(timerText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })

        root.addView(middleContainer)

        // ── BOTTOM CONTROLS capsule frame panel ──────────────────────────────────
        val bottomSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                bottomMargin = dp(40)
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
            layoutParams = lp

            // Elevated control horizontal capsule
            val controlsCapsule = LinearLayout(this@ActiveCallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(24), dp(20), dp(24))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(32).toFloat()
                    // Dark elegant glassmorphism
                    setColor(Color.argb(205, 15, 23, 42)) // zinc-900 / slate-900 with nice density
                    setStroke(dp(1), Color.argb(35, 255, 255, 255))
                }
                elevation = dp(16).toFloat()
            }

            // 1. ADD / INVITE BUTTON
            val inviteItem = createControlItem(
                label = "Hozzáadás",
                iconRes = R.drawable.ic_add_person,
                isActive = false
            ) {
                Toast.makeText(this@ActiveCallActivity, "Személy hozzáadása...", Toast.LENGTH_SHORT).show()
            }
            controlsCapsule.addView(inviteItem.container, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // 2. SPEAKER BUTTON
            val speakerItem = createControlItem(
                label = "Hangszóró",
                iconRes = R.drawable.ic_speaker,
                isActive = isSpeakerOn
            ) {
                toggleSpeakerState()
            }
            speakerBtn = speakerItem.circle
            speakerIcon = speakerItem.icon
            speakerLabel = speakerItem.label
            controlsCapsule.addView(speakerItem.container, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // 3. MICROPHONE / MUTE BUTTON
            val micItem = createControlItem(
                label = "Némítás",
                iconRes = R.drawable.ic_mic,
                isActive = isMuted
            ) {
                toggleMicrophoneMute()
            }
            micBtn = micItem.circle
            micIcon = micItem.icon
            micLabel = micItem.label
            controlsCapsule.addView(micItem.container, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // 4. END CALL RED BUTTON
            val endCallSection = createEndCallItem()
            controlsCapsule.addView(endCallSection, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(controlsCapsule)
        }

        root.addView(bottomSheet)

        return root
    }

    private fun RelativeLayout.LayoutParams.createRule(rule: Int): RelativeLayout.LayoutParams {
        this.addRule(rule)
        return this
    }

    private fun animatePulse(view: View, delayMs: Long) {
        view.alpha = 0.6f
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        view.animate()
            .alpha(0f)
            .scaleX(1.5f)
            .scaleY(1.5f)
            .setStartDelay(delayMs)
            .setDuration(1200)
            .start()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private class ControlItemViews(
        val container: LinearLayout,
        val circle: LinearLayout,
        val icon: ImageView,
        val label: TextView
    )

    private fun createControlItem(
        label: String,
        iconRes: Int,
        isActive: Boolean,
        onClick: () -> Unit
    ): ControlItemViews {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val circle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isActive) {
                    setColor(Color.WHITE)
                } else {
                    setColor(Color.argb(30, 255, 255, 255))
                }
            }
            setOnClickListener { onClick() }
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setImageResource(iconRes)
            if (isActive) {
                setColorFilter(Color.rgb(15, 23, 42)) // indigo / dark zinc
            } else {
                setColorFilter(Color.WHITE)
            }
        }
        circle.addView(icon)

        val tx = TextView(this).apply {
            text = label
            setTextColor(if (isActive) Color.WHITE else Color.rgb(148, 163, 184))
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        container.addView(circle)
        container.addView(tx, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        return ControlItemViews(container, circle, icon, tx)
    }

    private fun createEndCallItem(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val circle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(239, 68, 68)) // soft beautiful red
            }
            setOnClickListener {
                hangUp()
            }
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setImageResource(R.drawable.ic_call_decline)
            setColorFilter(Color.WHITE)
        }
        circle.addView(icon)

        val tx = TextView(this).apply {
            text = "Vége"
            setTextColor(Color.rgb(239, 68, 68))
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        container.addView(circle)
        container.addView(tx, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        return container
    }

    private fun toggleSpeakerState() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                isSpeakerOn = !isSpeakerOn
                audioManager.isSpeakerphoneOn = isSpeakerOn

                speakerBtn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isSpeakerOn) Color.WHITE else Color.argb(30, 255, 255, 255))
                }
                speakerIcon.setColorFilter(if (isSpeakerOn) Color.rgb(15, 23, 42) else Color.WHITE)
                speakerLabel.setTextColor(if (isSpeakerOn) Color.WHITE else Color.rgb(148, 163, 184))

                val message = if (isSpeakerOn) "Hangszóró bekapcsolva" else "Hangszóró kikapcsolva"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Speakerphone state changed to $isSpeakerOn")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed toggling speaker: ${e.message}")
        }
    }

    private fun toggleMicrophoneMute() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                isMuted = !isMuted
                audioManager.isMicrophoneMute = isMuted

                micBtn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isMuted) Color.WHITE else Color.argb(30, 255, 255, 255))
                }
                micIcon.setColorFilter(if (isMuted) Color.rgb(15, 23, 42) else Color.WHITE)
                micLabel.setTextColor(if (isMuted) Color.WHITE else Color.rgb(148, 163, 184))

                val message = if (isMuted) "Mikrofon elnémítva" else "Mikrofon visszahangosítva"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Microphone mute state changed to $isMuted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed toggling microphone mute: ${e.message}")
        }
    }

    private fun toggleCamera() {
        isCameraOn = !isCameraOn

        val boundsLp = cameraToggleDot.layoutParams as FrameLayout.LayoutParams
        if (isCameraOn) {
            boundsLp.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            (cameraTogglePill.background as GradientDrawable).setColor(Color.argb(80, 79, 70, 229))
            cameraToggleIcon.setImageResource(R.drawable.ic_video_on)
            cameraToggleIcon.setColorFilter(Color.rgb(79, 70, 229))
        } else {
            boundsLp.gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            (cameraTogglePill.background as GradientDrawable).setColor(Color.argb(40, 255, 255, 255))
            cameraToggleIcon.setImageResource(R.drawable.ic_video_off)
            cameraToggleIcon.setColorFilter(Color.WHITE)
        }
        cameraToggleDot.layoutParams = boundsLp

        val msg = if (isCameraOn) "Kamera bekapcsolva" else "Kamera kikapcsolva"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun minimizeToOverlay() {
        // Stop current full screen, show the floating miniature overlay
        FloatingCallOverlayService.show(this, callerName, callId, chatId, callType, avatarUrl)
        finish()
    }

    private fun hangUp() {
        // Trigger end call receiver broadcast
        val intent = Intent(this, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_END_CALL
            putExtra("callId", callId)
            putExtra("chatId", chatId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("avatarUrl", avatarUrl)
        }
        sendBroadcast(intent)
        finish()
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

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
        if (instanceRef.get() === this) instanceRef.clear()
    }
}
