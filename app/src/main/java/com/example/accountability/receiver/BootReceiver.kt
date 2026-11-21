package com.example.accountability.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.accountability.scheduler.AlarmScheduler

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Boot or package update received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val scheduler = AlarmScheduler(context)
            val nextAlarmEpoch = scheduler.scheduleDaily9PmAlarm()
            Log.i(TAG, "Re-armed daily 9 PM alarm following reboot: $nextAlarmEpoch")
        }
    }
}
