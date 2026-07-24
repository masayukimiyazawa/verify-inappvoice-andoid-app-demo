package com.example.voiceverify.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.voiceverify.BuildConfig
import com.example.voiceverify.VoiceVerifyApplication
import com.example.voiceverify.api.SendOTPRequest
import com.example.voiceverify.api.VerifyOTPRequest
import com.example.voiceverify.api.VoiceTokenRequest
import com.example.voiceverify.api.RetrofitClient
import com.example.voiceverify.utils.SharedPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Success(val user: com.example.voiceverify.api.User) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AuthViewModel"
    }

    private val apiService = RetrofitClient.apiService

    private val _authState = MutableLiveData<AuthState>()
    val authState: LiveData<AuthState> = _authState

    private var currentPhoneNumber: String? = null
    private var currentRequestId: String? = null

    fun getPhoneNumber(): String? = currentPhoneNumber

    fun setPhoneNumber(phoneNumber: String) {
        currentPhoneNumber = phoneNumber
    }

    fun sendOTP(phoneNumber: String, displayName: String?) {
        currentPhoneNumber = phoneNumber

        viewModelScope.launch {
            _authState.value = AuthState.Loading

            val request = SendOTPRequest(phoneNumber, displayName)

            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.sendOTP(request).execute()
                }

                if (response.isSuccessful && response.body() != null) {
                    currentRequestId = response.body()!!.requestId
                    _authState.value = AuthState.Success(
                        com.example.voiceverify.api.User(
                            phoneNumber = phoneNumber,
                            displayName = displayName
                        )
                    )
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                    _authState.value = AuthState.Error(
                        "Failed to send verification code: $errorMsg"
                    )
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    "Network error: ${e.message}"
                )
            }
        }
    }

    fun verifyOTP(code: String) {
        val phoneNumber = currentPhoneNumber ?: return

        viewModelScope.launch {
            _authState.value = AuthState.Loading

            val request = VerifyOTPRequest(phoneNumber, code)

            try {
                val response = withContext(Dispatchers.IO) {
                    apiService.verifyOTP(request).execute()
                }

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (body.verified && body.user != null) {
                        // Save credentials locally
                        val savedUrl = BuildConfig.BACKEND_URL?.takeIf { it.isNotBlank() }
                            ?: SharedPreferencesManager.getBackendUrl(getApplication())
                            ?: "http://localhost:3000"
                        SharedPreferencesManager.saveUserCredentials(
                            getApplication(),
                            phoneNumber,
                            body.user.displayName,
                            savedUrl
                        )
                        if (body.token != null) {
                            SharedPreferencesManager.saveAuthToken(getApplication(), body.token)
                        }

                        // Get Voice JWT and login to VoiceClient
                        getVoiceTokenAndLogin(phoneNumber)

                        _authState.value = AuthState.Success(body.user)
                    } else {
                        _authState.value = AuthState.Error(
                            body.error ?: "Verification failed"
                        )
                    }
                } else {
                    _authState.value = AuthState.Error(
                        "Verification failed. Please try again."
                    )
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    "Network error: ${e.message}"
                )
            }
        }
    }

    private fun getVoiceTokenAndLogin(phoneNumber: String) {
        viewModelScope.launch {
            try {
                val e164UserId = if (phoneNumber.startsWith("+")) phoneNumber else "+$phoneNumber"
                val request = VoiceTokenRequest(userId = e164UserId)
                Log.d(TAG, "Requesting voice token for userId=$e164UserId")

                val response = withContext(Dispatchers.IO) {
                    apiService.getVoiceToken(request).execute()
                }

                if (response.isSuccessful && response.body()?.token != null) {
                    val voiceToken = response.body()!!.token!!
                    Log.d(TAG, "Got voice token for login, length=${voiceToken.length}")

                    // Save voice token for later use
                    SharedPreferencesManager.saveVoiceToken(getApplication(), voiceToken)

                    // Login to VoiceClient
                    val app = getApplication<VoiceVerifyApplication>()
                    app.loginToVoiceClient(voiceToken) {
                        Log.d(TAG, "VoiceClient session is now ready")
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "unknown"
                    Log.e(TAG, "Failed to get voice token: code=${response.code()}, body=$errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get voice token", e)
            }
        }
    }
}
