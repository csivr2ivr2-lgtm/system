package com.example

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.example.brain.CommandEngine
import com.example.brain.CommandResult
import com.example.speech.SpeechEngine
import com.example.speech.SpeechState
import com.example.voice.VoiceResponse

class JarvisController(private val context: Context) : SpeechEngine.SpeechListener {

    private val speechEngine = SpeechEngine(context)

    // Observable states for Jetpack Compose UI
    val state = mutableStateOf(SpeechState.IDLE)
    val transcriptionText = mutableStateOf("")
    val activeResult = mutableStateOf<CommandResult?>(null)
    val commandHistory = mutableStateListOf<CommandResult>()
    val errorMessage = mutableStateOf<String?>(null)

    init {
        speechEngine.setSpeechListener(this)
        VoiceResponse.init(context)
        Log.d("JarvisController", "Initialized and TTS engine started.")
    }

    /**
     * Toggles speech recognition listening.
     */
    fun toggleListening() {
        errorMessage.value = null
        if (state.value == SpeechState.LISTENING) {
            speechEngine.stop()
        } else {
            transcriptionText.value = ""
            speechEngine.start("he-IL")
        }
    }

    /**
     * Executes a text command immediately (from manual input or quick command chip click).
     */
    fun executeTextCommand(commandText: String) {
        if (commandText.isBlank()) return
        
        errorMessage.value = null
        state.value = SpeechState.PROCESSING
        transcriptionText.value = commandText
        
        // Execute command
        val result = CommandEngine.handle(commandText, context)
        
        activeResult.value = result
        commandHistory.add(0, result) // Add to top of history
        state.value = SpeechState.IDLE
    }

    fun stop() {
        speechEngine.stop()
    }

    fun destroy() {
        speechEngine.destroy()
        VoiceResponse.shutdown()
        com.example.actions.SpotifyRemoteControl.disconnect()
    }

    // --- SpeechEngine.SpeechListener Overrides ---

    override fun onStateChanged(newState: SpeechState) {
        state.value = newState
    }

    override fun onSpeechResult(text: String) {
        transcriptionText.value = text
        if (text.isNotEmpty()) {
            val result = CommandEngine.handle(text, context)
            activeResult.value = result
            commandHistory.add(0, result)
        } else {
            errorMessage.value = "לא שמעתי פקודה ברורה."
        }
    }

    override fun onPartialSpeechResult(text: String) {
        transcriptionText.value = text
    }

    override fun onError(errorMsg: String) {
        errorMessage.value = errorMsg
        state.value = SpeechState.ERROR
    }
}
