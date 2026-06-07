package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

object FloatingCallOverlay {
    private const val TAG = "FloatingCallOverlay"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var equalizerRunnable: Runnable? = null
    
    // Convert dp to px
    private fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    fun show(context: Context, callData: CallData) {
        // Dismiss first to be safe
        dismiss()

        // Verify overlay permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Draw over other apps permission not granted. Skipping floating overlay.")
            return
        }

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            // Create window params
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
                y = dpToPx(context, 16) // Spaced slightly below the top bar
                // Setup layout animations or margin if supported, or apply padding inside
            }

            // High-fidelity Glassmorphic Container
            val rootLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // Add margins around container (left/right) to keep capsule shape intact
                val sideMargin = dpToPx(context, 12)
                setPadding(dpToPx(context, 14), dpToPx(context, 14), dpToPx(context, 14), dpToPx(context, 14))
            }

            // Create capsule-styled Glassmorphic Card container
            val cardContainer = FrameLayout(context).apply {
                val outerParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dpToPx(context, 12), 0, dpToPx(context, 12), 0)
                }
                layoutParams = outerParams

                // Glassmorphic background: deep charcoal translucent blue with glowing blue border
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(context, 26).toFloat()
                    setColor(Color.parseColor("#E60F172A")) // Translucent slate-900 (90% opacity)
                    setStroke(dpToPx(context, 2), Color.parseColor("#330084FF")) // Smooth neon blue edge glow
                }
                background = bgDrawable

                // Add drop shadow if supported by SDK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dpToPx(context, 10).toFloat()
                    translationZ = dpToPx(context, 4).toFloat()
                }
            }

            // Horizontal inner linear layout to structure contents inside card
            val innerContent = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(context, 14), dpToPx(context, 14), dpToPx(context, 14), dpToPx(context, 14))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 1. Left: Circle Avatar + Phone badge (Outer FrameLayout)
            val avatarSize = dpToPx(context, 48)
            val avatarFrameLayout = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    rightMargin = dpToPx(context, 12)
                }
            }

            // Circle Avatar Inner Circle
            val avatarCircle = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(callData.callerAvatarHexColor.toInt())
                }
            }

            // Initials TextView in avatar
            val initialText = TextView(context).apply {
                text = if (callData.callerName.isNotEmpty()) callData.callerName.substring(0, 1).uppercase() else "?"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            avatarCircle.addView(initialText)
            avatarFrameLayout.addView(avatarCircle)

            // Small Blue Phone Active Badge at the bottom-right corner of avatar
            val badgeSize = dpToPx(context, 16)
            val badgeFrame = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                    gravity = Gravity.BOTTOM or Gravity.RIGHT
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#0084FF")) // Blue badge
                    setStroke(dpToPx(context, 1), Color.parseColor("#0F172A")) // Slate-900 border to separate from avatar
                }
            }
            val badgeIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_call_accept)
                setColorFilter(Color.WHITE)
                setPadding(dpToPx(context, 3), dpToPx(context, 3), dpToPx(context, 3), dpToPx(context, 3))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            badgeFrame.addView(badgeIcon)
            avatarFrameLayout.addView(badgeFrame)

            innerContent.addView(avatarFrameLayout)

            // 2. Middle: Text Details + Voice Soundwaves
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
            }

            val nameView = TextView(context).apply {
                text = callData.callerName
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textColumn.addView(nameView)

            val subtitleView = TextView(context).apply {
                text = if (callData.callType == CallType.VIDEO) "Bejövő videóhívás" else "Bejövő hanghívás"
                setTextColor(Color.parseColor("#94A3B8")) // Slate-400
                textSize = 11f
                maxLines = 1
            }
            textColumn.addView(subtitleView)

            // Voice equalizer soundwave linear container
            val visualizerContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(context, 16)
                ).apply {
                    topMargin = dpToPx(context, 4)
                }
            }

            // Create 15 equalizier bars
            val barCount = 14
            val barViews = ArrayList<View>()
            val barWidth = dpToPx(context, 2)
            val barSpacing = dpToPx(context, 2)
            
            for (i in 0 until barCount) {
                val bar = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(barWidth, dpToPx(context, 4)).apply {
                        rightMargin = barSpacing
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(context, 1).toFloat()
                        setColor(Color.parseColor("#0084FF")) // Vivid Messenger Blue wave
                    }
                }
                visualizerContainer.addView(bar)
                barViews.add(bar)
            }
            textColumn.addView(visualizerContainer)
            innerContent.addView(textColumn)

            // Start soundwave animator runnable
            equalizerRunnable = object : Runnable {
                override fun run() {
                    for (i in 0 until barCount) {
                        val bar = barViews[i]
                        val lParams = bar.layoutParams as LinearLayout.LayoutParams
                        // Animate heights differently to mimic genuine stream sounds
                        val heightDp = when (i % 4) {
                            0 -> (3 + Math.random() * 11).toInt()
                            1 -> (2 + Math.random() * 8).toInt()
                            2 -> (4 + Math.random() * 12).toInt()
                            else -> (3 + Math.random() * 7).toInt()
                        }
                        lParams.height = dpToPx(context, heightDp)
                        bar.layoutParams = lParams
                    }
                    handler.postDelayed(this, 120) // 120ms tick
                }
            }
            handler.post(equalizerRunnable!!)

            // 3. Right: Horizontal Action Buttons (Chat, Decline, Accept)
            val buttonsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Click tactile helper
            val triggerFeedback = {
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(35)
                    }
                } catch (e: Exception) {}
            }

            // Button 1: Quick Chat Bubble Icon (Translucent gray)
            val btnChat = FrameLayout(context).apply {
                val btnSize = dpToPx(context, 38)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    rightMargin = dpToPx(context, 6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#26FFFFFF")) // 15% opacity white
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    triggerFeedback()
                    Log.d(TAG, "Chat button clicked in overlay")
                    dismiss()
                    // Start MainActivity and open Chat
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("open_chat_user", callData.callerName)
                    }
                    context.startActivity(mainIntent)
                }

                val chatIcon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_call_chat)
                    setColorFilter(Color.WHITE)
                    setPadding(dpToPx(context, 9), dpToPx(context, 9), dpToPx(context, 9), dpToPx(context, 9))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(chatIcon)
            }

            // Button 2: Decline Red Call End Icon
            val btnDecline = FrameLayout(context).apply {
                val btnSize = dpToPx(context, 38)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    rightMargin = dpToPx(context, 6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EF4444")) // Red
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    triggerFeedback()
                    Log.d(TAG, "Decline call from overlay")
                    dismiss()
                    CallManager.declineCall(context)
                }

                val declineIcon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_call_decline)
                    setColorFilter(Color.WHITE)
                    setPadding(dpToPx(context, 9), dpToPx(context, 9), dpToPx(context, 9), dpToPx(context, 9))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(declineIcon)
            }

            // Button 3: Accept Green Call Answer Icon
            val btnAccept = FrameLayout(context).apply {
                val btnSize = dpToPx(context, 38)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#22C55E")) // Green
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    triggerFeedback()
                    Log.d(TAG, "Accept call from overlay")
                    dismiss()
                    
                    // Answer call and bring up IncomingCallActivity
                    CallManager.answerCall(context)
                    val incomingIntent = Intent(context, IncomingCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    context.startActivity(incomingIntent)
                }

                val acceptIcon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_call_accept)
                    setColorFilter(Color.WHITE)
                    setPadding(dpToPx(context, 9), dpToPx(context, 9), dpToPx(context, 9), dpToPx(context, 9))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(acceptIcon)
            }

            buttonsRow.addView(btnChat)
            buttonsRow.addView(btnDecline)
            buttonsRow.addView(btnAccept)

            innerContent.addView(buttonsRow)
            cardContainer.addView(innerContent)
            rootLayout.addView(cardContainer)

            overlayView = rootLayout

            // Present the overlay via WindowManager
            windowManager?.addView(rootLayout, params)
            Log.d(TAG, "Floating calling overlay displayed successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "Error displaying floating call overlay", e)
        }
    }

    fun dismiss() {
        equalizerRunnable?.let {
            handler.removeCallbacks(it)
            equalizerRunnable = null
        }
        overlayView?.let {
            try {
                windowManager?.removeView(it)
                Log.d(TAG, "Floating calling overlay dismissed successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing overlay view", e)
            }
            overlayView = null
        }
        windowManager = null
    }
}
