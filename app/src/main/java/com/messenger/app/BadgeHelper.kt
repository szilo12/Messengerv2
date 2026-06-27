package com.messenger.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import me.leolin.shortcutbadger.ShortcutBadger

object BadgeHelper {
    private const val PREFS_NAME = "messenger_badge_prefs"
    private const val KEY_BADGE_COUNT = "badge_count"
    private const val TAG = "BadgeHelper"

    @JvmStatic
    fun incrementBadgeCount(context: Context) {
        val count = getBadgeCount(context) + 1
        setBadgeCount(context, count)
    }

    @JvmStatic
    fun setBadgeCount(context: Context, count: Int) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BADGE_COUNT, count).apply()
        Log.d(TAG, "Setting badge count to: $count")
        try {
            ShortcutBadger.applyCount(context, count)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply badge count using ShortcutBadger: ${e.message}")
        }
    }

    @JvmStatic
    fun getBadgeCount(context: Context): Int {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BADGE_COUNT, 0)
    }

    @JvmStatic
    fun clearBadge(context: Context) {
        setBadgeCount(context, 0)
        try {
            ShortcutBadger.removeCount(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove badge count using ShortcutBadger: ${e.message}")
        }
    }
}
