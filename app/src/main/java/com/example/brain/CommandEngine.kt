package com.example.brain

import android.content.Context
import com.example.actions.CallModule
import com.example.actions.SpotifyModule
import com.example.actions.SpotifyRemoteControl
import com.example.actions.WhatsAppModule
import com.example.actions.ClockModule
import com.example.voice.VoiceResponse
import java.time.ZonedDateTime
import java.time.ZoneId

enum class CommandType {
    CALL,
    WHATSAPP,
    SPOTIFY,
    TIME,
    CLOCK,
    UNKNOWN
}

data class CommandResult(
    val type: CommandType,
    val queryArg: String,
    val feedbackText: String
)

object CommandEngine {

    /**
     * Parses the recognized speech string, runs the respective intents,
     * speaks the reply via TTS, and returns a rich state package for the UI.
     */
    fun handle(inputText: String, context: Context): CommandResult {
        val text = inputText.lowercase().trim()

        return when {
            // CALL ACTION
            text.contains("תתקשר") || text.contains("חיוג") || text.contains("תחייג") || text.contains("טלפן") -> {
                val arg = cleanPrefixes(text, listOf("תתקשר אל", "תתקשרי אל", "תתקשר ל", "תתקשרי ל", "תחייג ל", "תחייג אל", "תתקשר", "חיוג", "תחייג", "טלפן"))
                val feedback = CallModule.call(arg, context)
                VoiceResponse.speak(feedback)
                CommandResult(CommandType.CALL, arg, feedback)
            }

            // WHATSAPP ACTION
            text.contains("וואטסאפ") || text.contains("ווטסאפ") || text.contains("שלח הודעה") || text.contains("ואטסאפ") -> {
                val arg = cleanPrefixes(text, listOf("שלח וואטסאפ ל", "שלח ווטסאפ ל", "וואטסאפ אל", "וואטסאפ ל", "ווטסאפ ל", "שלח הודעה ל", "וואטסאפ", "ווטסאפ", "ואטסאפ ל", "ואטסאפ"))
                val feedback = WhatsAppModule.send(arg, context)
                VoiceResponse.speak(feedback)
                CommandResult(CommandType.WHATSAPP, arg, feedback)
            }

            // PAUSE / STOP MUSIC ACTION
            text.contains("עצור") || text.contains("עצרי") || text.contains("תעצור") || text.contains("השהה") || text.contains("תשהה") || text.contains("השהי") -> {
                val feedback = SpotifyRemoteControl.pause(context)
                VoiceResponse.speak(feedback)
                CommandResult(CommandType.SPOTIFY, "עצור", feedback)
            }

            // NEXT / SKIP NEXT ACTION
            text.contains("דלג") || text.contains("שיר הבא") || text.contains("הבא בתור") || (text.contains("הבא") && (text.contains("שיר") || text.contains("נגן"))) -> {
                val feedback = SpotifyRemoteControl.skipNext(context)
                VoiceResponse.speak(feedback)
                CommandResult(CommandType.SPOTIFY, "הבא", feedback)
            }

            // PREVIOUS / SKIP PREVIOUS ACTION
            text.contains("שיר קודם") || text.contains("הקודם") -> {
                val feedback = SpotifyRemoteControl.skipPrevious(context)
                VoiceResponse.speak(feedback)
                CommandResult(CommandType.SPOTIFY, "קודם", feedback)
            }

            // MUSIC SPOTIFY PLAY/PLAYBACK ACTION
            text.contains("נגן") || text.contains("שמיע") || text.contains("תפעיל") || text.contains("תשמיע") || text.contains("תמשיך") || text.contains("תמשיכי") -> {
                val arg = cleanPrefixes(text, listOf("נגן את השיר", "נגני את השיר", "תשמיע את השיר", "תפעיל את השיר", "נגן לי את", "נגן את", "נגני את", "תשמיע את", "תפעיל את", "נגן", "נגני", "תפעיל", "תשמיע", "תמשיך", "תמשיכי"))
                val feedback = if (arg.isBlank()) {
                    SpotifyRemoteControl.resume(context)
                } else {
                    SpotifyModule.play(arg, context)
                }
                VoiceResponse.speak(feedback)
                CommandResult(CommandType.SPOTIFY, arg, feedback)
            }

            // ALARM / TIMER / CLOCK ACTION
            text.contains("מעורר") || text.contains("טיימר") || text.contains("שעון") || text.contains("תעיר") -> {
                val feedback = when {
                    text.contains("טיימר") -> ClockModule.setTimer(text, context)
                    text.contains("מעורר") || text.contains("תעיר") -> ClockModule.setAlarm(text, context)
                    else -> ClockModule.openClock(context)
                }
                VoiceResponse.speak(feedback)
                CommandResult(CommandType.CLOCK, text, feedback)
            }

            // TIME ACTION (Israel Timezone enforced)
            text.contains("שעה") || text.contains("מה השעה") || text.contains("השעה עכשיו") -> {
                val timeSpeech = getTimeHebrew()
                VoiceResponse.speak(timeSpeech)
                CommandResult(CommandType.TIME, "", timeSpeech)
            }

            // FALLBACK
            else -> {
                val defaultError = "לא הבנתי את הפקודה: \"$inputText\". נסה לומר מחייג, וואטסאפ, נגן, כוון שעון מעורר או שעה."
                VoiceResponse.speak("לא הבנתי")
                CommandResult(CommandType.UNKNOWN, "", defaultError)
            }
        }
    }

    private fun cleanPrefixes(input: String, prefixes: List<String>): String {
        var clean = input
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.substring(prefix.length).trim()
                break
            }
        }
        // Clean leftover prepositions commonly used in Hebrew
        if (clean.startsWith("ל") && clean.length > 2 && !clean.startsWith("לוי")) {
            clean = clean.substring(1).trim()
        } else if (clean.startsWith("אל ") && clean.length > 3) {
            clean = clean.substring(3).trim()
        }
        return clean
    }

    private fun getTimeHebrew(): String {
        // Enforce accurate Israel time regardless of runtime server timezone
        val now = ZonedDateTime.now(ZoneId.of("Asia/Jerusalem"))
        val hour = now.hour
        val minute = now.minute

        val minuteStr = when (minute) {
            0 -> "בדיוק"
            15 -> "ורבע"
            30 -> "וחצי"
            else -> "$minute דקות"
        }

        return if (minute == 0 || minute == 15 || minute == 30) {
            "השעה בישראל עכשיו $hour $minuteStr"
        } else {
            "השעה בישראל עכשיו $hour ו-$minuteStr"
        }
    }
}
