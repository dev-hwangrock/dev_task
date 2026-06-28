package com.chatbot.chat

import com.chatbot.ai.AiMessage
import com.chatbot.analytics.ActivityLogRepository
import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.domain.analytics.ActivityLog
import com.chatbot.domain.chat.Chat
import com.chatbot.domain.chat.ChatThread
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 퍼사드 분리용 짧은 트랜잭션 전용 서비스.
 * 외부 I/O(AI 호출)는 절대 포함하지 않는다.
 */
data class ThreadContext(
    val threadId: UUID,
    val messages: List<AiMessage>,
)

data class ChatResult(
    val chatId: UUID,
    val threadId: UUID,
    val createdAt: OffsetDateTime,
)

@Service
class ChatTransactionService(
    private val threadRepository: ThreadRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val activityLogRepository: ActivityLogRepository,
) {

    /**
     * tx1: 스레드 결정 + 히스토리 로드 + 메시지 구성.
     * AI 호출 전 짧게 완료되는 트랜잭션.
     */
    @Transactional
    fun openThread(userId: UUID, question: String): ThreadContext {
        val thread = getOrCreateThread(userId)
        val threadId = thread.id!!
        val history = chatRepository.findByThreadIdOrderByCreatedAtAsc(threadId)
        val messages = buildMessages(history, question)
        return ThreadContext(threadId = threadId, messages = messages)
    }

    /**
     * tx2: Chat 저장 + lastChatAt 갱신 + ActivityLog 저장.
     * AI 응답을 받은 직후 짧게 완료되는 트랜잭션.
     */
    @Transactional
    fun persistChat(threadId: UUID, userId: UUID, question: String, answer: String, model: String): ChatResult {
        val thread = threadRepository.findById(threadId)
            .orElseThrow { CustomException(ErrorCode.NOT_FOUND) }
        val user = userRepository.findById(userId)
            .orElseThrow { CustomException(ErrorCode.NOT_FOUND) }
        val chat = chatRepository.save(
            Chat(thread = thread, user = user, question = question, answer = answer, model = model),
        )
        thread.lastChatAt = OffsetDateTime.now()
        activityLogRepository.save(ActivityLog(user = user, action = ActivityAction.CHAT_CREATE))
        return ChatResult(chatId = chat.id!!, threadId = thread.id!!, createdAt = chat.createdAt)
    }

    /**
     * 30분 규칙: cutoff = now - 30분. lastChatAt.isAfter(cutoff)가 true이면 재사용.
     * 정확히 30분 = isAfter false → 신규 생성.
     */
    private fun getOrCreateThread(userId: UUID): ChatThread {
        val cutoff = OffsetDateTime.now().minusMinutes(30)
        return threadRepository.findTopByUserIdOrderByLastChatAtDesc(userId)
            ?.takeIf { it.lastChatAt.isAfter(cutoff) }
            ?: run {
                val user = userRepository.findById(userId)
                    .orElseThrow { CustomException(ErrorCode.NOT_FOUND) }
                threadRepository.save(ChatThread(user = user))
            }
    }

    private fun buildMessages(history: List<Chat>, currentQuestion: String): List<AiMessage> {
        val messages = mutableListOf<AiMessage>()
        history.forEach { chat ->
            messages += AiMessage("user", chat.question)
            messages += AiMessage("assistant", chat.answer)
        }
        messages += AiMessage("user", currentQuestion)
        return messages
    }
}
