package com.example.service

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.widget.Toast
import com.example.brain.CommandEngine
import com.example.speech.SpeechEngine
import com.example.speech.SpeechState
import com.example.voice.VoiceResponse

/**
 * Handles individual assist gesture occurrences by executing immediate offline speech recognition
 * and context-solving in the background, without requiring full screen UI activity takeover.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context), SpeechEngine.SpeechListener {

    private var speechEngine: SpeechEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        speechEngine = SpeechEngine(context).apply {
            setSpeechListener(this@JarvisVoiceInteractionSession)
        }
        VoiceResponse.init(context)
        Log.d("JarvisVoiceInteractionSession", "Headless assistant session active.")
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d("JarvisVoiceInteractionSession", "Voice interaction triggered. Running background recognition flow...")

        // Register speech-done callback so we finish the voice overlay session after reply speech completes
        VoiceResponse.setSpeechDoneCallback {
            mainHandler.post {
                Log.d("JarvisVoiceInteractionSession", "Speech feedback completed. Closing session.")
                finish()
            }
        }

        // Detect locale compatibility: check if Hebrew is default/supported on the device
        val systemLocale = java.util.Locale.getDefault()
        val isHebrew = systemLocale.language == "he" || systemLocale.language == "iw"
        val languageCode = if (isHebrew) "he-IL" else "en-US"

        // Notify user about assistant trigger state using the configured keyword
        val toastMessage = if (isHebrew) "הי עוזר המקשיב..." else "Hi..."
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()

        // Say 'Hi' in English if the device locale does not support Hebrew
        if (!isHebrew) {
            VoiceResponse.speak("Hi")
        }

        // Start listening
        speechEngine?.start(languageCode)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechEngine?.destroy()
        speechEngine = null
        VoiceResponse.setSpeechDoneCallback(null)
    }

    // --- SpeechEngine.SpeechListener Overrides ---

    override fun onStateChanged(newState: SpeechState) {
        Log.d("JarvisVoiceInteractionSession", "Speech state transition: $newState")
    }

    override fun onSpeechResult(text: String) {
        Log.d("JarvisVoiceInteractionSession", "Voice command text: $text")
        if (text.isNotEmpty()) {
            // Resolve action via the brain module
            CommandEngine.handle(text, context)
        } else {
            VoiceResponse.speak("לא נקלטה פקודה ברורה.")
        }
    }

    override fun onPartialSpeechResult(text: String) {
        // Can be logged or ignored for headless commands
    }

    override fun onError(errorMsg: String) {
        Log.e("JarvisVoiceInteractionSession", "Voice input error: $errorMsg")
        if (errorMsg.contains("זמן") || errorMsg.contains("timeout") || errorMsg.contains("דיבור")) {
            // Silence transition out if nothing was spoken
            finish()
        } else {
            VoiceResponse.speak("התרחשה שגיאה בזיהוי הקולי")
        }
    }
}
