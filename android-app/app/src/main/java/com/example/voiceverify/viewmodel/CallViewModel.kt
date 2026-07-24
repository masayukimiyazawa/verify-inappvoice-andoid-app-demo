package com.example.voiceverify.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.voiceverify.VoiceVerifyApplication

class CallViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CallViewModel"
    }

    sealed class CallState {
        object Idle : CallState()
        object Connecting : CallState()
        object Connected : CallState()
        object Hungup : CallState()
        data class Error(val message: String) : CallState()
    }

    private val _callState = MutableLiveData<CallState>()
    val callState: LiveData<CallState> = _callState

    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean> = _isConnected

    private val _isMuted = MutableLiveData(false)
    val isMuted: LiveData<Boolean> = _isMuted

    private val _isSpeakerOn = MutableLiveData(false)
    val isSpeakerOn: LiveData<Boolean> = _isSpeakerOn

    private var backendCallId: String? = null

    init {
        _callState.value = CallState.Idle
        _isConnected.value = false
    }

    fun backendCallInitiated() {
        _callState.value = CallState.Connecting
        _isConnected.value = false
        Log.d(TAG, "Waiting for backend call to connect...")
    }

    fun setCallConnected() {
        _callState.value = CallState.Connected
        _isConnected.value = true
        Log.d(TAG, "Call set as connected")
    }

    fun acceptIncomingCall(
        onConnected: () -> Unit,
        onFinish: () -> Unit,
        onError: (String) -> Unit
    ) {
        val app = getApplication<VoiceVerifyApplication>()
        val callId = app.getCurrentCallId()

        if (callId == null) {
            onError("No active incoming call")
            return
        }

        app.acceptIncomingCall(
            callId = callId,
            onConnected = {
                _callState.value = CallState.Connected
                _isConnected.value = true
                onConnected()
            },
            onDisconnected = {
                _callState.value = CallState.Hungup
                _isConnected.value = false
                backendCallId = null
                onFinish()
            },
            onError = { error ->
                onError("Call error: $error")
            }
        )
    }

    fun endCall() {
        val app = getApplication<VoiceVerifyApplication>()
        app.endCurrentCall {
            _callState.value = CallState.Hungup
            _isConnected.value = false
            backendCallId?.let { callId ->
                Log.d(TAG, "Backend call ended: $callId")
            }
            backendCallId = null
        }
    }

    fun toggleMute() {
        val app = getApplication<VoiceVerifyApplication>()
        val isCurrentlyMuted = app.isCurrentlyMuted()
        val newMutedState = !isCurrentlyMuted
        
        _isMuted.value = newMutedState

        if (newMutedState) {
            app.muteCurrentCall()
        } else {
            app.unmuteCurrentCall()
        }
        Log.d(TAG, "Mute ${if (newMutedState) "on" else "off"}")
    }

    fun toggleSpeaker() {
        val app = getApplication<VoiceVerifyApplication>()
        _isSpeakerOn.value = !(_isSpeakerOn.value ?: false)
        app.enableSpeakerphone(_isSpeakerOn.value!!)
    }

    fun isCurrentlyMuted(): Boolean = _isMuted.value ?: false
    fun isCurrentlySpeakerOn(): Boolean = _isSpeakerOn.value ?: false

    fun setBackendCallId(callId: String) {
        backendCallId = callId
    }
}

