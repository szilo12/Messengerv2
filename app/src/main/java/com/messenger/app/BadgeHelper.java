package com.messenger.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import me.leolin.shortcutbadger.ShortcutBadger;

public class BadgeHelper {
    private static final String PREFS_NAME = "messenger_badge_prefs";
    private static final String KEY_BADGE_COUNT = "badge_count";
    private static final String TAG = "BadgeHelper";

    public static void incrementBadgeCount(Context context) {
        int count = getBadgeCount(context) + 1;
        setBadgeCount(context, count);
    }

    public static void setBadgeCount(Context context, int count) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_BADGE_COUNT, count).apply();
        Log.d(TAG, "Setting badge count to: " + count);
        try {
            ShortcutBadger.applyCount(context, count);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply badge count using ShortcutBadger: " + e.getMessage());
        }
    }

    public static int getBadgeCount(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_BADGE_COUNT, 0);
    }

    public static void clearBadge(Context context) {
        setBadgeCount(context, 0);
        try {
            ShortcutBadger.removeCount(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove badge count using ShortcutBadger: " + e.getMessage());
        }
    }
}
