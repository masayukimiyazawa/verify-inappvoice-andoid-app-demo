package com.example.voiceverify.ui.login

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.example.voiceverify.BuildConfig
import com.example.voiceverify.R
import com.example.voiceverify.api.RetrofitClient
import com.example.voiceverify.databinding.ActivityPhoneInputBinding
import com.example.voiceverify.utils.SharedPreferencesManager
import com.example.voiceverify.viewmodel.AuthState
import com.example.voiceverify.viewmodel.AuthViewModel

class PhoneInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneInputBinding
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set backend URL: BuildConfig > SharedPreferences > string resource fallback
        val backendUrl = BuildConfig.BACKEND_URL?.takeIf { it.isNotBlank() }
            ?: SharedPreferencesManager.getBackendUrl(this)
            ?: getString(R.string.backend_url_placeholder)
        RetrofitClient.setBaseUrl(backendUrl)

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        viewModel.authState.observe(this, Observer { state ->
            when (state) {
                is AuthState.Idle -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnSendOTP.isEnabled = true
                }
                is AuthState.Loading -> {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.btnSendOTP.isEnabled = false
                    binding.tvStatusMessage.visibility = android.view.View.GONE
                }
                is AuthState.Success -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnSendOTP.isEnabled = true

                    // Navigate to verification screen
                    val intent = android.content.Intent(
                        this,
                        VerifyCodeActivity::class.java
                    ).apply {
                        putExtra("phone_number", state.user.phoneNumber)
                        putExtra("display_name", state.user.displayName)
                    }
                    startActivity(intent)
                    finish()
                }
                is AuthState.Error -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnSendOTP.isEnabled = true
                    binding.tvStatusMessage.text = state.message
                    binding.tvStatusMessage.visibility = android.view.View.VISIBLE
                }
            }
        })
    }

    private fun setupClickListeners() {
        binding.btnSendOTP.setOnClickListener {
            val phoneNumber = binding.etPhoneNumber.text?.toString()?.trim() ?: ""
            val displayName = binding.etDisplayName.text?.toString()?.trim()

            // Strip leading + for Vonage Verify V2 (E.164 without +)
            val normalizedPhone = phoneNumber.removePrefix("+")

            if (!isValidPhoneNumber(normalizedPhone)) {
                binding.tvStatusMessage.text = getString(R.string.error_invalid_phone)
                binding.tvStatusMessage.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }

            binding.tvStatusMessage.visibility = android.view.View.GONE
            viewModel.sendOTP(normalizedPhone, if (displayName.isNullOrEmpty()) null else displayName)
        }
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        // E.164 without leading + for Vonage Verify V2
        val pattern = Regex("^[1-9]\\d{5,14}$")
        return pattern.matches(phone.trim())
    }
}
