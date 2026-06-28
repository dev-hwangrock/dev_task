package com.chatbot.feedback

import com.chatbot.global.exception.ErrorCode
import com.chatbot.global.exception.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * HttpMessageNotReadableException 핸들러
 *
 * Jackson 역직렬화 실패 시 (잘못된 JSON 형식, 유효하지 않은 enum 값 등) 400을 반환.
 * GlobalExceptionHandler의 catch-all Exception 핸들러보다 높은 우선순위로 처리.
 *
 * 예: FeedbackStatusUpdateRequest.status = "INVALID_STATUS" → 400 VALIDATION_FAILED
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class FeedbackExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ErrorCode.VALIDATION_FAILED.code, "잘못된 요청 형식입니다"))
    }
}
