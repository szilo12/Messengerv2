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
        super.onCreate(savedInstanceState);

        // Set status bar and navigation bar to app's dark purple background color
        int appBgColor = Color.parseColor("#0b0614");
        getWindow().setStatusBarColor(appBgColor);
        getWindow().setNavigationBarColor(appBgColor);

        // Use light icons (white) on the dark bar backgrounds
        WindowInsetsControllerCompat insetsController =
            new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

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
    }

    @Override
    public void onPause() {
        super.onPause();
        isAppVisible = false;
        ChatHeadService.setAppVisible(false);
        Log.d(TAG, "onPause: App is in background.");
        showActiveCallReturnSurface();
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
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
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
}
