package com.example.util

import com.example.data.Task

data class TaskActionData(
    val type: String = "", // "CALL", "SMS", "WHATSAPP", or ""
    val contactName: String = "",
    val contactPhone: String = "",
    val message: String = ""
)

object TaskActionHelper {
    private val metaActionRegex = Regex("""\[Action: ([^\]]+)\]""")

    fun parseActionData(task: Task): TaskActionData {
        // 1. Check Task entity fields first
        if (task.actionType.isNotEmpty()) {
            return TaskActionData(
                type = task.actionType,
                contactName = task.actionContactName,
                contactPhone = task.actionContactPhone,
                message = task.actionMessage
            )
        }
        // 2. Fallback to description tag parsing
        val match = metaActionRegex.find(task.description) ?: return TaskActionData()
        val raw = match.groupValues.getOrNull(1) ?: return TaskActionData()
        val parts = raw.split("|")
        return TaskActionData(
            type = parts.getOrNull(0) ?: "",
            contactName = parts.getOrNull(1) ?: "",
            contactPhone = parts.getOrNull(2) ?: "",
            message = parts.getOrNull(3) ?: ""
        )
    }

    fun cleanDescription(description: String): String {
        return description.replace(metaActionRegex, "").trim()
    }

    fun applyActionToDescription(description: String, actionData: TaskActionData): String {
        val clean = cleanDescription(description)
        if (actionData.type.isEmpty()) return clean
        val tag = "[Action: ${actionData.type}|${actionData.contactName}|${actionData.contactPhone}|${actionData.message}]"
        return if (clean.isEmpty()) tag else "$clean\n$tag"
    }
}
