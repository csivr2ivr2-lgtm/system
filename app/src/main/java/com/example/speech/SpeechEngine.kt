package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

enum class SpeechState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

class SpeechEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false

    interface SpeechListener {
        fun onStateChanged(state: SpeechState)
        fun onSpeechResult(text: String)
        fun onPartialSpeechResult(text: String)
        fun onError(errorMsg: String)
    }

    private var listener: SpeechListener? = null

    init {
        mainHandler.post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                } else {
                    Log.e("SpeechEngine", "Speech Recognition is NOT available on this system.")
                }
            } catch (e: Exception) {
                Log.e("SpeechEngine", "Error making speech recognizer", e)
            }
        }
    }

    fun setSpeechListener(listener: SpeechListener) {
        this.listener = listener
    }

    fun start(languageCode: String = "he-IL") {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    if (SpeechRecognizer.isRecognitionAvailable(context)) {
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    } else {
                        listener?.onError("מנוע זיהוי דיבור אינו זמין במכשיר זה")
                        listener?.onStateChanged(SpeechState.ERROR)
                        return@post
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("SpeechEngine", "Ready for speech")
                        isListening = true
                        listener?.onStateChanged(SpeechState.LISTENING)
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d("SpeechEngine", "Beginning of speech")
                        listener?.onStateChanged(SpeechState.LISTENING)
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Can be used for audio level visualizations
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d("SpeechEngine", "End of speech")
                        isListening = false
                        listener?.onStateChanged(SpeechState.PROCESSING)
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "שגיאת הקלטת שמע"
                            SpeechRecognizer.ERROR_CLIENT -> "שגיאת לקוח זיהוי דיבור"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "חסרות הרשאות הקלטה"
                            SpeechRecognizer.ERROR_NETWORK -> "שגיאת רשת"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "פג זמן קשר לרשת"
                            SpeechRecognizer.ERROR_NO_MATCH -> "לא נמצאה התאמה, נסה שוב"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "מנוע זיהוי דיבור עסוק"
                            SpeechRecognizer.ERROR_SERVER -> "שגיאת שרת מנוע"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "לא נשמע דיבור, פג זמן המתנה"
                            else -> "שגיאה לא ידועה (קוד: $error)"
                        }
                        Log.d("SpeechEngine", "Speech recognizer error: $errorMsg ($error)")
                        listener?.onError(errorMsg)
                        listener?.onStateChanged(SpeechState.ERROR)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Log.d("SpeechEngine", "Results: $text")
                        listener?.onSpeechResult(text)
                        listener?.onStateChanged(SpeechState.IDLE)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            listener?.onPartialSpeechResult(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e("SpeechEngine", "Failed to start listening", e)
                listener?.onError("שגיאה בהפעלת מיקרופון: ${e.localizedMessage}")
                listener?.onStateChanged(SpeechState.ERROR)
            }
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                isListening = false
                listener?.onStateChanged(SpeechState.IDLE)
            } catch (e: Exception) {
                Log.e("SpeechEngine", "Failed to stop listening", e)
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                isListening = false
                listener?.onStateChanged(SpeechState.IDLE)
            } catch (e: Exception) {
                Log.e("SpeechEngine", "Failed to cancel listening", e)
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e("SpeechEngine", "Failed to destroy SpeechRecognizer", e)
            }
        }
    }
}
