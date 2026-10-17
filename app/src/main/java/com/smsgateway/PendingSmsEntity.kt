package com.smsgateway

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_sms",
    indices = [
        Index(value = ["backendId"]),
        Index(value = ["queuedAt"])
    ]
)
data class PendingSmsEntity(
    @PrimaryKey
    val id: String,
    val backendId: String,
    val to: String,
    val message: String,
    val templateId: String? = null,
    val createdAt: String? = null,
    val channel: String? = null,
    val queuedAt: Long = System.currentTimeMillis()
) {
    fun toSmsMessage(): SmsMessage = SmsMessage(
        id = id,
        to = to,
        message = message,
        templateId = templateId,
        createdAt = createdAt,
        channel = channel
    )
}

