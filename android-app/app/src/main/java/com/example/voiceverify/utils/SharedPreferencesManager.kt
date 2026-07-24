package com.example.voiceverify.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SharedPreferencesManager {

    private const val PREF_NAME = "voiceverify_encrypted_prefs"

    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_IS_VERIFIED = "is_verified"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_VOICE_TOKEN = "voice_token"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveUserCredentials(context: Context, phoneNumber: String, displayName: String?, backendUrl: String) {
        getSharedPreferences(context).edit().apply {
            putString(KEY_PHONE_NUMBER, phoneNumber)
            putString(KEY_DISPLAY_NAME, displayName)
            putString(KEY_BACKEND_URL, backendUrl)
            putBoolean(KEY_IS_VERIFIED, true)
            apply()
        }
    }

    fun getPhoneNumber(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_PHONE_NUMBER, null)
    }

    fun getDisplayName(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_DISPLAY_NAME, null)
    }

    fun isVerified(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_IS_VERIFIED, false)
    }

    fun getBackendUrl(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_BACKEND_URL, null)
    }

    fun setBackendUrl(context: Context, url: String) {
        getSharedPreferences(context).edit().apply {
            putString(KEY_BACKEND_URL, url)
            apply()
        }
    }

    fun clearCredentials(context: Context) {
        getSharedPreferences(context).edit().clear().apply()
    }

    fun saveUserId(context: Context, userId: String) {
        getSharedPreferences(context).edit().apply {
            putString(KEY_USER_ID, userId)
            apply()
        }
    }

    fun getUserId(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_USER_ID, null)
    }

    fun saveAuthToken(context: Context, token: String) {
        getSharedPreferences(context).edit().apply {
            putString(KEY_AUTH_TOKEN, token)
            apply()
        }
    }

    fun getAuthToken(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_AUTH_TOKEN, null)
    }

    fun saveVoiceToken(context: Context, token: String) {
        getSharedPreferences(context).edit().apply {
            putString(KEY_VOICE_TOKEN, token)
            apply()
        }
    }

    fun getVoiceToken(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_VOICE_TOKEN, null)
    }
}
