package com.example.voiceverify.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voiceverify.R
import com.example.voiceverify.VoiceVerifyApplication
import com.example.voiceverify.ui.call.IncomingCallActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FirebaseMessaging"
        private const val NOTIFICATION_ID_INCOMING = 1002
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received")
        Log.d(TAG, "FCM token prefix: ${token.take(6)}****")

        val app = VoiceVerifyApplication.getInstance()
        app.registerDevicePushToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received: ${remoteMessage.data}")

        val isVonagePush = remoteMessage.data["type"] == "vonage" ||
            remoteMessage.data.containsKey("action") &&
            (remoteMessage.data["action"] == "invite" ||
            remoteMessage.data["action"] == "call")

        if (!isVonagePush) {
            return
        }

        handleVonagePush(remoteMessage)
    }

    private fun handleVonagePush(remoteMessage: RemoteMessage) {
        val app = VoiceVerifyApplication.getInstance()
        val data = remoteMessage.data

        val action = data["action"] ?: "unknown"
        val from = data["from"] ?: "Unknown"

        Log.d(TAG, "Processing Vonage push: action=$action, from=$from")

        when (action) {
            "invite" -> {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val jsonData = org.json.JSONObject(remoteMessage.data).toString()
                        val callId = app.processPushCallInviteSync(jsonData)
                        if (callId != null) {
                            app.setCurrentCallId(callId)
                            Log.d(TAG, "Process push call invite successful: $callId")
                            showIncomingCallNotification(from, data["call_id"] ?: "")
                        } else {
                            Log.e(TAG, "Process push call invite returned null")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Process push call invite failed", e)
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unknown Vonage push action: $action")
            }
        }
    }

    private fun showIncomingCallNotification(from: String, callId: String) {
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_CALLER_NUMBER, from)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, from)
            putExtra("is_inapp", true)
            putExtra("call_id", callId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(this, "voice_verify_channel")
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Incoming call")
            .setContentText("Incoming call from $from")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_INCOMING, notification)
    }
}
