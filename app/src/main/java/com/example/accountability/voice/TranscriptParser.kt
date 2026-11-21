package com.example.accountability.voice

import com.example.accountability.model.TaskStatus
import com.example.accountability.model.TaskType
import java.util.Locale

object TranscriptParser {

    private val AFFIRMATIVE_KEYWORDS = setOf(
        "yes", "yeah", "yep", "yup", "aye", "sure", "done", "completed", "did",
        "finished", "already", "of course", "affirmative", "all set", "checked",
        "soaked", "hung", "packed", "absolutely", "i did"
    )

    private val NEGATIVE_KEYWORDS = setOf(
        "no", "nope", "nah", "not", "haven't", "have not", "didn't", "did not",
        "missed", "skipped", "forgot", "later", "negative", "never", "uncompleted",
        "havent", "didnt"
    )

    /**
     * Parses a spoken response during Option B (Turn-by-Turn Conversational flow)
     * for a specific task.
     *
     * @param spokenText The transcript received from Speech-to-Text
     * @return TaskStatus (YES, NO, or UNCLEAR)
     */
    fun parseTurnResponse(spokenText: String?): TaskStatus {
        if (spokenText.isNullOrBlank()) return TaskStatus.UNCLEAR

        val normalized = spokenText.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()

        val tokens = normalized.split("\\s+".toRegex())

        // Check for strong negative indicators (e.g., "no I didn't", "haven't done it", "not yet")
        val hasNegative = tokens.any { it in NEGATIVE_KEYWORDS || it == "not" || it == "didnt" || it == "havent" } ||
                normalized.contains("not yet") ||
                normalized.contains("did not") ||
                normalized.contains("have not") ||
                normalized.contains("haven t") ||
                normalized.contains("didn t") ||
                normalized.contains("not done")

        // Check for affirmative indicators (e.g., "yes", "done", "i did it")
        val hasAffirmative = tokens.any { it in AFFIRMATIVE_KEYWORDS } ||
                normalized.contains("i did") ||
                normalized.contains("all set") ||
                normalized.contains("already done")

        return when {
            // Negation takes precedence over action verbs (e.g., "haven't done", "didn't finish")
            hasNegative -> TaskStatus.NO
            hasAffirmative -> TaskStatus.YES
            else -> TaskStatus.UNCLEAR
        }
    }

    /**
     * Fallback batch parser if the user speaks about all 3 tasks in a single breath.
     */
    fun parseBatchResponse(transcript: String?): Map<TaskType, TaskStatus> {
        val result = mutableMapOf(
            TaskType.SOCKS to TaskStatus.UNCLEAR,
            TaskType.TOWEL to TaskStatus.UNCLEAR,
            TaskType.BOOKS to TaskStatus.UNCLEAR
        )

        if (transcript.isNullOrBlank()) return result

        val normalized = transcript.lowercase(Locale.ROOT)

        // Global affirmatives
        if (normalized.contains("all done") ||
            normalized.contains("did everything") ||
            normalized.contains("yes to all") ||
            normalized.contains("completed all") ||
            normalized.contains("all three done")
        ) {
            return mapOf(
                TaskType.SOCKS to TaskStatus.YES,
                TaskType.TOWEL to TaskStatus.YES,
                TaskType.BOOKS to TaskStatus.YES
            )
        }

        // Global negatives
        if (normalized.contains("none") ||
            normalized.contains("didn't do anything") ||
            normalized.contains("no to all") ||
            normalized.contains("skipped all")
        ) {
            return mapOf(
                TaskType.SOCKS to TaskStatus.NO,
                TaskType.TOWEL to TaskStatus.NO,
                TaskType.BOOKS to TaskStatus.NO
            )
        }

        // Segment-based resolution
        result[TaskType.SOCKS] = extractTaskStatusForKeyword(normalized, "sock")
        result[TaskType.TOWEL] = extractTaskStatusForKeyword(normalized, "towel")
        result[TaskType.BOOKS] = extractTaskStatusForKeyword(normalized, "book")

        return result
    }

    private fun extractTaskStatusForKeyword(text: String, keyword: String): TaskStatus {
        val index = text.indexOf(keyword)
        if (index == -1) return TaskStatus.UNCLEAR

        // Extract window around keyword (-30 to +30 chars)
        val start = (index - 30).coerceAtLeast(0)
        val end = (index + 30).coerceAtMost(text.length)
        val window = text.substring(start, end)

        return parseTurnResponse(window)
    }
}
