package com.example.voiceverify.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST

data class SendOTPRequest(
    val phoneNumber: String,
    val displayName: String? = null
)

data class SendOTPResponse(
    val requestId: String,
    val message: String
)

data class VerifyOTPRequest(
    val phoneNumber: String,
    val code: String
)

data class VerifyOTPResponse(
    val verified: Boolean,
    val user: User? = null,
    val error: String? = null,
    val status: String? = null,
    val token: String? = null
)

data class User(
    val phoneNumber: String,
    val displayName: String? = null,
    val isInAppAvailable: Boolean = false
)

data class OutgoingCallRequest(
    val to: String,
    val from: String? = null
)

data class OutgoingCallResponse(
    val callId: String? = null,
    val call: Any? = null,
    val message: String? = null,
    val isInApp: Boolean? = null
)

data class ReconnectResponse(
    val token: String? = null,
    val expires_in: Int? = null,
    val session_id: String? = null,
    val device_id: String? = null
)

data class PushTokenRequest(
    val userId: String,
    val deviceId: String,
    val action: String = "register"
)

data class VoiceTokenRequest(
    val userId: String,
    val sessionId: String? = null,
    val deviceId: String? = null
)

interface ApiService {

    /**
     * Send SMS verification code (Vonage Verify V2)
     */
    @POST("api/auth/send-otp")
    fun sendOTP(@Body request: SendOTPRequest): Call<SendOTPResponse>

    /**
     * Verify the OTP code
     */
    @POST("api/auth/verify-otp")
    fun verifyOTP(@Body request: VerifyOTPRequest): Call<VerifyOTPResponse>

    /**
     * List verified users (for debugging/testing)
     */
    @GET("api/auth/users")
    fun listUsers(): Call<List<User>>

    /**
     * Initiate an outgoing voice call
     */
    @POST("api/voice/call")
    fun initiateCall(@Body request: OutgoingCallRequest): Call<OutgoingCallResponse>

    /**
     * Get a fresh JWT token for reconnecting to Vonage LVN
     */
    @POST("api/voice/reconnect")
    fun getReconnectToken(@Body request: VoiceTokenRequest): Call<ReconnectResponse>

    /**
     * Register device push token with Vonage
     */
    @POST("api/voice/push-token")
    fun registerPushToken(@Body request: PushTokenRequest): Call<PushTokenResponse>

    /**
     * Get a voice auth token for re-initializing Vonage client
     */
    @POST("api/voice/auth/token")
    fun getVoiceToken(@Body request: VoiceTokenRequest): Call<VoiceTokenResponse>
}

data class PushTokenResponse(
    val success: Boolean,
    val userId: String? = null,
    val deviceId: String? = null,
    val action: String? = null
)

data class VoiceTokenResponse(
    val token: String? = null,
    val expires_in: Int? = null,
    val session_id: String? = null,
    val device_id: String? = null,
    val error: String? = null
)
