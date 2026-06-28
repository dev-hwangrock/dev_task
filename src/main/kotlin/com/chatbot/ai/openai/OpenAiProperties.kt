package com.chatbot.ai.openai

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "openai")
data class OpenAiProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val defaultModel: String = "gpt-5-nano",
)
