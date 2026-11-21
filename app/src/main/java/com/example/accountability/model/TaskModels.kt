package com.example.accountability.model

enum class TaskType(val title: String, val question: String) {
    SOCKS("Soaking Socks", "Did you complete soaking your socks?"),
    TOWEL("Hanging Towel", "Did you complete hanging the towel?"),
    BOOKS("Packing Books", "Did you complete packing your books?")
}

enum class TaskStatus(val label: String) {
    YES("YES"),
    NO("NO"),
    UNCLEAR("UNCLEAR")
}

data class TaskItem(
    val type: TaskType,
    var status: TaskStatus = TaskStatus.UNCLEAR,
    var transcript: String = ""
)

data class AccountabilitySubmission(
    val timestamp: String,
    val socksStatus: TaskStatus,
    val towelStatus: TaskStatus,
    val booksStatus: TaskStatus,
    val rawTranscript: String,
    val isSynced: Boolean = false
)
