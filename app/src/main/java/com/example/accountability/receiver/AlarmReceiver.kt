package com.example.accountability.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.accountability.AccountabilityApplication
import com.example.accountability.R
import com.example.accountability.scheduler.AlarmScheduler
import com.example.accountability.ui.FullScreenPromptActivity

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_DAILY_ALARM = "com.example.accountability.ACTION_TRIGGER_DAILY_ALARM"
        private const val NOTIFICATION_ID = 9001
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Alarm triggered! onReceive action: ${intent?.action}")

        // 1. Acquire partial wake lock to ensure CPU remains active during transition
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AccountabilityApp::AlarmReceiverWakeLock"
        )
        wakeLock?.acquire(30 * 1000L) // 30 second safety release

        try {
            // 2. Prepare Intent to launch FullScreenPromptActivity
            val fullScreenIntent = Intent(context, FullScreenPromptActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 3. Build High-Priority Notification with Full-Screen Intent
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val notification = NotificationCompat.Builder(context, AccountabilityApplication.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(context.getString(R.string.notification_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 500, 250, 500))
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)

            // 4. Also launch Activity directly for immediate full-screen wake
            context.startActivity(fullScreenIntent)

            // 5. Automatically reschedule for the next day's 9 PM
            val scheduler = AlarmScheduler(context)
            scheduler.scheduleDaily9PmAlarm()

        } catch (e: Exception) {
            Log.e(TAG, "Error handling alarm trigger", e)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }
}
