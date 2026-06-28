package com.chatbot.chat.dto

import jakarta.validation.constraints.NotBlank

data class ChatCreateRequest(
    @field:NotBlank
    val question: String,
    val isStreaming: Boolean = false,
    val model: String? = null,
)
