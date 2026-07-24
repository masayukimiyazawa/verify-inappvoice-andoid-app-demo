package com.example.voiceverify

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.os.Build
import androidx.annotation.MainThread
import com.example.voiceverify.api.ApiService
import com.example.voiceverify.api.PushTokenRequest
import com.example.voiceverify.api.ReconnectResponse
import com.example.voiceverify.api.VoiceTokenRequest
import com.example.voiceverify.service.VonageVoiceService
import com.example.voiceverify.utils.SharedPreferencesManager
import com.example.voiceverify.api.RetrofitClient
import com.vonage.clientcore.core.conversation.VoiceChannelType
import com.vonage.voice.api.VoiceClient
import com.vonage.android_core.VGClientInitConfig
import com.vonage.android_core.VGClientConfig
import com.vonage.clientcore.core.api.ClientConfigRegion
import com.vonage.clientcore.core.api.LoggingLevel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Call as RetrofitCall

const val TAG = "VoiceVerifyApp"

class VoiceVerifyApplication : Application() {

    companion object {
        @Volatile
        private var instance: VoiceVerifyApplication? = null

        fun getInstance(): VoiceVerifyApplication = instance
            ?: throw IllegalStateException("VoiceVerifyApplication not initialized")

        fun getApplicationContext(): Context = (instance
            ?: throw IllegalStateException("VoiceVerifyApplication not initialized")).applicationContext

        val callListeners = mutableListOf<(String, VoiceChannelType) -> Unit>()
    }

    var voiceClient: VoiceClient? = null
    private var currentCallId: String? = null
    private var incomingCallListener: ((String, String, VoiceChannelType) -> Unit)? = null
    private var currentSessionId: String? = null
    private var _isMuted: Boolean = false
    private lateinit var audioManager: AudioManager

    // Session state tracking
    @Volatile
    var isSessionReady: Boolean = false
        private set

