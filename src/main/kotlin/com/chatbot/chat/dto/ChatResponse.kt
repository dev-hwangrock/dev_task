package com.chatbot.chat.dto

import java.time.OffsetDateTime
import java.util.UUID

data class ChatResponse(
    val id: UUID,
    val threadId: UUID,
    val question: String,
    val answer: String,
    val model: String,
    val createdAt: OffsetDateTime,
)
