package com.chatbot.domain.chat

import com.chatbot.domain.user.User
import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "chats")
class Chat(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id", nullable = false)
    val thread: ChatThread,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, columnDefinition = "TEXT")
    val question: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var answer: String,

    @Column(nullable = false)
    val model: String,

    @Column(nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
