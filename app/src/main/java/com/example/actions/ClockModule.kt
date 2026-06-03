package com.example.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

object ClockModule {

    /**
     * Sets an alarm using the standard Android AlarmClock API.
     * Extracts time heuristics from Hebrew speech text.
     */
    fun setAlarm(inputText: String, context: Context): String {
        val text = inputText.lowercase().trim()
        val (hour, minute) = parseAlarmTime(text)

        if (hour == null) {
            // If we couldn't parse a specific time, just open the clock app as fallback
            return openClock(context)
        }

        val actualMinute = minute ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, actualMinute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "כוון על ידי עוזר חכם")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            context.startActivity(intent)
            val timeStr = String.format("%02d:%02d", hour, actualMinute)
            "מכוון שעון מעורר לשעה $timeStr"
        } catch (e: Exception) {
            Log.e("ClockModule", "Failed to set alarm", e)
            openClock(context)
        }
    }

    /**
     * Sets a timer using standard Android AlarmClock API.
     * Parses duration/length in minutes or seconds from Hebrew speech.
     */
    fun setTimer(inputText: String, context: Context): String {
        val text = inputText.lowercase().trim()
        val seconds = parseTimerSeconds(text) ?: 300 // default to 5 minutes if not clear

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "כוון על ידי עוזר חכם")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            context.startActivity(intent)
            val minutes = seconds / 60
            if (minutes > 0) {
                "כוון טיימר ל-$minutes דקות"
            } else {
                "כוון טיימר ל-$seconds שניות"
            }
        } catch (e: Exception) {
            Log.e("ClockModule", "Failed to set timer", e)
            openClock(context)
        }
    }

    /**
     * Safely opens the system Clock App/show alarms.
     */
    fun openClock(context: Context): String {
        // Option 1: SHOW_ALARMS
        val showAlarmsIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(showAlarmsIntent)
            "פותח את השעון המערכתי"
        } catch (e: Exception) {
            // Option 2: Try standard category clock launcher
            try {
                val clockIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName("com.android.deskclock", "com.android.deskclock.DeskClock")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(clockIntent)
                "פותח את השעון"
            } catch (ex: Exception) {
                // Option 3: general settings/fallback
                try {
                    val fallbackIntent = Intent(android.provider.Settings.ACTION_DATE_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallbackIntent)
                    "פותח הגדרות זמן ושעון"
                } catch (exc: Exception) {
                    "לא ניתן לפתוח את אפליקציית השעון במכשיר"
                }
            }
        }
    }

    /**
     * Parses hours & minutes from text.
     * Supports formats like: "8:30", "08:00", "7 וחצי", "רבע לתשע", or "עשר"
     */
    private fun parseAlarmTime(text: String): Pair<Int?, Int?> {
        // 1. Look for explicit double digits like 08:30 or 14:00
        val timeRegex = Regex("(\\d{1,2}):(\\d{2})")
        val match = timeRegex.find(text)
        if (match != null) {
            val h = match.groupValues[1].toIntOrNull()
            val m = match.groupValues[2].toIntOrNull()
            if (h in 0..23 && m in 0..59) {
                return Pair(h, m)
            }
        }

        // 2. Look for single digits like "ב-8" or "בשעה 7" or "שעה 9"
        val singleDigitRegex = Regex("(?:שעה|לשעה|בשעה|ב|ל)\\s*(\\d{1,2})")
        val singleMatch = singleDigitRegex.find(text)
        var parsedHour: Int? = null
        if (singleMatch != null) {
            val h = singleMatch.groupValues[1].toIntOrNull()
            if (h in 0..23) {
                parsedHour = h
            }
        }

        // Heuristic Hebrew written words for hours (e.g., "שבע", "שמונה")
        if (parsedHour == null) {
            val keywordMap = mapOf(
                "אחת" to 1, "אחד" to 1,
                "שתיים" to 2, "שתים" to 2, "שנים" to 2, "שני" to 2,
                "שלוש" to 3, "שלש" to 3,
                "ארבע" to 4, "חמש" to 5,
                "שש" to 6, "שבע" to 7, "שמונה" to 8, "שמנה" to 8,
                "תשע" to 9, "עשר" to 10,
                "אחת עשרה" to 11, "אחד עשר" to 11,
                "שתים עשרה" to 12, "שנים עשר" to 12
            )
            for ((word, valNum) in keywordMap) {
                if (text.contains(word)) {
                    parsedHour = valNum
                    break
                }
            }
        }

        if (parsedHour == null) {
            return Pair(null, null)
        }

        // Adjust for AM/PM if it is small and context suggests afternoon (e.g. "בצהריים", "בערב")
        var hour = parsedHour
        if (hour in 1..11 && (text.contains("צהריים") || text.contains("בערב") || text.contains("בלילה") || text.contains("אחהצ"))) {
            hour += 12
        }

        // Heuristic minute modifiers like "וחצי", "ורבע"
        var minute = 0
        if (text.contains("חצי") || text.contains("וחצי")) {
            minute = 30
        } else if (text.contains("רבע") || text.contains("ורבע")) {
            minute = 15
        } else if (text.contains("עשרים") || text.contains("ועשרים")) {
            minute = 20
        } else if (text.contains("ארבעים") || text.contains("וארבעים")) {
            minute = 40
        } else {
            // See if there's any other digit after the hour
            val minutesRegex = Regex("(?:\\d{1,2})\\s*(?:ועוד|ו|\\s)\\s*(\\d{1,2})")
            val minMatch = minutesRegex.find(text)
            if (minMatch != null) {
                val m = minMatch.groupValues[1].toIntOrNull()
                if (m in 1..59) {
                    minute = m!!
                }
            }
        }

        return Pair(hour, minute)
    }

    /**
     * Parse seconds from "טיימר של 5 דקות" etc.
     */
    private fun parseTimerSeconds(text: String): Int? {
        // Find digits
        val digitRegex = Regex("(\\d+)")
        val match = digitRegex.find(text)
        val num = match?.groupValues[1]?.toIntOrNull() ?: return null

        return if (text.contains("שניה") || text.contains("שניות")) {
            num
        } else {
            // Default is minutes
            num * 60
        }
    }
}
