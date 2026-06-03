package com.example.actions

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.util.Log

object SpotifyRemoteControl {
    private const val TAG = "SpotifyRemoteControl"

    /**
     * Dispatch a local media key event to control playback of the active media application.
     */
    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)

            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
            Log.d(TAG, "Successfully dispatched media keycode: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed dispatching media keyevent for keycode $keyCode", e)
        }
    }

    /**
     * Pauses the music.
     */
    fun pause(context: Context): String {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        return "משהה את המוזיקה בספוטיפיי"
    }

    /**
     * Resumes the music.
     */
    fun resume(context: Context): String {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        return "ממשיך את המוזיקה בספוטיפיי"
    }

    /**
     * Skips to the next track.
     */
    fun skipNext(context: Context): String {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        return "מדלג לשיר הבא"
    }

    /**
     * Skips to the previous track.
     */
    fun skipPrevious(context: Context): String {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return "חוזר לשיר הקודם"
    }

    /**
     * Disconnect stub for compatibility.
     */
    fun disconnect() {
        Log.d(TAG, "App Remote disconnect called (using native controller now)")
    }
}
