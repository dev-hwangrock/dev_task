package com.chatbot.chat

import com.chatbot.domain.chat.ChatThread
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ThreadRepository : JpaRepository<ChatThread, UUID> {
    fun findTopByUserIdOrderByLastChatAtDesc(userId: UUID): ChatThread?
    fun findByUserId(userId: UUID, pageable: Pageable): Page<ChatThread>
}
