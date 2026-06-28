package com.chatbot.analytics.dto

import java.time.OffsetDateTime

data class ActivitySummaryResponse(
    val signupCount: Long,
    val loginCount: Long,
    val chatCreateCount: Long,
    val from: OffsetDateTime,
    val to: OffsetDateTime
)
