package com.chatbot.feedback

import com.chatbot.feedback.dto.FeedbackCreateRequest
import com.chatbot.feedback.dto.FeedbackResponse
import com.chatbot.feedback.dto.FeedbackStatusUpdateRequest
import com.chatbot.global.common.PageResponse
import com.chatbot.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/feedbacks")
class FeedbackController(
    private val feedbackService: FeedbackService
) {

    /**
     * 피드백 생성
     * - MEMBER: 본인 대화만 허용 (서비스 레이어에서 소유권 검사)
     * - ADMIN: 모든 대화 허용
     */
    @PostMapping
    fun createFeedback(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: FeedbackCreateRequest
    ): ResponseEntity<FeedbackResponse> {
        val response = feedbackService.createFeedback(userDetails, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    /**
     * 피드백 목록 조회
     * - MEMBER: 본인 피드백만 조회
     * - ADMIN: 전체 피드백 조회
     */
    @GetMapping
    fun getFeedbacks(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) isPositive: Boolean?,
        @RequestParam(defaultValue = "desc") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<FeedbackResponse>> {
        val response = feedbackService.getFeedbacks(userDetails, isPositive, sort, page, size)
        return ResponseEntity.ok(response)
    }

    /**
     * 피드백 상태 변경 (ADMIN 전용)
     */
    @PatchMapping("/{feedbackId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    fun updateStatus(
        @PathVariable feedbackId: UUID,
        @Valid @RequestBody request: FeedbackStatusUpdateRequest
    ): ResponseEntity<FeedbackResponse> {
        val response = feedbackService.updateStatus(feedbackId, request)
        return ResponseEntity.ok(response)
    }
}
