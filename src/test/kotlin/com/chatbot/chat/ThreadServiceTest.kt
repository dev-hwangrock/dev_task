package com.chatbot.chat

import com.chatbot.ai.AiProvider
import com.chatbot.ai.openai.OpenAiProperties
import com.chatbot.analytics.ActivityLogRepository
import com.chatbot.domain.chat.ChatThread
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

/**
 * TDD: 30분 규칙 경계값 검증.
 *
 * PLAN:
 * 판정 로직: cutoff = now - 30분. lastChatAt.isAfter(cutoff)가 true이면 기존 스레드 재사용.
 * - 29분 전 → isAfter(cutoff) = true  → 기존 스레드 반환
 * - 31분 전 → isAfter(cutoff) = false → 신규 스레드 생성
 * - 정확히 30분 전 → isAfter(cutoff) = false (equal은 after가 아님) → 신규 스레드 생성
 * - 스레드 없음 → 신규 스레드 생성
 */
class ThreadServiceTest {

    private val threadRepository: ThreadRepository = mockk()
    private val chatRepository: ChatRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val activityLogRepository: ActivityLogRepository = mockk()
    private val aiProvider: AiProvider = mockk()
    private val openAiProperties: OpenAiProperties = mockk()
    private val transactionManager: PlatformTransactionManager = mockk()

    private lateinit var chatService: ChatService
    private lateinit var testUser: User

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
    }

    // GIVEN: 마지막 스레드의 lastChatAt이 29분 전
    // WHEN: getOrCreateThread 호출
    // THEN: 기존 스레드를 반환하고 신규 저장은 없다
    @Test
    @DisplayName("lastChatAt이 29분 전이면 기존 스레드를 재사용한다")
    fun `getOrCreateThread - lastChatAt 29분 전이면 기존 스레드 재사용`() {
        val existingThread = ChatThread(
            user = testUser,
            lastChatAt = OffsetDateTime.now().minusMinutes(29),
        )
        every { threadRepository.findTopByUserIdOrderByLastChatAtDesc(testUser.id!!) } returns existingThread

        val result = chatService.getOrCreateThread(testUser.id!!)

        assertEquals(existingThread.id, result.id)
        verify(exactly = 0) { userRepository.findById(any<UUID>()) }
        verify(exactly = 0) { threadRepository.save(any()) }
    }

    // GIVEN: 마지막 스레드의 lastChatAt이 31분 전
    // WHEN: getOrCreateThread 호출
    // THEN: 신규 스레드를 생성하고 저장한다
    @Test
    @DisplayName("lastChatAt이 31분 전이면 신규 스레드를 생성한다")
    fun `getOrCreateThread - lastChatAt 31분 전이면 신규 스레드 생성`() {
        val oldThread = ChatThread(
            user = testUser,
            lastChatAt = OffsetDateTime.now().minusMinutes(31),
        )
        val newThread = ChatThread(user = testUser)

        every { threadRepository.findTopByUserIdOrderByLastChatAtDesc(testUser.id!!) } returns oldThread
        every { userRepository.findById(testUser.id!!) } returns Optional.of(testUser)
        every { threadRepository.save(any()) } returns newThread

        val result = chatService.getOrCreateThread(testUser.id!!)

        assertEquals(newThread.id, result.id)
        verify(exactly = 1) { threadRepository.save(any()) }
    }

    // GIVEN: 마지막 스레드의 lastChatAt이 정확히 30분 전 (경계값)
    // WHEN: getOrCreateThread 호출
    // THEN: 신규 스레드를 생성한다 (30분 이내가 아니므로)
    @Test
    @DisplayName("lastChatAt이 정확히 30분 전이면 신규 스레드를 생성한다 (경계값)")
    fun `getOrCreateThread - lastChatAt 정확히 30분 전이면 신규 스레드 생성`() {
        // 테스트 내 now()와 서비스 내 now() 사이에 수 밀리초 차이가 생기므로
        // 테스트에서 계산한 "30분 전"이 서비스의 cutoff보다 미세하게 이전 = isAfter false → 신규 생성
        val exactlyThirtyMinAgo = OffsetDateTime.now().minusMinutes(30)
        val oldThread = ChatThread(user = testUser, lastChatAt = exactlyThirtyMinAgo)
        val newThread = ChatThread(user = testUser)

        every { threadRepository.findTopByUserIdOrderByLastChatAtDesc(testUser.id!!) } returns oldThread
        every { userRepository.findById(testUser.id!!) } returns Optional.of(testUser)
        every { threadRepository.save(any()) } returns newThread

        val result = chatService.getOrCreateThread(testUser.id!!)

        assertEquals(newThread.id, result.id)
        verify(exactly = 1) { threadRepository.save(any()) }
    }

    // GIVEN: 유저의 스레드가 없음
    // WHEN: getOrCreateThread 호출
    // THEN: 신규 스레드를 생성한다
    @Test
    @DisplayName("스레드가 없으면 신규 스레드를 생성한다")
    fun `getOrCreateThread - 스레드 없으면 신규 스레드 생성`() {
        val newThread = ChatThread(user = testUser)

        every { threadRepository.findTopByUserIdOrderByLastChatAtDesc(testUser.id!!) } returns null
        every { userRepository.findById(testUser.id!!) } returns Optional.of(testUser)
        every { threadRepository.save(any()) } returns newThread

        val result = chatService.getOrCreateThread(testUser.id!!)

        assertEquals(newThread.id, result.id)
        verify(exactly = 1) { userRepository.findById(testUser.id!!) }
        verify(exactly = 1) { threadRepository.save(any()) }
    }
}
