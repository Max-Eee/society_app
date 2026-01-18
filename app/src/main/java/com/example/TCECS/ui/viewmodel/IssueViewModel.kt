package com.example.TCECS.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.TCECS.data.models.Member
import com.example.TCECS.data.models.MemberUpdate
import com.example.TCECS.data.repository.MemberRepository
import com.example.TCECS.utils.PhoneNumberManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class IssueViewModel : ViewModel() {
    private val repository = MemberRepository()

    var searchQuery by mutableStateOf("")
        private set
    var member by mutableStateOf<Member?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var issuerPhoneNumber by mutableStateOf<String?>(null)
        private set
    var showPhoneNumberPicker by mutableStateOf(false)
        private set
    var successMessage by mutableStateOf<String?>(null)
        private set
    var showPrintFailureDialog by mutableStateOf(false)
    var printFailureReason by mutableStateOf("")

    // --- Conflict Resolution State ---
    var conflictMembers by mutableStateOf<List<Member>>(emptyList())
        private set
    var showConflictDialog by mutableStateOf(false)
        private set

    // --- HELPER: Clean Phone Number ---
    private fun cleanPhoneNumber(phone: String?): String {
        if (phone.isNullOrEmpty()) return ""
        var cleaned = phone.replace(" ", "").replace("-", "")
        if (cleaned.startsWith("+91")) cleaned = cleaned.substring(3)
        else if (cleaned.startsWith("+191")) cleaned = cleaned.substring(4)
        else if (cleaned.startsWith("91") && cleaned.length > 10) cleaned = cleaned.substring(2)
        return if (cleaned.length > 10) cleaned.takeLast(10) else cleaned
    }

    fun updateSearchQuery(query: String) { searchQuery = query }

    fun updateIssuerPhoneNumber(phoneNumber: String?, context: Context) {
        val cleaned = cleanPhoneNumber(phoneNumber)
        issuerPhoneNumber = cleaned
        showPhoneNumberPicker = false

        // Save to persistent storage
        if (cleaned.isNotBlank()) {
            PhoneNumberManager.savePhoneNumber(context, cleaned)
        }
    }

    fun loadSavedPhoneNumber(context: Context) {
        val saved = PhoneNumberManager.getSavedPhoneNumber(context)
        if (!saved.isNullOrBlank()) {
            issuerPhoneNumber = saved
        }
    }

    fun requestPhoneNumberPicker() { showPhoneNumberPicker = true }
    fun dismissPhoneNumberPicker() { showPhoneNumberPicker = false }
    fun dismissPrintFailureDialog() { showPrintFailureDialog = false; printFailureReason = "" }

    fun dismissConflictDialog() {
        showConflictDialog = false
        conflictMembers = emptyList()
    }

    fun selectMemberFromConflict(selectedMember: Member) {
        member = selectedMember
        showConflictDialog = false
        conflictMembers = emptyList()
        errorMessage = null
    }

    fun setPrintFailure(reason: String) {
        printFailureReason = reason
        showPrintFailureDialog = true
        isLoading = false
    }

    fun setLoadingState(loading: Boolean) { isLoading = loading }

    fun showSuccessMessage(message: String) {
        successMessage = message
        errorMessage = null
        viewModelScope.launch {
            delay(3000)
            successMessage = null
        }
    }

    fun showErrorMessage(message: String) {
        errorMessage = message
        successMessage = null
    }

    fun searchMember() {
        if (searchQuery.isBlank()) {
            errorMessage = "Please enter employee number (last 5 digits)"
            return
        }

        val queryLength = searchQuery.trim().length

        // Only accept 5 digit employee number (last 5 digits)
        if (queryLength != 5) {
            errorMessage = "Invalid: Enter exactly 5 digits for Employee No (last 5 digits)"
            return
        }

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                // Search by last 5 digits of employee number
                val result = repository.searchMembersByLast5Digits(searchQuery.trim())
                result.onSuccess { members ->
                    when (members.size) {
                        0 -> {
                            member = null
                            errorMessage = "No employee found with last 5 digits: $searchQuery"
                        }
                        1 -> {
                            // Single match - directly show the member
                            member = members.first()
                            errorMessage = null
                        }
                        else -> {
                            // Multiple matches - show conflict dialog
                            member = null
                            conflictMembers = members
                            showConflictDialog = true
                        }
                    }
                }.onFailure { exception ->
                    member = null
                    errorMessage = "Error searching employee: ${exception.message}"
                }
            } catch (e: Exception) {
                member = null
                errorMessage = "Unexpected error: ${e.message}"
            }
            isLoading = false
        }
    }

    fun clearSearch() {
        searchQuery = ""
        member = null
        errorMessage = null
        conflictMembers = emptyList()
        showConflictDialog = false
    }

    fun prepareMemberForIssue(): Member? {
        val currentMember = member ?: return null

        if (currentMember.isIssued) {
            errorMessage = "This member has already been issued"
            return null
        }

        if (issuerPhoneNumber.isNullOrBlank()) {
            requestPhoneNumberPicker()
            return null
        }

        val currentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        return currentMember.copy(
            issueDate = currentDateTime,
            issuerNumber = issuerPhoneNumber
        )
    }

    fun finalizeIssue(preparedMember: Member) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null

            val update = MemberUpdate(
                issueDate = preparedMember.issueDate,
                issuerNumber = preparedMember.issuerNumber
            )

            val mno = member?.memberNumber ?: preparedMember.memberNumber ?: ""
            val result = repository.updateMemberIssueInfo(mno, update)

            result.onSuccess {
                member = preparedMember
                showSuccessMessage("Issued & Saved Successfully!")
            }.onFailure { exception ->
                errorMessage = "CRITICAL: Printed but Save Failed! ${exception.message}"
            }

            isLoading = false
        }
    }
}