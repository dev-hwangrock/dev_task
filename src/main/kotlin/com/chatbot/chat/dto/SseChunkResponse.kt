package com.chatbot.chat.dto

import java.util.UUID

/** SSE 스트리밍 이벤트 DTO. */
data class SseTokenChunk(val token: String)

data class SseDoneChunk(
    val done: Boolean = true,
    val chatId: UUID,
    val threadId: UUID,
)
