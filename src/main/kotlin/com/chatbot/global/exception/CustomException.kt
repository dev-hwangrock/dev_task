package com.chatbot.global.exception

class CustomException(
    val errorCode: ErrorCode,
    message: String = errorCode.message
) : RuntimeException(message)
