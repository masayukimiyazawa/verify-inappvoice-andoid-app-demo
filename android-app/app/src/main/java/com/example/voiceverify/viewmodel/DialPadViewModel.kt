package com.example.voiceverify.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class DialPadViewModel : ViewModel() {

    private val _phone = MutableLiveData<String>()
    val phone: LiveData<String> = _phone

    fun appendDigit(digit: String) {
        _phone.value = (_phone.value ?: "") + digit
    }

    fun removeLastDigit() {
        _phone.value = (_phone.value ?: "").dropLast(1)
    }

    fun clear() {
        _phone.value = ""
    }
}
