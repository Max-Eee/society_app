package com.example.TCECS.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.TCECS.data.models.Member
import com.example.TCECS.data.repository.MemberRepository
import com.example.TCECS.utils.PhoneNumberManager
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ScanViewModel : ViewModel() {
    private val repository = MemberRepository()

    // --- SECURITY CONFIGURATION ---
    // MUST match the key used in ThermalPrinterManager.kt
    private val SECRET_KEY = "s0c1ety_Sup3r_S3cr3t_K3y_@2024"

    var scannedMember by mutableStateOf<Member?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var scannerPhoneNumber by mutableStateOf<String?>(null)
        private set

    var scanStatus by mutableStateOf<ScanStatus>(ScanStatus.Idle)
        private set

    // --- HELPER: Clean Phone Number ---
    private fun cleanPhoneNumber(phone: String?): String {
        if (phone.isNullOrEmpty()) return ""
        var cleaned = phone.replace(" ", "").replace("-", "")

        if (cleaned.startsWith("+91")) {
            cleaned = cleaned.substring(3)
        } else if (cleaned.startsWith("+191")) {
            cleaned = cleaned.substring(4)
        } else if (cleaned.startsWith("91") && cleaned.length > 10) {
            cleaned = cleaned.substring(2)
        }

        return if (cleaned.length > 10) cleaned.takeLast(10) else cleaned
    }

    // --- HELPER: Date Validation ---
    private fun hasValidScanDate(dateStr: String?): Boolean {
        if (dateStr.isNullOrBlank()) return false
        val clean = dateStr.trim().lowercase(Locale.getDefault())
        if (clean == "null") return false
        if (clean.startsWith("0000-00-00")) return false
        return true
    }

    // --- SECURITY: HMAC VERIFICATION ---
    /**
     * Parses the raw QR string.
     * Expected format: "MemberID|HMACSignature"
     * Returns the MemberID if valid, null if invalid/tampered.
     */
    private fun verifyQrSignature(rawQrData: String): String? {
        try {
            val parts = rawQrData.split("|")

            // If it doesn't have exactly 2 parts, it's either an old QR or invalid format
            if (parts.size != 2) return null

            val memberId = parts[0]
            val receivedSignature = parts[1]

            // Re-calculate signature based on the ID we found
            val calculatedSignature = computeHmacSha256(memberId, SECRET_KEY)

            // Compare calculated vs received
            return if (calculatedSignature == receivedSignature) {
                memberId // Valid!
            } else {
                null // Tampered!
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun computeHmacSha256(data: String, key: String): String {
        try {
            val algorithm = "HmacSHA256"
            val secretKeySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), algorithm)
            val mac = Mac.getInstance(algorithm)
            mac.init(secretKeySpec)
            val bytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return bytesToHex(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = "0123456789ABCDEF"[v ushr 4]
            hexChars[j * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
        }
        return String(hexChars)
    }

    // --- LOGIC ---

    fun detectPhoneNumber(context: Context) {
        // First, try to load from saved preferences
        val savedPhone = PhoneNumberManager.getSavedPhoneNumber(context)
        if (!savedPhone.isNullOrBlank()) {
            scannerPhoneNumber = savedPhone
            return
        }

        // Fallback to device detection if not saved
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val rawNumber = telephonyManager.line1Number
                val cleaned = cleanPhoneNumber(rawNumber)
                if (cleaned.isNotBlank()) {
                    scannerPhoneNumber = cleaned
                    // Save for future use
                    PhoneNumberManager.savePhoneNumber(context, cleaned)
                }
            } catch (e: Exception) {
                scannerPhoneNumber = null
            }
        }
    }

    fun handleQrCodeScan(rawQrCode: String, context: Context) {
        viewModelScope.launch {
            isLoading = true
            scanStatus = ScanStatus.Scanning

            // 1. VERIFY SIGNATURE FIRST
            val validMemberId = verifyQrSignature(rawQrCode)

            if (validMemberId == null) {
                // Signature check failed (Fake QR or Old Format)
                isLoading = false
                scanStatus = ScanStatus.Error("Security Alert: Invalid or Tampered QR Code.")
                return@launch
            }

            // 2. Proceed with detected Phone Logic
            if (scannerPhoneNumber == null) {
                detectPhoneNumber(context)
            }

            // 3. Query Repo using the EXTRACTED ID (validMemberId), not the raw string
            val result = repository.getMemberByQrCode(validMemberId)

            result.onSuccess { member ->
                if (member != null) {
                    scannedMember = member

                    if (hasValidScanDate(member.scannerDate)) {
                        scanStatus = ScanStatus.AlreadyScanned(member)
                    } else {
                        if (!scannerPhoneNumber.isNullOrEmpty()) {
                            updateScanInfo(member)
                        } else {
                            scanStatus = ScanStatus.Error("Unable to detect scanner phone number")
                        }
                    }
                } else {
                    scanStatus = ScanStatus.Invalid // ID verified, but not found in DB? (Rare)
                }
            }.onFailure { exception ->
                scanStatus = ScanStatus.Error(exception.message ?: "Unknown error")
            }

            isLoading = false
        }
    }

    private fun updateScanInfo(member: Member) {
        viewModelScope.launch {
            if (member.memberNumber.isNullOrEmpty()) {
                scanStatus = ScanStatus.Error("Invalid member data: Member number is missing")
                return@launch
            }

            val phoneToSend = cleanPhoneNumber(scannerPhoneNumber)

            if (phoneToSend.isBlank()) {
                scanStatus = ScanStatus.Error("Unable to detect scanner phone number")
                return@launch
            }

            val result = repository.updateScannerInfo(member.memberNumber, phoneToSend)

            result.onSuccess {
                val currentDisplayDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                scannedMember = member.copy(
                    scannerNumber = phoneToSend,
                    scannerDate = currentDisplayDate
                )
                scanStatus = ScanStatus.Verified(scannedMember!!)
            }.onFailure { exception ->
                scanStatus = ScanStatus.Error(exception.message ?: "Failed to update scan info")
            }
        }
    }

    fun retryScanWithPhoneNumber(phoneNumber: String, context: Context) {
        val cleaned = cleanPhoneNumber(phoneNumber)
        scannerPhoneNumber = cleaned

        // Save to persistent storage
        if (cleaned.isNotBlank()) {
            PhoneNumberManager.savePhoneNumber(context, cleaned)
        }

        scannedMember?.let { member ->
            updateScanInfo(member)
        }
    }

    fun forceShowError(message: String) {
        scanStatus = ScanStatus.Error(message)
    }

    fun resetScan() {
        scannedMember = null
        scanStatus = ScanStatus.Idle
    }
}

sealed class ScanStatus {
    object Idle : ScanStatus()
    object Scanning : ScanStatus()
    data class Verified(val member: Member) : ScanStatus()
    data class AlreadyIssued(val member: Member) : ScanStatus()
    data class AlreadyScanned(val member: Member) : ScanStatus()
    object Invalid : ScanStatus()
    data class Error(val message: String) : ScanStatus()
}