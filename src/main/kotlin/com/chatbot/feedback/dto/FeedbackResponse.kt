package com.chatbot.feedback.dto

import com.chatbot.domain.feedback.Feedback
import java.time.OffsetDateTime
import java.util.UUID

data class FeedbackResponse(
    val id: UUID,
    val chatId: UUID,
    val userId: UUID,
    val isPositive: Boolean,
    val status: String,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(feedback: Feedback): FeedbackResponse = FeedbackResponse(
            id = feedback.id!!,
            chatId = feedback.chat.id!!,
            userId = feedback.user.id!!,
            isPositive = feedback.isPositive,
            status = feedback.status.name,
            createdAt = feedback.createdAt
        )
    }
}
