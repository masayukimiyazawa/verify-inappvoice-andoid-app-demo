package com.example.voiceverify.utils

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

class RingtonePlayer(private val context: Context) {

    companion object {
        private const val TAG = "RingtonePlayer"
    }

    private var ringtone: Ringtone? = null
    private var isPlaying = false

    private val defaultUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    fun start() {
        if (isPlaying) return

        try {
            ringtone = RingtoneManager.getRingtone(context, defaultUri)
            ringtone?.setVolume(1.0f)
            ringtone?.play()
            isPlaying = true
            Log.d(TAG, "Ringtone started playing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ringtone", e)
        }
    }

    fun stop() {
        if (!isPlaying) return

        try {
            ringtone?.stop()
            isPlaying = false
            Log.d(TAG, "Ringtone stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ringtone", e)
        }
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun release() {
        stop()
        ringtone = null
    }
}
