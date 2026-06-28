package com.chatbot.domain.chat

import com.chatbot.domain.user.User
import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "threads")
class ChatThread(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    var lastChatAt: OffsetDateTime = OffsetDateTime.now(),
)
