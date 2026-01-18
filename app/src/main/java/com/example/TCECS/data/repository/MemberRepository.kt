package com.example.TCECS.data.repository

import android.util.Log
import com.example.TCECS.data.models.DividendEntry
import com.example.TCECS.data.models.Member
import com.example.TCECS.data.models.MemberUpdate
import com.example.TCECS.data.remote.SupabaseClient
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

    private val TABLE_NAME = "financial_records"

    suspend fun searchMemberByNumber(searchQuery: String): Result<Member?> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Searching for member with query: $searchQuery")

                // Determine search field based on query length
                val searchField = if (searchQuery.length == 8) "edpno" else "mno"
                Log.d(TAG, "Searching by field: $searchField")

                val rows = client.from(TABLE_NAME)
                    .select() {
                        filter {
                            eq(searchField, searchQuery)
                        }
                    }
                    .decodeList<DividendEntry>()

                if (rows.isNotEmpty()) {
                    val profileData = client.from(TABLE_NAME)
                        .select() {
                            filter {
                                eq(searchField, searchQuery)
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

    suspend fun searchMembersByLast5Digits(last5Digits: String): Result<List<Member>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Searching for members with last 5 digits: $last5Digits")

                // Get all unique employee numbers from the table
                val allMembers = client.from(TABLE_NAME)
                    .select(Columns.list("edpno", "mno", "name")) {
                        filter {
                            // Only get records where edpno is not null/empty
                            neq("edpno", "")
                        }
                    }
                    .decodeList<Map<String, String>>()

                // Filter by last 5 digits on client side
                val matchingEdpNos = allMembers
                    .mapNotNull { it["edpno"] }
                    .filter { it.takeLast(5) == last5Digits }
                    .distinct()

                Log.d(TAG, "Found ${matchingEdpNos.size} matching employee numbers: $matchingEdpNos")

                if (matchingEdpNos.isEmpty()) {
                    return@withContext Result.success(emptyList())
                }

                // Fetch full member data for each matching employee number
                val members = matchingEdpNos.mapNotNull { edpno ->
                    try {
                        val rows = client.from(TABLE_NAME)
                            .select() {
                                filter {
                                    eq("edpno", edpno)
                                }
                            }
                            .decodeList<DividendEntry>()

                        if (rows.isNotEmpty()) {
                            val profileData = client.from(TABLE_NAME)
                                .select() {
                                    filter {
                                        eq("edpno", edpno)
                                    }
                                    limit(1)
                                }
                                .decodeList<Member>()
                                .firstOrNull()

                            profileData?.copy(dividend = rows)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching member with edpno $edpno: ${e.message}", e)
                        null
                    }
                }

                Result.success(members)
            } catch (e: Exception) {
                Log.e(TAG, "Error searching by last 5 digits: ${e.message}", e)
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
                // Determine search field based on qrCode length
                val searchField = if (qrCode.length == 8) "edpno" else "mno"

                val rows = client.from(TABLE_NAME)
                    .select() {
                        filter {
                            eq(searchField, qrCode)
                        }
                    }
                    .decodeList<DividendEntry>()

                if (rows.isNotEmpty()) {
                    val profileData = client.from(TABLE_NAME)
                        .select() {
                            filter {
                                eq(searchField, qrCode)
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