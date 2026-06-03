package com.example.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object VoiceResponse {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onSpeechDoneCallback: (() -> Unit)? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale("he", "IL")
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("VoiceResponse", "Hebrew language is not supported or missing data. Falling back to default.")
                    tts?.language = Locale.getDefault()
                }
                isInitialized = true
                setupProgressListener()
            } else {
                Log.e("VoiceResponse", "TTS initialization failed status = $status")
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("VoiceResponse", "TTS playback started")
            }

            override fun onDone(utteranceId: String?) {
                Log.d("VoiceResponse", "TTS playback completed")
                onSpeechDoneCallback?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e("VoiceResponse", "TTS playback error")
                onSpeechDoneCallback?.invoke()
            }
        })
    }

    fun setSpeechDoneCallback(callback: (() -> Unit)?) {
        onSpeechDoneCallback = callback
    }

    fun speak(text: String) {
        if (!isInitialized) {
            Log.w("VoiceResponse", "TTS not initialized yet. Attempting to speak: $text")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UtteranceId")
    }

    fun shutdown() {
        try {
            tts?.shutdown()
            tts = null
            isInitialized = false
            onSpeechDoneCallback = null
        } catch (e: Exception) {
            Log.e("VoiceResponse", "Error shutting down TTS", e)
        }
    }
}
