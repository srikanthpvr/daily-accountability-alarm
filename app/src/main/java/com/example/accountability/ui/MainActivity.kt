package com.example.accountability.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.accountability.data.local.AccountabilityEntity
import com.example.accountability.data.remote.AuthMode
import com.example.accountability.data.remote.GoogleSheetsService
import com.example.accountability.data.repository.AccountabilityRepository
import com.example.accountability.scheduler.AlarmScheduler
import com.example.accountability.ui.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
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
    val coroutineScope = rememberCoroutineScope()
    val sheetsService = repository.getSheetsService()

    var isAlarmEnabled by remember { mutableStateOf(true) }
    var nextAlarmTime by remember { mutableStateOf(scheduler.calculateNext9PmTrigger()) }
    var spreadsheetId by remember { mutableStateOf(sheetsService.getSpreadsheetId()) }
    var sheetName by remember { mutableStateOf(sheetsService.getSheetName()) }
    var authMode by remember { mutableStateOf(sheetsService.getAuthMode()) }

    var hasCredentials by remember { mutableStateOf(sheetsService.hasValidAuthentication()) }
    var isGoogleSignedIn by remember { mutableStateOf(sheetsService.isGoogleSignedIn()) }
    var googleAccountEmail by remember { mutableStateOf(sheetsService.getGoogleAccountEmail()) }
    var serviceAccountEmail by remember { mutableStateOf(sheetsService.getServiceAccountEmail()) }

    // Dialog state
    var showServiceAccountDialog by remember { mutableStateOf(false) }
    var showOAuthDialog by remember { mutableStateOf(false) }
    var credentialsInput by remember { mutableStateOf("") }
    var oauthClientIdInput by remember { mutableStateOf("") }
    var oauthClientSecretInput by remember { mutableStateOf("") }
    var oauthRefreshTokenInput by remember { mutableStateOf("") }

    // Test connection state
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

    // Google Sign-In setup
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(GoogleSheetsService.SCOPE_SPREADSHEETS))
            .build()
    }
    val googleSignInClient = remember {
        GoogleSignIn.getClient(context, gso)
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                val email = account.email ?: ""
                val name = account.displayName
                sheetsService.saveGoogleAccount(email, name)
                sheetsService.setAuthMode(AuthMode.GOOGLE_SIGN_IN)
                authMode = AuthMode.GOOGLE_SIGN_IN
                isGoogleSignedIn = true
                googleAccountEmail = email
                hasCredentials = true
                Toast.makeText(context, "Signed in as $email! Daily sync ready.", Toast.LENGTH_LONG).show()
            }
        } catch (e: ApiException) {
            Log.e("MainActivity", "Google Sign-In failed code: ${e.statusCode}", e)
            val msg = when (e.statusCode) {
                10 -> "Google Play Services configuration (code 10). If unlinked, you can also use Service Account or OAuth tab."
                12500 -> "Sign-in cancelled."
                else -> "Sign-in failed (code ${e.statusCode}). Please try again."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

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

            // Google Sheets Integration Card
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
                            Text(
                                "Google Sheets API Integration",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextLight
                            )
                            Badge(
                                containerColor = if (hasCredentials) AccentGreen else WarningAmber
                            ) {
                                Text(
                                    if (hasCredentials) "Authenticated" else "Auth Required",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Auth Mode Selector Tabs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = authMode == AuthMode.GOOGLE_SIGN_IN,
                                onClick = {
                                    authMode = AuthMode.GOOGLE_SIGN_IN
                                    sheetsService.setAuthMode(AuthMode.GOOGLE_SIGN_IN)
                                    hasCredentials = sheetsService.hasValidAuthentication()
                                },
                                label = { Text("Google Account (1-Tap)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = authMode == AuthMode.SERVICE_ACCOUNT,
                                onClick = {
                                    authMode = AuthMode.SERVICE_ACCOUNT
                                    sheetsService.setAuthMode(AuthMode.SERVICE_ACCOUNT)
                                    hasCredentials = sheetsService.hasValidAuthentication()
                                },
                                label = { Text("Service Account", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = authMode == AuthMode.OAUTH_REFRESH_TOKEN,
                                onClick = {
                                    authMode = AuthMode.OAUTH_REFRESH_TOKEN
                                    sheetsService.setAuthMode(AuthMode.OAUTH_REFRESH_TOKEN)
                                    hasCredentials = sheetsService.hasValidAuthentication()
                                },
                                label = { Text("OAuth (Manual)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Render Section Based on Auth Mode
                        when (authMode) {
                            AuthMode.GOOGLE_SIGN_IN -> {
                                if (isGoogleSignedIn && !googleAccountEmail.isNullOrBlank()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2D24)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = AccentGreen,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        googleAccountEmail ?: "Connected",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextLight
                                                    )
                                                }
                                                TextButton(
                                                    onClick = {
                                                        sheetsService.signOutGoogle {
                                                            isGoogleSignedIn = false
                                                            googleAccountEmail = null
                                                            hasCredentials = sheetsService.hasValidAuthentication()
                                                        }
                                                    }
                                                ) {
                                                    Text("Sign Out", fontSize = 12.sp, color = DangerRed)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "1-time authorization active. Daily 9 PM updates will sync automatically to your Google Sheet without prompting you again.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextDim
                                            )
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "Sign in once with your Google account. No API keys or JSON files required:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextDim
                                        )
                                        Button(
                                            onClick = {
                                                googleSignInLauncher.launch(googleSignInClient.signInIntent)
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                                        ) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                "Sign In with Google Account",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                        }
                                    }
                                }
                            }

                            AuthMode.SERVICE_ACCOUNT -> {
                                if (serviceAccountEmail != null) {
                                    Text(
                                        "Active Key: $serviceAccountEmail",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AccentGreen,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                OutlinedButton(
                                    onClick = { showServiceAccountDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (hasCredentials) "Replace Key (JSON)" else "Paste Service Account JSON")
                                }
                            }

                            AuthMode.OAUTH_REFRESH_TOKEN -> {
                                OutlinedButton(
                                    onClick = { showOAuthDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Configure Client ID & Refresh Token")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Spreadsheet Settings
                        OutlinedTextField(
                            value = spreadsheetId,
                            onValueChange = {
                                spreadsheetId = it
                                sheetsService.saveSpreadsheetId(it)
                            },
                            label = { Text("Google Spreadsheet ID") },
                            placeholder = { Text("e.g. 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = sheetName,
                            onValueChange = {
                                sheetName = it
                                sheetsService.saveSheetName(it)
                            },
                            label = { Text("Sheet Tab Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Test Connection Button
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
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Connection & Access")
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

    // Service Account JSON Dialog
    if (showServiceAccountDialog) {
        AlertDialog(
            onDismissRequest = { showServiceAccountDialog = false },
            title = { Text("Service Account Key (JSON)") },
            text = {
                Column {
                    Text(
                        "Paste your Google Cloud Service Account JSON credentials below. It will be securely persisted on this device:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = credentialsInput,
                        onValueChange = { credentialsInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        placeholder = { Text("{\n  \"type\": \"service_account\",\n  \"client_email\": \"...\"\n}") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (credentialsInput.isNotBlank()) {
                            val ok = sheetsService.saveCredentialsJson(credentialsInput)
                            if (ok) {
                                hasCredentials = sheetsService.hasValidAuthentication()
                                serviceAccountEmail = sheetsService.getServiceAccountEmail()
                                showServiceAccountDialog = false
                                Toast.makeText(context, "Service Account saved permanently!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Invalid Service Account JSON. Ensure it has client_email and private_key.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Save & Persist")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServiceAccountDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // OAuth 2.0 Configuration Dialog
    if (showOAuthDialog) {
        AlertDialog(
            onDismissRequest = { showOAuthDialog = false },
            title = { Text("OAuth 2.0 (1-Time Authorization)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter your OAuth credentials and Refresh Token. Once configured, access persists indefinitely and auto-refreshes daily at 9 PM without user prompts:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim
                    )
                    OutlinedTextField(
                        value = oauthClientIdInput,
                        onValueChange = { oauthClientIdInput = it },
                        label = { Text("OAuth Client ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = oauthClientSecretInput,
                        onValueChange = { oauthClientSecretInput = it },
                        label = { Text("Client Secret (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = oauthRefreshTokenInput,
                        onValueChange = { oauthRefreshTokenInput = it },
                        label = { Text("OAuth Refresh Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (oauthRefreshTokenInput.isNotBlank()) {
                            sheetsService.saveOAuthCredentials(
                                clientId = oauthClientIdInput,
                                clientSecret = oauthClientSecretInput,
                                refreshToken = oauthRefreshTokenInput
                            )
                            hasCredentials = sheetsService.hasValidAuthentication()
                            showOAuthDialog = false
                            Toast.makeText(context, "OAuth credentials saved. Auto-refresh enabled!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save & Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOAuthDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
