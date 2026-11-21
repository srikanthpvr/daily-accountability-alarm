package com.example.accountability.data.remote

import android.content.Context
import android.util.Log
import com.example.accountability.data.local.AccountabilityEntity
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.TimeUnit

enum class AuthMode {
    SERVICE_ACCOUNT,
    OAUTH_REFRESH_TOKEN
}

class GoogleSheetsService(private val context: Context) {

    companion object {
        private const val TAG = "GoogleSheetsService"
        private const val SCOPE_SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets"
        private const val PREFS_NAME = "accountability_settings"
        
        // Configuration Keys
        private const val KEY_SPREADSHEET_ID = "spreadsheet_id"
        private const val KEY_SHEET_NAME = "sheet_name"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val DEFAULT_SHEET_NAME = "Sheet1"
        
        // Service Account Key
        const val CREDENTIALS_FILE_NAME = "service_account.json"
        private const val KEY_SERVICE_ACCOUNT_JSON = "service_account_raw_json"
        
        // OAuth 2.0 Keys (1-Time Authorization & Persistent Background Refresh)
        private const val KEY_OAUTH_CLIENT_ID = "oauth_client_id"
        private const val KEY_OAUTH_CLIENT_SECRET = "oauth_client_secret"
        private const val KEY_OAUTH_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_CACHED_ACCESS_TOKEN = "cached_access_token"
        private const val KEY_TOKEN_EXPIRY_EPOCH = "token_expiry_epoch"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAuthMode(): AuthMode {
        val modeStr = prefs.getString(KEY_AUTH_MODE, AuthMode.SERVICE_ACCOUNT.name)
        return try {
            AuthMode.valueOf(modeStr ?: AuthMode.SERVICE_ACCOUNT.name)
        } catch (e: Exception) {
            AuthMode.SERVICE_ACCOUNT
        }
    }

    fun setAuthMode(mode: AuthMode) {
        prefs.edit().putString(KEY_AUTH_MODE, mode.name).apply()
    }

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

    // -----------------------------------------------------------------------------------
    // 1. Service Account (Option A) Storage & Credentials
    // -----------------------------------------------------------------------------------

    fun saveCredentialsJson(jsonString: String): Boolean {
        return try {
            val trimmed = jsonString.trim()
            val jsonObject = JSONObject(trimmed)
            
            // Validate minimum required fields for Google Service Account
            if (!jsonObject.has("client_email") || !jsonObject.has("private_key")) {
                Log.e(TAG, "Invalid Service Account JSON: Missing client_email or private_key")
                return false
            }

            // Persist to internal file
            val file = File(context.filesDir, CREDENTIALS_FILE_NAME)
            file.writeText(trimmed)

            // Also persist as fallback string in SharedPreferences
            prefs.edit()
                .putString(KEY_SERVICE_ACCOUNT_JSON, trimmed)
                .putString(KEY_AUTH_MODE, AuthMode.SERVICE_ACCOUNT.name)
                .apply()

            Log.i(TAG, "Successfully persisted Service Account for: ${jsonObject.optString("client_email")}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse and save Service Account JSON", e)
            false
        }
    }

    fun getServiceAccountEmail(): String? {
        val jsonStr = getRawServiceAccountJson() ?: return null
        return try {
            JSONObject(jsonStr).optString("client_email", null)
        } catch (e: Exception) {
            null
        }
    }

    fun hasServiceAccountCredentials(): Boolean {
        val file = File(context.filesDir, CREDENTIALS_FILE_NAME)
        if (file.exists() && file.length() > 50) return true

        val raw = prefs.getString(KEY_SERVICE_ACCOUNT_JSON, null)
        if (!raw.isNullOrBlank()) return true

        return try {
            context.assets.open(CREDENTIALS_FILE_NAME).use { it.available() > 50 }
        } catch (e: Exception) {
            false
        }
    }

    private fun getRawServiceAccountJson(): String? {
        val file = File(context.filesDir, CREDENTIALS_FILE_NAME)
        if (file.exists() && file.length() > 0) {
            return file.readText()
        }

        val rawPref = prefs.getString(KEY_SERVICE_ACCOUNT_JSON, null)
        if (!rawPref.isNullOrBlank()) {
            return rawPref
        }

        return try {
            context.assets.open(CREDENTIALS_FILE_NAME).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun openCredentialsStream(): InputStream? {
        val raw = getRawServiceAccountJson() ?: return null
        return ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8))
    }

    // -----------------------------------------------------------------------------------
    // 2. OAuth 2.0 with Refresh Token (1-Time Authorization & Daily Persistent Access)
    // -----------------------------------------------------------------------------------

    fun saveOAuthCredentials(clientId: String, clientSecret: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_OAUTH_CLIENT_ID, clientId.trim())
            .putString(KEY_OAUTH_CLIENT_SECRET, clientSecret.trim())
            .putString(KEY_OAUTH_REFRESH_TOKEN, refreshToken.trim())
            .remove(KEY_CACHED_ACCESS_TOKEN) // Force fresh token on next call
            .remove(KEY_TOKEN_EXPIRY_EPOCH)
            .putString(KEY_AUTH_MODE, AuthMode.OAUTH_REFRESH_TOKEN.name)
            .apply()
        Log.i(TAG, "Saved OAuth 2.0 credentials with permanent Refresh Token.")
    }