    private var sessionReadyCallback: (() -> Unit)? = null
    private var onCallInviteListener: (() -> Unit)? = null
    private var onCallHangupListener: (() -> Unit)? = null
    private var onLegStatusListener: (() -> Unit)? = null
    private var onDTMFListener: (() -> Unit)? = null
    private var onMuteListener: (() -> Unit)? = null
    private var onEarmuffListener: (() -> Unit)? = null
    private var onCallTransferListener: (() -> Unit)? = null
    private var onCallMediaReconnectListener: (() -> Unit)? = null
    private var onCallMediaReconnectionListener: (() -> Unit)? = null
    private var onCallMediaDisconnectListener: (() -> Unit)? = null
    private var onRtcStatsListener: (() -> Unit)? = null
    private var onCallMediaErrorListener: (() -> Unit)? = null
    private var onCallInviteCancelListener: (() -> Unit)? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        initVoiceClient()
    }

    private fun initVoiceClient() {
        try {
            val applicationId = BuildConfig.VONAGE_APPLICATION_ID

            if (applicationId == "your-app-id") {
                Log.w(TAG, "Vonage credentials not configured. InApp Voice will not work.")
                return
            }

            val config = VGClientInitConfig(
                LoggingLevel.Debug,
                emptyList(),
                false,
                ClientConfigRegion.AP
            )

            voiceClient = VoiceClientHelper.createClient(this, config)
            voiceClient?.setConfig(VGClientConfig(ClientConfigRegion.AP))

            setupVoiceListeners()

            Log.i(TAG, "Vonage Voice client initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vonage client", e)
        }
    }

    fun initVoiceClientWithToken(token: String) {
        try {
            val config = VGClientInitConfig(
                LoggingLevel.Debug, emptyList(), false, ClientConfigRegion.AP
            )

            voiceClient = VoiceClientHelper.createClient(this, config)
            voiceClient?.setConfig(VGClientConfig(ClientConfigRegion.AP))

            clearAllListeners()
            setupVoiceListeners()

            Log.i(TAG, "Voice client initialized with token")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Voice client with token", e)
        }
    }

    fun loginToVoiceClient(voiceToken: String, onReady: (() -> Unit)? = null) {
        try {
            if (voiceClient == null) {
                initVoiceClient()
            }

            isSessionReady = false
            sessionReadyCallback = onReady

            Log.d(TAG, "Creating session with voice token...")

            voiceClient?.createSession(voiceToken) { error, sessionId ->
                if (error != null) {
                    Log.e(TAG, "Failed to create session: ${error.message}")
                    isSessionReady = false
                    sessionReadyCallback?.let { callback ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            callback()
                        }
                    }
                } else {
                    currentSessionId = sessionId
                    isSessionReady = true
                    Log.i(TAG, "VoiceClient session created: sessionId=$sessionId")

                    sessionReadyCallback?.let { callback ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            callback()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to login to VoiceClient", e)
            isSessionReady = false
        }
    }

    fun makeOutboundCall(phoneNumber: String) {
        try {
            val context = mapOf(
                "to" to phoneNumber,
                "type" to "outbound"
            )

            voiceClient?.serverCall(context) { error, callId ->
                if (error != null) {
                    Log.e(TAG, "Failed to make outbound call: ${error.message}")
                } else {
                    currentCallId = callId
                    Log.i(TAG, "Outbound call initiated: callId=$callId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate outbound call", e)
        }
    }

    private fun setupVoiceListeners() {
        onCallInviteListener = voiceClient?.setCallInviteListener { callId, from, channelType ->
            Log.d(TAG, "Incoming call invite: callId=$callId, from=$from, channelType=$channelType")
            currentCallId = callId
            if (channelType == VoiceChannelType.app) {
                incomingCallListener?.invoke(callId, from, channelType)
                callListeners.forEach { listener ->
                    listener(callId, channelType)
                }
                val intent = Intent(this, VonageVoiceService::class.java).apply {
                    action = VonageVoiceService.ACTION_INCOMING_CALL
                    putExtra(VonageVoiceService.EXTRA_CALLER_NUMBER, from)
                    putExtra(VonageVoiceService.EXTRA_CALLER_NAME, from)
                    putExtra(VonageVoiceService.EXTRA_IS_INAPP, true)
                    putExtra("call_id", callId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }

        onCallHangupListener = voiceClient?.setOnCallHangupListener { callId, _, _ ->
            Log.d(TAG, "Call hangup: callId=$callId")
            if (currentCallId == callId) {
                currentCallId = null
                _isMuted = false
            }
        }

        onLegStatusListener = voiceClient?.setOnLegStatusUpdate { callId, legId, status ->
            Log.d(TAG, "Leg status update: callId=$callId, legId=$legId, status=$status")
        }

        onDTMFListener = voiceClient?.setOnDTMFListener { callId, legId, digits ->
            Log.d(TAG, "DTMF received: callId=$callId, legId=$legId, digits=$digits")
        }

        onMuteListener = voiceClient?.setOnMutedListener { callId, _, isMuted ->
            Log.d(TAG, "Mute update: callId=$callId, isMuted=$isMuted")
            _isMuted = isMuted
        }

        onEarmuffListener = voiceClient?.setOnEarmuffListener { callId, legId, earmuffStatus ->
            Log.d(TAG, "Earmuff update: callId=$callId, legId=$legId, status=$earmuffStatus")
        }

        onCallTransferListener = voiceClient?.setCallTransferListener { callId, conversationId ->
            Log.d(TAG, "Call transfer: callId=$callId, conversationId=$conversationId")
        }

        onCallMediaReconnectListener = voiceClient?.setOnCallMediaReconnectingListener { callId ->
            Log.d(TAG, "Media reconnecting: callId=$callId")
        }

        onCallMediaReconnectionListener = voiceClient?.setOnCallMediaReconnectionListener { callId ->
            Log.d(TAG, "Media reconnected: callId=$callId")
        }

        onCallMediaDisconnectListener = voiceClient?.setOnCallMediaDisconnectListener { callId, reason ->
            Log.e(TAG, "Media disconnected: callId=$callId, reason=$reason")
        }

        onRtcStatsListener = voiceClient?.setOnRtcStatsUpdateListener { rtcStats, _ ->
            Log.d(TAG, "RTC stats: $rtcStats")
        }

        onCallMediaErrorListener = voiceClient?.setOnCallMediaErrorListener { callId, error ->
            Log.e(TAG, "Call media error: callId=$callId, error=$error")
        }

        onCallInviteCancelListener = voiceClient?.setCallInviteCancelListener { callId, reason ->
            Log.w(TAG, "Call invite cancelled: callId=$callId, reason=$reason")
        }
    }

    fun clearAllListeners() {
        onCallInviteListener?.invoke()
        onCallHangupListener?.invoke()
        onLegStatusListener?.invoke()
        onDTMFListener?.invoke()
        onMuteListener?.invoke()
        onEarmuffListener?.invoke()
        onCallTransferListener?.invoke()
        onCallMediaReconnectListener?.invoke()
        onCallMediaReconnectionListener?.invoke()
        onCallMediaDisconnectListener?.invoke()
        onRtcStatsListener?.invoke()
        onCallMediaErrorListener?.invoke()
        onCallInviteCancelListener?.invoke()
    }

    fun setIncomingCallListener(listener: (String, String, VoiceChannelType) -> Unit) {
        incomingCallListener = listener
    }

    fun addCallListener(listener: (String, VoiceChannelType) -> Unit) {
        callListeners.add(listener)
    }

    fun removeCallListener(listener: (String, VoiceChannelType) -> Unit) {
        callListeners.remove(listener)
    }

    fun muteCurrentCall() {
        _isMuted = true
        val callId = currentCallId
        if (callId != null) {
            voiceClient?.mute(callId) { error ->
                if (error != null) {
                    Log.e(TAG, "Failed to mute: ${error.message}")
                }
            }
        }
        Log.d(TAG, "Muted microphone")
    }

    fun unmuteCurrentCall() {
        _isMuted = false
        val callId = currentCallId
        if (callId != null) {
            voiceClient?.unmute(callId) { error ->
                if (error != null) {
                    Log.e(TAG, "Failed to unmute: ${error.message}")
                }
            }
        }
        Log.d(TAG, "Unmuted microphone")
    }

    fun isCurrentlyMuted(): Boolean = _isMuted

    fun enableSpeakerphone(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.setSpeakerphoneOn(on)
        }
        Log.d(TAG, "Speakerphone ${if (on) "on" else "off"}")
    }

    fun acceptIncomingCall(
        callId: String,
        onConnected: () -> Unit,
        onDisconnected: (String?) -> Unit,
        onError: (String) -> Unit
    ) {
        if (voiceClient == null) {
            Log.e(TAG, "Cannot accept call: voiceClient is null")
            onError("Voice client not initialized")
            return
        }
        voiceClient?.answer(callId) { error ->
            if (error != null) {
                Log.e(TAG, "Failed to accept call: ${error.message}")
                onError(error.message ?: "Accept failed: ${error.message}")
                return@answer
            }
            onConnected()
        }
    }

    fun rejectIncomingCall(callId: String) {
        voiceClient?.reject(callId) { error ->
            if (error != null) {
                Log.e(TAG, "Failed to reject call: ${error.message}")
            }
        }
    }

    fun endCurrentCall(onComplete: () -> Unit) {
        val callId = currentCallId
        if (callId != null) {
            voiceClient?.hangup(callId) { error ->
                if (error != null) {
                    Log.e(TAG, "Failed to hangup: ${error.message}")
                }
            }
            currentCallId = null
            _isMuted = false
        }
        onComplete()
    }

    fun registerDevicePushToken(fcmToken: String) {
        try {
            voiceClient?.registerDevicePushToken(fcmToken) { error, deviceId ->
                if (error != null) {
                    Log.e(TAG, "Failed to register device push token: ${error.message}")
                    return@registerDevicePushToken
                }

                Log.i(TAG, "Device push token registered successfully: $deviceId")

                val phoneNumber = SharedPreferencesManager.getPhoneNumber(this@VoiceVerifyApplication)
                val finalDeviceId = deviceId ?: "unknown_device"
                phoneNumber?.let { phone ->
                    registerPushTokenWithBackend(phone, finalDeviceId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering device push token", e)
        }
    }

    private fun registerPushTokenWithBackend(userId: String, deviceId: String) {
        try {
            val apiService = RetrofitClient.apiService
            val request = PushTokenRequest(userId, deviceId, "register")
            apiService.registerPushToken(request).enqueue(object : Callback<com.example.voiceverify.api.PushTokenResponse> {
                override fun onResponse(call: RetrofitCall<com.example.voiceverify.api.PushTokenResponse>, response: Response<com.example.voiceverify.api.PushTokenResponse>) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Push token registered with backend: userId=$userId, deviceId=$deviceId")
                    } else {
                        Log.e(TAG, "Failed to register push token with backend: ${response.code()}")
                    }
                }

                override fun onFailure(call: RetrofitCall<com.example.voiceverify.api.PushTokenResponse>, t: Throwable) {
                    Log.e(TAG, "Push token registration failed", t)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error registering push token with backend", e)
        }
    }

    suspend fun processPushCallInvite(pushData: String): String? {
        return try {
            voiceClient?.processPushCallInvite(pushData)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing push call invite", e)
            null
        }
    }

    fun processPushCallInviteSync(pushData: String): String? {
        return try {
            voiceClient?.processPushCallInvite(pushData)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing push call invite (sync)", e)
            null
        }
    }

    @MainThread
    fun reconnect(
        userId: String,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            val apiService = RetrofitClient.apiService
            val request = VoiceTokenRequest(userId)

            apiService.getReconnectToken(request).enqueue(object : Callback<ReconnectResponse> {
                override fun onResponse(call: RetrofitCall<ReconnectResponse>, response: Response<ReconnectResponse>) {
                    if (response.isSuccessful && response.body()?.token != null) {
                        val newToken = response.body()!!.token!!
                        Log.d(TAG, "Got reconnect token for userId=$userId")

                        try {
                            initVoiceClientWithToken(newToken)
                            Log.d(TAG, "Reconnected successfully")
                            onSuccess()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during reconnect", e)
                            onError(e)
                        }
                    } else {
                        Log.e(TAG, "Failed to get reconnect token: ${response.code()}")
                        onError(Exception("Failed to get reconnect token"))
                    }
                }

                override fun onFailure(call: RetrofitCall<ReconnectResponse>, t: Throwable) {
                    Log.e(TAG, "Reconnect token request failed", t)
                    onError(Exception("Network error: ${t.message}"))
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating reconnect", e)
            onError(e)
        }
    }

    fun getOrCreateUserId(): String {
        val existing = SharedPreferencesManager.getUserId(this@VoiceVerifyApplication)
        if (existing != null) return existing

        val phone = SharedPreferencesManager.getPhoneNumber(this@VoiceVerifyApplication) ?: "unknown"
        val newId = "user_${System.currentTimeMillis()}_$phone"
        SharedPreferencesManager.saveUserId(this@VoiceVerifyApplication, newId)
        return newId
    }

    fun getCurrentCallId(): String? = currentCallId
    fun setCurrentCallId(callId: String?) { currentCallId = callId }

    override fun onTerminate() {
        super.onTerminate()
        val callId = currentCallId
        if (callId != null) {
            voiceClient?.hangup(callId) { error ->
                if (error != null) {
                    Log.e(TAG, "Failed to hangup during terminate: ${error.message}")
                }
            }
        }
        clearAllListeners()
    }
}
