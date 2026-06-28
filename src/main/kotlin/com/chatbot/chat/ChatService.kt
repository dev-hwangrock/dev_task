package com.chatbot.chat

import com.chatbot.ai.AiProvider
import com.chatbot.ai.openai.OpenAiProperties
import com.chatbot.chat.dto.ChatCreateRequest
import com.chatbot.chat.dto.ChatItem
import com.chatbot.chat.dto.ChatResponse
import com.chatbot.chat.dto.ThreadWithChatsResponse
import com.chatbot.domain.user.Role
import com.chatbot.global.common.PageResponse
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.global.security.CustomUserDetails
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.scheduler.Schedulers
import java.util.UUID

/**
 * 퍼사드 서비스.
 * 비즈니스 흐름을 조율하되, 트랜잭션 범위 안에 외부 I/O를 포함하지 않는다.
 * - tx1: chatTransactionService.openThread (짧음)
 * - 외부 I/O: aiProvider.complete / aiProvider.stream (트랜잭션 밖)
 * - tx2: chatTransactionService.persistChat (짧음)
 */
@Service
class ChatService(
    private val chatTransactionService: ChatTransactionService,
    private val threadRepository: ThreadRepository,
    private val chatRepository: ChatRepository,
    private val aiProvider: AiProvider,
    private val openAiProperties: OpenAiProperties,
    private val objectMapper: ObjectMapper,
) {

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
     * 비스트리밍 대화 생성.
     * tx1 → AI 호출(트랜잭션 밖) → tx2 순서로 실행.
     * @Transactional 없음: DB 커넥션을 AI 대기 동안 점유하지 않는다.
     */
    fun createChat(userId: UUID, req: ChatCreateRequest): ChatResponse {
        val model = resolveModel(req.model)
        val ctx = chatTransactionService.openThread(userId, req.question)       // tx1 (짧음)
        val answer = aiProvider.complete(ctx.messages, model)                   // 트랜잭션 밖
        val result = chatTransactionService.persistChat(                        // tx2 (짧음)
            ctx.threadId, userId, req.question, answer, model,
        )
        return ChatResponse(
            id = result.chatId,
            threadId = result.threadId,
            question = req.question,
            answer = answer,
            model = model,
            createdAt = result.createdAt,
        )
    }

    /**
     * SSE 스트리밍 대화 생성.
     * - tx1으로 스레드/메시지 준비 후 Reactor subscribe (논블로킹)
     * - publishOn(boundedElastic): 이벤트루프 보호 + 블로킹 emitter.send 격리
     * - 완료 시 tx2로 Chat/ActivityLog 저장 후 SSE done 이벤트 전송
     * - timeout/error/completion 시 disposable 정리
     */
    fun createChatStreaming(userId: UUID, req: ChatCreateRequest, emitter: SseEmitter) {
        val model = resolveModel(req.model)
        val ctx = chatTransactionService.openThread(userId, req.question)       // tx1
        val sb = StringBuilder()
        val disposable = aiProvider.stream(ctx.messages, model)
            .publishOn(Schedulers.boundedElastic())
            .subscribe(
                { token ->
                    sb.append(token)
                    emitter.send(
                        SseEmitter.event().data(
                            objectMapper.writeValueAsString(mapOf("token" to token)),
                        ),
                    )
                },
                { e -> emitter.completeWithError(e) },
                {
                    try {
                        val r = chatTransactionService.persistChat(             // tx2
                            ctx.threadId, userId, req.question, sb.toString(), model,
                        )
                        emitter.send(
                            SseEmitter.event().data(
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "done" to true,
                                        "chatId" to r.chatId.toString(),
                                        "threadId" to r.threadId.toString(),
                                    ),
                                ),
                            ),
                        )
                        emitter.complete()
                    } catch (e: Exception) {
                        emitter.completeWithError(e)
                    }
                },
            )
        emitter.onTimeout { disposable.dispose(); emitter.complete() }
        emitter.onError { disposable.dispose() }
        emitter.onCompletion { disposable.dispose() }
    }

    /**
     * 대화 목록 조회 (스레드 그룹화, N+1 방지).
     * - ADMIN: 전체 스레드 조회
     * - MEMBER: 본인 스레드만
     */
    @Transactional(readOnly = true)
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
