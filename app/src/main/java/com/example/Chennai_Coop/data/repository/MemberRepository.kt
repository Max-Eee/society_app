package com.example.Chennai_Coop.data.repository

import android.util.Log
import com.example.Chennai_Coop.data.models.DividendEntry
import com.example.Chennai_Coop.data.models.BulkGroup
import com.example.Chennai_Coop.data.models.Member
import com.example.Chennai_Coop.data.models.MemberUpdate
import com.example.Chennai_Coop.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemberRepository {
    private val client = SupabaseClient.client
    private val TAG = "MemberRepository"

    private val TABLE_NAME = "ccocs"

    private fun searchFieldFor(value: String): String =
        if (value.length in 4..5 && value.all(Char::isDigit)) "mno" else "edpno"

    suspend fun searchMemberByNumber(searchQuery: String): Result<Member?> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Searching for member with query: $searchQuery")

                val normalizedQuery = searchQuery.trim()
                // Four or five digits identify a member number; all other inputs are employee numbers.
                val searchField = searchFieldFor(normalizedQuery)
                Log.d(TAG, "Searching by field: $searchField")

                val rows = client.from(TABLE_NAME)
                    .select() {
                        filter {
                            eq(searchField, normalizedQuery)
                        }
                    }
                    .decodeList<DividendEntry>()

                if (rows.isNotEmpty()) {
                    val profileData = client.from(TABLE_NAME)
                        .select() {
                            filter {
                                eq(searchField, normalizedQuery)
                            }
                            limit(1)
                        }
                        .decodeList<Member>()
                        .firstOrNull()

                    if (profileData != null) {
                        Result.success(profileData.copy(dividend = rows))
                    } else {
                        Result.success(null)
                    }
                } else {
                    Result.success(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error searching member: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateMemberIssueInfo(memberNumber: String, update: MemberUpdate): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                client.from(TABLE_NAME)
                    .update(update) {
                        filter {
                            eq("mno", memberNumber)
                        }
                    }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getMemberByQrCode(qrCode: String): Result<Member?> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedQrCode = qrCode.trim()
                val searchField = searchFieldFor(normalizedQrCode)

                val rows = client.from(TABLE_NAME)
                    .select() {
                        filter {
                            eq(searchField, normalizedQrCode)
                        }
                    }
                    .decodeList<DividendEntry>()

                if (rows.isNotEmpty()) {
                    val profileData = client.from(TABLE_NAME)
                        .select() {
                            filter {
                                eq(searchField, normalizedQrCode)
                            }
                            limit(1)
                        }
                        .decodeList<Member>()
                        .firstOrNull()

                    if (profileData != null) {
                        Result.success(profileData.copy(dividend = rows))
                    } else {
                        Result.success(null)
                    }
                } else {
                    Result.success(null)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun updateScannerInfo(memberNumber: String, scannerNumber: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val currentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val update = MemberUpdate(
                    scannerNumber = scannerNumber,
                    scannerDate = currentDateTime
                )
                client.from(TABLE_NAME)
                    .update(update) {
                        filter {
                            eq("mno", memberNumber)
                        }
                    }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getMembersByGroupQrId(qrId: String): Result<BulkGroup?> {
        return withContext(Dispatchers.IO) {
            try {
                val rows = client.from(TABLE_NAME)
                    .select(columns = Columns.list(
                        "sno",
                        "mno",
                        "edpno",
                        "name",
                        "station",
                        "scan_date",
                        "sweet_issuer_mobile",
                        "group_id",
                        "group_qr_id"
                    )) {
                        filter { eq("group_qr_id", qrId) }
                    }
                    .decodeList<Member>()

                val first = rows.firstOrNull() ?: return@withContext Result.success(null)
                val groupId = first.groupId ?: return@withContext Result.success(null)

                val members = rows
                    .groupBy { it.memberNumber }
                    .values
                    .map { records ->
                        val profile = records.first()
                        val scannedRecord = records.firstOrNull { !it.scannerDate.isNullOrBlank() }
                        if (scannedRecord == null) profile else profile.copy(
                            scannerDate = scannedRecord.scannerDate,
                            scannerNumber = scannedRecord.scannerNumber
                        )
                    }
                    .sortedWith(
                        compareBy<Member> { it.memberNumber?.toIntOrNull() ?: Int.MAX_VALUE }
                            .thenBy { it.memberNumber.orEmpty() }
                    )

                Result.success(BulkGroup(groupId = groupId, qrId = qrId, members = members))
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching group: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateGroupScanInfo(
        groupId: String,
        memberNumbers: List<String>,
        scannerNumber: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                require(memberNumbers.isNotEmpty()) { "Select at least one member" }
                val currentDateTime = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())
                val update = MemberUpdate(
                    scannerNumber = scannerNumber,
                    scannerDate = currentDateTime
                )

                client.from(TABLE_NAME)
                    .update(update) {
                        filter {
                            eq("group_id", groupId)
                            isIn("mno", memberNumbers.distinct())
                        }
                    }

                Result.success(currentDateTime)
            } catch (e: Exception) {
                Log.e(TAG, "Error issuing group members: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    // --- UPDATED: Fixed Nesting Structure ---
    suspend fun getTotalReport(): Result<List<Member>> {
        return withContext(Dispatchers.IO) {
            try {
                val rows = client.from(TABLE_NAME)
                    .select(columns = Columns.list(
                        "mno",
                        "issue_date",
                        "token_issuer",
                        "scan_date",
                        "sweet_issuer_mobile"
                    )) {
                        // FIX: 'or' must be inside 'filter { }'
                        filter {
                            or {
                                // Checking for date > 1900-01-01 effectively checks IS NOT NULL
                                gte("issue_date", "1800-01-01")
                                gte("scan_date", "1800-01-01")
                            }
                        }
                    }
                    .decodeList<Member>()

                Result.success(rows)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching report: ${e.message}", e)
                Result.failure(e)
            }
        }
    }
}
