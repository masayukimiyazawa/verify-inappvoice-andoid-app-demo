package com.example.voiceverify.ui.login

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.example.voiceverify.R
import com.example.voiceverify.databinding.ActivityVerifyCodeBinding
import com.example.voiceverify.ui.dialpad.DialPadActivity
import com.example.voiceverify.viewmodel.AuthState
import com.example.voiceverify.viewmodel.AuthViewModel

class VerifyCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerifyCodeBinding
    private val viewModel: AuthViewModel by viewModels()

    private lateinit var resendTimer: CountDownTimer
    private var phoneNumber: String = ""
    private var displayName: String? = null

    companion object {
        private const val RESEND_DELAY = 60000L // 60 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get phone number and display name from intent
        phoneNumber = intent.getStringExtra("phone_number") ?: ""
        displayName = intent.getStringExtra("display_name")

        // Display phone number hint
        binding.tvPhoneHint.text = "Sent to: $phoneNumber"

        // Set phone number in ViewModel for verifyOTP
        viewModel.setPhoneNumber(phoneNumber)

        setupObservers()
        setupClickListeners()
        startResendTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        resendTimer.cancel()
    }

    private fun setupObservers() {
        viewModel.authState.observe(this, Observer { state ->
            when (state) {
                is AuthState.Idle -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnVerify.isEnabled = true
                }
                is AuthState.Loading -> {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnVerify.isEnabled = false
                    binding.tvVerifyStatus.visibility = android.view.View.GONE
                }
                is AuthState.Success -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnVerify.isEnabled = true

                    // Navigate to dial pad
                    val intent = Intent(this, DialPadActivity::class.java)
                    startActivity(intent)
                    finish()
                    return@Observer
                }
                is AuthState.Error -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnVerify.isEnabled = true
                    binding.tvVerifyStatus.text = state.message
                    binding.tvVerifyStatus.visibility = android.view.View.VISIBLE
                }
            }
        })
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnVerify.setOnClickListener {
            val code = binding.etVerificationCode.text?.toString()?.trim() ?: ""
            if (code.length < 4) {
                binding.tvVerifyStatus.text = getString(R.string.verification_code_hint)
                binding.tvVerifyStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            viewModel.verifyOTP(code)
        }

        binding.btnResendOTP.setOnClickListener {
            resendTimer.cancel()
            val phoneNumber = viewModel.getPhoneNumber() ?: ""
            if (phoneNumber.isNotEmpty()) {
                viewModel.sendOTP(phoneNumber, displayName)
                startResendTimer()
            }
        }
    }

    private fun startResendTimer() {
        binding.btnResendOTP.isEnabled = false
        binding.tvResendTimer.visibility = android.view.View.VISIBLE

        resendTimer = object : CountDownTimer(RESEND_DELAY, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                binding.tvResendTimer.text = "Resend available: ${seconds}s"
            }

            override fun onFinish() {
                binding.btnResendOTP.isEnabled = true
                binding.tvResendTimer.text = "Resend code"
            }
        }.start()
    }
}
