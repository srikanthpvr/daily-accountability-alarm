package com.example.accountability.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.accountability.receiver.AlarmReceiver
import com.example.accountability.ui.MainActivity
import java.util.Calendar

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    companion object {
        private const val TAG = "AlarmScheduler"
        const val ALARM_REQUEST_CODE = 9001
        const val TEST_ALARM_REQUEST_CODE = 9002
        const val TARGET_HOUR = 21 // 9 PM
        const val TARGET_MINUTE = 0
        const val TARGET_SECOND = 0
    }

    /**
     * Calculates next 9:00 PM trigger time in milliseconds.
     * If 9:00 PM has already passed today, targets tomorrow at 9:00 PM.
     */
    fun calculateNext9PmTrigger(nowMillis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
            set(Calendar.MINUTE, TARGET_MINUTE)
            set(Calendar.SECOND, TARGET_SECOND)
            set(Calendar.MILLISECOND, 0)
        }

        // If 9 PM has already passed today, advance by one day
        if (calendar.timeInMillis <= nowMillis) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis
    }

    /**
     * Checks if exact alarms are allowed on Android 12+ (API 31+)
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    /**
     * Schedules the daily 9 PM exact alarm using AlarmManager.setAlarmClock()
     * which reliably triggers even during Doze / Idle modes.
     */
    fun scheduleDaily9PmAlarm(): Long {
        val triggerTime = calculateNext9PmTrigger()
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TRIGGER_DAILY_ALARM
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            0,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager?.let { am ->
            try {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
                am.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.i(TAG, "Successfully scheduled daily 9 PM alarm for epoch: $triggerTime")
            } catch (e: SecurityException) {
                Log.e(TAG, "Exact alarm permission denied. Falling back to setAndAllowWhileIdle", e)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }

        return triggerTime
    }

    /**
     * Helper to test the full-screen lockscreen alarm immediately with a small delay.
     */
    fun scheduleTestAlarm(delaySeconds: Int = 5): Long {
        val triggerTime = System.currentTimeMillis() + (delaySeconds * 1000L)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TRIGGER_DAILY_ALARM
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            TEST_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            0,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager?.let { am ->
            try {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
                am.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.i(TAG, "Scheduled test alarm in $delaySeconds seconds")
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }

        return triggerTime
    }

    /**
     * Cancels existing scheduled daily alarm
     */
    fun cancelDailyAlarm() {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TRIGGER_DAILY_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "Daily 9 PM alarm cancelled")
        }
    }
}
