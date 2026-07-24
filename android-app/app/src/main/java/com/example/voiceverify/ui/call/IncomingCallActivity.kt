package com.example.voiceverify.ui.call

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceverify.R
import com.example.voiceverify.VoiceVerifyApplication
import com.example.voiceverify.databinding.ActivityIncomingCallBinding
import com.example.voiceverify.utils.RingtonePlayer

class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private var callerNumber: String = ""
    private lateinit var ringtonePlayer: RingtonePlayer

    companion object {
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        ringtonePlayer = RingtonePlayer(this)
        ringtonePlayer.start()

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()

        callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: getString(R.string.unknown_caller)

        binding.tvCallerName.text = callerName
        binding.tvCallerNumber.text = if (callerNumber.isNotEmpty()) {
            getString(R.string.ringing_from, callerNumber)
        } else {
            getString(R.string.incoming_call)
        }

        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnAccept.setOnClickListener {
            ringtonePlayer.stop()
            acceptAndDial()
        }

        binding.btnDecline.setOnClickListener {
            ringtonePlayer.stop()
            val app = VoiceVerifyApplication.getInstance()
            app.getCurrentCallId()?.let { callId ->
                app.rejectIncomingCall(callId)
            }
            finish()
        }
    }

    private fun acceptAndDial() {
        val app = VoiceVerifyApplication.getInstance()
        var callId = app.getCurrentCallId()
        if (callId == null) {
            callId = intent.getStringExtra("call_id")
        }
        if (callId == null || callId.isEmpty()) {
            ringtonePlayer.stop()
            finish()
            return
        }
        if (app.getCurrentCallId() == null) {
            app.setCurrentCallId(callId)
        }

        app.acceptIncomingCall(
            callId = callId,
            onConnected = {
                ringtonePlayer.stop()
                val intent = Intent(this, CallActivity::class.java).apply {
                    putExtra("phone_number", callerNumber)
                    putExtra("incoming_call", true)
                }
                startActivity(intent)
                finish()
            },
            onDisconnected = {
                ringtonePlayer.stop()
                finish()
            },
            onError = { error ->
                ringtonePlayer.stop()
                finish()
            }
        )
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtonePlayer.release()
    }
}
