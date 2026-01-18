package com.example.TCECS.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Member(
    // Mapping to 'financial_records' columns
    @SerialName("fy") val financialYear: String? = "",
    @SerialName("sno") val sno: Int? = 0,
    @SerialName("mno") val memberNumber: String? = "",
    @SerialName("edpno") val employeeNumber: String? = "",
    @SerialName("name") val name: String? = "",
    @SerialName("station") val station: String? = "",

    // Aggregated/Profile fields (taken from the first row found)
    @SerialName("acno") val accountNumber: String? = "",
    @SerialName("ins") val insurance: Double? = 0.0,
    @SerialName("neft") val neft: Double? = 0.0,

    // New financial fields
    @SerialName("sc") val shareCapital: Double? = 0.0,
    @SerialName("td") val thriftDeposit: Double? = 0.0,
    @SerialName("fbf") val familyDeposit: Double? = 0.0,
    @SerialName("sldt") val loanDate: String? = "",
    @SerialName("slamt") val loanAmount: Double? = 0.0,
    @SerialName("slbal") val loanBalance: Double? = 0.0,
    @SerialName("mobile") val mobile: String? = "",

    // Issue & Scan Status Columns
    @SerialName("issue_date") val issueDate: String? = null,
    @SerialName("token_issuer") val issuerNumber: String? = null, // Using token_issuer for Issue Phone
    @SerialName("sweet_issuer_mobile") val scannerNumber: String? = null, // Using sweet_issuer_mobile for Scan Phone
    @SerialName("scan_date") val scannerDate: String? = null,

    // This list will contain the rows themselves (including this one) to show history
    val dividend: List<DividendEntry> = emptyList()
) {
    val isIssued: Boolean
        get() = !scannerDate.isNullOrEmpty()
}

// This matches the single table structure as well
@Serializable
data class DividendEntry(
    @SerialName("fy") val financialYear: String? = "",
    @SerialName("sno") val serialNumber: Int? = 0,
    @SerialName("mno") val memberNumber: String? = "",
    @SerialName("edpno") val employeeNumber: String? = "",
    @SerialName("name") val name: String? = "",
    @SerialName("station") val station: String? = "",
    @SerialName("fdate") val from: String? = null,
    @SerialName("tdate") val to: String? = null,
    @SerialName("scr") val scr: Double? = 0.0,
    @SerialName("cb") val balance: Double? = 0.0,
    @SerialName("days") val days: Int? = 0,
    @SerialName("roi") val rateOfInterest: Double? = 0.0,
    @SerialName("ic") val amount: Double? = 0.0,
    @SerialName("tdi") val tdi: Double? = 0.0,
    @SerialName("ins") val insurance: Double? = 0.0,
    @SerialName("neft") val neft: Double? = 0.0,
    @SerialName("acno") val accountNumber: String? = "",
    @SerialName("mobile") val mobile: String? = "",
    @SerialName("sc") val shareCapital: Double? = 0.0,
    @SerialName("td") val thriftDeposit: Double? = 0.0,
    @SerialName("fbf") val familyDeposit: Double? = 0.0,
    @SerialName("sldt") val loanDate: String? = "",
    @SerialName("slamt") val loanAmount: Double? = 0.0,
    @SerialName("slbal") val loanBalance: Double? = 0.0
)

@Serializable
data class MemberUpdate(
    @SerialName("issue_date") val issueDate: String? = null,
    @SerialName("token_issuer") val issuerNumber: String? = null,

    // Mapping scanner updates
    @SerialName("sweet_issuer_mobile") val scannerNumber: String? = null,
    @SerialName("scan_date") val scannerDate: String? = null
)