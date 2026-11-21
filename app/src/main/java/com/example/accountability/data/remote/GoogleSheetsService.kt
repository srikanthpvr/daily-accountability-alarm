package com.example.accountability.data.remote

import android.content.Context
import android.util.Log
import com.example.accountability.data.local.AccountabilityEntity
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit

class GoogleSheetsService(private val context: Context) {

    companion object {
        private const val TAG = "GoogleSheetsService"
        private const val SCOPE_SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets"
        private const val PREFS_NAME = "accountability_settings"
        private const val KEY_SPREADSHEET_ID = "spreadsheet_id"
        private const val KEY_SHEET_NAME = "sheet_name"
        private const val DEFAULT_SHEET_NAME = "Sheet1"
        const val CREDENTIALS_FILE_NAME = "service_account.json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    /**
     * Checks if service account credentials file is available in internal files or assets.
     */
    fun hasCredentials(): Boolean {
        val internalFile = File(context.filesDir, CREDENTIALS_FILE_NAME)
        if (internalFile.exists() && internalFile.length() > 0) return true

        return try {
            context.assets.open(CREDENTIALS_FILE_NAME).use { it.available() > 0 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Saves user uploaded/pasted Service Account JSON string to internal secure files.
     */
    fun saveCredentialsJson(jsonString: String) {
        val file = File(context.filesDir, CREDENTIALS_FILE_NAME)
        file.writeText(jsonString.trim())
    }

    private fun openCredentialsStream(): InputStream {
        val internalFile = File(context.filesDir, CREDENTIALS_FILE_NAME)
        if (internalFile.exists()) {
            return internalFile.inputStream()
        }
        return context.assets.open(CREDENTIALS_FILE_NAME)
    }

    /**
     * Generates a valid OAuth2 Access Token using Google Service Account credentials.
     */
    private suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        openCredentialsStream().use { stream ->
            val credentials = GoogleCredentials.fromStream(stream)
                .createScoped(Collections.singletonList(SCOPE_SPREADSHEETS))
            credentials.refreshIfExpired()
            credentials.accessToken.tokenValue
        }
    }

    /**
     * Appends a record to Google Sheets using the Google Sheets REST API v4.
     */
    suspend fun appendRecord(record: AccountabilityEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val spreadsheetId = getSpreadsheetId()
            if (spreadsheetId.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Spreadsheet ID is not configured"))
            }

            if (!hasCredentials()) {
                return@withContext Result.failure(IllegalStateException("Service Account credentials.json not found"))
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
