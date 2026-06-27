package com.messenger.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class ChatHeadService : Service() {
    companion object {
        private const val TAG = "ChatHeadService"
        private const val FOREGROUND_ID = 2026
        private const val CHANNEL_ID = "messenger_chat"
        private var instanceRef = WeakReference<ChatHeadService>(null)
        private var appVisible = false
        private var unreadCount = 0

        @JvmStatic
        fun setAppVisible(visible: Boolean) {
            appVisible = visible
            val instance = instanceRef.get()
            instance?.applyOverlayVisibility()
        }
    }

    private var windowManager: WindowManager? = null
    private var chatHeadView: View? = null
    private var dismissView: View? = null
    
    private var chatHeadParams: WindowManager.LayoutParams? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    private var chatHeadRoot: LinearLayout? = null
    private var avatarWrapper: FrameLayout? = null
    private var avatarImage: ImageView? = null
    private var unreadBadge: TextView? = null
    private val stackedAvatarViews = mutableListOf<ImageView>()
    private var previewBubble: LinearLayout? = null
    private var previewSender: TextView? = null
    private var previewText: TextView? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var chatHeadWidth = 0
    private var chatHeadHeight = 0

    private var currentChatId = ""

    private val bubbleHideHandler = Handler(Looper.getMainLooper())
    private var bubbleHideRunnable: Runnable? = null

    private var isDragging = false
    private var isMagnetized = false
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Creating ChatHeadService")
        instanceRef = WeakReference(this)
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        updateScreenMetrics()
        setupLayoutDimensions()
        initNotification()
        createChatHeadView()
        restoreConversationStack()
        applyOverlayVisibility()
    }

    private fun applyOverlayVisibility() {
        chatHeadView?.visibility = if (appVisible) View.GONE else View.VISIBLE
        if (appVisible) {
            hideDismissZone()
            previewBubble?.visibility = View.GONE
        }
    }

    private fun updateScreenMetrics() {
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
    }

    private fun setupLayoutDimensions() {
        chatHeadWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 92f, resources.displayMetrics
        ).toInt()
        chatHeadHeight = chatHeadWidth
    }

    private fun initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lebegő Üzenetek",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val count = BadgeHelper.getBadgeCount(this)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lebegő üzenetek aktívak")
            .setContentText("Húzd el a bezáráshoz vagy koppints a megnyitáshoz.")
            .setSmallIcon(R.drawable.ic_call_chat)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (count > 0) {
            builder.setNumber(count)
        }

        startForeground(FOREGROUND_ID, builder.build())
    }

    private fun createChatHeadView() {
        val chatHeadParent = FrameLayout(this)
        chatHeadView = LayoutInflater.from(this).inflate(R.layout.chat_head_layout, chatHeadParent, false)

        chatHeadView?.let { view ->
            chatHeadRoot = view.findViewById(R.id.chat_head_root)
            avatarWrapper = view.findViewById(R.id.avatar_wrapper)
            avatarImage = view.findViewById(R.id.avatar_image)
            unreadBadge = view.findViewById(R.id.unread_badge)
            previewBubble = view.findViewById(R.id.preview_bubble)
            previewSender = view.findViewById(R.id.preview_sender)
            previewText = view.findViewById(R.id.preview_text)

            avatarWrapper?.layoutParams = LinearLayout.LayoutParams(dp(92), dp(82))
            repeat(2) { index ->
                val stacked = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                    elevation = dp((3 - index).coerceAtLeast(1)).toFloat()
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (index == 0) "#1D4ED8" else "#312E81"))
                        setStroke(dp(2), Color.WHITE)
                    }
                    clipToOutline = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    translationX = dp(34 + index * 7).toFloat()
                    translationY = dp(17 + index * 5).toFloat()
                }
                avatarWrapper?.addView(stacked, 0, FrameLayout.LayoutParams(dp(46), dp(46)))
                stackedAvatarViews.add(stacked)
            }
        }

        chatHeadParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = screenHeight / 4
        }

        setupTouchListener()

        try {
            windowManager?.addView(chatHeadView, chatHeadParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add chat head view: ${e.message}")
        }
    }

    private fun setupTouchListener() {
        avatarWrapper?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var touchStartTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                updateScreenMetrics()
                val params = chatHeadParams ?: return false
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartTime = System.currentTimeMillis()
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = true
                        
                        showDismissZone()
                        previewBubble?.visibility = View.GONE
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY

                        var newX = initialX + deltaX.toInt()
                        var newY = initialY + deltaY.toInt()

                        if (newY < 0) newY = 0
                        if (newY > screenHeight - chatHeadHeight) newY = screenHeight - chatHeadHeight

                        checkMagneticClose(newX, newY)

                        if (!isMagnetized) {
                            params.x = newX
                            params.y = newY
                        }
                        
                        try {
                            windowManager?.updateViewLayout(chatHeadView, params)
                        } catch (e: Exception) {}
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        isDragging = false

                        val shouldDismiss = isMagnetized || isReleaseInsideDismissZone(event.rawX, event.rawY)
                        hideDismissZone()

                        if (shouldDismiss) {
                            dismissChatHead()
                            return true
                        }

                        val clickDuration = System.currentTimeMillis() - touchStartTime
                        val dragDistance = Math.sqrt(
                            Math.pow((event.rawX - initialTouchX).toDouble(), 2.0) + 
                            Math.pow((event.rawY - initialTouchY).toDouble(), 2.0)
                        )

                        if (clickDuration < 300 && dragDistance < 15) {
                            openMessengerApp()
                        } else {
                            snapToEdge()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showDismissZone() {
        if (dismissView != null) return

        val dismissParent = FrameLayout(this)
        dismissView = LayoutInflater.from(this).inflate(R.layout.chat_head_dismiss_layout, dismissParent, false)
        
        dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(dismissView, dismissParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add dismiss zone: ${e.message}")
        }
    }

    private fun hideDismissZone() {
        dismissView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (ignored: Exception) {}
            dismissView = null
            isMagnetized = false
        }
    }

    private fun isReleaseInsideDismissZone(rawX: Float, rawY: Float): Boolean {
        val bottomHotZone = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 230f, resources.displayMetrics
        ).toInt()
        val horizontalHotZone = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 190f, resources.displayMetrics
        ).toInt()

        return rawY >= screenHeight - bottomHotZone
            && Math.abs(rawX - (screenWidth / 2f)) <= horizontalHotZone
    }

    private fun dismissChatHead() {
        Log.d(TAG, "dismissChatHead: Removing chat head service.")
        BadgeHelper.clearBadge(this)
        unreadCount = 0
        getSharedPreferences("messenger_chat_heads", Context.MODE_PRIVATE).edit().remove("conversations").apply()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.let {
            it.cancel(FOREGROUND_ID)
            if (currentChatId.isNotEmpty()) {
                it.cancel(currentChatId.hashCode())
            }
        }

        stopSelf()
    }

    private fun checkMagneticClose(chatHeadX: Int, chatHeadY: Int) {
        val dView = dismissView ?: return

        val dismissCircle: View = dView.findViewById(R.id.dismiss_circle)
        val dismissText: TextView = dView.findViewById(R.id.dismiss_text)
        
        val dismissMarginBottom = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics
        ).toInt()
        val dismissCircleRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics
        ).toInt()
        
        val dismissCenterX = screenWidth / 2
        val dismissCenterY = screenHeight - dismissMarginBottom - dismissCircleRadius

        val chatHeadCenterX = chatHeadX + chatHeadWidth / 2
        val chatHeadCenterY = chatHeadY + chatHeadHeight / 2

        val distance = Math.sqrt(
            Math.pow((chatHeadCenterX - dismissCenterX).toDouble(), 2.0) + 
            Math.pow((chatHeadCenterY - dismissCenterY).toDouble(), 2.0)
        )

        val snapBoundary = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 150f, resources.displayMetrics
        ).toInt()

        val params = chatHeadParams ?: return

        if (distance < snapBoundary) {
            if (!isMagnetized) {
                isMagnetized = true
                triggerHapticFeedback()
                
                dismissCircle.animate().scaleX(1.25f).scaleY(1.25f).setDuration(150).start()
                dismissText.text = "Engedd el a bezáráshoz"
            }
            
            params.x = dismissCenterX - chatHeadWidth / 2
            params.y = dismissCenterY - chatHeadHeight / 2
        } else {
            if (isMagnetized) {
                isMagnetized = false
                
                dismissCircle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                dismissText.text = "Húzd ide a bezáráshoz"
            }
        }
    }

    private fun triggerHapticFeedback() {
        try {
            val vib = vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(40)
                }
            }
        } catch (ignored: Exception) {}
    }

    private fun snapToEdge() {
        val params = chatHeadParams ?: return
        val currentX = params.x
        val targetX: Int
        val isLeftEdge: Boolean

        if (currentX + chatHeadWidth / 2 < screenWidth / 2) {
            targetX = 0
            isLeftEdge = true
        } else {
            targetX = screenWidth - chatHeadWidth
            isLeftEdge = false
        }

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 250
        animator.addUpdateListener { animation ->
            if (chatHeadView != null && windowManager != null) {
                params.x = animation.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(chatHeadView, params)
                } catch (e: Exception) {}
            }
        }
        
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                updateBubbleOrientation(isLeftEdge)
            }
        })
        
        animator.start()
    }

    private fun updateBubbleOrientation(isLeftEdge: Boolean) {
        val root = chatHeadRoot ?: return
        val wrapper = avatarWrapper ?: return
        val bubble = previewBubble ?: return
        
        root.removeAllViews()
        if (isLeftEdge) {
            root.addView(wrapper)
            root.addView(bubble)
            bubble.setBackgroundResource(R.drawable.bg_preview_bubble_left)
        } else {
            root.addView(bubble)
            root.addView(wrapper)
            bubble.setBackgroundResource(R.drawable.bg_preview_bubble_right)
        }
    }

    private fun openMessengerApp() {
        Log.d(TAG, "openMessengerApp: Clicking chat head, opening app with chatId: $currentChatId")

        if (currentChatId.isNotEmpty()) {
            ChatHeadPlugin.pendingChatId = currentChatId
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chatId", currentChatId)
        }
        startActivity(intent)

        dismissChatHead()
    }

    private fun updateNotification(count: Int) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lebegő üzenetek aktívak")
            .setContentText("Húzd el a bezáráshoz vagy koppints a megnyitáshoz.")
            .setSmallIcon(R.drawable.ic_call_chat)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (count > 0) {
            builder.setNumber(count)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(FOREGROUND_ID, builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if ("ACTION_DISMISS_CHAT_HEAD" == intent.action) {
                dismissChatHead()
                return START_NOT_STICKY
            }

            val senderName = intent.getStringExtra("senderName")
            val messageText = intent.getStringExtra("messageText")
            val avatarUrl = intent.getStringExtra("avatarUrl")
            val chatId = intent.getStringExtra("chatId")
            val isNewMessage = intent.getBooleanExtra("isNewMessage", false)

            if (chatId != null) {
                currentChatId = chatId
            }

            if (!chatId.isNullOrBlank() && !senderName.isNullOrBlank()) {
                updateConversationStack(chatId, senderName, messageText ?: "", avatarUrl ?: "")
            }

            if (isNewMessage) {
                BadgeHelper.incrementBadgeCount(this)
            }
            unreadCount = BadgeHelper.getBadgeCount(this)
            applyOverlayVisibility()

            unreadBadge?.let { badge ->
                if (unreadCount > 0) {
                    badge.text = unreadCount.toString()
                    badge.visibility = View.VISIBLE
                } else {
                    badge.visibility = View.GONE
                }
            }

            updateNotification(unreadCount)

            if (senderName != null && messageText != null && !isDragging) {
                showPreviewBubble(senderName, messageText)
            }
        }
        
        return START_STICKY
    }

    private fun updateConversationStack(chatId: String, sender: String, message: String, avatar: String) {
        val prefs = getSharedPreferences("messenger_chat_heads", Context.MODE_PRIVATE)
        val existing = readConversationStack().filterNot { it.optString("chatId") == chatId }.toMutableList()
        existing.add(0, JSONObject().apply {
            put("chatId", chatId)
            put("sender", sender)
            put("message", message)
            put("avatar", avatar)
            put("updatedAt", System.currentTimeMillis())
        })
        val trimmed = existing.take(5)
        prefs.edit().putString("conversations", JSONArray(trimmed).toString()).apply()
        renderConversationStack(trimmed)
    }

    private fun restoreConversationStack() {
        renderConversationStack(readConversationStack())
    }

    private fun readConversationStack(): List<JSONObject> {
        return try {
            val raw = getSharedPreferences("messenger_chat_heads", Context.MODE_PRIVATE)
                .getString("conversations", "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun renderConversationStack(items: List<JSONObject>) {
        if (items.isEmpty()) return
        val newest = items.first()
        currentChatId = newest.optString("chatId", currentChatId)
        loadAvatarImage(newest.optString("avatar"), avatarImage ?: return, newest.optString("sender"))
        stackedAvatarViews.forEachIndexed { index, image ->
            val item = items.getOrNull(index + 1)
            if (item == null) {
                image.visibility = View.GONE
            } else {
                image.visibility = View.VISIBLE
                loadAvatarImage(item.optString("avatar"), image, item.optString("sender"))
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showPreviewBubble(sender: String, text: String) {
        val bubble = previewBubble ?: return
        previewSender?.text = sender
        previewText?.text = text
        
        val params = chatHeadParams ?: return
        val isLeftEdge = params.x + chatHeadWidth / 2 < screenWidth / 2
        updateBubbleOrientation(isLeftEdge)

        bubble.visibility = View.VISIBLE
        bubble.alpha = 0.0f
        bubble.animate().alpha(1.0f).setDuration(250).start()

        bubbleHideRunnable?.let {
            bubbleHideHandler.removeCallbacks(it)
        }

        bubbleHideRunnable = Runnable {
            previewBubble?.let { b ->
                b.animate().alpha(0.0f).setDuration(250).withEndAction {
                    if (previewBubble != null && !isDragging) {
                        previewBubble?.visibility = View.GONE
                    }
                }.start()
            }
        }
        bubbleHideHandler.postDelayed(bubbleHideRunnable!!, 4000)
    }

    private fun loadAvatarImage(urlString: String?, imageView: ImageView, senderName: String?) {
        val value = urlString?.trim() ?: ""

        if (value.isEmpty() || value.startsWith("preset:")) {
            imageView.setImageBitmap(createInitialAvatarBitmap(senderName, value))
            return
        }

        if (value.startsWith("data:image")) {
            val dataBitmap = decodeDataImage(value)
            if (dataBitmap != null) {
                imageView.setImageBitmap(dataBitmap)
            } else {
                imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"))
            }
            return
        }

        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"))
            return
        }

        Thread {
            var connection: HttpURLConnection? = null
            var input: InputStream? = null
            try {
                val url = URL(value)
                connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.connect()

                val code = connection.responseCode
                if (code < 200 || code >= 300) {
                    throw RuntimeException("Avatar HTTP error: $code")
                }

                input = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(input)

                Handler(Looper.getMainLooper()).post {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading avatar image: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"))
                }
            } finally {
                try {
                    input?.close()
                    connection?.disconnect()
                } catch (ignored: Exception) {}
            }
        }.start()
    }

    private fun decodeDataImage(dataImage: String): Bitmap? {
        return try {
            val commaIndex = dataImage.indexOf(',')
            if (commaIndex < 0) return null
            val base64 = dataImage.substring(commaIndex + 1)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed decoding data image avatar: ${e.message}")
            null
        }
    }

    private fun createInitialAvatarBitmap(senderName: String?, preset: String): Bitmap {
        val size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics
        ).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val color = presetColor(preset)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.42f
        }

        val initial = getInitial(senderName)
        val metrics = textPaint.fontMetrics
        val y = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(initial, size / 2f, y, textPaint)

        return bitmap
    }

    private fun getInitial(senderName: String?): String {
        val trimmed = senderName?.trim() ?: ""
        if (trimmed.isEmpty()) return "?"
        return trimmed.substring(0, 1).uppercase(Locale.ROOT)
    }

    private fun presetColor(preset: String?): Int {
        if (preset == null) return Color.rgb(59, 130, 246)
        val p = preset.lowercase(Locale.ROOT)
        if (p.contains("green")) return Color.rgb(16, 185, 129)
        if (p.contains("red")) return Color.rgb(239, 68, 68)
        if (p.contains("pink")) return Color.rgb(236, 72, 153)
        if (p.contains("purple")) return Color.rgb(139, 92, 246)
        if (p.contains("orange")) return Color.rgb(249, 115, 22)
        if (p.contains("yellow")) return Color.rgb(234, 179, 8)
        if (p.contains("slate") || p.contains("gray") || p.contains("grey")) return Color.rgb(71, 85, 105)
        return Color.rgb(59, 130, 246)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Destroying ChatHeadService")
        val instance = instanceRef.get()
        if (instance == this) {
            instanceRef.clear()
        }
        
        bubbleHideRunnable?.let {
            bubbleHideHandler.removeCallbacks(it)
        }

        hideDismissZone()

        chatHeadView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (ignored: Exception) {}
            chatHeadView = null
        }
    }
}
