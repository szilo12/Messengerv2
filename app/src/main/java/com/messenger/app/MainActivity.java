package com.messenger.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import com.getcapacitor.BridgeActivity;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    private static final String CALL_CHANNEL_ID = "messenger_calls_fullscreen";
    public static boolean isAppVisible = false;
    public static String activeChatId = null;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 5469;
    private static final int MEDIA_PERMISSION_REQ_CODE = 5470;
    private static final int NOTIFICATION_PERMISSION_REQ_CODE = 5471;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(ChatHeadPlugin.class);
        super.onCreate(savedInstanceState);

        // Set status bar and navigation bar to app's dark purple background color
        int appBgColor = Color.parseColor("#0b0614");
        getWindow().setStatusBarColor(appBgColor);
        getWindow().setNavigationBarColor(appBgColor);

        // Use white icons on the dark bar backgrounds, including Samsung/Android 15.
        applyDarkSystemBarAppearance();

        allowWebViewMediaPermissions();
        requestMediaPermissionsIfNeeded();
        requestNotificationPermissionIfNeeded();
        createNotificationChannels();
        handleIntent(getIntent());
        checkOverlayPermission();
        checkFullScreenCallPermission();
    }

    private void requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        if (!cameraGranted || !audioGranted) {
            ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO },
                MEDIA_PERMISSION_REQ_CODE
            );
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    new String[] { Manifest.permission.POST_NOTIFICATIONS },
                    NOTIFICATION_PERMISSION_REQ_CODE
                );
            }
        }
    }

    private void allowWebViewMediaPermissions() {
        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        getBridge().getWebView().setWebChromeClient(new com.getcapacitor.BridgeWebChromeClient(getBridge()) {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                if (request == null) return;
                try {
                    request.grant(request.getResources());
                } catch (Exception error) {
                    Log.w(TAG, "WebView media permission request failed: " + error.getMessage());
                }
            }
        });
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
                Toast.makeText(this, "Kérlek engedélyezd a 'Megjelenítés más alkalmazások felett' opciót a Chat fejekhez!", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkFullScreenCallPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null && !manager.canUseFullScreenIntent()) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    Toast.makeText(this, "Engedelyezd a teljes kepernyos hivasertesiteseket, hogy zarolt kijelzon is megjelenjen a hivo.", Toast.LENGTH_LONG).show();
                }
            } catch (Exception error) {
                Log.w(TAG, "Full-screen intent settings could not be opened: " + error.getMessage());
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "onActivityResult: Overlay permission granted.");
                    showActiveCallReturnSurface();
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        isAppVisible = true;
        Log.d(TAG, "onResume: App is visible. Keeping active chat heads alive.");
        ChatHeadService.setAppVisible(true);
        
        // Stop all ringtones (both native and web)
        MyFirebaseMessagingService.stopRingtone();
        
        // Tell the JS side to stop any playing ringtones as well
        ChatHeadPlugin.sendStopRingtoneEvent();

        // Clear app icon notification badge count
        BadgeHelper.clearBadge(this);

        // Hide the floating call overlay view since we are now in the app foreground
        Intent hideOverlayIntent = new Intent(this, FloatingCallOverlayService.class);
        hideOverlayIntent.setAction("com.messenger.app.FLOATING_CALL_HIDE_OVERLAY_ONLY");
        try {
            startService(hideOverlayIntent);
        } catch (Exception e) {
            Log.w(TAG, "Could not hide floating call overlay on resume: " + e.getMessage());
        }

        // Tell React app that lifecycle is resuming, to check pending intents / call actions
        ChatHeadPlugin.triggerWebEvent("appResume");

        // Restore system bars when the app is in the foreground without an active call
        if (!ChatHeadPlugin.hasActiveOngoingCall()) {
            clearFullScreenCallWindow();
        }

        // Update ongoing call banner inside the app
        updateOngoingCallBanner();
    }

    private void applyDarkSystemBarAppearance() {
        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat compatController =
            new WindowInsetsControllerCompat(getWindow(), decorView);
        compatController.setAppearanceLightStatusBars(false);
        compatController.setAppearanceLightNavigationBars(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        } else {
            int visibility = decorView.getSystemUiVisibility();
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decorView.setSystemUiVisibility(visibility);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        isAppVisible = false;
        ChatHeadService.setAppVisible(false);
        Log.d(TAG, "onPause: App is in background.");
        showActiveCallReturnSurface();

        // Hide ongoing call banner
        if (callBannerView != null) {
            android.widget.FrameLayout root = findViewById(android.R.id.content);
            root.removeView(callBannerView);
            callBannerView = null;
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (ChatHeadPlugin.hasActiveOngoingCall()) {
            showActiveCallReturnSurface();
        }
    }

    private void showActiveCallReturnSurface() {
        if (ChatHeadPlugin.hasActiveOngoingCall()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                checkOverlayPermission();
                return;
            }
            ChatHeadPlugin.showActiveCallOverlayFromNative(this);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null) {
            // Stop any playing background calling ringtone
            MyFirebaseMessagingService.stopRingtone();

            if (intent.hasExtra("chatId")) {
                String chatId = intent.getStringExtra("chatId");
                Log.d(TAG, "handleIntent: Received intent with chatId: " + chatId);
                
                if ("like".equals(intent.getStringExtra("messageAction"))) {
                    Log.d(TAG, "handleIntent: Received like action");
                    ChatHeadPlugin.pendingMessageAction = "like";
                    ChatHeadPlugin.pendingMessageActionChatId = chatId;
                    ChatHeadPlugin.sendLikeActionEvent(chatId);
                } else if (chatId != null && !chatId.isEmpty()) {
                    activeChatId = chatId;
                    ChatHeadPlugin.pendingChatId = chatId;
                    ChatHeadPlugin.sendChatHeadTappedEvent(chatId);
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    if (manager != null) {
                        manager.cancel(chatId.hashCode());
                    }
                }
            }

            if (intent.hasExtra("callId") && intent.hasExtra("callAction")) {
                String callId = intent.getStringExtra("callId");
                String callAction = intent.getStringExtra("callAction");
                String chatId = intent.getStringExtra("chatId");
                String callerName = intent.hasExtra("callerName") ? intent.getStringExtra("callerName") : "";
                String callType = intent.hasExtra("callType") ? intent.getStringExtra("callType") : "";
                String avatarUrl = intent.hasExtra("avatarUrl") ? intent.getStringExtra("avatarUrl") : "";
                long callStartedAt = intent.getLongExtra("callStartedAt", 0L);
                Log.d(TAG, "handleIntent: Received call action: " + callAction + " for callId: " + callId);

                prepareFullScreenCallWindow();
                if (callStartedAt > 0L) {
                    ChatHeadPlugin.activeCallStartedAt = callStartedAt;
                }
                
                if ("accept".equals(callAction) || "maximize".equals(callAction)) {
                    Intent hideOverlayIntent = new Intent(this, FloatingCallOverlayService.class);
                    hideOverlayIntent.setAction(FloatingCallOverlayService.ACTION_HIDE_OVERLAY_ONLY);
                    try {
                        startService(hideOverlayIntent);
                    } catch (Exception e) {
                        Log.w(TAG, "Could not hide call overlay only: " + e.getMessage());
                    }
                } else {
                    FloatingCallOverlayService.stop(this, callId);
                }

                ChatHeadPlugin.pendingCallId = callId;
                ChatHeadPlugin.pendingCallAction = callAction;
                ChatHeadPlugin.pendingCallChatId = chatId;
                ChatHeadPlugin.pendingCallerName = callerName;
                ChatHeadPlugin.pendingCallType = callType;
                ChatHeadPlugin.pendingAvatarUrl = avatarUrl;
                ChatHeadPlugin.sendCallActionEvent(callId, callAction, chatId, callerName, callType, avatarUrl);
                
                if (!"accept".equals(callAction) && !"maximize".equals(callAction)) {
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    if (manager != null) {
                        manager.cancel(7777);
                        manager.cancel(8888);
                    }
                }
                updateOngoingCallBanner();
            }
        }
    }

    private void prepareFullScreenCallWindow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not prepare full-screen call window: " + error.getMessage());
        }
    }

    private void clearFullScreenCallWindow() {
        try {
            getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                }
            } else {
                int flags = getWindow().getDecorView().getSystemUiVisibility();
                flags &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
                flags &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                flags &= ~View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not clear full-screen call window: " + error.getMessage());
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                // Delete existing channel to force update the sound settings
                manager.deleteNotificationChannel("messenger_calls");
                manager.deleteNotificationChannel(CALL_CHANNEL_ID);
            }

            // Messenger-style Chat Channel
            NotificationChannel chatChannel = new NotificationChannel(
                "messenger_chat",
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            );
            chatChannel.setDescription("Chat messages and bubbles");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                chatChannel.setAllowBubbles(true);
            }

            // Messenger-style Call Channel
            NotificationChannel callChannel = new NotificationChannel(
                CALL_CHANNEL_ID,
                "Calls full screen",
                NotificationManager.IMPORTANCE_HIGH
            );
            callChannel.setDescription("Incoming call notifications");
            callChannel.enableVibration(true);

            // Configure call channel to be silent, as we play/stop the ringtone ourselves via MediaPlayer
            callChannel.setSound(null, null);

            if (manager != null) {
                manager.createNotificationChannel(chatChannel);
                manager.createNotificationChannel(callChannel);
            }
        }
    }

    private View callBannerView = null;

    public void updateOngoingCallBanner() {
        runOnUiThread(() -> {
            android.widget.FrameLayout root = findViewById(android.R.id.content);
            if (callBannerView != null) {
                root.removeView(callBannerView);
                callBannerView = null;
            }

            // The in-app active call card is rendered by the React sidebar so it can
            // sit exactly between the filter chips and the "active now" row.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BASE) {
                return;
            }

            if (!ChatHeadPlugin.hasActiveOngoingCall()) {
                return;
            }

            float density = getResources().getDisplayMetrics().density;
            int dp2 = dp(density, 2);
            int dp3 = dp(density, 3);
            int dp6 = dp(density, 6);
            int dp8 = dp(density, 8);
            int dp10 = dp(density, 10);
            int dp12 = dp(density, 12);
            int dp14 = dp(density, 14);
            int dp18 = dp(density, 18);
            int dp22 = dp(density, 22);
            int dp28 = dp(density, 28);
            int dp40 = dp(density, 40);
            int dp62 = dp(density, 62);

            String caller = ChatHeadPlugin.activeCallerName != null && !ChatHeadPlugin.activeCallerName.isEmpty()
                ? ChatHeadPlugin.activeCallerName
                : "Messenger hivas";
            boolean isVideoCall = "video".equals(ChatHeadPlugin.activeCallType);
            long startedAt = ChatHeadPlugin.activeCallStartedAt > 0L
                ? ChatHeadPlugin.activeCallStartedAt
                : RtcConnectionManager.INSTANCE.getCallStartedAt();

            android.widget.LinearLayout banner = new android.widget.LinearLayout(this);
            banner.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            banner.setGravity(android.view.Gravity.CENTER_VERTICAL);
            banner.setMinimumHeight(dp(density, 82));
            banner.setPadding(dp12, dp10, dp10, dp10);

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            cardBg.setCornerRadius(dp18);
            cardBg.setColor(Color.parseColor("#F7FBFF"));
            cardBg.setStroke(dp2, Color.parseColor("#D6E8FF"));
            banner.setBackground(cardBg);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                banner.setElevation(7 * density);
                banner.setTranslationZ(3 * density);
            }

            android.widget.FrameLayout avatarFrame = new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable avatarBg = new android.graphics.drawable.GradientDrawable();
            avatarBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            avatarBg.setColor(Color.parseColor("#EAF4FF"));
            avatarBg.setStroke(dp2, Color.parseColor("#1D8CFF"));
            avatarFrame.setBackground(avatarBg);

            android.widget.ImageView avatarIcon = new android.widget.ImageView(this);
            avatarIcon.setImageResource(R.drawable.ic_avatar_placeholder);
            avatarIcon.setPadding(dp8, dp8, dp8, dp8);
            avatarFrame.addView(avatarIcon, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ));

            android.widget.FrameLayout phoneBadge = new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
            badgeBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            badgeBg.setColor(Color.parseColor("#1D8CFF"));
            phoneBadge.setBackground(badgeBg);
            android.widget.ImageView phoneIcon = new android.widget.ImageView(this);
            phoneIcon.setImageResource(R.drawable.ic_call_accept);
            phoneIcon.setColorFilter(Color.WHITE);
            phoneIcon.setPadding(dp6, dp6, dp6, dp6);
            phoneBadge.addView(phoneIcon, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ));
            android.widget.FrameLayout.LayoutParams badgeLp = new android.widget.FrameLayout.LayoutParams(dp28, dp28);
            badgeLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
            avatarFrame.addView(phoneBadge, badgeLp);

            android.widget.LinearLayout.LayoutParams avatarLp = new android.widget.LinearLayout.LayoutParams(dp62, dp62);
            avatarLp.rightMargin = dp12;
            banner.addView(avatarFrame, avatarLp);

            android.widget.LinearLayout textColumn = new android.widget.LinearLayout(this);
            textColumn.setOrientation(android.widget.LinearLayout.VERTICAL);
            textColumn.setGravity(android.view.Gravity.CENTER_VERTICAL);

            android.widget.LinearLayout statusRow = new android.widget.LinearLayout(this);
            statusRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.ImageView smallCallIcon = new android.widget.ImageView(this);
            smallCallIcon.setImageResource(R.drawable.ic_call_accept);
            smallCallIcon.setColorFilter(Color.parseColor("#1D8CFF"));
            statusRow.addView(smallCallIcon, new android.widget.LinearLayout.LayoutParams(dp14, dp14));

            android.widget.TextView statusText = new android.widget.TextView(this);
            statusText.setText(isVideoCall ? " AKTIV VIDEOHIVAS" : " AKTIV HANGHIVAS");
            statusText.setTextColor(Color.parseColor("#2B74C7"));
            statusText.setTextSize(10.5f);
            statusText.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            statusRow.addView(statusText);
            textColumn.addView(statusRow);

            android.widget.TextView titleText = new android.widget.TextView(this);
            titleText.setText(caller);
            titleText.setTextColor(Color.parseColor("#0F172A"));
            titleText.setTextSize(16f);
            titleText.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            titleText.setSingleLine(true);
            titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textColumn.addView(titleText, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            android.widget.LinearLayout timerRow = new android.widget.LinearLayout(this);
            timerRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            timerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.TextView dot = new android.widget.TextView(this);
            dot.setText("•");
            dot.setTextColor(Color.parseColor("#1D8CFF"));
            dot.setTextSize(18f);
            timerRow.addView(dot);

            android.widget.Chronometer timer = new android.widget.Chronometer(this);
            long elapsedMs = System.currentTimeMillis() - (startedAt > 0L ? startedAt : System.currentTimeMillis());
            timer.setBase(android.os.SystemClock.elapsedRealtime() - elapsedMs);
            timer.setTextColor(Color.parseColor("#64748B"));
            timer.setTextSize(12f);
            timer.setTypeface(android.graphics.Typeface.MONOSPACE);
            timer.start();
            timerRow.addView(timer);
            textColumn.addView(timerRow);

            banner.addView(textColumn, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.LinearLayout waveRow = new android.widget.LinearLayout(this);
            waveRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            waveRow.setGravity(android.view.Gravity.CENTER);
            int[] heights = new int[] { 8, 14, 20, 12, 26, 18, 30, 16, 24, 10, 18, 12 };
            for (int h : heights) {
                View bar = new View(this);
                android.graphics.drawable.GradientDrawable barBg = new android.graphics.drawable.GradientDrawable();
                barBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                barBg.setCornerRadius(dp2);
                barBg.setColor(Color.parseColor("#7AB8F5"));
                bar.setBackground(barBg);
                android.widget.LinearLayout.LayoutParams barLp = new android.widget.LinearLayout.LayoutParams(dp3, dp(density, h));
                barLp.leftMargin = dp2;
                waveRow.addView(bar, barLp);

                android.view.animation.ScaleAnimation voicePulse = new android.view.animation.ScaleAnimation(
                    1f,
                    1f,
                    0.35f,
                    1.18f,
                    android.view.animation.Animation.RELATIVE_TO_SELF,
                    0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF,
                    0.5f
                );
                voicePulse.setDuration(520 + (h * 9L));
                voicePulse.setStartOffset((h * 23L) % 360L);
                voicePulse.setRepeatCount(android.view.animation.Animation.INFINITE);
                voicePulse.setRepeatMode(android.view.animation.Animation.REVERSE);
                voicePulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                bar.startAnimation(voicePulse);
            }
            android.widget.LinearLayout.LayoutParams waveLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                dp40
            );
            waveLp.leftMargin = dp8;
            waveLp.rightMargin = dp10;
            banner.addView(waveRow, waveLp);

            android.widget.TextView returnButton = new android.widget.TextView(this);
            returnButton.setText("Vissza");
            returnButton.setTextColor(Color.WHITE);
            returnButton.setTextSize(12f);
            returnButton.setGravity(android.view.Gravity.CENTER);
            returnButton.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            android.graphics.drawable.GradientDrawable returnBg = new android.graphics.drawable.GradientDrawable();
            returnBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            returnBg.setCornerRadius(dp22);
            returnBg.setColor(Color.parseColor("#2F80ED"));
            returnButton.setBackground(returnBg);
            returnButton.setOnClickListener(v -> openActiveCallScreen());
            android.widget.LinearLayout.LayoutParams returnLp = new android.widget.LinearLayout.LayoutParams(dp(density, 64), dp40);
            returnLp.rightMargin = dp8;
            banner.addView(returnButton, returnLp);

            android.widget.FrameLayout endButton = new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable endBg = new android.graphics.drawable.GradientDrawable();
            endBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            endBg.setColor(Color.parseColor("#EF3F5A"));
            endButton.setBackground(endBg);
            android.widget.ImageView endIcon = new android.widget.ImageView(this);
            endIcon.setImageResource(R.drawable.ic_call_decline);
            endIcon.setColorFilter(Color.WHITE);
            endIcon.setPadding(dp8, dp8, dp8, dp8);
            endButton.addView(endIcon, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ));
            endButton.setOnClickListener(v -> {
                Intent endIntent = new Intent(this, NotificationReceiver.class);
                endIntent.setAction(NotificationReceiver.ACTION_END_CALL);
                endIntent.putExtra("chatId", ChatHeadPlugin.activeChatId);
                endIntent.putExtra("callId", ChatHeadPlugin.activeCallId);
                endIntent.putExtra("callerName", ChatHeadPlugin.activeCallerName);
                endIntent.putExtra("callType", ChatHeadPlugin.activeCallType);
                endIntent.putExtra("avatarUrl", ChatHeadPlugin.activeAvatarUrl);
                sendBroadcast(endIntent);
            });
            banner.addView(endButton, new android.widget.LinearLayout.LayoutParams(dp40, dp40));

            banner.setOnClickListener(v -> openActiveCallScreen());

            android.widget.FrameLayout.LayoutParams bannerParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            );
            bannerParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            bannerParams.leftMargin = dp18;
            bannerParams.rightMargin = dp18;
            bannerParams.topMargin = dp(density, 182);

            root.addView(banner, bannerParams);
            callBannerView = banner;
        });
    }

    private int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }

    private void openActiveCallScreen() {
        Intent activeCallIntent = new Intent(this, ActiveCallActivity.class);
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activeCallIntent.putExtra("chatId", ChatHeadPlugin.activeChatId);
        activeCallIntent.putExtra("callId", ChatHeadPlugin.activeCallId);
        activeCallIntent.putExtra("callerName", ChatHeadPlugin.activeCallerName);
        activeCallIntent.putExtra("callType", ChatHeadPlugin.activeCallType);
        activeCallIntent.putExtra("avatarUrl", ChatHeadPlugin.activeAvatarUrl);
        activeCallIntent.putExtra("callStartedAt", ChatHeadPlugin.activeCallStartedAt > 0L
            ? ChatHeadPlugin.activeCallStartedAt
            : RtcConnectionManager.INSTANCE.getCallStartedAt());
        startActivity(activeCallIntent);
    }

    private void updateOngoingCallBannerLegacy() {
        runOnUiThread(() -> {
            boolean hasCall = ChatHeadPlugin.hasActiveOngoingCall();
            if (hasCall) {
                if (callBannerView != null) {
                    android.widget.FrameLayout root = findViewById(android.R.id.content);
                    root.removeView(callBannerView);
                    callBannerView = null;
                }
                
                float density = getResources().getDisplayMetrics().density;
                int dp24 = (int) (24 * density + 0.5f);
                int dp12 = (int) (12 * density + 0.5f);
                int dp16 = (int) (16 * density + 0.5f);
                int dp40 = (int) (40 * density + 0.5f);
                int dp20 = (int) (20 * density + 0.5f);

                android.widget.LinearLayout banner = new android.widget.LinearLayout(this);
                banner.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                banner.setGravity(android.view.Gravity.CENTER_VERTICAL);
                
                // Deep Charcoal/Slate glassmorphic card background with neon blue edge glow
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                gd.setCornerRadius(dp24);
                gd.setColor(Color.parseColor("#E60F172A")); // Translucent Slate-900 (90% opacity)
                gd.setStroke((int) (1.5f * density + 0.5f), Color.parseColor("#330084FF")); // Neon blue border glow
                banner.setBackground(gd);
                
                banner.setPadding(dp16, dp12, dp16, dp12);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    banner.setElevation(10 * density);
                    banner.setTranslationZ(4 * density);
                }
                
                // Emerald circular wrapper for the call icon
                android.widget.FrameLayout iconWrapper = new android.widget.FrameLayout(this);
                android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
                iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                iconBg.setColor(Color.parseColor("#10B981")); // Emerald Green
                iconWrapper.setBackground(iconBg);
                
                android.widget.ImageView icon = new android.widget.ImageView(this);
                int iconResId = getResources().getIdentifier("ic_call_chat", "drawable", getPackageName());
                if (iconResId != 0) {
                    icon.setImageResource(iconResId);
                } else {
                    icon.setImageResource(android.R.drawable.ic_menu_call);
                }
                icon.setColorFilter(Color.WHITE);
                
                android.widget.FrameLayout.LayoutParams iconInnerParams = new android.widget.FrameLayout.LayoutParams(dp20, dp20);
                iconInnerParams.gravity = android.view.Gravity.CENTER;
                iconWrapper.addView(icon, iconInnerParams);
                
                // Slow ambient pulsing animation on the icon wrapper
                android.view.animation.AlphaAnimation pulseAnim = new android.view.animation.AlphaAnimation(0.5f, 1.0f);
                pulseAnim.setDuration(1200);
                pulseAnim.setRepeatCount(android.view.animation.Animation.INFINITE);
                pulseAnim.setRepeatMode(android.view.animation.Animation.REVERSE);
                iconWrapper.startAnimation(pulseAnim);
                
                android.widget.LinearLayout.LayoutParams wrapperLp = new android.widget.LinearLayout.LayoutParams(dp40, dp40);
                wrapperLp.rightMargin = dp12;
                banner.addView(iconWrapper, wrapperLp);
                
                // Vertical text details Column
                android.widget.LinearLayout textColumn = new android.widget.LinearLayout(this);
                textColumn.setOrientation(android.widget.LinearLayout.VERTICAL);
                
                android.widget.TextView titleText = new android.widget.TextView(this);
                String caller = ChatHeadPlugin.activeCallerName != null ? ChatHeadPlugin.activeCallerName : "hívás";
                titleText.setText("Aktív hívás • " + caller);
                titleText.setTextColor(Color.WHITE);
                titleText.setTextSize(13.5f);
                titleText.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
                textColumn.addView(titleText);
                
                android.widget.TextView subtitleText = new android.widget.TextView(this);
                subtitleText.setText("Koppints a visszatéréshez a beszélgetéshez");
                subtitleText.setTextColor(Color.parseColor("#94A3B8")); // Slate-400
                subtitleText.setTextSize(10.5f);
                subtitleText.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
                
                android.widget.LinearLayout.LayoutParams subLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                );
                subLp.topMargin = (int) (2 * density + 0.5f);
                textColumn.addView(subtitleText, subLp);
                
                banner.addView(textColumn, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                
                banner.setOnClickListener(v -> {
                    Intent activeCallIntent = new Intent(this, ActiveCallActivity.class);
                    activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    activeCallIntent.putExtra("chatId", ChatHeadPlugin.activeChatId);
                    activeCallIntent.putExtra("callId", ChatHeadPlugin.activeCallId);
                    activeCallIntent.putExtra("callerName", ChatHeadPlugin.activeCallerName);
                    activeCallIntent.putExtra("callType", ChatHeadPlugin.activeCallType);
                    activeCallIntent.putExtra("avatarUrl", ChatHeadPlugin.activeAvatarUrl);
                    activeCallIntent.putExtra("callStartedAt", RtcConnectionManager.INSTANCE.getCallStartedAt());
                    startActivity(activeCallIntent);
                });
                
                android.widget.FrameLayout root = findViewById(android.R.id.content);
                android.widget.FrameLayout.LayoutParams bannerParams = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                );
                bannerParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                
                int marginHor = (int) (14 * density + 0.5f);
                int marginTop = (int) (10 * density + 0.5f);
                bannerParams.leftMargin = marginHor;
                bannerParams.rightMargin = marginHor;
                
                int statusBarHeight = 0;
                int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (resourceId > 0) {
                    statusBarHeight = getResources().getDimensionPixelSize(resourceId);
                }
                bannerParams.topMargin = statusBarHeight + marginTop;
                
                root.addView(banner, bannerParams);
                callBannerView = banner;
            } else {
                if (callBannerView != null) {
                    android.widget.FrameLayout root = findViewById(android.R.id.content);
                    root.removeView(callBannerView);
                    callBannerView = null;
                }
            }
        });
    }
}
