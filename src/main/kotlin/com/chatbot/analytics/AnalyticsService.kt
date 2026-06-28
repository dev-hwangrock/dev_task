package com.chatbot.analytics

import com.chatbot.analytics.dto.ActivitySummaryResponse
import com.chatbot.chat.ChatRepository
import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.global.common.CsvUtil
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class AnalyticsService(
    private val activityLogRepository: ActivityLogRepository,
    private val chatRepository: ChatRepository
) {

    fun getActivitySummary(): ActivitySummaryResponse {
        val now = OffsetDateTime.now()
        val from = now.minusHours(24)

        val signupCount = activityLogRepository.countByActionAndCreatedAtAfter(ActivityAction.SIGNUP, from)
        val loginCount = activityLogRepository.countByActionAndCreatedAtAfter(ActivityAction.LOGIN, from)
        val chatCreateCount = activityLogRepository.countByActionAndCreatedAtAfter(ActivityAction.CHAT_CREATE, from)

        return ActivitySummaryResponse(
            signupCount = signupCount,
            loginCount = loginCount,
            chatCreateCount = chatCreateCount,
            from = from,
            to = now
        )
    }

    @Transactional(readOnly = true)
    fun generateReport(response: HttpServletResponse) {
        val now = OffsetDateTime.now()
        val from = now.minusHours(24)

        val headers = listOf(
            "chat_id", "thread_id", "user_id", "user_email", "user_name",
            "question", "answer", "model", "created_at"
        )

        chatRepository.streamWithUserByCreatedAtBetween(from, now).use { stream ->
            CsvUtil.writeCsvStream(
                headers,
                stream.iterator().asSequence().map { chat ->
                    listOf(
                        chat.id.toString(),
                        chat.thread.id.toString(),
                        chat.user.id.toString(),
                        chat.user.email,
                        chat.user.name,
                        chat.question,
                        chat.answer,
                        chat.model,
                        chat.createdAt.toString()
                    )
                },
                response
            )
        }
    }
}
