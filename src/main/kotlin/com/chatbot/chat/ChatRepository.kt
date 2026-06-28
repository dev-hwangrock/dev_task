package com.chatbot.chat

import com.chatbot.domain.chat.Chat
import jakarta.persistence.QueryHint
import org.hibernate.jpa.HibernateHints
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID
import java.util.stream.Stream

interface ChatRepository : JpaRepository<Chat, UUID> {
    fun findByThreadIdOrderByCreatedAtAsc(threadId: UUID): List<Chat>
    fun findByThreadIdInOrderByCreatedAtAsc(threadIds: List<UUID>): List<Chat>
    fun findByCreatedAtBetween(from: OffsetDateTime, to: OffsetDateTime): List<Chat>

    @Query("SELECT c FROM Chat c JOIN FETCH c.user WHERE c.createdAt BETWEEN :from AND :to ORDER BY c.createdAt ASC")
    fun findWithUserByCreatedAtBetween(
        @Param("from") from: OffsetDateTime,
        @Param("to") to: OffsetDateTime
    ): List<Chat>

    @QueryHints(value = [QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "100")])
    @Query("SELECT c FROM Chat c JOIN FETCH c.user WHERE c.createdAt BETWEEN :from AND :to ORDER BY c.createdAt ASC")
    fun streamWithUserByCreatedAtBetween(
        @Param("from") from: OffsetDateTime,
        @Param("to") to: OffsetDateTime
    ): Stream<Chat>
}
