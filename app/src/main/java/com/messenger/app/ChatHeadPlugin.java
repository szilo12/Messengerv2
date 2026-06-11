package com.messenger.app;

import android.content.Intent;
import android.app.NotificationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import android.graphics.Color;
import androidx.core.view.WindowInsetsControllerCompat;

@CapacitorPlugin(name = "ChatHead")
public class ChatHeadPlugin extends Plugin {
    private static final String TAG = "ChatHeadPlugin";
    private static final String PREFS_NAME = "messenger_notification_prefs";
    private static ChatHeadPlugin instance;

    public static String pendingChatId = null;
    public static String pendingCallId = null;
    public static String pendingCallAction = null;
    public static String pendingCallChatId = null;
    public static String pendingCallerName = null;
    public static String pendingCallType = null;
    public static String pendingAvatarUrl = null;
    public static String pendingMessageAction = null;
    public static String pendingMessageActionChatId = null;

    public static boolean activeOngoingCall = false;
    public static String activeCallId = null;
    public static String activeCallerName = null;
    public static String activeCallType = null;
    public static String activeAvatarUrl = null;
    public static String activeChatId = null;
    public static long activeCallStartedAt = 0L;

    @Override
    public void load() {
        super.load();
        instance = this;
        Log.d(TAG, "ChatHeadPlugin loaded");
    }

    @Override
    protected void handleOnDestroy() {
        instance = null;
        super.handleOnDestroy();
    }

