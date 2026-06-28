package com.chatbot.chat

import com.chatbot.ai.AiMessage
import com.chatbot.ai.AiProvider
import com.chatbot.ai.openai.OpenAiProperties
import com.chatbot.analytics.ActivityLogRepository
import com.chatbot.chat.dto.ChatCreateRequest
import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.domain.chat.Chat
import com.chatbot.domain.chat.ChatThread
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import java.util.UUID

/**
 * TDD: 모델 검증, 히스토리 구성, 비스트리밍 대화 생성 검증.
 */
class ChatServiceTest {

    private val threadRepository: ThreadRepository = mockk()
    private val chatRepository: ChatRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val activityLogRepository: ActivityLogRepository = mockk()
    private val aiProvider: AiProvider = mockk()
    private val openAiProperties: OpenAiProperties = mockk()
    private val transactionManager: PlatformTransactionManager = mockk()

    private lateinit var chatService: ChatService
    private lateinit var testUser: User
    private lateinit var testThread: ChatThread

    @BeforeEach
    fun setUp() {
        chatService = ChatService(
            threadRepository = threadRepository,
            chatRepository = chatRepository,
            userRepository = userRepository,
            activityLogRepository = activityLogRepository,
            aiProvider = aiProvider,
            openAiProperties = openAiProperties,
            transactionManager = transactionManager,
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

    // ---- buildMessages ----

    // GIVEN: 히스토리 없음
    // WHEN: buildMessages 호출
    // THEN: 현재 질문만 담긴 user 메시지 1개
    @Test
    @DisplayName("히스토리가 없으면 현재 질문 메시지 하나만 반환한다")
    fun `buildMessages - 히스토리 없으면 현재 질문만`() {
        val messages = chatService.buildMessages(emptyList(), "현재 질문")

        assertEquals(1, messages.size)
        assertEquals(AiMessage("user", "현재 질문"), messages[0])
    }

    // GIVEN: 이전 대화 1개
    // WHEN: buildMessages 호출
    // THEN: user/assistant 쌍 후 현재 질문 = 총 3개
    @Test
    @DisplayName("히스토리 1개가 있으면 user-assistant-user 순서의 메시지 3개를 반환한다")
    fun `buildMessages - 히스토리 있으면 user-assistant 쌍 후 현재 질문`() {
        val historyChat = Chat(
            thread = testThread,
            user = testUser,
            question = "이전 질문",
            answer = "이전 답변",
            model = "gpt-5-nano",
        )

        val messages = chatService.buildMessages(listOf(historyChat), "현재 질문")

        assertEquals(3, messages.size)
        assertEquals(AiMessage("user", "이전 질문"), messages[0])
        assertEquals(AiMessage("assistant", "이전 답변"), messages[1])
        assertEquals(AiMessage("user", "현재 질문"), messages[2])
    }

    // GIVEN: 이전 대화 2개
    // WHEN: buildMessages 호출
    // THEN: user/assistant 쌍 2개 후 현재 질문 = 총 5개
    @Test
    @DisplayName("히스토리 2개가 있으면 5개의 메시지를 올바른 순서로 반환한다")
    fun `buildMessages - 히스토리 2개이면 5개 메시지`() {
        val chat1 = Chat(thread = testThread, user = testUser, question = "q1", answer = "a1", model = "gpt-5-nano")
        val chat2 = Chat(thread = testThread, user = testUser, question = "q2", answer = "a2", model = "gpt-5-nano")

        val messages = chatService.buildMessages(listOf(chat1, chat2), "현재 질문")

        assertEquals(5, messages.size)
        assertEquals(AiMessage("user", "q1"), messages[0])
        assertEquals(AiMessage("assistant", "a1"), messages[1])
        assertEquals(AiMessage("user", "q2"), messages[2])
        assertEquals(AiMessage("assistant", "a2"), messages[3])
        assertEquals(AiMessage("user", "현재 질문"), messages[4])
    }

    // ---- createChat (비스트리밍) ----

    // GIVEN: 유효한 요청 + 기존 스레드 재사용 (29분 내)
    // WHEN: createChat 호출
    // THEN: aiProvider.complete 1회 호출, chatRepository.save 1회, ActivityLog CHAT_CREATE 저장
    @Test
    @DisplayName("비스트리밍 createChat은 AI 응답을 저장하고 ActivityLog를 남긴다")
    fun `createChat - 비스트리밍으로 저장과 ActivityLog 검증`() {
        val req = ChatCreateRequest(question = "테스트 질문", isStreaming = false, model = "gpt-5-nano")
        val aiAnswer = "AI 답변"
        val savedChat = Chat(
            id = UUID.randomUUID(),
            thread = testThread,
            user = testUser,
            question = req.question,
            answer = aiAnswer,
            model = "gpt-5-nano",
        )

        every { threadRepository.findTopByUserIdOrderByLastChatAtDesc(testUser.id!!) } returns testThread
        every { chatRepository.findByThreadIdOrderByCreatedAtAsc(testThread.id!!) } returns emptyList()
        every { aiProvider.complete(any(), eq("gpt-5-nano")) } returns aiAnswer
        every { chatRepository.save(any()) } returns savedChat
        every { activityLogRepository.save(any()) } returns mockk()

        val response = chatService.createChat(testUser.id!!, req)

        assertEquals(req.question, response.question)
        assertEquals(aiAnswer, response.answer)
        assertEquals("gpt-5-nano", response.model)
        assertEquals(testThread.id, response.threadId)

        verify(exactly = 1) { aiProvider.complete(any(), eq("gpt-5-nano")) }
        verify(exactly = 1) { chatRepository.save(any()) }
        verify(exactly = 1) {
            activityLogRepository.save(
                match { it.action == ActivityAction.CHAT_CREATE },
            )
        }
    }

    // GIVEN: 지원하지 않는 모델 요청
    // WHEN: createChat 호출
    // THEN: UNSUPPORTED_MODEL 예외, AI 호출 없음
    @Test
    @DisplayName("미지원 모델로 createChat 호출 시 UNSUPPORTED_MODEL 예외가 발생한다")
    fun `createChat - 미지원 모델이면 UNSUPPORTED_MODEL 예외`() {
        val req = ChatCreateRequest(question = "질문", model = "gemini-3-flash")

        val ex = assertThrows<CustomException> {
            chatService.createChat(testUser.id!!, req)
        }

        assertEquals(ErrorCode.UNSUPPORTED_MODEL, ex.errorCode)
        verify(exactly = 0) { aiProvider.complete(any(), any()) }
    }
}
