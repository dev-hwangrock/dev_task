package com.chatbot.analytics

import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.domain.analytics.ActivityLog
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface ActivityLogRepository : JpaRepository<ActivityLog, UUID> {
    fun countByActionAndCreatedAtAfter(action: ActivityAction, from: OffsetDateTime): Long
}
