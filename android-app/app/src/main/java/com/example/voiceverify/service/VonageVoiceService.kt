package com.example.voiceverify.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voiceverify.R
import com.example.voiceverify.VoiceVerifyApplication
import com.example.voiceverify.api.OutgoingCallRequest
import com.example.voiceverify.api.RetrofitClient
import com.example.voiceverify.ui.call.IncomingCallActivity
import com.example.voiceverify.utils.SharedPreferencesManager
import com.example.voiceverify.utils.RingtonePlayer

class VonageVoiceService : Service() {

    companion object {
        private const val TAG = "VonageVoiceService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_verify_channel"

        const val ACTION_INCOMING_CALL = "com.example.voiceverify.ACTION_INCOMING_CALL"
        const val ACTION_MUTE = "com.example.voiceverify.ACTION_MUTE"
        const val ACTION_SPEAKER = "com.example.voiceverify.ACTION_SPEAKER"
        const val ACTION_END_CALL = "com.example.voiceverify.ACTION_END_CALL"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_IS_INAPP = "is_inapp"
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private lateinit var ringtonePlayer: RingtonePlayer

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        ringtonePlayer = RingtonePlayer(this)
        setupAudioFocus()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started: ${intent?.action}")

        when (intent?.action) {
            ACTION_INCOMING_CALL -> {
                handleIncomingCall(intent)
            }
            ACTION_END_CALL -> {
                endCurrentCall()
            }
        }

        val notification = if (intent?.action == ACTION_INCOMING_CALL) {
            buildIncomingCallNotification(intent)
        } else {
            buildActiveCallNotification()
        }
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun setupAudioFocus() {
        audioFocusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnAudioFocusChangeListener { }
            }.build()
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                {},
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
            null
        }
        audioFocusRequest?.let {
            audioManager?.requestAudioFocus(it)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Verify Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for incoming and active voice calls"
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildIncomingCallNotification(intent: Intent): Notification {
        val callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown Caller"
        val isInApp = intent.getBooleanExtra(EXTRA_IS_INAPP, false)

        val callActivityIntent = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra("is_inapp", isInApp)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, callActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val declineIntent = Intent(this, VonageVoiceService::class.java).apply {
            action = ACTION_END_CALL
        }
        val declinePendingIntent = PendingIntent.getService(
            this, 1, declineIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceVerify - Incoming")
            .setContentText("Incoming call from $callerName")
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_call_end,
                "Decline",
                declinePendingIntent
            )
            .build()
    }

    private fun buildActiveCallNotification(): Notification {
        val endCallIntent = Intent(this, VonageVoiceService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endCallPendingIntent = PendingIntent.getService(
            this, 1, endCallIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceVerify - In Call")
            .setContentText("In call...")
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                R.drawable.ic_call_end,
                "End call",
                endCallPendingIntent
            )
            .build()
    }

    private fun handleIncomingCall(intent: Intent) {
        var callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
        var callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: getString(R.string.unknown_caller)
        var isInApp = intent.getBooleanExtra(EXTRA_IS_INAPP, false)
        val callId = intent.getStringExtra("call_id") ?: ""

        Log.d(TAG, "Incoming call from: $callerNumber (InApp: $isInApp) callId=$callId")

        if (callerNumber.isEmpty()) {
            callerNumber = SharedPreferencesManager.getPhoneNumber(this) ?: ""
            callerName = "Saved Number"
            isInApp = true
        }

        ringtonePlayer.start()

        val app = VoiceVerifyApplication.getInstance()
        if (callId.isNotEmpty()) {
            app.setCurrentCallId(callId)
        }

        startActivity(Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra("is_inapp", isInApp)
            putExtra("call_id", callId)
        })

        if (!isInApp) {
            handlePSTNFallback(callerNumber)
        }
    }

    private fun handlePSTNFallback(_callerNumber: String) {
        Log.d(TAG, "PSTN Voice Proxy fallback - calling registered number")

        val phoneNumber = SharedPreferencesManager.getPhoneNumber(this) ?: ""

        if (phoneNumber.isEmpty()) {
            Log.e(TAG, "No registered phone number for PSTN fallback")
            return
        }

        val request = OutgoingCallRequest(to = phoneNumber)

        RetrofitClient.apiService.initiateCall(request).enqueue(object :
            retrofit2.Callback<com.example.voiceverify.api.OutgoingCallResponse> {
            override fun onResponse(
                call: retrofit2.Call<com.example.voiceverify.api.OutgoingCallResponse>,
                response: retrofit2.Response<com.example.voiceverify.api.OutgoingCallResponse>
            ) {
                if (response.isSuccessful) {
                    Log.d(TAG, "PSTN fallback call initiated: ${response.body()?.callId}")
                } else {
                    Log.e(TAG, "PSTN fallback call failed: ${response.code()}")
                }
            }

            override fun onFailure(
                call: retrofit2.Call<com.example.voiceverify.api.OutgoingCallResponse>,
                t: Throwable
            ) {
                Log.e(TAG, "PSTN fallback call error: ${t.message}")
            }
        })
    }

    private fun endCurrentCall() {
        val app = VoiceVerifyApplication.getInstance()
        app.getCurrentCallId()?.let { callId ->
            app.rejectIncomingCall(callId)
        }
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        ringtonePlayer.release()

        audioFocusRequest?.let {
            audioManager?.abandonAudioFocusRequest(it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
