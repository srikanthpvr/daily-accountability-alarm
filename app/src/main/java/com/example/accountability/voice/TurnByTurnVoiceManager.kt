package com.example.accountability.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
        fun onAudioVolumeChanged(rmsdB: Float)
        fun onTaskResult(taskIndex: Int, status: TaskStatus, spokenText: String)
        fun onAllTasksCompleted(tasks: List<TaskItem>)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "TurnByTurnVoiceManager"
        private const val UTTERANCE_PREFIX = "task_utterance_"
        private const val HARDWARE_SWITCH_DELAY_MS = 400L
    }

    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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
                Log.w(TAG, "TTS: US English not supported, falling back to default locale")
                textToSpeech?.setLanguage(Locale.getDefault())
            }
            textToSpeech?.setSpeechRate(0.95f)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started speaking: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS finished speaking: $utteranceId")
                    // Delay 400ms to allow Android AudioTrack to release audio hardware focus
                    mainHandler.postDelayed({
                        startListeningForCurrentTask()
                    }, HARDWARE_SWITCH_DELAY_MS)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS utterance error: $utteranceId")
                    mainHandler.postDelayed({
                        startListeningForCurrentTask()
                    }, HARDWARE_SWITCH_DELAY_MS)
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
        try {
            if (speechRecognizer != null) {
                try { speechRecognizer?.destroy() } catch (e: Exception) {}
                speechRecognizer = null
            }

            val available = SpeechRecognizer.isRecognitionAvailable(context)
            Log.i(TAG, "SpeechRecognizer.isRecognitionAvailable = $available")

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            Log.i(TAG, "SpeechRecognizer created and attached to RecognitionListener.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate SpeechRecognizer", e)
            listener.onError("Speech recognition service error: ${e.message}")
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
            // Wait 500ms if TTS is still warming up
            mainHandler.postDelayed({ askCurrentTask() }, 500)
        }
    }

    /**
     * Starts listening on the microphone for the current task response.
     */
    fun startListeningForCurrentTask() {
        if (isListening) return

        // Stop any lingering audio output before opening recording input
        try { textToSpeech?.stop() } catch (e: Exception) {}

        if (speechRecognizer == null) {
            initSpeechRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            isListening = true
            listener.onSttListening(currentTaskIndex)
            speechRecognizer?.startListening(intent)
            Log.i(TAG, "Microphone recording active for task $currentTaskIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SpeechRecognizer", e)
            isListening = false
            listener.onError("Microphone start failed: ${e.message}")
        }
    }

    /**
     * Touch fallback if user chooses to tap Yes or No directly on screen.
     */
    fun setManualAnswer(status: TaskStatus) {
        if (currentTaskIndex < tasks.size) {
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
                Log.d(TAG, "STT: Ready for speech. Microphone listening.")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "STT: User sound detected.")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Forward real-time audio energy to the UI for waveform animation
                listener.onAudioVolumeChanged(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "STT: End of speech utterance detected.")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording hardware error"
                    SpeechRecognizer.ERROR_CLIENT -> "Speech recognizer client reset"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO permission missing"
                    SpeechRecognizer.ERROR_NETWORK -> "Network issue with speech service"
                    else -> "Speech recognition event code: $error"
                }
                Log.w(TAG, "STT error: $errorMsg ($error)")

                if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_AUDIO) {
                    // Re-instantiate recognizer if client engine was corrupted
                    initSpeechRecognizer()
                }

                // Application must wait to collect response:
                // Re-prompt user and keep waiting rather than advancing prematurely
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
                Log.i(TAG, "STT Transcript for task $currentTaskIndex: '$spokenText'")

                if (currentTaskIndex < tasks.size) {
                    val status = TranscriptParser.parseTurnResponse(spokenText)

                    if (status == TaskStatus.YES || status == TaskStatus.NO) {
                        val task = tasks[currentTaskIndex]
                        task.status = status
                        task.transcript = spokenText

                        listener.onTaskResult(currentTaskIndex, status, spokenText)
                        advanceToNextTask()
                    } else {
                        // Unclear answer - prompt gently and continue waiting
                        Log.i(TAG, "Heard '$spokenText' but unclear. Continuing to wait.")
                        listener.onTtsSpeaking(currentTaskIndex, "Heard '$spokenText'. Please answer Yes or No.")
                        mainHandler.postDelayed({
                            startListeningForCurrentTask()
                        }, 1200)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    Log.d(TAG, "STT partial: '$partial'")
                }
            }

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
