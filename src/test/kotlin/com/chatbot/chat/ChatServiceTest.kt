package com.chatbot.chat

import com.chatbot.ai.AiMessage
import com.chatbot.ai.AiProvider
import com.chatbot.ai.openai.OpenAiProperties
import com.chatbot.chat.dto.ChatCreateRequest
import com.chatbot.domain.chat.ChatThread
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

/**
 * TDD: 퍼사드 ChatService 검증.
 * - resolveModel: 모델 검증 로직
 * - createChat: openThread → aiComplete → persistChat 호출 순서
 */
class ChatServiceTest {

    private val chatTransactionService: ChatTransactionService = mockk()
    private val threadRepository: ThreadRepository = mockk()
    private val chatRepository: ChatRepository = mockk()
    private val aiProvider: AiProvider = mockk()
    private val openAiProperties: OpenAiProperties = mockk()
    private val objectMapper: ObjectMapper = mockk()

    private lateinit var chatService: ChatService
    private lateinit var testUser: User
    private lateinit var testThread: ChatThread

    @BeforeEach
    fun setUp() {
        chatService = ChatService(
            chatTransactionService = chatTransactionService,
            threadRepository = threadRepository,
            chatRepository = chatRepository,
            aiProvider = aiProvider,
            openAiProperties = openAiProperties,
            objectMapper = objectMapper,
        )
        testUser = User(
            id = UUID.randomUUID(),
            email = "test@test.com",
            password = "pw",
            name = "TestUser",
            role = Role.MEMBER,
        )
        testThread = ChatThread(
            id = UUID.randomUUID(),
            user = testUser,
            lastChatAt = OffsetDateTime.now().minusMinutes(5),
        )
        every { openAiProperties.defaultModel } returns "gpt-5-nano"
    }

    // ---- resolveModel ----

    // GIVEN: gemini-3-flash (Google 모델)
    // WHEN: resolveModel 호출
    // THEN: UNSUPPORTED_MODEL 예외
    @Test
    @DisplayName("gemini로 시작하는 모델은 UNSUPPORTED_MODEL 예외를 던진다")
    fun `resolveModel - gemini 모델은 UNSUPPORTED_MODEL`() {
        val ex = assertThrows<CustomException> {
            chatService.resolveModel("gemini-3-flash")
        }
        assertEquals(ErrorCode.UNSUPPORTED_MODEL, ex.errorCode)
    }

    // GIVEN: 허용 목록 밖의 모델
    // WHEN: resolveModel 호출
    // THEN: UNSUPPORTED_MODEL 예외
    @Test
    @DisplayName("허용 목록 밖의 모델은 UNSUPPORTED_MODEL 예외를 던진다")
    fun `resolveModel - 미허용 모델은 UNSUPPORTED_MODEL`() {
        val ex = assertThrows<CustomException> {
            chatService.resolveModel("claude-3-opus")
        }
        assertEquals(ErrorCode.UNSUPPORTED_MODEL, ex.errorCode)
    }

    // GIVEN: model = null
    // WHEN: resolveModel 호출
    // THEN: openAiProperties.defaultModel 반환
    @Test
    @DisplayName("null 모델은 기본 모델을 반환한다")
    fun `resolveModel - null이면 기본 모델 반환`() {
        val model = chatService.resolveModel(null)
        assertEquals("gpt-5-nano", model)
    }

    // GIVEN: "gpt-5-nano"
    // WHEN: resolveModel 호출
    // THEN: "gpt-5-nano" 반환
    @Test
    @DisplayName("gpt-5-nano는 그대로 반환된다")
    fun `resolveModel - gpt-5-nano는 통과`() {
        val model = chatService.resolveModel("gpt-5-nano")
        assertEquals("gpt-5-nano", model)
    }

    // GIVEN: "gpt-5.2"
    // WHEN: resolveModel 호출
    // THEN: "gpt-5.2" 반환
    @Test
    @DisplayName("gpt-5.2는 그대로 반환된다")
    fun `resolveModel - gpt-5점2는 통과`() {
        val model = chatService.resolveModel("gpt-5.2")
        assertEquals("gpt-5.2", model)
    }

    // ---- createChat (퍼사드 흐름) ----

    // GIVEN: 유효한 요청
    // WHEN: createChat 호출
    // THEN: openThread → aiProvider.complete → persistChat 순서로 호출되고 응답이 올바르다
    @Test
    @DisplayName("createChat은 openThread → AI complete → persistChat 순서로 호출한다")
    fun `createChat - openThread 후 AI 호출 후 persistChat 순서 검증`() {
        val userId = testUser.id!!
        val threadId = testThread.id!!
        val req = ChatCreateRequest(question = "테스트 질문", isStreaming = false, model = "gpt-5-nano")
        val aiAnswer = "AI 답변"
        val chatId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now()
        val messages = listOf(AiMessage("user", "테스트 질문"))
        val ctx = ThreadContext(threadId = threadId, messages = messages)
        val result = ChatResult(chatId = chatId, threadId = threadId, createdAt = createdAt)

        every { chatTransactionService.openThread(userId, req.question) } returns ctx
        every { aiProvider.complete(messages, "gpt-5-nano") } returns aiAnswer
        every { chatTransactionService.persistChat(threadId, userId, req.question, aiAnswer, "gpt-5-nano") } returns result

        val response = chatService.createChat(userId, req)

        assertEquals(req.question, response.question)
        assertEquals(aiAnswer, response.answer)
        assertEquals("gpt-5-nano", response.model)
        assertEquals(threadId, response.threadId)
        assertEquals(chatId, response.id)

        verifyOrder {
            chatTransactionService.openThread(userId, req.question)
            aiProvider.complete(messages, "gpt-5-nano")
            chatTransactionService.persistChat(threadId, userId, req.question, aiAnswer, "gpt-5-nano")
        }
    }

    // GIVEN: 지원하지 않는 모델 요청
    // WHEN: createChat 호출
    // THEN: UNSUPPORTED_MODEL 예외, openThread/AI 호출 없음
    @Test
    @DisplayName("미지원 모델로 createChat 호출 시 UNSUPPORTED_MODEL 예외가 발생한다")
    fun `createChat - 미지원 모델이면 UNSUPPORTED_MODEL 예외`() {
        val req = ChatCreateRequest(question = "질문", model = "gemini-3-flash")

        val ex = assertThrows<CustomException> {
            chatService.createChat(testUser.id!!, req)
        }

        assertEquals(ErrorCode.UNSUPPORTED_MODEL, ex.errorCode)
        verify(exactly = 0) { aiProvider.complete(any(), any()) }
        verify(exactly = 0) { chatTransactionService.openThread(any(), any()) }
    }
}
