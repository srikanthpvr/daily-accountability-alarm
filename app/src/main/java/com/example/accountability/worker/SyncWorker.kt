package com.example.accountability.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.accountability.data.local.AppDatabase
import com.example.accountability.data.remote.GoogleSheetsService

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting SyncWorker execution for pending offline records")
        val dao = AppDatabase.getInstance(applicationContext).accountabilityDao()
        val sheetsService = GoogleSheetsService(applicationContext)

        val pendingList = dao.getPendingUnsyncedRecords()
        if (pendingList.isEmpty()) {
            Log.i(TAG, "No pending records to sync")
            return Result.success()
        }

        var anyFailed = false

        for (record in pendingList) {
            val syncResult = sheetsService.appendRecord(record)
            if (syncResult.isSuccess) {
                dao.markAsSynced(record.id)
                Log.i(TAG, "SyncWorker successfully synced record ID: ${record.id}")
            } else {
                Log.w(TAG, "SyncWorker failed for record ID: ${record.id}", syncResult.exceptionOrNull())
                anyFailed = true
            }
        }

        return if (anyFailed) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
