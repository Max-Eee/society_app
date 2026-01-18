package com.example.TCECS.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persistent storage of the issuer/scanner phone number.
 * Stores the phone number once and retrieves it for both Issue and Scan operations.
 */
object PhoneNumberManager {
    private const val PREFS_NAME = "chennai_coop_prefs"
    private const val KEY_PHONE_NUMBER = "saved_phone_number"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves the phone number to persistent storage.
     */
    fun savePhoneNumber(context: Context, phoneNumber: String) {
        if (phoneNumber.isNotBlank()) {
            getPrefs(context).edit()
                .putString(KEY_PHONE_NUMBER, phoneNumber.trim())
                .apply()
        }
    }

    /**
     * Retrieves the saved phone number, or null if not set.
     */
    fun getSavedPhoneNumber(context: Context): String? {
        return getPrefs(context).getString(KEY_PHONE_NUMBER, null)
    }

    /**
     * Clears the saved phone number (optional - for testing or reset).
     */
    fun clearPhoneNumber(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_PHONE_NUMBER)
            .apply()
    }

    /**
     * Checks if a phone number is already saved.
     */
    fun hasPhoneNumber(context: Context): Boolean {
        return !getSavedPhoneNumber(context).isNullOrBlank()
    }
}

