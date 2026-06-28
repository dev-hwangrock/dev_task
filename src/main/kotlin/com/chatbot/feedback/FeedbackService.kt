package com.chatbot.feedback

import com.chatbot.chat.ChatRepository
import com.chatbot.domain.feedback.Feedback
import com.chatbot.domain.user.Role
import com.chatbot.feedback.dto.FeedbackCreateRequest
import com.chatbot.feedback.dto.FeedbackResponse
import com.chatbot.feedback.dto.FeedbackStatusUpdateRequest
import com.chatbot.global.common.PageResponse
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.global.security.CustomUserDetails
import com.chatbot.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FeedbackService(
    private val feedbackRepository: FeedbackRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository
) {

    @Transactional
    fun createFeedback(userDetails: CustomUserDetails, req: FeedbackCreateRequest): FeedbackResponse {
        val userId = userDetails.id
        val chatId = req.chatId!!

        val chat = chatRepository.findById(chatId).orElseThrow {
            CustomException(ErrorCode.NOT_FOUND)
        }

        // MEMBER는 본인 대화만 허용; ADMIN은 모든 대화 허용
        if (userDetails.role == Role.MEMBER && chat.user.id != userId) {
            throw CustomException(ErrorCode.FORBIDDEN)
        }

        // 동일 user+chat 중복 피드백 검사 (application level)
        if (feedbackRepository.existsByUserIdAndChatId(userId, chatId)) {
            throw CustomException(ErrorCode.DUPLICATE_FEEDBACK)
        }

        val user = userRepository.findById(userId).orElseThrow {
            CustomException(ErrorCode.NOT_FOUND)
        }

        val feedback = Feedback(
            user = user,
            chat = chat,
            isPositive = req.isPositive!!
        )

        return try {
            FeedbackResponse.from(feedbackRepository.save(feedback))
        } catch (e: DataIntegrityViolationException) {
            // 동시성 경쟁으로 인한 DB 유니크 제약 위반 → DUPLICATE_FEEDBACK으로 매핑
            throw CustomException(ErrorCode.DUPLICATE_FEEDBACK)
        }
    }

    @Transactional(readOnly = true)
    fun getFeedbacks(
        userDetails: CustomUserDetails,
        isPositive: Boolean?,
        sort: String,
        page: Int,
        size: Int
    ): PageResponse<FeedbackResponse> {
        val direction = if (sort == "asc") Sort.Direction.ASC else Sort.Direction.DESC
        val pageable = PageRequest.of(page, size, Sort.by(direction, "createdAt"))

        val feedbackPage = when {
            userDetails.role == Role.ADMIN && isPositive != null ->
                feedbackRepository.findByIsPositive(isPositive, pageable)

            userDetails.role == Role.ADMIN ->
                feedbackRepository.findAll(pageable)

            isPositive != null ->
                feedbackRepository.findByUserIdAndIsPositive(userDetails.id, isPositive, pageable)

            else ->
                feedbackRepository.findByUserId(userDetails.id, pageable)
        }

        return PageResponse.of(feedbackPage.map { FeedbackResponse.from(it) })
    }

    @Transactional
    fun updateStatus(feedbackId: UUID, req: FeedbackStatusUpdateRequest): FeedbackResponse {
        val feedback = feedbackRepository.findById(feedbackId).orElseThrow {
            CustomException(ErrorCode.NOT_FOUND)
        }

        feedback.status = req.status!!

        return FeedbackResponse.from(feedbackRepository.save(feedback))
    }
}
