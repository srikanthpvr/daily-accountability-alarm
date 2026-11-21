package com.example.accountability.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accountability.data.repository.AccountabilityRepository
import com.example.accountability.model.TaskItem
import com.example.accountability.model.TaskStatus
import com.example.accountability.ui.theme.*
import com.example.accountability.voice.TurnByTurnVoiceManager
import kotlinx.coroutines.launch

class FullScreenPromptActivity : ComponentActivity(), TurnByTurnVoiceManager.VoiceInteractionListener {

    private lateinit var voiceManager: TurnByTurnVoiceManager
    private lateinit var repository: AccountabilityRepository

    private val currentStepState = mutableIntStateOf(0)
    private val voiceStatusText = mutableStateOf("Initializing evening check...")
    private val isListeningState = mutableStateOf(false)
    private val isSpeakingState = mutableStateOf(false)
    private val audioVolumeNormalized = mutableFloatStateOf(0f)
    private val lastTranscriptState = mutableStateOf("")
    private val isCompletedState = mutableStateOf(false)
    private val isSubmittingState = mutableStateOf(false)
    private val submissionResultText = mutableStateOf<String?>(null)

    private val tasksState = mutableStateListOf<TaskItem>()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceStatusText.value = "Microphone permitted. Starting check..."
            voiceManager.startDialogue()
        } else {
            voiceStatusText.value = "Microphone permission denied. Use buttons below."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupLockScreenWake()

        repository = AccountabilityRepository(this)
        voiceManager = TurnByTurnVoiceManager(this, this)
        tasksState.addAll(voiceManager.tasks)

        setContent {
            AccountabilityTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    FullScreenContent()
                }
            }
        }

        // Verify microphone permission before launching audio flow
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            window.decorView.postDelayed({
                voiceManager.startDialogue()
            }, 700)
        }
    }

    private fun setupLockScreenWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Composable
    private fun FullScreenContent() {
        val currentStep by currentStepState
        val statusText by voiceStatusText
        val isListening by isListeningState
        val isSpeaking by isSpeakingState
        val volumeLevel by audioVolumeNormalized
        val lastTranscript by lastTranscriptState
        val isCompleted by isCompletedState
        val isSubmitting by isSubmittingState
        val submissionResult by submissionResultText

        val animatedVolume by animateFloatAsState(targetValue = volumeLevel, label = "volume")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "9:00 PM Accountability Check",
                    style = MaterialTheme.typography.titleLarge,
                    color = PrimaryBlue,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (!isCompleted) "Task ${currentStep + 1} of 3 (Listening for answer)" else "All Tasks Reviewed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Question Card or Completion View
            if (!isCompleted && currentStep < tasksState.size) {
                val currentTask = tasksState[currentStep]

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentTask.type.title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = PrimaryBlue,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = currentTask.type.question,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextLight,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Voice status indicator badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isListening) AccentGreen.copy(alpha = 0.2f) else SurfaceDark)
                        .border(
                            1.dp,
                            if (isListening) AccentGreen else if (isSpeaking) PrimaryBlue else Color.Transparent,
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isListening) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        } else if (isSpeaking) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isListening) AccentGreen else TextLight
                        )
                    }
                }

                // Real-time Audio Level Decibel Meter (Proves Mic is Active)
                if (isListening) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.8f)) {
                        LinearProgressIndicator(
                            progress = { animatedVolume.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentGreen,
                            trackColor = SurfaceDark,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (volumeLevel > 0.15f) "🎙️ Audio Input Detected" else "Speak into microphone...",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (volumeLevel > 0.15f) AccentGreen else TextDim
                        )
                    }
                }

                if (lastTranscript.isNotBlank()) {
                    Text(
                        text = "Heard: \"$lastTranscript\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Manual Touch Fallback Buttons
                Text(
                    text = "Speak into mic or tap your answer directly:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Button(
                        onClick = { voiceManager.setManualAnswer(TaskStatus.YES) },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("YES", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Button(
                        onClick = { voiceManager.setManualAnswer(TaskStatus.NO) },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("NO", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            } else {
                // Completed Summary Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Daily Summary",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextLight,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        tasksState.forEach { task ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(task.type.title, color = TextLight, fontSize = 15.sp)
                                Badge(
                                    containerColor = when (task.status) {
                                        TaskStatus.YES -> AccentGreen
                                        TaskStatus.NO -> DangerRed
                                        TaskStatus.UNCLEAR -> WarningAmber
                                    }
                                ) {
                                    Text(
                                        task.status.name,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isSubmitting) {
                            CircularProgressIndicator(color = PrimaryBlue)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Syncing with Google Sheets...", color = TextDim, fontSize = 13.sp)
                        } else if (submissionResult != null) {
                            Text(
                                text = submissionResult ?: "",
                                color = AccentGreen,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Footer action
            if (isCompleted) {
                Button(
                    onClick = { finish() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done & Close", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { voiceManager.startListeningForCurrentTask() }) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tap to Speak")
                    }
                    TextButton(onClick = { voiceManager.askCurrentTask() }) {
                        Text("Repeat Question", color = PrimaryBlue)
                    }
                }
            }
        }
    }

    // Voice Interaction Callbacks
    override fun onTtsSpeaking(taskIndex: Int, questionText: String) {
        runOnUiThread {
            currentStepState.intValue = taskIndex
            isSpeakingState.value = true
            isListeningState.value = false
            audioVolumeNormalized.floatValue = 0f
            voiceStatusText.value = "Asking task ${taskIndex + 1}..."
        }
    }

    override fun onSttListening(taskIndex: Int) {
        runOnUiThread {
            isSpeakingState.value = false
            isListeningState.value = true
            voiceStatusText.value = "Listening... Say 'Yes' or 'No'"
        }
    }

    override fun onAudioVolumeChanged(rmsdB: Float) {
        runOnUiThread {
            // Map typical speech RMS dB (-2dB to 10dB) to 0.0 - 1.0 range
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            audioVolumeNormalized.floatValue = normalized
        }
    }

    override fun onTaskResult(taskIndex: Int, status: TaskStatus, spokenText: String) {
        runOnUiThread {
            isListeningState.value = false
            audioVolumeNormalized.floatValue = 0f
            lastTranscriptState.value = spokenText
            if (taskIndex < tasksState.size) {
                tasksState[taskIndex] = tasksState[taskIndex].copy(status = status, transcript = spokenText)
            }
        }
    }

    override fun onAllTasksCompleted(tasks: List<TaskItem>) {
        runOnUiThread {
            isSpeakingState.value = false
            isListeningState.value = false
            audioVolumeNormalized.floatValue = 0f
            isCompletedState.value = true
            submitResultsToGoogleSheets(tasks)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            voiceStatusText.value = message
            isListeningState.value = false
            audioVolumeNormalized.floatValue = 0f
        }
    }

    private fun submitResultsToGoogleSheets(tasks: List<TaskItem>) {
        isSubmittingState.value = true

        val socks = tasks.find { it.type.name == "SOCKS" }?.status?.name ?: TaskStatus.UNCLEAR.name
        val towel = tasks.find { it.type.name == "TOWEL" }?.status?.name ?: TaskStatus.UNCLEAR.name
        val books = tasks.find { it.type.name == "BOOKS" }?.status?.name ?: TaskStatus.UNCLEAR.name

        val fullTranscript = tasks.joinToString(" | ") { "${it.type.title}: ${it.transcript}" }

        lifecycleScope.launch {
            val result = repository.submitRecord(
                socksStatus = socks,
                towelStatus = towel,
                booksStatus = books,
                rawTranscript = fullTranscript
            )

            isSubmittingState.value = false
            if (result.isSuccess && result.getOrNull() == true) {
                submissionResultText.value = "✓ Appended successfully to Google Sheet!"
            } else {
                submissionResultText.value = "Saved locally. Will sync automatically when online."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.destroy()
    }
}
