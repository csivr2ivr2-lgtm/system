package com.example.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * System voice interaction service representing the default digital assistant capability.
 * Relies on the standard Android/Samsung system assistant invocation (home button long-press, gestures, hardware keys)
 * to open the Assistant Session, saving massive battery power and respecting user privacy without continuous background mic listening.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onCreate() {
        super.onCreate()
        Log.d("JarvisVoiceInteractionService", "Service created successfully.")
    }

    override fun onReady() {
        super.onReady()
        Log.d("JarvisVoiceInteractionService", "Service is ready. Awaiting native system trigger.")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d("JarvisVoiceInteractionService", "Service has been shut down.")
    }
}
