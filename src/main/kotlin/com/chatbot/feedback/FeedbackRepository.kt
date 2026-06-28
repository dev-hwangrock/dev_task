package com.chatbot.feedback

import com.chatbot.domain.feedback.Feedback
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FeedbackRepository : JpaRepository<Feedback, UUID> {
    fun existsByUserIdAndChatId(userId: UUID, chatId: UUID): Boolean
    fun findByUserId(userId: UUID, pageable: Pageable): Page<Feedback>
    fun findByUserIdAndIsPositive(userId: UUID, isPositive: Boolean, pageable: Pageable): Page<Feedback>
    fun findByIsPositive(isPositive: Boolean, pageable: Pageable): Page<Feedback>
}
