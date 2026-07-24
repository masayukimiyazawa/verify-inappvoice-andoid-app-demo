package com.example.voiceverify.ui.dialpad

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.voiceverify.R
import com.example.voiceverify.VoiceVerifyApplication
import com.example.voiceverify.api.RetrofitClient
import com.example.voiceverify.api.VoiceTokenRequest
import com.example.voiceverify.databinding.ActivityDialpadBinding
import com.example.voiceverify.ui.call.CallActivity
import com.example.voiceverify.utils.SharedPreferencesManager
import com.example.voiceverify.viewmodel.DialPadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DialPadActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DialPadActivity"
    }

    private lateinit var binding: ActivityDialpadBinding
    private val viewModel: DialPadViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    private var isCalling = false
    private var retryCount = 0
    private val maxRetries = 5
    private val retryDelayMs = 1000L

    private var pendingPhoneNumber: String? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingPhoneNumber?.let { initiateInAppCallWithNumber(it) }
        } else {
            Toast.makeText(this, R.string.permission_audio_required, Toast.LENGTH_LONG).show()
        }
        pendingPhoneNumber = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDialPadButtons()
        setupCallButton()
        observeCallState()
    }

    private fun setupDialPadButtons() {
        val numberButtons = listOf(
            binding.btnKey1 to "1",
            binding.btnKey2 to "2",
            binding.btnKey3 to "3",
            binding.btnKey4 to "4",
            binding.btnKey5 to "5",
            binding.btnKey6 to "6",
            binding.btnKey7 to "7",
            binding.btnKey8 to "8",
            binding.btnKey9 to "9",
            binding.btnKey0 to "0"
        )

        for ((button, digit) in numberButtons) {
            button.setOnClickListener {
                viewModel.appendDigit(digit)
            }
        }

        binding.btnKey0.setOnLongClickListener {
            viewModel.appendDigit("+")
            true
        }

        binding.btnKeyStar.setOnClickListener {
            viewModel.appendDigit("*")
        }

        binding.btnKeyHash.setOnClickListener {
            viewModel.appendDigit("#")
        }

        binding.btnBackspace.setOnClickListener {
            viewModel.removeLastDigit()
        }
    }

    private fun setupCallButton() {
        binding.btnCall.setOnClickListener {
            if (isCalling) return@setOnClickListener

            val phoneNumber = binding.etPhoneNumber.text?.toString()?.trim() ?: ""
            if (phoneNumber.length < 7) {
                Toast.makeText(this, R.string.error_invalid_phone, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingPhoneNumber = phoneNumber
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }

            initiateInAppCallWithNumber(phoneNumber)
        }

        binding.btnDisconnect.setOnClickListener {
            if (!isCalling) return@setOnClickListener
            endCallFromDialPad()
        }
    }

    private fun setCallingState(calling: Boolean) {
        isCalling = calling
        binding.btnCall.visibility = if (calling) View.GONE else View.VISIBLE
        binding.btnDisconnect.visibility = if (calling) View.VISIBLE else View.GONE
    }

    private fun endCallFromDialPad() {
        val app = VoiceVerifyApplication.getInstance()
        app.endCurrentCall {
            handler.post {
                setCallingState(false)
                hideCallStatus()
            }
        }
    }

    private fun initiateInAppCallWithNumber(phoneNumber: String) {
        val e164Number = if (phoneNumber.startsWith("+")) phoneNumber else "+$phoneNumber"

        val app = VoiceVerifyApplication.getInstance()

        if (!app.isSessionReady) {
            showCallStatus(getString(R.string.voice_session_retrying))
            retryCount = 0
            retrySessionAndCall(e164Number, phoneNumber)
            return
        }

        startOutboundCall(e164Number, phoneNumber)
    }

    private fun retrySessionAndCall(e164Number: String, displayNumber: String) {
        if (retryCount >= maxRetries) {
            showCallStatus(getString(R.string.voice_session_failed))
            Toast.makeText(this, R.string.voice_session_not_ready, Toast.LENGTH_LONG).show()
            return
        }

        retryCount++
        showCallStatus(getString(R.string.voice_session_retry_count, retryCount, maxRetries))

        val app = VoiceVerifyApplication.getInstance()
        var voiceToken = SharedPreferencesManager.getVoiceToken(this)

        if (voiceToken.isNullOrBlank()) {
            fetchAndSaveVoiceToken(e164Number) { fetched ->
                if (fetched) {
                    voiceToken = SharedPreferencesManager.getVoiceToken(this)
                    if (!voiceToken.isNullOrBlank()) {
                        app.loginToVoiceClient(voiceToken!!) {
                            retryAfterLogin(e164Number, displayNumber)
                        }
                    } else {
                        handler.postDelayed({ retrySessionAndCall(e164Number, displayNumber) }, retryDelayMs)
                    }
                } else {
                    handler.postDelayed({ retrySessionAndCall(e164Number, displayNumber) }, retryDelayMs)
                }
            }
            return
        }

        app.loginToVoiceClient(voiceToken!!) {
            retryAfterLogin(e164Number, displayNumber)
        }
    }

    private fun retryAfterLogin(e164Number: String, displayNumber: String) {
        val app = VoiceVerifyApplication.getInstance()
        if (app.isSessionReady) {
            startOutboundCall(e164Number, displayNumber)
        } else {
            handler.postDelayed({ retrySessionAndCall(e164Number, displayNumber) }, retryDelayMs)
        }
    }

    private fun fetchAndSaveVoiceToken(e164Number: String, onResult: (Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                val currentUserPhone = SharedPreferencesManager.getPhoneNumber(this@DialPadActivity)
                val e164UserId = if (currentUserPhone != null) {
                    if (currentUserPhone.startsWith("+")) currentUserPhone else "+$currentUserPhone"
                } else {
                    if (e164Number.startsWith("+")) e164Number else "+$e164Number"
                }
                val request = VoiceTokenRequest(userId = e164UserId)

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getVoiceToken(request).execute()
                }

                if (response.isSuccessful && response.body()?.token != null) {
                    val voiceToken = response.body()!!.token!!
                    SharedPreferencesManager.saveVoiceToken(this@DialPadActivity, voiceToken)
                    Log.d(TAG, "Fetched and saved voice token, length=${voiceToken.length}")
                    onResult(true)
                } else {
                    Log.e(TAG, "Failed to fetch voice token: code=${response.code()}")
                    onResult(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch voice token", e)
                onResult(false)
            }
        }
    }

    private fun startOutboundCall(e164Number: String, displayNumber: String) {
        setCallingState(true)
        retryCount = 0
        showCallStatus(getString(R.string.call_status_connecting))

        val app = VoiceVerifyApplication.getInstance()
        val context = mapOf(
            "to" to e164Number,
            "type" to "outbound"
        )

        app.voiceClient?.serverCall(context) { error, callId ->
            handler.post {
                if (error != null) {
                    setCallingState(false)
                    showCallStatus(getString(R.string.call_status_error, error.message))
                    Toast.makeText(this, "Call error: ${error.message}", Toast.LENGTH_LONG).show()
                } else {
                    app.setCurrentCallId(callId)
                    showCallStatus(getString(R.string.call_status_ringing))
                    val intent = Intent(this, CallActivity::class.java).apply {
                        putExtra("phone_number", displayNumber)
                        putExtra("is_inapp_available", true)
                        putExtra("call_id", callId ?: "")
                    }
                    startActivity(intent)
                    setCallingState(false)
                    hideCallStatus()
                }
            }
        }
    }

    private fun showCallStatus(status: String) {
        binding.tvCallStatus.text = status
        binding.tvCallStatus.visibility = View.VISIBLE
    }

    private fun hideCallStatus() {
        binding.tvCallStatus.visibility = View.GONE
    }

    private fun observeCallState() {
        viewModel.phone.observe(this) { number ->
            binding.etPhoneNumber.setText(number)
            binding.etPhoneNumber.setSelection(number.length)
        }
    }

    override fun onResume() {
        super.onResume()
        val app = VoiceVerifyApplication.getInstance()
        if (!app.isSessionReady) {
            Log.d(TAG, "Session not ready on resume, reconnecting...")
            val userId = app.getOrCreateUserId()
            app.reconnect(userId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
