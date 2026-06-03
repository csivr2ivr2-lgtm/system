package com.example.service

import android.content.Intent
import android.speech.RecognitionService

/**
 * A standard, crash-safe implementation of RecognitionService to satisfy system configuration requirements.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Can be empty as speech engine handles active recording on demand
    }

    override fun onCancel(listener: Callback?) {
        // Can be empty
    }

    override fun onStopListening(listener: Callback?) {
        // Can be empty
    }
}
