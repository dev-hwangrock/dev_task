package com.chatbot.feedback.dto

import com.chatbot.domain.feedback.FeedbackStatus
import jakarta.validation.constraints.NotNull

data class FeedbackStatusUpdateRequest(
    @field:NotNull(message = "status는 필수입니다")
    val status: FeedbackStatus?
)
