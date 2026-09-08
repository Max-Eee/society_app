package com.example.Chennai_Coop.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class EventConfig(
    val societyNameLines: List<String>,
    val meetingTitle: String,
    val meetingDate: String,
    val venue: String,
    val eventTitle: String
) {
    companion object {
        private const val ASSET_NAME = "event_config.json"
        private const val TAG = "EventConfig"

        private val fallback = EventConfig(
            societyNameLines = listOf(
                "Chennai Corporation Official",
                "Co-Operative Society Limited - 5125"
            ),
            meetingTitle = "G.B MEETING NOTICE",
            meetingDate = "08-10-2026 at 9:30 a.m",
            venue = "Conference Hall. Amma Maligai",
            eventTitle = "Sweet List 2026"
        )

        fun load(context: Context): EventConfig = runCatching {
            val json = JSONObject(
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            )
            val societyLinesJson = json.getJSONArray("society_name_lines")
            val societyLines = List(societyLinesJson.length()) { index ->
                societyLinesJson.getString(index)
            }
            EventConfig(
                societyNameLines = societyLines,
                meetingTitle = json.getString("meeting_title"),
                meetingDate = json.getString("meeting_date"),
                venue = json.getString("venue"),
                eventTitle = json.getString("event_title")
            )
        }.getOrElse { error ->
            Log.e(TAG, "Unable to load $ASSET_NAME; using fallback values", error)
            fallback
        }
    }
}
