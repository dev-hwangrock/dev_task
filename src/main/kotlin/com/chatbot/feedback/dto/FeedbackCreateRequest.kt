package com.chatbot.feedback.dto

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class FeedbackCreateRequest(
    @field:NotNull(message = "chatId는 필수입니다")
    val chatId: UUID?,

    @field:NotNull(message = "isPositive는 필수입니다")
    val isPositive: Boolean?
)
