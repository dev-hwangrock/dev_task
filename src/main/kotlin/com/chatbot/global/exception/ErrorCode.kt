package com.chatbot.global.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val code: String,
    val message: String
) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 유효성 실패"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "리소스를 찾을 수 없습니다"),
    DUPLICATE_FEEDBACK(HttpStatus.CONFLICT, "DUPLICATE_FEEDBACK", "이미 해당 대화에 피드백이 존재합니다"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다"),
    UNSUPPORTED_MODEL(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_MODEL", "지원하지 않는 AI 모델입니다"),
    AI_API_ERROR(HttpStatus.BAD_GATEWAY, "AI_API_ERROR", "AI API 호출에 실패했습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다")
}
