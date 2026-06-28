package com.chatbot.ai

/**
 * AI 대화 메시지 한 턴. OpenAI Chat Completions 의 message 포맷과 동일 구조.
 * role: "system" | "user" | "assistant"
 */
data class AiMessage(
    val role: String,
    val content: String,
)
