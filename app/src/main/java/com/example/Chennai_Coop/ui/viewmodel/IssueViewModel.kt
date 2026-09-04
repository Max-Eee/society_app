package com.example.Chennai_Coop.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.Chennai_Coop.data.models.Member
import com.example.Chennai_Coop.data.models.MemberUpdate
import com.example.Chennai_Coop.data.repository.MemberRepository
import com.example.Chennai_Coop.utils.PhoneNumberManager
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
            errorMessage = "Please enter a member or employee number"
            return
        }

        val normalizedQuery = searchQuery.trim()
        val isMemberNumber = normalizedQuery.length == 5 && normalizedQuery.all(Char::isDigit)

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val result = repository.searchMemberByNumber(normalizedQuery)
                result.onSuccess { foundMember ->
                    if (foundMember != null) {
                        member = foundMember
                        errorMessage = null
                    } else {
                        member = null
                        val numberType = if (isMemberNumber) "member" else "employee"
                        errorMessage = "No $numberType found with number: $normalizedQuery"
                    }
                }.onFailure { exception ->
                    member = null
                    errorMessage = "Error searching member: ${exception.message}"
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