    public static void sendChatHeadTappedEvent(String chatId) {
        if (instance != null) {
            Log.d(TAG, "sendChatHeadTappedEvent: Dispatching chatHeadTapped to JS with chatId: " + chatId);
            JSObject data = new JSObject();
            data.put("chatId", chatId);
            instance.notifyListeners("chatHeadTapped", data);
        } else {
            Log.w(TAG, "sendChatHeadTappedEvent: Plugin instance not loaded yet. Event queued.");
        }
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject ret = new JSObject();
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = Settings.canDrawOverlays(getContext());
        }
        ret.put("granted", granted);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        JSObject ret = new JSObject();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(getContext())) {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName())
                );
                getActivity().startActivity(intent);
                ret.put("requested", true);
                ret.put("granted", false);
            } else {
                ret.put("requested", false);
                ret.put("granted", true);
            }
        } else {
            ret.put("requested", false);
            ret.put("granted", true);
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void show(PluginCall call) {
        String senderName = call.getString("senderName", "Messenger");
        String messageText = call.getString("messageText", "");
        String avatarUrl = call.getString("avatarUrl", "");
        String chatId = call.getString("chatId", "");

        Log.d(TAG, "show: Showing chat head from JS side. Sender: " + senderName);

        Intent intent = new Intent(getContext(), ChatHeadService.class);
        intent.putExtra("senderName", senderName);
        intent.putExtra("messageText", messageText);
        intent.putExtra("avatarUrl", avatarUrl);
        intent.putExtra("chatId", chatId);
        intent.putExtra("isNewMessage", true);

        try {
            androidx.core.content.ContextCompat.startForegroundService(getContext(), intent);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ChatHeadService from plugin: " + e.getMessage());
            call.reject("Failed to start service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void hide(PluginCall call) {
        Log.d(TAG, "hide: Hiding chat head from JS side");
        Intent intent = new Intent(getContext(), ChatHeadService.class);
        getContext().stopService(intent);
        call.resolve();
    }

    @PluginMethod
    public void setActiveChat(PluginCall call) {
        MainActivity.activeChatId = call.getString("chatId", null);
        if (MainActivity.activeChatId != null && !MainActivity.activeChatId.isEmpty()) {
            NotificationManager manager = (NotificationManager) getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(MainActivity.activeChatId.hashCode());
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void setNotificationPreferences(PluginCall call) {
        boolean soundEnabled = call.getBoolean("soundEnabled", true);
        boolean vibrationEnabled = call.getBoolean("vibrationEnabled", true);
        String messageSound = call.getString("messageSound", "default");

        getContext()
            .getSharedPreferences("messenger_notification_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("soundEnabled", soundEnabled)
            .putBoolean("vibrationEnabled", vibrationEnabled)
            .putString("messageSound", messageSound)
            .apply();

        call.resolve();
    }

    @PluginMethod
    public void setBackendUrl(PluginCall call) {
        String url = call.getString("url", "");
        Log.d(TAG, "setBackendUrl: Saving backend URL: " + url);
        getContext()
            .getSharedPreferences("messenger_notification_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("backendUrl", url)
            .apply();
        call.resolve();
    }

    @PluginMethod
    public void stopRingtone(PluginCall call) {
        Log.d(TAG, "stopRingtone: Stopping ringtone from JS side");
        MyFirebaseMessagingService.stopRingtone();

        try {
            NotificationManager manager = (NotificationManager) getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(8888);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to cancel call notification from plugin: " + e.getMessage());
        }

        call.resolve();
    }

    @PluginMethod
    public void showOngoingCallNotification(PluginCall call) {
        String callId = call.getString("callId", "");
        String callerName = call.getString("callerName", "Messenger");
        String callType = call.getString("callType", "audio");
        String avatarUrl = call.getString("avatarUrl", "");
        String chatId = call.getString("chatId", "");
        long callStartedAt = call.getLong("callStartedAt", 0L);
        if (callStartedAt <= 0L) {
            callStartedAt = System.currentTimeMillis();
        }

        activeOngoingCall = true;
        activeCallId = callId;
        activeCallerName = callerName;
        activeCallType = callType;
        activeAvatarUrl = avatarUrl;
        activeChatId = chatId;
        activeCallStartedAt = callStartedAt;

        Intent intent = new Intent(getContext(), FloatingCallOverlayService.class);
        intent.setAction(FloatingCallOverlayService.ACTION_SHOW_NOTIFICATION_ONLY);
        intent.putExtra("callId", callId);
        intent.putExtra("chatId", chatId);
        intent.putExtra("callerName", callerName);
        intent.putExtra("callType", callType);
        intent.putExtra("avatarUrl", avatarUrl);
        intent.putExtra("statusText", call.getString("statusText", ""));
        intent.putExtra("isOngoingOrOutgoing", true);
        intent.putExtra("callStartedAt", callStartedAt);
        try {
            androidx.core.content.ContextCompat.startForegroundService(getContext(), intent);
        } catch (Exception e) {
            Log.e(TAG, "showOngoingCallNotification failed: " + e.getMessage());
        }

        call.resolve();
    }

    @PluginMethod
    public void dismissOngoingCallNotification(PluginCall call) {
        activeOngoingCall = false;
        activeCallId = null;
        activeCallerName = null;
        activeCallType = null;
        activeAvatarUrl = null;
        activeChatId = null;
        activeCallStartedAt = 0L;
        FloatingCallOverlayService.stop(getContext(), null);
        call.resolve();
    }

    @PluginMethod
    public void showFloatingCallOverlay(PluginCall call) {
        String callId = call.getString("callId", "");
        String callerName = call.getString("callerName", "Messenger");
        String callType = call.getString("callType", "audio");
        String avatarUrl = call.getString("avatarUrl", "");
        String chatId = call.getString("chatId", "");
        long callStartedAt = call.getLong("callStartedAt", 0L);
        if (callStartedAt <= 0L) {
            callStartedAt = System.currentTimeMillis();
        }

        activeOngoingCall = true;
        activeCallId = callId;
        activeCallerName = callerName;
        activeCallType = callType;
        activeAvatarUrl = avatarUrl;
        activeChatId = chatId;
        activeCallStartedAt = callStartedAt;

        Intent intent = new Intent(getContext(), FloatingCallOverlayService.class);
        intent.setAction(FloatingCallOverlayService.ACTION_SHOW);
        intent.putExtra("callId", callId);
        intent.putExtra("chatId", chatId);
        intent.putExtra("callerName", callerName);
        intent.putExtra("callType", callType);
        intent.putExtra("avatarUrl", avatarUrl);
        intent.putExtra("statusText", call.getString("statusText", ""));
        intent.putExtra("isOngoingOrOutgoing", true);
        intent.putExtra("forceShowOverlay", true);
        intent.putExtra("callStartedAt", callStartedAt);

        try {
            androidx.core.content.ContextCompat.startForegroundService(getContext(), intent);
        } catch (Exception e) {
            Log.e(TAG, "showFloatingCallOverlay failed: " + e.getMessage());
        }
        call.resolve();
    }

    @PluginMethod
    public void dismissFloatingCallOverlay(PluginCall call) {
        Intent intent = new Intent(getContext(), FloatingCallOverlayService.class);
        intent.setAction(FloatingCallOverlayService.ACTION_HIDE_OVERLAY_ONLY);
        try {
            getContext().startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "dismissFloatingCallOverlay failed: " + e.getMessage());
        }
        call.resolve();
    }

    @PluginMethod
    public void showIncomingCallNotification(PluginCall call) {
        String callId = call.getString("callId", "");
        String callerName = call.getString("callerName", "Messenger");
        String callType = call.getString("callType", "audio");
        String avatarUrl = call.getString("avatarUrl", "");
        String chatId = call.getString("chatId", "");

        Intent intent = new Intent(getContext(), FloatingCallOverlayService.class);
        intent.setAction(FloatingCallOverlayService.ACTION_SHOW);
        intent.putExtra("callId", callId);
        intent.putExtra("chatId", chatId);
        intent.putExtra("callerName", callerName);
        intent.putExtra("callType", callType);
        intent.putExtra("avatarUrl", avatarUrl);
        intent.putExtra("isOngoingOrOutgoing", false);
        intent.putExtra("forceShowOverlay", true);
        try {
            androidx.core.content.ContextCompat.startForegroundService(getContext(), intent);
        } catch (Exception e) {
            Log.e(TAG, "showIncomingCallNotification failed: " + e.getMessage());
        }
        call.resolve();
    }

    @PluginMethod
    public void getPendingChatId(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("chatId", pendingChatId);
        call.resolve(ret);
        pendingChatId = null;
    }

    @PluginMethod
    public void getPendingCall(PluginCall call) {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        if (pendingCallId == null) {
            pendingCallId = prefs.getString("pendingCallId", null);
            pendingCallAction = prefs.getString("pendingCallAction", null);
            pendingCallChatId = prefs.getString("pendingCallChatId", null);
            pendingCallerName = prefs.getString("pendingCallerName", null);
            pendingCallType = prefs.getString("pendingCallType", null);
            pendingAvatarUrl = prefs.getString("pendingAvatarUrl", null);
        }

        JSObject ret = new JSObject();
        ret.put("callId", pendingCallId);
        ret.put("action", pendingCallAction);
        ret.put("chatId", pendingCallChatId);
        ret.put("callerName", pendingCallerName);
        ret.put("callType", pendingCallType);
        ret.put("avatarUrl", pendingAvatarUrl);
        call.resolve(ret);
        
        pendingCallId = null;
        pendingCallAction = null;
        pendingCallChatId = null;
        pendingCallerName = null;
        pendingCallType = null;
        pendingAvatarUrl = null;
        prefs.edit()
            .remove("pendingCallId")
            .remove("pendingCallAction")
            .remove("pendingCallChatId")
            .remove("pendingCallerName")
            .remove("pendingCallType")
            .remove("pendingAvatarUrl")
            .apply();
    }

    @PluginMethod
    public void getActiveCall(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("active", activeOngoingCall);
        ret.put("callId", activeCallId);
        ret.put("chatId", activeChatId);
        ret.put("callerName", activeCallerName);
        ret.put("callType", activeCallType);
        ret.put("avatarUrl", activeAvatarUrl);
        call.resolve(ret);
    }

    @PluginMethod
    public void getPendingMessageAction(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("action", pendingMessageAction);
        ret.put("chatId", pendingMessageActionChatId);
        call.resolve(ret);
        
        pendingMessageAction = null;
        pendingMessageActionChatId = null;
    }

    public static void sendCallActionEvent(String callId, String action, String chatId, String callerName, String callType, String avatarUrl) {
        pendingCallId = callId;
        pendingCallAction = action;
        pendingCallChatId = chatId;
        pendingCallerName = callerName;
        pendingCallType = callType;
        pendingAvatarUrl = avatarUrl;

        if (instance != null) {
            instance.getContext()
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("pendingCallId", callId)
                .putString("pendingCallAction", action)
                .putString("pendingCallChatId", chatId)
                .putString("pendingCallerName", callerName)
                .putString("pendingCallType", callType)
                .putString("pendingAvatarUrl", avatarUrl)
                .apply();

            Log.d(TAG, "sendCallActionEvent: Dispatching callAction to JS");
            JSObject data = new JSObject();
            data.put("callId", callId);
            data.put("action", action);
            data.put("chatId", chatId);
            data.put("callerName", callerName);
            data.put("callType", callType);
            data.put("avatarUrl", avatarUrl);
            instance.notifyListeners("callAction", data);
        }
    }

    public static void queueCallAction(android.content.Context context, String callId, String action, String chatId, String callerName, String callType, String avatarUrl) {
        pendingCallId = callId;
        pendingCallAction = action;
        pendingCallChatId = chatId;
        pendingCallerName = callerName;
        pendingCallType = callType;
        pendingAvatarUrl = avatarUrl;

        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("pendingCallId", callId)
                .putString("pendingCallAction", action)
                .putString("pendingCallChatId", chatId)
                .putString("pendingCallerName", callerName)
                .putString("pendingCallType", callType)
                .putString("pendingAvatarUrl", avatarUrl)
                .apply();
        }

        sendCallActionEvent(callId, action, chatId, callerName, callType, avatarUrl);
    }

    public static void sendLikeActionEvent(String chatId) {
        if (instance != null) {
            Log.d(TAG, "sendLikeActionEvent: Dispatching messageAction to JS");
            JSObject data = new JSObject();
            data.put("action", "like");
            data.put("chatId", chatId);
            instance.notifyListeners("messageAction", data);
        }
    }

    public static void sendStopRingtoneEvent() {
        if (instance != null) {
            Log.d(TAG, "sendStopRingtoneEvent: Dispatching stopRingtone to JS");
            JSObject data = new JSObject();
            instance.notifyListeners("stopRingtone", data);
        }
    }

    @PluginMethod
    public void minimizeCallToPip(PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    getActivity().moveTaskToBack(true);
                    call.resolve();
                } catch (Exception e) {
                    call.reject("Failed to minimize: " + e.getMessage());
                }
            }
        });
    }

    @PluginMethod
    public void setSystemBars(PluginCall call) {
        String statusColorHex = call.getString("statusBarColor");
        String navColorHex = call.getString("navigationBarColor");
        Boolean isLightStatus = call.getBoolean("isLightStatus");
        Boolean isLightNav = call.getBoolean("isLightNav");

        getActivity().runOnUiThread(() -> {
            try {
                if (statusColorHex != null && !statusColorHex.isEmpty()) {
                    getActivity().getWindow().setStatusBarColor(Color.parseColor(statusColorHex));
                }
                if (navColorHex != null && !navColorHex.isEmpty()) {
                    getActivity().getWindow().setNavigationBarColor(Color.parseColor(navColorHex));
                }
                WindowInsetsControllerCompat insetsController =
                    new WindowInsetsControllerCompat(getActivity().getWindow(), getActivity().getWindow().getDecorView());
                if (isLightStatus != null) {
                    insetsController.setAppearanceLightStatusBars(isLightStatus);
                }
                if (isLightNav != null) {
                    insetsController.setAppearanceLightNavigationBars(isLightNav);
                }
                call.resolve();
            } catch (Exception e) {
                call.reject("Failed to set system bars: " + e.getMessage());
            }
        });
    }

    public static void triggerWebEvent(String eventName) {
        if (instance != null) {
            instance.getActivity().runOnUiThread(() -> {
                try {
                    instance.getBridge().getWebView().evaluateJavascript(
                        "window.dispatchEvent(new Event('" + eventName + "'))",
                        null
                    );
                } catch (Exception e) {
                    Log.e(TAG, "triggerWebEvent failed: " + e.getMessage());
                }
            });
        }
    }

    public static boolean hasActiveOngoingCall() {
        return activeOngoingCall;
    }

    public static void showActiveCallOverlayFromNative(android.content.Context context) {
        if (activeOngoingCall && activeCallId != null) {
            if (activeCallStartedAt <= 0L) {
                activeCallStartedAt = System.currentTimeMillis();
            }
            Intent intent = new Intent(context, FloatingCallOverlayService.class);
            intent.setAction(FloatingCallOverlayService.ACTION_SHOW);
            intent.putExtra("callId", activeCallId);
            intent.putExtra("chatId", activeChatId);
            intent.putExtra("callerName", activeCallerName);
            intent.putExtra("callType", activeCallType);
            intent.putExtra("avatarUrl", activeAvatarUrl);
            intent.putExtra("statusText", "Hivas...");
            intent.putExtra("isOngoingOrOutgoing", true);
            intent.putExtra("forceShowOverlay", true);
            intent.putExtra("callStartedAt", activeCallStartedAt);
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent);
            } catch (Exception e) {
                Log.e(TAG, "showActiveCallOverlayFromNative failed: " + e.getMessage());
            }
        }
    }
}
