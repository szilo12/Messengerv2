package com.messenger.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class ChatHeadService extends Service {
    private static final String TAG = "ChatHeadService";
    private static final int FOREGROUND_ID = 2026;
    private static final String CHANNEL_ID = "messenger_chat";
    private static WeakReference<ChatHeadService> instanceRef = new WeakReference<>(null);
    private static boolean appVisible = false;

    private WindowManager windowManager;
    private View chatHeadView;
    private View dismissView;
    
    private WindowManager.LayoutParams chatHeadParams;
    private WindowManager.LayoutParams dismissParams;

    private LinearLayout chatHeadRoot;
    private FrameLayout avatarWrapper;
    private ImageView avatarImage;
    private TextView unreadBadge;
    private LinearLayout previewBubble;
    private TextView previewSender;
    private TextView previewText;

    private int screenWidth;
    private int screenHeight;
    private int chatHeadWidth;
    private int chatHeadHeight;

    private static int unreadCount = 0;
    private String currentChatId = "";

    private final Handler bubbleHideHandler = new Handler(Looper.getMainLooper());
    private Runnable bubbleHideRunnable;

    private boolean isDragging = false;
    private boolean isMagnetized = false;
    private Vibrator vibrator;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Creating ChatHeadService");
        instanceRef = new WeakReference<>(this);
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        updateScreenMetrics();
        setupLayoutDimensions();
        initNotification();
        createChatHeadView();
        applyOverlayVisibility();
    }

    public static void setAppVisible(boolean visible) {
        appVisible = visible;
        ChatHeadService instance = instanceRef.get();
        if (instance != null) {
            instance.applyOverlayVisibility();
        }
    }

    private void applyOverlayVisibility() {
        if (chatHeadView != null) {
            chatHeadView.setVisibility(appVisible ? View.GONE : View.VISIBLE);
        }
        if (appVisible) {
            hideDismissZone();
            if (previewBubble != null) {
                previewBubble.setVisibility(View.GONE);
            }
        }
    }

    private void updateScreenMetrics() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        screenWidth = displayMetrics.widthPixels;
        screenHeight = displayMetrics.heightPixels;
    }

    private void setupLayoutDimensions() {
        // Chat head bubble is 60dp avatar + 8dp margin on each side = 76dp
        chatHeadWidth = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 76, getResources().getDisplayMetrics()
        );
        chatHeadHeight = chatHeadWidth;
    }

    private void initNotification() {
        // Ensure notification channel exists (MainActivity creates it, but we double check)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Lebegő Üzenetek",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        int count = BadgeHelper.getBadgeCount(this);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lebegő üzenetek aktívak")
            .setContentText("Húzd el a bezáráshoz vagy koppints a megnyitáshoz.")
            .setSmallIcon(R.drawable.ic_call_chat)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE);

        if (count > 0) {
            builder.setNumber(count);
        }

        startForeground(FOREGROUND_ID, builder.build());
    }

    private void createChatHeadView() {
        FrameLayout chatHeadParent = new FrameLayout(this);
        chatHeadView = LayoutInflater.from(this).inflate(R.layout.chat_head_layout, chatHeadParent, false);

        chatHeadRoot = chatHeadView.findViewById(R.id.chat_head_root);
        avatarWrapper = chatHeadView.findViewById(R.id.avatar_wrapper);
        avatarImage = chatHeadView.findViewById(R.id.avatar_image);
        unreadBadge = chatHeadView.findViewById(R.id.unread_badge);
        previewBubble = chatHeadView.findViewById(R.id.preview_bubble);
        previewSender = chatHeadView.findViewById(R.id.preview_sender);
        previewText = chatHeadView.findViewById(R.id.preview_text);

        // Layout parameters for chat head window
        chatHeadParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );

        chatHeadParams.gravity = Gravity.TOP | Gravity.LEFT;
        chatHeadParams.x = 0; // Starts at left
        chatHeadParams.y = screenHeight / 4; // Starts 25% down the screen

        // Setup touch listeners
        setupTouchListener();

        windowManager.addView(chatHeadView, chatHeadParams);
    }

    private void setupTouchListener() {
        avatarWrapper.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long touchStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                updateScreenMetrics(); // Keep screen bounds up to date on rotation/screen changes
                
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartTime = System.currentTimeMillis();
                        initialX = chatHeadParams.x;
                        initialY = chatHeadParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = true;
                        
                        showDismissZone();
                        
                        // Temporarily hide preview bubble while dragging
                        previewBubble.setVisibility(View.GONE);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - initialTouchX;
                        float deltaY = event.getRawY() - initialTouchY;

                        int newX = initialX + (int) deltaX;
                        int newY = initialY + (int) deltaY;

                        // Constraint bounds
                        if (newY < 0) newY = 0;
                        if (newY > screenHeight - chatHeadHeight) newY = screenHeight - chatHeadHeight;

                        // Check magnet close zone overlap
                        checkMagneticClose(newX, newY);

                        if (!isMagnetized) {
                            chatHeadParams.x = newX;
                            chatHeadParams.y = newY;
                        }
                        
                        windowManager.updateViewLayout(chatHeadView, chatHeadParams);
                        return true;

                    case MotionEvent.ACTION_UP:
                        isDragging = false;

                        boolean shouldDismiss = isMagnetized || isReleaseInsideDismissZone(event.getRawX(), event.getRawY());
                        hideDismissZone();

                        if (shouldDismiss) {
                            dismissChatHead();
                            return true;
                        }

                        // Check if it was a quick click rather than a drag
                        long clickDuration = System.currentTimeMillis() - touchStartTime;
                        double dragDistance = Math.sqrt(
                            Math.pow(event.getRawX() - initialTouchX, 2) + 
                            Math.pow(event.getRawY() - initialTouchY, 2)
                        );

                        // If moved less than 10 pixels and pressed briefly, treat as click
                        if (clickDuration < 300 && dragDistance < 15) {
                            openMessengerApp();
                        } else {
                            // Snap to nearest screen edge (left or right)
                            snapToEdge();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void showDismissZone() {
        if (dismissView != null) return;

        FrameLayout dismissParent = new FrameLayout(this);
        dismissView = LayoutInflater.from(this).inflate(R.layout.chat_head_dismiss_layout, dismissParent, false);
        
        dismissParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );

        windowManager.addView(dismissView, dismissParams);
    }

    private void hideDismissZone() {
        if (dismissView != null) {
            try {
                windowManager.removeView(dismissView);
            } catch (Exception ignored) {}
            dismissView = null;
            isMagnetized = false;
        }
    }

    private boolean isReleaseInsideDismissZone(float rawX, float rawY) {
        int bottomHotZone = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 230, getResources().getDisplayMetrics()
        );
        int horizontalHotZone = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 190, getResources().getDisplayMetrics()
        );

        return rawY >= screenHeight - bottomHotZone
            && Math.abs(rawX - (screenWidth / 2f)) <= horizontalHotZone;
    }

    private void dismissChatHead() {
        Log.d(TAG, "dismissChatHead: Removing chat head service.");
        BadgeHelper.clearBadge(this);
        unreadCount = 0;

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(FOREGROUND_ID);
            if (currentChatId != null && !currentChatId.isEmpty()) {
                manager.cancel(currentChatId.hashCode());
            }
        }

        stopSelf();
    }

    private void checkMagneticClose(int chatHeadX, int chatHeadY) {
        if (dismissView == null) return;

        View dismissCircle = dismissView.findViewById(R.id.dismiss_circle);
        TextView dismissText = dismissView.findViewById(R.id.dismiss_text);
        
        // Calculate center of dismiss circle (bottom-center)
        int dismissMarginBottom = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 40, getResources().getDisplayMetrics()
        );
        int dismissCircleRadius = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics()
        );
        
        int dismissCenterX = screenWidth / 2;
        int dismissCenterY = screenHeight - dismissMarginBottom - dismissCircleRadius;

        // Calculate center of chat head
        int chatHeadCenterX = chatHeadX + chatHeadWidth / 2;
        int chatHeadCenterY = chatHeadY + chatHeadHeight / 2;

        // Distance formula
        double distance = Math.sqrt(
            Math.pow(chatHeadCenterX - dismissCenterX, 2) + 
            Math.pow(chatHeadCenterY - dismissCenterY, 2)
        );

        // Larger snapping boundary so the user can easily remove the bubble.
        int snapBoundary = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 150, getResources().getDisplayMetrics()
        );

        if (distance < snapBoundary) {
            if (!isMagnetized) {
                isMagnetized = true;
                triggerHapticFeedback();
                
                // Animate dismiss circle scaling up
                dismissCircle.animate().scaleX(1.25f).scaleY(1.25f).setDuration(150).start();
                dismissText.setText("Engedd el a bezáráshoz");
            }
            
            // Magnetically snap the bubble exactly onto the dismiss circle
            chatHeadParams.x = dismissCenterX - chatHeadWidth / 2;
            chatHeadParams.y = dismissCenterY - chatHeadHeight / 2;
        } else {
            if (isMagnetized) {
                isMagnetized = false;
                
                // Animate dismiss circle back to normal size
                dismissCircle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                dismissText.setText("Húzd ide a bezáráshoz");
            }
        }
    }

    private void triggerHapticFeedback() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(40);
            }
        }
    }

    private void snapToEdge() {
        int currentX = chatHeadParams.x;
        int targetX;
        final boolean isLeftEdge;

        // Determine closest edge
        if (currentX + chatHeadWidth / 2 < screenWidth / 2) {
            targetX = 0;
            isLeftEdge = true;
        } else {
            targetX = screenWidth - chatHeadWidth;
            isLeftEdge = false;
        }

        ValueAnimator animator = ValueAnimator.ofInt(currentX, targetX);
        animator.setDuration(250);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (chatHeadView != null && windowManager != null) {
                    chatHeadParams.x = (int) animation.getAnimatedValue();
                    windowManager.updateViewLayout(chatHeadView, chatHeadParams);
                }
            }
        });
        
        // When animation completes, update bubble text orientation
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                updateBubbleOrientation(isLeftEdge);
            }
        });
        
        animator.start();
    }

    private void updateBubbleOrientation(boolean isLeftEdge) {
        chatHeadRoot.removeAllViews();
        if (isLeftEdge) {
            chatHeadRoot.addView(avatarWrapper);
            chatHeadRoot.addView(previewBubble);
            previewBubble.setBackgroundResource(R.drawable.bg_preview_bubble_left);
        } else {
            chatHeadRoot.addView(previewBubble);
            chatHeadRoot.addView(avatarWrapper);
            previewBubble.setBackgroundResource(R.drawable.bg_preview_bubble_right);
        }
    }

    private void openMessengerApp() {
        Log.d(TAG, "openMessengerApp: Clicking chat head, opening app with chatId: " + currentChatId);

        if (currentChatId != null && !currentChatId.isEmpty()) {
            ChatHeadPlugin.pendingChatId = currentChatId;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("chatId", currentChatId);
        startActivity(intent);

        // Messenger behavior requested: tapping opens the conversation and removes the floating head.
        dismissChatHead();
    }

    private void updateNotification(int count) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lebegő üzenetek aktívak")
            .setContentText("Húzd el a bezáráshoz vagy koppints a megnyitáshoz.")
            .setSmallIcon(R.drawable.ic_call_chat)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE);

        if (count > 0) {
            builder.setNumber(count);
        }

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(FOREGROUND_ID, builder.build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("ACTION_DISMISS_CHAT_HEAD".equals(intent.getAction())) {
                dismissChatHead();
                return START_NOT_STICKY;
            }

            String senderName = intent.getStringExtra("senderName");
            String messageText = intent.getStringExtra("messageText");
            String avatarUrl = intent.getStringExtra("avatarUrl");
            String chatId = intent.getStringExtra("chatId");
            boolean isNewMessage = intent.getBooleanExtra("isNewMessage", false);

            if (chatId != null) {
                currentChatId = chatId;
            }

            if (isNewMessage) {
                BadgeHelper.incrementBadgeCount(this);
            }
            unreadCount = BadgeHelper.getBadgeCount(this);
            applyOverlayVisibility();

            // Update badge unread count
            if (unreadCount > 0) {
                unreadBadge.setText(String.valueOf(unreadCount));
                unreadBadge.setVisibility(View.VISIBLE);
            } else {
                unreadBadge.setVisibility(View.GONE);
            }

            updateNotification(unreadCount);

            // Load the real sender profile picture when possible. Supports:
            // - http/https URLs
            // - backend /avatar/{uid} image URL
            // - data:image base64
            // - preset:* fallback with sender initials
            loadAvatarImage(avatarUrl, avatarImage, senderName);

            // Trigger and animate preview bubble
            if (senderName != null && messageText != null && !isDragging) {
                showPreviewBubble(senderName, messageText);
            }
        }
        
        return START_STICKY;
    }

    private void showPreviewBubble(String sender, String text) {
        previewSender.setText(sender);
        previewText.setText(text);
        
        // Determine current edge for bubble positioning
        boolean isLeftEdge = chatHeadParams.x + chatHeadWidth / 2 < screenWidth / 2;
        updateBubbleOrientation(isLeftEdge);

        previewBubble.setVisibility(View.VISIBLE);
        previewBubble.setAlpha(0.0f);
        previewBubble.animate().alpha(1.0f).setDuration(250).start();

        // Cancel previous pending hide runnables
        if (bubbleHideRunnable != null) {
            bubbleHideHandler.removeCallbacks(bubbleHideRunnable);
        }

        // Auto-hide bubble after 4 seconds
        bubbleHideRunnable = new Runnable() {
            @Override
            public void run() {
                if (previewBubble != null) {
                    previewBubble.animate().alpha(0.0f).setDuration(250).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (previewBubble != null && !isDragging) {
                                previewBubble.setVisibility(View.GONE);
                            }
                        }
                    }).start();
                }
            }
        };
        bubbleHideHandler.postDelayed(bubbleHideRunnable, 4000);
    }

    private void loadAvatarImage(final String urlString, final ImageView imageView, final String senderName) {
        final String value = urlString == null ? "" : urlString.trim();

        if (value.isEmpty() || value.startsWith("preset:")) {
            imageView.setImageBitmap(createInitialAvatarBitmap(senderName, value));
            return;
        }

        if (value.startsWith("data:image")) {
            Bitmap dataBitmap = decodeDataImage(value);
            if (dataBitmap != null) {
                imageView.setImageBitmap(dataBitmap);
            } else {
                imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"));
            }
            return;
        }

        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"));
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                InputStream input = null;
                try {
                    URL url = new URL(value);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setDoInput(true);
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    connection.connect();

                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new RuntimeException("Avatar HTTP error: " + code);
                    }

                    input = connection.getInputStream();
                    final Bitmap bitmap = BitmapFactory.decodeStream(input);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (bitmap != null) {
                                imageView.setImageBitmap(bitmap);
                            } else {
                                imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"));
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error downloading avatar image: " + e.getMessage());
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            imageView.setImageBitmap(createInitialAvatarBitmap(senderName, "preset:blue"));
                        }
                    });
                } finally {
                    try {
                        if (input != null) input.close();
                        if (connection != null) connection.disconnect();
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private Bitmap decodeDataImage(String dataImage) {
        try {
            int commaIndex = dataImage.indexOf(',');
            if (commaIndex < 0) return null;
            String base64 = dataImage.substring(commaIndex + 1);
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed decoding data image avatar: " + e.getMessage());
            return null;
        }
    }

    private Bitmap createInitialAvatarBitmap(String senderName, String preset) {
        int size = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 60, getResources().getDisplayMetrics()
        );
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        int color = presetColor(preset);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(size * 0.42f);

        String initial = getInitial(senderName);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float y = size / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(initial, size / 2f, y, textPaint);

        return bitmap;
    }

    private String getInitial(String senderName) {
        if (senderName == null) return "?";
        String trimmed = senderName.trim();
        if (trimmed.isEmpty()) return "?";
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private int presetColor(String preset) {
        if (preset == null) return Color.rgb(59, 130, 246);
        String p = preset.toLowerCase(Locale.ROOT);
        if (p.contains("green")) return Color.rgb(16, 185, 129);
        if (p.contains("red")) return Color.rgb(239, 68, 68);
        if (p.contains("pink")) return Color.rgb(236, 72, 153);
        if (p.contains("purple")) return Color.rgb(139, 92, 246);
        if (p.contains("orange")) return Color.rgb(249, 115, 22);
        if (p.contains("yellow")) return Color.rgb(234, 179, 8);
        if (p.contains("slate") || p.contains("gray") || p.contains("grey")) return Color.rgb(71, 85, 105);
        return Color.rgb(59, 130, 246);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Destroying ChatHeadService");
        ChatHeadService instance = instanceRef.get();
        if (instance == this) {
            instanceRef.clear();
        }
        
        if (bubbleHideRunnable != null) {
            bubbleHideHandler.removeCallbacks(bubbleHideRunnable);
        }

        hideDismissZone();

        if (chatHeadView != null) {
            try {
                windowManager.removeView(chatHeadView);
            } catch (Exception ignored) {}
            chatHeadView = null;
        }
    }
}
