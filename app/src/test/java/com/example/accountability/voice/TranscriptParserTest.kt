package com.example.accountability.voice

import com.example.accountability.model.TaskStatus
import com.example.accountability.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptParserTest {

    @Test
    fun testAffirmativeSpokenResponses() {
        val yesInputs = listOf(
            "yes",
            "yeah",
            "yep",
            "done",
            "completed",
            "i did it",
            "already soaked them",
            "finished",
            "yes I did",
            "all set",
            "of course"
        )

        for (input in yesInputs) {
            val result = TranscriptParser.parseTurnResponse(input)
            assertEquals("Failed for affirmative input: '$input'", TaskStatus.YES, result)
        }
    }

    @Test
    fun testNegativeSpokenResponses() {
        val noInputs = listOf(
            "no",
            "nope",
            "not yet",
            "haven't done it",
            "have not",
            "didn't get time",
            "no I didn't",
            "skipped",
            "forgot about it",
            "negative"
        )

        for (input in noInputs) {
            val result = TranscriptParser.parseTurnResponse(input)
            assertEquals("Failed for negative input: '$input'", TaskStatus.NO, result)
        }
    }

    @Test
    fun testUnclearOrEmptySpokenResponses() {
        assertEquals(TaskStatus.UNCLEAR, TranscriptParser.parseTurnResponse(""))
        assertEquals(TaskStatus.UNCLEAR, TranscriptParser.parseTurnResponse("   "))
        assertEquals(TaskStatus.UNCLEAR, TranscriptParser.parseTurnResponse(null))
        assertEquals(TaskStatus.UNCLEAR, TranscriptParser.parseTurnResponse("maybe tomorrow"))
        assertEquals(TaskStatus.UNCLEAR, TranscriptParser.parseTurnResponse("hello there"))
    }

    @Test
    fun testBatchParserFallback() {
        val allDone = "I have completed all tasks"
        val batchResult1 = TranscriptParser.parseBatchResponse(allDone)
        assertEquals(TaskStatus.YES, batchResult1[TaskType.SOCKS])
        assertEquals(TaskStatus.YES, batchResult1[TaskType.TOWEL])
        assertEquals(TaskStatus.YES, batchResult1[TaskType.BOOKS])

        val noneDone = "didn't do anything today"
        val batchResult2 = TranscriptParser.parseBatchResponse(noneDone)
        assertEquals(TaskStatus.NO, batchResult2[TaskType.SOCKS])
        assertEquals(TaskStatus.NO, batchResult2[TaskType.TOWEL])
        assertEquals(TaskStatus.NO, batchResult2[TaskType.BOOKS])
    }
}
