package com.example.voiceverify.ui.call

import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceverify.R
import com.example.voiceverify.VoiceVerifyApplication
import com.example.voiceverify.databinding.ActivityCallBinding
import com.example.voiceverify.viewmodel.CallViewModel

class CallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CallActivity"
    }

    private lateinit var binding: ActivityCallBinding
    private val viewModel: CallViewModel by viewModels()

    private var callDurationSeconds = 0
    private var durationHandler = Handler(Looper.getMainLooper())
    private var durationRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)

        val phoneNumber = intent.getStringExtra("phone_number") ?: getString(R.string.unknown_caller)
        val isIncoming = intent.getBooleanExtra("incoming_call", false)
        val callId = intent.getStringExtra("call_id") ?: ""

        setupPhoneNumberInUI(phoneNumber)

        if (isIncoming) {
            setContentView(binding.root)
            setupAudio()
            viewModel.setCallConnected()
            setupPermissionsForIncomingCall()
            observeCallStateForIncoming()
            setupClickListeners()
        } else {
            setContentView(binding.root)
            setupAudio()
            observeCallStateForOutgoing(phoneNumber)
            setupClickListeners()
            viewModel.backendCallInitiated()

            if (callId.isNotEmpty()) {
                setupCallEventListeners(callId)
            }
        }
    }

    private fun setupCallEventListeners(callId: String) {
        val app = VoiceVerifyApplication.getInstance()

        app.voiceClient?.setOnCallHangupListener { hungupCallId, _, _ ->
            if (hungupCallId == callId) {
                Log.d(TAG, "Call hung up: $hungupCallId")
                runOnUiThread {
                    viewModel.endCall()
                }
            }
        }

        app.voiceClient?.setOnMutedListener { mutedCallId, _, isMuted ->
            if (mutedCallId == callId) {
                Log.d(TAG, "Call muted: $isMuted")
            }
        }

        app.voiceClient?.setOnLegStatusUpdate { legCallId, legId, status ->
            if (legCallId == callId) {
                Log.d(TAG, "Leg status: $legId -> $status")
            }
        }
    }

    private fun setupPhoneNumberInUI(phoneNumber: String) {
        binding.callInfoLayout.visibility = View.VISIBLE
        binding.tvCallStatus.text = phoneNumber
    }

    private fun setupAudio() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager?.isSpeakerphoneOn = false
    }

    private fun setupPermissionsForIncomingCall() {
        // Call already answered by IncomingCallActivity — just observe state
        viewModel.callState.observe(this) { state ->
            when (state) {
                is CallViewModel.CallState.Connected -> {
                    binding.tvCallStatus.text = getString(R.string.call_status_connected)
                    binding.callInfoLayout.visibility = View.VISIBLE
                    startCallTimer()
                }
                is CallViewModel.CallState.Hungup -> {
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                }
                is CallViewModel.CallState.Error -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
                }
                else -> {}
            }
        }
    }

    private fun observeCallStateForIncoming() {
        viewModel.callState.observe(this) { state ->
            when (state) {
                is CallViewModel.CallState.Connecting -> {
                    binding.tvCallStatus.text = getString(R.string.call_status_connecting)
                    binding.callInfoLayout.visibility = View.VISIBLE
                }
                is CallViewModel.CallState.Connected -> {
                    binding.tvCallStatus.text = getString(R.string.call_status_connected)
                    binding.callInfoLayout.visibility = View.VISIBLE
                    startCallTimer()
                }
                is CallViewModel.CallState.Hungup -> {
                    binding.tvCallStatus.text = getString(R.string.call_status_hangup)
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1500)
                }
                is CallViewModel.CallState.Error -> {
                    binding.tvCallStatus.text = state.message
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 2000)
                }
                else -> {}
            }
        }
    }

    private fun observeCallStateForOutgoing(phoneNumber: String) {
        binding.tvCallStatus.text = getString(R.string.call_status_connecting)
        binding.callInfoLayout.visibility = View.VISIBLE

        viewModel.callState.observe(this) { state ->
            when (state) {
                is CallViewModel.CallState.Connecting -> {
                    binding.tvCallStatus.text = getString(R.string.call_status_connecting)
                }
                is CallViewModel.CallState.Connected -> {
                    binding.tvCallStatus.text = getString(R.string.call_status_connected)
                    binding.callInfoLayout.visibility = View.VISIBLE
                    startCallTimer()
                }
                is CallViewModel.CallState.Hungup -> {
                    binding.tvCallStatus.text = getString(R.string.call_status_hangup)
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1500)
                }
                is CallViewModel.CallState.Error -> {
                    binding.tvCallStatus.text = state.message
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 2000)
                }
                else -> {}
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnHangUp.setOnClickListener {
            viewModel.endCall()
        }

        binding.btnMute.setOnClickListener {
            viewModel.toggleMute()
            val isMuted = viewModel.isCurrentlyMuted()
            if (isMuted) {
                binding.btnMute.setImageResource(R.drawable.ic_mic_off)
                Toast.makeText(this, getString(R.string.muted), Toast.LENGTH_SHORT).show()
            } else {
                binding.btnMute.setImageResource(android.R.drawable.ic_menu_send)
                Toast.makeText(this, getString(R.string.unmuted), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSpeaker.setOnClickListener {
            viewModel.toggleSpeaker()
            val isSpeakerOn = viewModel.isCurrentlySpeakerOn()

            if (isSpeakerOn) {
                binding.btnSpeaker.setImageResource(R.drawable.ic_speaker_on)
            } else {
                binding.btnSpeaker.setImageResource(R.drawable.ic_speaker_off)
            }
        }
    }

    private fun startCallTimer() {
        callDurationSeconds = 0
        durationRunnable = object : Runnable {
            override fun run() {
                callDurationSeconds++
                val minutes = callDurationSeconds / 60
                val secs = callDurationSeconds % 60
                binding.tvCallDuration.text = String.format("%02d:%02d", minutes, secs)
                durationHandler.postDelayed(this, 1000)
            }
        }
        durationRunnable?.let { durationHandler.post(it) }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
        viewModel.endCall()

        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        audioManager?.mode = AudioManager.MODE_NORMAL
    }
}
