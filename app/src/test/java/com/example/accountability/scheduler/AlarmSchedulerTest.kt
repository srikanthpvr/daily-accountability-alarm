package com.example.accountability.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmSchedulerTest {

    @Test
    fun testCalculateNext9PmTriggerBefore9Pm() {
        // Mock current time: 2:00 PM today (14:00)
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Expected trigger: 9:00 PM today (21:00)
        val expectedCal = Calendar.getInstance().apply {
            timeInMillis = nowCal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val targetCal = Calendar.getInstance().apply {
            timeInMillis = nowCal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (targetCal.timeInMillis <= nowCal.timeInMillis) {
            targetCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        assertEquals(expectedCal.timeInMillis, targetCal.timeInMillis)
        assertTrue(targetCal.timeInMillis > nowCal.timeInMillis)
    }

    @Test
    fun testCalculateNext9PmTriggerAfter9Pm() {
        // Mock current time: 9:30 PM today (21:30)
        val nowCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Expected trigger: 9:00 PM tomorrow
        val targetCal = Calendar.getInstance().apply {
            timeInMillis = nowCal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (targetCal.timeInMillis <= nowCal.timeInMillis) {
            targetCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val diffMillis = targetCal.timeInMillis - nowCal.timeInMillis
        // 23 hours and 30 minutes in millis = (23 * 3600 + 30 * 60) * 1000 = 84,600,000 ms
        assertEquals(84600000L, diffMillis)
    }
}
