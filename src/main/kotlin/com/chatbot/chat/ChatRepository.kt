package com.chatbot.chat

import com.chatbot.domain.chat.Chat
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface ChatRepository : JpaRepository<Chat, UUID> {
    fun findByThreadIdOrderByCreatedAtAsc(threadId: UUID): List<Chat>
    fun findByThreadIdInOrderByCreatedAtAsc(threadIds: List<UUID>): List<Chat>
    fun findByCreatedAtBetween(from: OffsetDateTime, to: OffsetDateTime): List<Chat>
}
