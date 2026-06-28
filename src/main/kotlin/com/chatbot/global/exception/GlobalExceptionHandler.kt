package com.chatbot.global.exception

import jakarta.validation.ConstraintViolationException
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(CustomException::class)
    fun handleCustomException(e: CustomException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(e.errorCode.status)
            .body(ErrorResponse(e.errorCode.code, e.message ?: e.errorCode.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .status(ErrorCode.VALIDATION_FAILED.status)
            .body(ErrorResponse(ErrorCode.VALIDATION_FAILED.code, message))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolationException(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val message = e.constraintViolations
            .joinToString(", ") { "${it.propertyPath}: ${it.message}" }
        return ResponseEntity
            .status(ErrorCode.VALIDATION_FAILED.status)
            .body(ErrorResponse(ErrorCode.VALIDATION_FAILED.code, message))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(ErrorCode.FORBIDDEN.status)
            .body(ErrorResponse(ErrorCode.FORBIDDEN.code, ErrorCode.FORBIDDEN.message))
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(e: AuthenticationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(ErrorCode.UNAUTHORIZED.status)
            .body(ErrorResponse(ErrorCode.UNAUTHORIZED.code, ErrorCode.UNAUTHORIZED.message))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.status)
            .body(ErrorResponse(ErrorCode.INTERNAL_ERROR.code, ErrorCode.INTERNAL_ERROR.message))
    }
}
