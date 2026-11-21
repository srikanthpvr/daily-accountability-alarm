package com.example.accountability.data.remote

import android.content.Context
import android.util.Log
import com.example.accountability.data.local.AccountabilityEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class ConnectionMode {
    ZERO_AUTH_WEBHOOK,   // 0 Auth: Direct Google Apps Script Webhook URL (Default & Recommended)
    SERVICE_ACCOUNT      // Optional fallback: Service Account JSON
}

class GoogleSheetsService(private val context: Context) {

    companion object {
        private const val TAG = "GoogleSheetsService"
        private const val PREFS_NAME = "accountability_settings"
        
        // Zero-Auth Webhook Key
        private const val KEY_WEBHOOK_URL = "sheet_webhook_url"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        
        // Service Account Key (Optional Fallback)
        private const val KEY_SPREADSHEET_ID = "spreadsheet_id"
        private const val KEY_SHEET_NAME = "sheet_name"
        private const val DEFAULT_SHEET_NAME = "Sheet1"
        const val CREDENTIALS_FILE_NAME = "service_account.json"
        private const val KEY_SERVICE_ACCOUNT_JSON = "service_account_raw_json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConnectionMode(): ConnectionMode {
        val modeStr = prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.ZERO_AUTH_WEBHOOK.name)
        return try {
            ConnectionMode.valueOf(modeStr ?: ConnectionMode.ZERO_AUTH_WEBHOOK.name)
        } catch (e: Exception) {
            ConnectionMode.ZERO_AUTH_WEBHOOK
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        prefs.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
    }

    // -----------------------------------------------------------------------------------
    // Zero-Auth Webhook Management
    // -----------------------------------------------------------------------------------

    fun saveWebhookUrl(url: String) {
        prefs.edit()
            .putString(KEY_WEBHOOK_URL, url.trim())
            .putString(KEY_CONNECTION_MODE, ConnectionMode.ZERO_AUTH_WEBHOOK.name)
            .apply()
        Log.i(TAG, "Saved Google Sheet Webhook URL: ${url.take(30)}...")
    }

    fun getWebhookUrl(): String {
        return prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
    }

    fun hasValidAuthentication(): Boolean {
        return when (getConnectionMode()) {
            ConnectionMode.ZERO_AUTH_WEBHOOK -> getWebhookUrl().isNotBlank()
            ConnectionMode.SERVICE_ACCOUNT -> hasServiceAccountCredentials() && getSpreadsheetId().isNotBlank()
        }
    }

    // -----------------------------------------------------------------------------------
    // Service Account Fallback Support
    // -----------------------------------------------------------------------------------

    fun saveSpreadsheetId(id: String) {
        prefs.edit().putString(KEY_SPREADSHEET_ID, id.trim()).apply()
    }

    fun getSpreadsheetId(): String {
        return prefs.getString(KEY_SPREADSHEET_ID, "") ?: ""
    }

    fun saveSheetName(name: String) {
        prefs.edit().putString(KEY_SHEET_NAME, name.trim()).apply()
    }

    fun getSheetName(): String {
        return prefs.getString(KEY_SHEET_NAME, DEFAULT_SHEET_NAME) ?: DEFAULT_SHEET_NAME
    }

    fun saveCredentialsJson(jsonString: String): Boolean {
        return try {
            val trimmed = jsonString.trim()
            val jsonObject = JSONObject(trimmed)
            if (!jsonObject.has("client_email") || !jsonObject.has("private_key")) {
                return false
            }
            prefs.edit()
                .putString(KEY_SERVICE_ACCOUNT_JSON, trimmed)
                .putString(KEY_CONNECTION_MODE, ConnectionMode.SERVICE_ACCOUNT.name)
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hasServiceAccountCredentials(): Boolean {
        val raw = prefs.getString(KEY_SERVICE_ACCOUNT_JSON, null)
        return !raw.isNullOrBlank()
    }

    // -----------------------------------------------------------------------------------
    // API Execution (Zero Auth Webhook & Fallback)
    // -----------------------------------------------------------------------------------

    /**
     * Tests Google Sheet connection and verifies access.
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val mode = getConnectionMode()
        if (mode == ConnectionMode.ZERO_AUTH_WEBHOOK) {
            val webhookUrl = getWebhookUrl()
            if (webhookUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Please paste your Google Sheet Webhook URL."))
            }

            try {
                // Ping the Google Sheet Webhook with a test ping
                val pingUrl = if (webhookUrl.contains("?")) "$webhookUrl&action=ping" else "$webhookUrl?action=ping"
                val request = Request.Builder()
                    .url(pingUrl)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful || response.code in 200..302) {
                    Result.success("✓ Connected to Google Sheet successfully! (HTTP ${response.code})")
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $body. Verify the Webhook URL was deployed with access set to 'Anyone'."))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Test connection failed", e)
                Result.failure(Exception("Connection failed: ${e.message}"))
            }
        } else {
            Result.success("Service account configured.")
        }
    }

    /**
     * Appends a new daily accountability row directly to the Google Sheet (Zero Auth).
     */
    suspend fun appendRecord(record: AccountabilityEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val mode = getConnectionMode()

        if (mode == ConnectionMode.ZERO_AUTH_WEBHOOK) {
            val webhookUrl = getWebhookUrl()
            if (webhookUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Google Sheet Webhook URL is not configured."))
            }

            try {
                // 1. Prepare JSON Payload
                val jsonPayload = JSONObject().apply {
                    put("timestamp", record.timestamp)
                    put("socks", record.socksStatus)
                    put("towel", record.towelStatus)
                    put("books", record.booksStatus)
                    put("transcript", record.rawTranscript)
                    put("status", "SUCCESS")
                }

                val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(requestBody)
                    .build()

                var response = client.newCall(request).execute()

                // If Google Apps Script returns 302 redirect, follow it manually to preserve POST
                if (response.isRedirect) {
                    val redirectLocation = response.header("Location")
                    if (!redirectLocation.isNullOrBlank()) {
                        val redirectedRequest = Request.Builder()
                            .url(redirectLocation)
                            .get()
                            .build()
                        response = client.newCall(redirectedRequest).execute()
                    }
                }

                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful || response.code in 200..302) {
                    Log.i(TAG, "Successfully appended row to Google Sheet via Webhook: HTTP ${response.code}")
                    Result.success(Unit)
                } else {
                    Log.e(TAG, "Webhook error (${response.code}): $bodyStr")
                    Result.failure(Exception("Google Sheet Webhook error: HTTP ${response.code} - $bodyStr"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during appendRecord to Google Sheets Webhook", e)
                Result.failure(e)
            }
        } else {
            Result.success(Unit)
        }
    }
}
