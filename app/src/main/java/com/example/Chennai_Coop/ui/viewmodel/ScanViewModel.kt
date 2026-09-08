package com.example.Chennai_Coop.ui.viewmodel

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
import com.example.Chennai_Coop.data.models.BulkGroup
import com.example.Chennai_Coop.data.models.BulkScanBatch
import com.example.Chennai_Coop.data.models.Member
import com.example.Chennai_Coop.data.repository.MemberRepository
import com.example.Chennai_Coop.utils.PhoneNumberManager
import com.example.Chennai_Coop.utils.ThermalPrinterManager
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

    var bulkGroup by mutableStateOf<BulkGroup?>(null)
        private set

    var selectedBulkMemberNumbers by mutableStateOf<Set<String>>(emptySet())
        private set

    var bulkPrintMessage by mutableStateOf<String?>(null)
        private set

    var reprintingBatchId by mutableStateOf<String?>(null)
        private set

    private var loadedBulkBatches: List<BulkScanBatch> = emptyList()

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
     * Expected format: "Payload|HMACSignature". Payload is either a member ID or
     * "GROUP:<opaque-group-qr-id>".
     */
    private fun verifyQrSignature(rawQrData: String): String? {
        try {
            val parts = rawQrData.split("|")

            // If it doesn't have exactly 2 parts, it's either an old QR or invalid format
            if (parts.size != 2) return null

            val payload = parts[0]
            val receivedSignature = parts[1]

            val calculatedSignature = computeHmacSha256(payload, SECRET_KEY)

            // Compare calculated vs received
            return if (calculatedSignature == receivedSignature) {
                payload
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
            val validPayload = verifyQrSignature(rawQrCode)

            if (validPayload == null) {
                // Signature check failed (Fake QR or Old Format)
                isLoading = false
                scanStatus = ScanStatus.Error("Security Alert: Invalid or Tampered QR Code.")
                return@launch
            }

            if (validPayload.startsWith(GROUP_PAYLOAD_PREFIX)) {
                val groupQrId = validPayload.removePrefix(GROUP_PAYLOAD_PREFIX).trim()
                if (groupQrId.isBlank()) {
                    isLoading = false
                    scanStatus = ScanStatus.Error("Invalid group QR code")
                    return@launch
                }

                repository.getMembersByGroupQrId(groupQrId)
                    .onSuccess { group ->
                        if (group == null || group.members.isEmpty()) {
                            scanStatus = ScanStatus.Invalid
                        } else {
                            bulkGroup = group
                            selectedBulkMemberNumbers = group.members
                                .filterNot(::hasMemberBeenScanned)
                                .mapNotNull { it.memberNumber?.takeIf(String::isNotBlank) }
                                .toSet()
                            bulkPrintMessage = null
                            scanStatus = statusForGroup(group)
                        }
                    }
                    .onFailure { exception ->
                        scanStatus = ScanStatus.Error(exception.message ?: "Unable to load group")
                    }

                isLoading = false
                return@launch
            }

            // 2. Proceed with detected Phone Logic
            if (scannerPhoneNumber == null) {
                detectPhoneNumber(context)
            }

            // 3. Query Repo using the EXTRACTED ID (validMemberId), not the raw string
            val result = repository.getMemberByQrCode(validPayload)

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

    private fun hasMemberBeenScanned(member: Member): Boolean =
        hasValidScanDate(member.scannerDate)

    fun toggleBulkMember(member: Member) {
        val memberNumber = member.memberNumber ?: return
        if (hasMemberBeenScanned(member)) return

        selectedBulkMemberNumbers = if (memberNumber in selectedBulkMemberNumbers) {
            selectedBulkMemberNumbers - memberNumber
        } else {
            selectedBulkMemberNumbers + memberNumber
        }
    }

    fun toggleAllBulkMembers() {
        val selectableMemberNumbers = bulkGroup?.members
            ?.filterNot(::hasMemberBeenScanned)
            ?.mapNotNull { it.memberNumber?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()

        selectedBulkMemberNumbers = if (
            selectableMemberNumbers.isNotEmpty() &&
            selectedBulkMemberNumbers.containsAll(selectableMemberNumbers)
        ) {
            emptySet()
        } else {
            selectableMemberNumbers
        }
    }

    fun scanSelectedGroupMembers(
        context: Context,
        printerManager: ThermalPrinterManager
    ) {
        val group = bulkGroup ?: return
        val selectedMembers = group.members.filter {
            it.memberNumber in selectedBulkMemberNumbers && !hasMemberBeenScanned(it)
        }
        if (selectedMembers.isEmpty()) return

        if (scannerPhoneNumber.isNullOrBlank()) detectPhoneNumber(context)
        val phoneToSend = cleanPhoneNumber(scannerPhoneNumber)
        if (phoneToSend.isBlank()) {
            scanStatus = ScanStatus.Error("Unable to detect scanner phone number")
            return
        }

        viewModelScope.launch {
            isLoading = true
            bulkPrintMessage = null
            scanStatus = ScanStatus.BulkScanning(group.groupId, selectedMembers.size)

            repository.updateGroupScanInfo(
                groupId = group.groupId,
                memberNumbers = selectedMembers.mapNotNull { it.memberNumber },
                scannerNumber = phoneToSend
            ).onSuccess { result ->
                val scannedAt = result.scannedAt
                val selectedNumbers = selectedMembers.mapNotNull { it.memberNumber }.toSet()
                val updatedMembers = group.members.map { member ->
                    if (member.memberNumber in selectedNumbers) {
                        member.copy(
                            scannerDate = scannedAt,
                            scannerNumber = phoneToSend,
                            scanBatchId = result.batchId
                        )
                    } else {
                        member
                    }
                }
                val updatedGroup = group.copy(members = updatedMembers)
                bulkGroup = updatedGroup
                selectedBulkMemberNumbers = emptySet()

                val totalScanned = updatedMembers.count(::hasMemberBeenScanned)
                scanStatus = ScanStatus.BulkScanned(
                    groupId = group.groupId,
                    scannedMembers = selectedMembers,
                    scannedAt = scannedAt,
                    batchId = result.batchId,
                    scannerNumber = phoneToSend,
                    totalScanned = totalScanned,
                    totalMembers = updatedMembers.size
                )

                printerManager.printBulkScan(
                    groupId = group.groupId,
                    members = selectedMembers,
                    scannedAt = scannedAt,
                    issuerNumber = phoneToSend,
                    onSuccess = { bulkPrintMessage = "Scanned member list printed automatically" },
                    onError = { error -> bulkPrintMessage = "Scan saved successfully. Print failed: $error" }
                )
            }.onFailure { exception ->
                scanStatus = ScanStatus.Error(exception.message ?: "Failed to issue selected members")
            }

            isLoading = false
        }
    }

    fun openBulkBatchHistory() {
        val group = bulkGroup ?: return
        viewModelScope.launch {
            bulkPrintMessage = null
            scanStatus = ScanStatus.BulkBatchHistoryLoading(group)
            repository.getGroupScanBatches(group.groupId)
                .onSuccess { batches ->
                    if (scanStatus !is ScanStatus.BulkBatchHistoryLoading) return@onSuccess
                    loadedBulkBatches = batches
                    scanStatus = ScanStatus.BulkBatchHistory(group, batches)
                }
                .onFailure { error ->
                    if (scanStatus !is ScanStatus.BulkBatchHistoryLoading) return@onFailure
                    loadedBulkBatches = emptyList()
                    scanStatus = ScanStatus.BulkBatchHistory(
                        group = group,
                        batches = emptyList(),
                        errorMessage = error.message ?: "Unable to load scan batches"
                    )
                }
        }
    }

    fun showBulkBatch(batch: BulkScanBatch) {
        val group = bulkGroup ?: return
        bulkPrintMessage = null
        scanStatus = ScanStatus.BulkBatchDetails(group, batch)
    }

    fun reprintBulkBatch(batch: BulkScanBatch, printerManager: ThermalPrinterManager) {
        if (reprintingBatchId != null) return
        reprintingBatchId = batch.id
        bulkPrintMessage = null
        printerManager.printBulkScan(
            groupId = batch.groupId,
            members = batch.members,
            scannedAt = batch.scannedAt,
            issuerNumber = batch.scannerNumber,
            onSuccess = {
                reprintingBatchId = null
                bulkPrintMessage = "Two copies reprinted successfully"
            },
            onError = { error ->
                reprintingBatchId = null
                bulkPrintMessage = "Reprint failed: $error"
            }
        )
    }

    fun navigateBackInBulkHistory() {
        scanStatus = when (val status = scanStatus) {
            is ScanStatus.BulkBatchDetails -> ScanStatus.BulkBatchHistory(
                group = status.group,
                batches = loadedBulkBatches
            )
            is ScanStatus.BulkBatchHistory,
            is ScanStatus.BulkBatchHistoryLoading -> bulkGroup?.let(::statusForGroup)
                ?: ScanStatus.Idle
            else -> status
        }
        bulkPrintMessage = null
    }

    private fun statusForGroup(group: BulkGroup): ScanStatus =
        if (group.members.all(::hasMemberBeenScanned)) {
            ScanStatus.BulkAllScanned(group)
        } else {
            ScanStatus.BulkGroupReady(group)
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

        val group = bulkGroup
        if (group != null) {
            scanStatus = statusForGroup(group)
        } else {
            scannedMember?.let { member -> updateScanInfo(member) }
        }
    }

    fun forceShowError(message: String) {
        scanStatus = ScanStatus.Error(message)
    }

    fun resetScan() {
        scannedMember = null
        bulkGroup = null
        selectedBulkMemberNumbers = emptySet()
        bulkPrintMessage = null
        reprintingBatchId = null
        loadedBulkBatches = emptyList()
        scanStatus = ScanStatus.Idle
    }

    companion object {
        private const val GROUP_PAYLOAD_PREFIX = "GROUP:"
    }
}

sealed class ScanStatus {
    object Idle : ScanStatus()
    object Scanning : ScanStatus()
    data class Verified(val member: Member) : ScanStatus()
    data class AlreadyScanned(val member: Member) : ScanStatus()
    data class BulkGroupReady(val group: BulkGroup) : ScanStatus()
    data class BulkScanning(val groupId: String, val selectedCount: Int) : ScanStatus()
    data class BulkScanned(
        val groupId: String,
        val scannedMembers: List<Member>,
        val scannedAt: String,
        val batchId: String,
        val scannerNumber: String,
        val totalScanned: Int,
        val totalMembers: Int
    ) : ScanStatus()
    data class BulkAllScanned(val group: BulkGroup) : ScanStatus()
    data class BulkBatchHistoryLoading(val group: BulkGroup) : ScanStatus()
    data class BulkBatchHistory(
        val group: BulkGroup,
        val batches: List<BulkScanBatch>,
        val errorMessage: String? = null
    ) : ScanStatus()
    data class BulkBatchDetails(val group: BulkGroup, val batch: BulkScanBatch) : ScanStatus()
    object Invalid : ScanStatus()
    data class Error(val message: String) : ScanStatus()
}
