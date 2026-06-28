package com.chatbot.auth.dto

import java.time.OffsetDateTime
import java.util.UUID

data class SignupResponse(
    val id: UUID,
    val email: String,
    val name: String,
    val role: String,
    val createdAt: OffsetDateTime
)
