package com.example.Chennai_Coop.data.remote

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

object SupabaseClient {
    // Connected CCOCS Supabase project. This is a public client key, not a secret/service-role key.
    private const val SUPABASE_URL = "https://ytkyjilhyrkklnuwdzuj.supabase.co"
    private const val SUPABASE_API_KEY = "sb_publishable_YhRzvg60s5gTJR4xpyXO5w_f6CjdcU4"

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