    fun hasOAuthRefreshToken(): Boolean {
        val token = prefs.getString(KEY_OAUTH_REFRESH_TOKEN, null)
        return !token.isNullOrBlank()
    }

    fun hasValidAuthentication(): Boolean {
        return when (getAuthMode()) {
            AuthMode.SERVICE_ACCOUNT -> hasServiceAccountCredentials()
            AuthMode.OAUTH_REFRESH_TOKEN -> hasOAuthRefreshToken()
        }
    }

    /**
     * Refreshes access token in the background using the stored permanent OAuth Refresh Token.
     */
    private suspend fun refreshOAuthAccessToken(): String = withContext(Dispatchers.IO) {
        val clientId = prefs.getString(KEY_OAUTH_CLIENT_ID, "") ?: ""
        val clientSecret = prefs.getString(KEY_OAUTH_CLIENT_SECRET, "") ?: ""
        val refreshToken = prefs.getString(KEY_OAUTH_REFRESH_TOKEN, "") ?: ""

        if (refreshToken.isBlank()) {
            throw IllegalStateException("OAuth Refresh Token is missing. Please authorize once.")
        }

        val formBodyBuilder = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)

        if (clientSecret.isNotBlank()) {
            formBodyBuilder.add("client_secret", clientSecret)
        }

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(formBodyBuilder.build())
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            Log.e(TAG, "OAuth Refresh Token exchange failed (${response.code}): $bodyStr")
            throw IllegalStateException("Failed to refresh OAuth token: HTTP ${response.code} - $bodyStr")
        }

        val json = JSONObject(bodyStr)
        val newAccessToken = json.getString("access_token")
        val expiresInSeconds = json.optLong("expires_in", 3600L)
        val expiryEpoch = System.currentTimeMillis() + (expiresInSeconds * 1000L) - (60 * 1000L) // 60s buffer

        // Persist cached token & expiry
        prefs.edit()
            .putString(KEY_CACHED_ACCESS_TOKEN, newAccessToken)
            .putLong(KEY_TOKEN_EXPIRY_EPOCH, expiryEpoch)
            .apply()

        Log.i(TAG, "OAuth access token refreshed successfully. Valid for $expiresInSeconds seconds.")
        newAccessToken
    }

    /**
     * Resolves valid Access Token using either:
     * - Service Account private key JWT exchange, OR
     * - OAuth 2.0 Refresh Token exchange
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val mode = getAuthMode()

        if (mode == AuthMode.OAUTH_REFRESH_TOKEN && hasOAuthRefreshToken()) {
            val cachedToken = prefs.getString(KEY_CACHED_ACCESS_TOKEN, null)
            val expiry = prefs.getLong(KEY_TOKEN_EXPIRY_EPOCH, 0L)
            
            // If cached access token is still fresh, reuse it immediately
            if (!cachedToken.isNullOrBlank() && System.currentTimeMillis() < expiry) {
                return@withContext cachedToken
            }
            return@withContext refreshOAuthAccessToken()
        }

        // Service Account flow
        val stream = openCredentialsStream()
            ?: throw IllegalStateException("Service Account credentials JSON not found in app storage or assets.")

        stream.use { s ->
            val credentials = GoogleCredentials.fromStream(s)
                .createScoped(Collections.singletonList(SCOPE_SPREADSHEETS))
            credentials.refreshIfExpired()
            credentials.accessToken.tokenValue
        }
    }

    // -----------------------------------------------------------------------------------
    // 3. API Execution & Connection Verification
    // -----------------------------------------------------------------------------------

    /**
     * Tests Google Sheet connection and verifies access.
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val spreadsheetId = getSpreadsheetId()
            if (spreadsheetId.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Spreadsheet ID is empty."))
            }

            val accessToken = getAccessToken()
            val url = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val json = JSONObject(body)
                val title = json.getJSONObject("properties").getString("title")
                Result.success("Connected to: '$title'")
            } else {
                val errorMsg = when (response.code) {
                    403 -> "HTTP 403 Forbidden: Did you share the Google Sheet with the Service Account email or grant Sheets scope?"
                    404 -> "HTTP 404: Spreadsheet ID not found. Verify the ID."
                    else -> "HTTP ${response.code}: $body"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Appends a new daily accountability row to the configured Google Sheet.
     */
    suspend fun appendRecord(record: AccountabilityEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val spreadsheetId = getSpreadsheetId()
            if (spreadsheetId.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Spreadsheet ID is not configured."))
            }

            val accessToken = getAccessToken()
            val sheetName = getSheetName()

            val range = "${sheetName}!A:F"
            val url = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range:append?valueInputOption=USER_ENTERED"

            val rowArray = JSONArray().apply {
                put(record.timestamp)
                put(record.socksStatus)
                put(record.towelStatus)
                put(record.booksStatus)
                put(record.rawTranscript)
                put("SUCCESS")
            }

            val valuesMatrix = JSONArray().apply {
                put(rowArray)
            }

            val jsonBody = JSONObject().apply {
                put("range", range)
                put("majorDimension", "ROWS")
                put("values", valuesMatrix)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "Successfully appended row to Google Sheet: ${response.code}")
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string().orEmpty()
                Log.e(TAG, "Google Sheets API Error (${response.code}): $errorBody")
                Result.failure(Exception("Google Sheets API error: HTTP ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during appendRecord to Google Sheets", e)
            Result.failure(e)
        }
    }
}
