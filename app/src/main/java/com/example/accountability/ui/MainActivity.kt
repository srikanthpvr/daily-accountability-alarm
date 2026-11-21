package com.example.accountability.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.accountability.data.local.AccountabilityEntity
import com.example.accountability.data.remote.GoogleSheetsService
import com.example.accountability.data.repository.AccountabilityRepository
import com.example.accountability.scheduler.AlarmScheduler
import com.example.accountability.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var scheduler: AlarmScheduler
    private lateinit var repository: AccountabilityRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scheduler = AlarmScheduler(this)
        repository = AccountabilityRepository(this)

        // Ensure 9 PM alarm is armed on first app launch
        scheduler.scheduleDaily9PmAlarm()

        setContent {
            AccountabilityTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    MainDashboardScreen(scheduler, repository)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    scheduler: AlarmScheduler,
    repository: AccountabilityRepository
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val sheetsService = repository.getSheetsService()

    var isAlarmEnabled by remember { mutableStateOf(true) }
    var nextAlarmTime by remember { mutableStateOf(scheduler.calculateNext9PmTrigger()) }
    var webhookUrl by remember { mutableStateOf(sheetsService.getWebhookUrl()) }
    var isConfigured by remember { mutableStateOf(sheetsService.hasValidAuthentication()) }

    var showScriptGuide by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<String?>(null) }
    var isConnectionSuccess by remember { mutableStateOf(false) }

    val records by repository.getAllRecords().collectAsState(initial = emptyList())
    val pendingCount by repository.getPendingCount().collectAsState(initial = 0)

    // Permission state
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasMicPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Daily Accountability (9 PM)",
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Alarm Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Daily 9:00 PM Alarm",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextLight
                                )
                                val dateFormat = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
                                Text(
                                    "Next: ${dateFormat.format(Date(nextAlarmTime))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentGreen
                                )
                            }
                            Switch(
                                checked = isAlarmEnabled,
                                onCheckedChange = { enabled ->
                                    isAlarmEnabled = enabled
                                    if (enabled) {
                                        nextAlarmTime = scheduler.scheduleDaily9PmAlarm()
                                        Toast.makeText(context, "Alarm armed for 9 PM", Toast.LENGTH_SHORT).show()
                                    } else {
                                        scheduler.cancelDailyAlarm()
                                        Toast.makeText(context, "Alarm disabled", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Test Alarm Button
                        Button(
                            onClick = {
                                scheduler.scheduleTestAlarm(5)
                                Toast.makeText(
                                    context,
                                    "Alarm set for 5s! Lock your phone now to test full-screen wake up.",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Alarm, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Alarm Now (5s delay)")
                        }
                    }
                }
            }

            // Permissions Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "System Permissions & Audio",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        PermissionRow(
                            title = "Microphone (STT Input)",
                            isGranted = hasMicPermission,
                            onFix = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )

                        val canExactAlarm = scheduler.canScheduleExactAlarms()
                        PermissionRow(
                            title = "Exact Alarm Timing",
                            isGranted = canExactAlarm,
                            onFix = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        )
                    }
                }
            }

            // Google Sheets Direct Connection Card (Zero Auth)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.TableChart,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Google Sheet Direct (Zero Auth)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextLight
                                )
                            }
                            Badge(
                                containerColor = if (isConfigured) AccentGreen else WarningAmber
                            ) {
                                Text(
                                    if (isConfigured) "Connected" else "URL Required",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "Connects directly to your Google Sheet without any sign-in, login passwords, or API keys.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextDim
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = webhookUrl,
                            onValueChange = {
                                webhookUrl = it
                                sheetsService.saveWebhookUrl(it)
                                isConfigured = sheetsService.hasValidAuthentication()
                            },
                            label = { Text("Google Sheet Webhook URL") },
                            placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isTestingConnection = true
                                    connectionTestResult = null
                                    coroutineScope.launch {
                                        val res = sheetsService.testConnection()
                                        isTestingConnection = false
                                        if (res.isSuccess) {
                                            isConnectionSuccess = true
                                            connectionTestResult = res.getOrNull()
                                        } else {
                                            isConnectionSuccess = false
                                            connectionTestResult = res.exceptionOrNull()?.message
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                                } else {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Test Connection")
                                }
                            }

                            OutlinedButton(
                                onClick = { showScriptGuide = !showScriptGuide },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    if (showScriptGuide) Icons.Default.ExpandLess else Icons.Default.HelpOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (showScriptGuide) "Hide Guide" else "1-Min Setup")
                            }
                        }

                        if (connectionTestResult != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = connectionTestResult ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isConnectionSuccess) AccentGreen else DangerRed
                            )
                        }

                        // Expandable 1-Minute Setup Guide
                        AnimatedVisibility(visible = showScriptGuide) {
                            Column(
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    "How to get your Webhook URL in 1 minute:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentGreen
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("1. Open your Google Sheet in browser.", style = MaterialTheme.typography.bodySmall, color = TextLight)
                                Text("2. Click Extensions → Apps Script.", style = MaterialTheme.typography.bodySmall, color = TextLight)
                                Text("3. Replace all code with this snippet:", style = MaterialTheme.typography.bodySmall, color = TextLight)

                                val scriptCode = """
function doPost(e) {
  var d = JSON.parse(e.postData.contents);
  SpreadsheetApp.getActiveSpreadsheet().getActiveSheet().appendRow([
    d.timestamp, d.socks, d.towel, d.books, d.transcript, "SUCCESS"
  ]);
  return ContentService.createTextOutput("OK");
}
function doGet(e) {
  return ContentService.createTextOutput("OK");
}
                                """.trimIndent()

                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = Color(0xFF020617),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            scriptCode,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = Color(0xFF38BDF8)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(scriptCode))
                                                Toast.makeText(context, "Script copied to clipboard!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.align(Alignment.End),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy Script", fontSize = 12.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text("4. Click Deploy → New deployment → select Web app.", style = MaterialTheme.typography.bodySmall, color = TextLight)
                                Text("5. Set 'Who has access' to Anyone, click Deploy, and copy the Web URL here!", style = MaterialTheme.typography.bodySmall, color = AccentGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // History Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Submissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )
                    if (pendingCount > 0) {
                        Badge(containerColor = WarningAmber) {
                            Text("$pendingCount Pending Sync", color = Color.White)
                        }
                    }
                }
            }

            if (records.isEmpty()) {
                item {
                    Text(
                        "No accountability records yet. Tap 'Test Alarm Now' or wait until 9 PM!",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(records) { record ->
                    HistoryItemCard(record)
                }
            }
        }
    }
}

