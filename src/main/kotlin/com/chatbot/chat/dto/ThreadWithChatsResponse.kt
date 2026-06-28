package com.chatbot.chat.dto

import java.time.OffsetDateTime
import java.util.UUID

data class ThreadWithChatsResponse(
    val threadId: UUID,
    val createdAt: OffsetDateTime,
    val lastChatAt: OffsetDateTime,
    val chats: List<ChatItem>,
)

data class ChatItem(
    val id: UUID,
    val question: String,
    val answer: String,
    val model: String,
    val createdAt: OffsetDateTime,
)
