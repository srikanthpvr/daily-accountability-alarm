package com.example.accountability.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.accountability.model.TaskItem
import com.example.accountability.model.TaskStatus
import com.example.accountability.model.TaskType
import java.util.Locale

class TurnByTurnVoiceManager(
    private val context: Context,
    private val listener: VoiceInteractionListener
) : TextToSpeech.OnInitListener {

    interface VoiceInteractionListener {
        fun onTtsSpeaking(taskIndex: Int, questionText: String)
        fun onSttListening(taskIndex: Int)
        fun onTaskResult(taskIndex: Int, status: TaskStatus, spokenText: String)
        fun onAllTasksCompleted(tasks: List<TaskItem>)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "TurnByTurnVoiceManager"
        private const val UTTERANCE_PREFIX = "task_utterance_"
    }

    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val tasks = listOf(
        TaskItem(TaskType.SOCKS),
        TaskItem(TaskType.TOWEL),
        TaskItem(TaskType.BOOKS)
    )

    var currentTaskIndex = 0
        private set

    private var isTtsReady = false
    private var isListening = false

    init {
        textToSpeech = TextToSpeech(context.applicationContext, this)
        initSpeechRecognizer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS: US English not supported, using default locale")
                textToSpeech?.setLanguage(Locale.getDefault())
            }
            textToSpeech?.setSpeechRate(0.95f)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started speaking: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS finished speaking: $utteranceId")
                    mainHandler.post {
                        startListeningForCurrentTask()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS utterance error: $utteranceId")
                    mainHandler.post {
                        startListeningForCurrentTask()
                    }
                }
            })
            isTtsReady = true
            Log.i(TAG, "TextToSpeech initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize TextToSpeech, status: $status")
            listener.onError("Failed to initialize Text-to-Speech")
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            Log.i(TAG, "SpeechRecognizer initialized successfully")
        } else {
            Log.w(TAG, "Speech recognition is not available on this device")
            listener.onError("Speech recognition not available on device")
        }
    }

    /**
     * Begins the Turn-by-Turn conversational dialogue from Task 0.
     */
    fun startDialogue() {
        currentTaskIndex = 0
        askCurrentTask()
    }

    /**
     * Prompts the question for the current task.
     */
    fun askCurrentTask() {
        if (currentTaskIndex >= tasks.size) {
            listener.onAllTasksCompleted(tasks)
            return
        }

        val task = tasks[currentTaskIndex]
        val question = task.type.question
        listener.onTtsSpeaking(currentTaskIndex, question)

        if (isTtsReady && textToSpeech != null) {
            val utteranceId = "$UTTERANCE_PREFIX$currentTaskIndex"
            textToSpeech?.speak(question, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            // If TTS is still warming up, wait 500ms and retry
            mainHandler.postDelayed({ askCurrentTask() }, 500)
        }
    }

    private fun startListeningForCurrentTask() {
        if (isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            isListening = true
            listener.onSttListening(currentTaskIndex)
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SpeechRecognizer", e)
            isListening = false
            listener.onError("Could not start microphone: ${e.message}")
        }
    }

    /**
     * Manual override / Touch fallback if user chooses to tap Yes or No directly on screen.
     */
    fun setManualAnswer(status: TaskStatus) {
        if (currentTaskIndex < tasks.size) {
            // Cancel current TTS or STT
            stopVoice()
            val task = tasks[currentTaskIndex]
            task.status = status
            task.transcript = "[Manual Selection: ${status.name}]"
            listener.onTaskResult(currentTaskIndex, status, task.transcript)

            advanceToNextTask()
        }
    }

    private fun advanceToNextTask() {
        currentTaskIndex++
        if (currentTaskIndex < tasks.size) {
            mainHandler.postDelayed({
                askCurrentTask()
            }, 600)
        } else {
            listener.onAllTasksCompleted(tasks)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "STT Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "STT User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "STT End of speech")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Still waiting for your speech..."
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording issue"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    else -> "Speech recognition waiting... (code: $error)"
                }
                Log.w(TAG, "STT Waiting: $errorMsg ($error)")

                // App MUST wait to collect response from the end-user:
                // Do NOT mark as done or advance. Keep listening until user provides a response.
                if (currentTaskIndex < tasks.size) {
                    listener.onTtsSpeaking(currentTaskIndex, "Waiting for your answer... Say 'Yes' or 'No'")
                    mainHandler.postDelayed({
                        startListeningForCurrentTask()
                    }, 800)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull().orEmpty()
                Log.i(TAG, "STT Transcript received for task $currentTaskIndex: '$spokenText'")

                if (currentTaskIndex < tasks.size) {
                    val status = TranscriptParser.parseTurnResponse(spokenText)

                    if (status == TaskStatus.YES || status == TaskStatus.NO) {
                        // Valid response collected!
                        val task = tasks[currentTaskIndex]
                        task.status = status
                        task.transcript = spokenText

                        listener.onTaskResult(currentTaskIndex, status, spokenText)

                        // Move to the next task question
                        advanceToNextTask()
                    } else {
                        // Unclear spoken response - Prompt user again and continue waiting
                        Log.i(TAG, "Spoken response '$spokenText' unclear. Continuing to wait for Yes or No.")
                        listener.onTtsSpeaking(currentTaskIndex, "Heard '$spokenText'. Please say Yes or No.")
                        mainHandler.postDelayed({
                            startListeningForCurrentTask()
                        }, 1200)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun stopVoice() {
        try {
            if (isListening) {
                speechRecognizer?.stopListening()
                isListening = false
            }
            if (textToSpeech?.isSpeaking == true) {
                textToSpeech?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping voice components", e)
        }
    }

    fun destroy() {
        stopVoice()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying voice components", e)
        }
    }
}
