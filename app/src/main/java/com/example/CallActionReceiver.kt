package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ANSWER = "com.example.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.example.ACTION_DECLINE"
        private const val TAG = "CallActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "Received call action: $action")

        when (action) {
            ACTION_ANSWER -> {
                // Answer the call state
                CallManager.answerCall(context)

                // Launch IncomingCallActivity (which will transition directly to the ONGOING call view)
                val launchIntent = Intent(context, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("start_ongoing", true)
                }
                context.startActivity(launchIntent)
            }
            ACTION_DECLINE -> {
                // Decline the call and stop alerts
                CallManager.declineCall(context)
            }
        }
    }
}
