package com.chatbot.chat

import com.chatbot.ai.AiMessage
import com.chatbot.ai.AiProvider
import com.chatbot.ai.openai.OpenAiProperties
import com.chatbot.analytics.ActivityLogRepository
import com.chatbot.chat.dto.ChatCreateRequest
import com.chatbot.chat.dto.ChatItem
import com.chatbot.chat.dto.ChatResponse
import com.chatbot.chat.dto.ThreadWithChatsResponse
import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.domain.analytics.ActivityLog
import com.chatbot.domain.chat.Chat
import com.chatbot.domain.chat.ChatThread
import com.chatbot.domain.user.Role
import com.chatbot.global.common.PageResponse
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.global.security.CustomUserDetails
import com.chatbot.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Service
class ChatService(
    private val threadRepository: ThreadRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val activityLogRepository: ActivityLogRepository,
    private val aiProvider: AiProvider,
    private val openAiProperties: OpenAiProperties,
    transactionManager: PlatformTransactionManager,
) {

    private val transactionTemplate = TransactionTemplate(transactionManager)

    companion object {
        private val SUPPORTED_MODELS = setOf("gpt-5-nano", "gpt-5.2", "gpt-4o-mini", "gpt-4.1")
    }

    /**
     * 요청된 model을 검증하고 실제 사용할 모델 문자열을 반환한다.
     * - null → openAiProperties.defaultModel
     * - "gemini"로 시작하거나 허용 목록 밖 → UNSUPPORTED_MODEL(422)
     */
    fun resolveModel(requested: String?): String {
        val model = requested ?: openAiProperties.defaultModel
        if (model.startsWith("gemini")) throw CustomException(ErrorCode.UNSUPPORTED_MODEL)
        if (model !in SUPPORTED_MODELS) throw CustomException(ErrorCode.UNSUPPORTED_MODEL)
        return model
    }

    /**
     * 30분 규칙: 마지막 스레드의 lastChatAt이 30분 이내면 재사용, 아니면 신규 생성.
     * cutoff = now - 30분; lastChatAt.isAfter(cutoff) → 재사용 (정확히 30분 전 = isAfter false → 신규)
     */
    fun getOrCreateThread(userId: UUID): ChatThread {
        val cutoff = OffsetDateTime.now().minusMinutes(30)
        return threadRepository.findTopByUserIdOrderByLastChatAtDesc(userId)
            ?.takeIf { it.lastChatAt.isAfter(cutoff) }
            ?: run {
                val user = userRepository.findById(userId)
                    .orElseThrow { CustomException(ErrorCode.NOT_FOUND) }
                threadRepository.save(ChatThread(user = user))
            }
    }

    /**
     * 이전 대화 히스토리를 user/assistant 쌍으로 구성하고 마지막에 현재 질문을 추가한다.
     */
    fun buildMessages(history: List<Chat>, currentQuestion: String): List<AiMessage> {
        val messages = mutableListOf<AiMessage>()
        history.forEach { chat ->
            messages += AiMessage("user", chat.question)
            messages += AiMessage("assistant", chat.answer)
        }
        messages += AiMessage("user", currentQuestion)
        return messages
    }

    /**
     * 비스트리밍 대화 생성.
     * 트랜잭션 내에서: 스레드 결정 → 히스토리 로드 → AI 호출 → Chat 저장 → ActivityLog 저장.
     */
    @Transactional
    fun createChat(userId: UUID, req: ChatCreateRequest): ChatResponse {
        val model = resolveModel(req.model)
        val thread = getOrCreateThread(userId)
        val history = chatRepository.findByThreadIdOrderByCreatedAtAsc(thread.id!!)
        val messages = buildMessages(history, req.question)
        val answer = aiProvider.complete(messages, model)

        val user = thread.user
        val chat = chatRepository.save(
            Chat(thread = thread, user = user, question = req.question, answer = answer, model = model),
        )
        thread.lastChatAt = OffsetDateTime.now()
        activityLogRepository.save(ActivityLog(user = user, action = ActivityAction.CHAT_CREATE))

        return ChatResponse(
            id = chat.id!!,
            threadId = thread.id!!,
            question = chat.question,
            answer = chat.answer,
            model = chat.model,
            createdAt = chat.createdAt,
        )
    }

    /**
     * SSE 스트리밍 대화 생성.
     * - 스레드/히스토리/메시지 준비 후 CompletableFuture로 비동기 실행
     * - 각 토큰을 emitter로 전송하며 누적
     * - 완료 시 별도 트랜잭션(TransactionTemplate)으로 Chat/ActivityLog 저장
     * - 에러 시 emitter.completeWithError
     */
    fun createChatStreaming(userId: UUID, req: ChatCreateRequest, emitter: SseEmitter) {
        val model = resolveModel(req.model)
        val thread = getOrCreateThread(userId)
        val threadId = thread.id!!
        val history = chatRepository.findByThreadIdOrderByCreatedAtAsc(threadId)
        val messages = buildMessages(history, req.question)
        val accumulator = StringBuilder()

        CompletableFuture.runAsync {
            try {
                aiProvider.stream(messages, model)
                    .doOnNext { token ->
                        accumulator.append(token)
                        emitter.send(SseEmitter.event().data("""{"token":"$token"}"""))
                    }
                    .doOnComplete {
                        try {
                            transactionTemplate.executeWithoutResult {
                                val user = userRepository.findById(userId)
                                    .orElseThrow { CustomException(ErrorCode.NOT_FOUND) }
                                val managedThread = threadRepository.findById(threadId)
                                    .orElseThrow { CustomException(ErrorCode.NOT_FOUND) }
                                val chat = chatRepository.save(
                                    Chat(
                                        thread = managedThread,
                                        user = user,
                                        question = req.question,
                                        answer = accumulator.toString(),
                                        model = model,
                                    ),
                                )
                                managedThread.lastChatAt = OffsetDateTime.now()
                                activityLogRepository.save(
                                    ActivityLog(user = user, action = ActivityAction.CHAT_CREATE),
                                )
                                emitter.send(
                                    SseEmitter.event().data(
                                        """{"done":true,"chatId":"${chat.id}","threadId":"${managedThread.id}"}""",
                                    ),
                                )
                                emitter.complete()
                            }
                        } catch (e: Exception) {
                            emitter.completeWithError(e)
                        }
                    }
                    .doOnError { e ->
                        emitter.completeWithError(e)
                    }
                    .subscribe()
            } catch (e: Exception) {
                emitter.completeWithError(e)
            }
        }
    }

    /**
     * 대화 목록 조회 (스레드 그룹화, N+1 방지).
     * - ADMIN: 전체 스레드 조회
     * - MEMBER: 본인 스레드만
     * - 스레드에 속한 chats를 IN 쿼리로 일괄 조회 후 그룹화
     */
    fun getChats(
        userDetails: CustomUserDetails,
        sort: String,
        page: Int,
        size: Int,
    ): PageResponse<ThreadWithChatsResponse> {
        val direction = if (sort == "asc") Sort.Direction.ASC else Sort.Direction.DESC
        val pageable = PageRequest.of(page, size, Sort.by(direction, "createdAt"))

        val threadPage = if (userDetails.role == Role.ADMIN) {
            threadRepository.findAll(pageable)
        } else {
            threadRepository.findByUserId(userDetails.id, pageable)
        }

        val threadIds = threadPage.content.map { it.id!! }
        val chats = if (threadIds.isEmpty()) {
            emptyList()
        } else {
            chatRepository.findByThreadIdInOrderByCreatedAtAsc(threadIds)
        }

        // chat.thread.id: Hibernate 프록시가 PK를 알고 있으므로 초기화 없이 반환
        val chatsByThreadId = chats.groupBy { it.thread.id }

        val content = threadPage.content.map { thread ->
            ThreadWithChatsResponse(
                threadId = thread.id!!,
                createdAt = thread.createdAt,
                lastChatAt = thread.lastChatAt,
                chats = chatsByThreadId[thread.id]?.map { chat ->
                    ChatItem(
                        id = chat.id!!,
                        question = chat.question,
                        answer = chat.answer,
                        model = chat.model,
                        createdAt = chat.createdAt,
                    )
                } ?: emptyList(),
            )
        }

        return PageResponse(
            content = content,
            page = threadPage.number,
            size = threadPage.size,
            totalElements = threadPage.totalElements,
            totalPages = threadPage.totalPages,
        )
    }

    /**
     * 스레드 삭제. 소유자만 가능 (ADMIN도 타인 스레드 불가).
     */
    @Transactional
    fun deleteThread(userId: UUID, threadId: UUID) {
        val thread = threadRepository.findById(threadId)
            .orElseThrow { CustomException(ErrorCode.NOT_FOUND) }
        if (thread.user.id != userId) throw CustomException(ErrorCode.FORBIDDEN)
        threadRepository.delete(thread)
    }
}
