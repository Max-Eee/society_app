package com.example.TCECS.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.TCECS.data.repository.MemberRepository
import kotlinx.coroutines.launch

// Data Model for the UI
data class ReportItem(
    val number: String, // This is the Phone Number of the Admin/Issuer
    val scanCount: Int,
    val issueCount: Int
)

class ReportViewModel : ViewModel() {
    private val repository = MemberRepository()

    var reportList by mutableStateOf<List<ReportItem>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Calculated Grand Totals
    val totalScans: Int
        get() = reportList.sumOf { it.scanCount }

    val totalIssued: Int
        get() = reportList.sumOf { it.issueCount }

    init {
        loadReportData()
    }

    fun loadReportData() {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null

            // UPDATED: Call getTotalReport instead of Daily
            val result = repository.getTotalReport()

            result.onSuccess { rawRows ->
                processReportData(rawRows)
            }.onFailure {
                errorMessage = "Failed to load report: ${it.message}"
                reportList = emptyList()
            }

            isLoading = false
        }
    }

    private fun processReportData(rawRows: List<com.example.TCECS.data.models.Member>) {
        // 1. Group rows by Member Number (mno1) to ensure we count DISTINCT members
        val distinctMembers = rawRows.groupBy { it.memberNumber }.map { (_, rows) -> rows.first() }

        // 2. Maps to hold counts per Admin Phone Number
        val issuerCounts = mutableMapOf<String, Int>()
        val scannerCounts = mutableMapOf<String, Int>()

        // 3. Iterate through distinct members and attribute counts
        distinctMembers.forEach { member ->
            // Check Issue (Total - just check if it exists)
            if (!member.issueDate.isNullOrEmpty()) {
                val issuer = member.issuerNumber ?: "Unknown"
                issuerCounts[issuer] = (issuerCounts[issuer] ?: 0) + 1
            }

            // Check Scan (Total - just check if it exists)
            if (!member.scannerDate.isNullOrEmpty()) {
                val scanner = member.scannerNumber ?: "Unknown"
                scannerCounts[scanner] = (scannerCounts[scanner] ?: 0) + 1
            }
        }

        // 4. Merge into a list of ReportItems
        val allNumbers = issuerCounts.keys + scannerCounts.keys
        val reportItems = allNumbers.distinct().map { number ->
            ReportItem(
                number = number,
                scanCount = scannerCounts[number] ?: 0,
                issueCount = issuerCounts[number] ?: 0
            )
        }.sortedByDescending { it.issueCount + it.scanCount }

        reportList = reportItems
    }
}