@Composable
fun PermissionRow(title: String, isGranted: Boolean, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) AccentGreen else DangerRed,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextLight
            )
        }
        if (!isGranted) {
            TextButton(onClick = onFix) {
                Text("Grant", color = PrimaryBlue, fontWeight = FontWeight.Bold)
            }
        } else {
            Text("Active", style = MaterialTheme.typography.bodySmall, color = AccentGreen)
        }
    }
}

@Composable
fun HistoryItemCard(record: AccountabilityEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    record.timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim
                )
                Badge(
                    containerColor = if (record.isSynced) AccentGreen else WarningAmber
                ) {
                    Text(
                        if (record.isSynced) "Synced to Sheets" else "Pending Sync",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusPill("Socks", record.socksStatus)
                StatusPill("Towel", record.towelStatus)
                StatusPill("Books", record.booksStatus)
            }

            if (record.rawTranscript.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Transcript: \"${record.rawTranscript}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun StatusPill(label: String, status: String) {
    val isDone = status.equals("YES", ignoreCase = true)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isDone) Color(0xFF1E3A24) else Color(0xFF3A1E1E)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label: ",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim
            )
            Text(
                if (isDone) "DONE" else "MISSED",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (isDone) AccentGreen else DangerRed
            )
        }
    }
}
