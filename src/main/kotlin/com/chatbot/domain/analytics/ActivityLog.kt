package com.chatbot.domain.analytics

import com.chatbot.domain.user.User
import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "activity_logs")
class ActivityLog(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val action: ActivityAction,

    @Column(nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
