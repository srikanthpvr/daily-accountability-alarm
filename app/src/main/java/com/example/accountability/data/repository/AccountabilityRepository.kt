package com.example.accountability.data.repository

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.accountability.data.local.AccountabilityDao
import com.example.accountability.data.local.AccountabilityEntity
import com.example.accountability.data.local.AppDatabase
import com.example.accountability.data.remote.GoogleSheetsService
import com.example.accountability.worker.SyncWorker
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountabilityRepository(private val context: Context) {

    companion object {
        private const val TAG = "AccountabilityRepo"
    }

    private val dao: AccountabilityDao = AppDatabase.getInstance(context).accountabilityDao()
    private val sheetsService: GoogleSheetsService = GoogleSheetsService(context)
    private val workManager: WorkManager = WorkManager.getInstance(context)

    fun getAllRecords(): Flow<List<AccountabilityEntity>> = dao.getAllRecords()

    fun getPendingCount(): Flow<Int> = dao.getPendingCount()

    fun getSheetsService(): GoogleSheetsService = sheetsService

    /**
     * Submits accountability result:
     * 1. Saves to local Room DB first (ensuring zero data loss).
     * 2. Attempts immediate sync to Google Sheets via Service Account.
     * 3. If online & successful, updates record status to synced.
     * 4. If offline or failed, enqueues WorkManager with NetworkType.CONNECTED.
     */
    suspend fun submitRecord(
        socksStatus: String,
        towelStatus: String,
        booksStatus: String,
        rawTranscript: String
    ): Result<Boolean> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val formattedTimestamp = dateFormat.format(Date())

        val record = AccountabilityEntity(
            timestamp = formattedTimestamp,
            socksStatus = socksStatus,
            towelStatus = towelStatus,
            booksStatus = booksStatus,
            rawTranscript = rawTranscript,
            isSynced = false
        )

        // 1. Insert locally into Room DB
        val insertedId = dao.insert(record)
        val entityWithId = record.copy(id = insertedId)
        Log.i(TAG, "Saved record to local database with ID: $insertedId")

        // 2. Attempt immediate network sync
        val syncResult = sheetsService.appendRecord(entityWithId)
        return if (syncResult.isSuccess) {
            dao.markAsSynced(insertedId)
            Log.i(TAG, "Record $insertedId synced immediately to Google Sheet")
            Result.success(true)
        } else {
            Log.w(TAG, "Immediate sync failed. Scheduling WorkManager fallback.", syncResult.exceptionOrNull())
            scheduleOfflineSyncWork()
            Result.success(false) // Saved locally, pending background sync
        }
    }

    /**
     * Enqueues WorkManager task requiring internet connectivity.
     */
    fun scheduleOfflineSyncWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueue(syncWorkRequest)
        Log.i(TAG, "Enqueued SyncWorker with CONNECTED network constraint")
    }
}
