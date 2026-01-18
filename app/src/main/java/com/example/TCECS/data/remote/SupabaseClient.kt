package com.example.TCECS.data.remote

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

object SupabaseClient {
    // TODO: Replace with your actual Supabase URL and API Key
    private const val SUPABASE_URL = "https://ybpmaombndxxrlyamrpg.supabase.co"
    private const val SUPABASE_API_KEY = "sb_publishable_eJIsRKcqUHLizKr702BpXw_4PkO2kcI"

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_API_KEY
        ) {
            install(Postgrest)
            install(Realtime)

            // Configure JSON serialization to ignore unknown keys
            defaultSerializer = KotlinXSerializer(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                }
            )
        }
    }
}

