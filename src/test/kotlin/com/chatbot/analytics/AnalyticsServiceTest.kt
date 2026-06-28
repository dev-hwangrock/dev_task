package com.chatbot.analytics

import com.chatbot.analytics.dto.ActivitySummaryResponse
import com.chatbot.chat.ChatRepository
import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.domain.chat.Chat
import com.chatbot.domain.chat.ChatThread
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.global.common.CsvUtil
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * TDD: RED → GREEN → IMPROVE
 *
 * PLAN:
 * 1. 24h 이내 활동 집계 - 정확한 카운트
 *    GIVEN  SIGNUP=3, LOGIN=10, CHAT_CREATE=20 건의 로그가 24h 이내 존재
 *    WHEN   getActivitySummary() 호출
 *    THEN   signupCount=3, loginCount=10, chatCreateCount=20 반환
 *
 * 2. countByActionAndCreatedAtAfter 호출 인자(from이 약 24h전) 검증
 *    GIVEN  getActivitySummary() 호출
 *    WHEN   ActivityLogRepository.countByActionAndCreatedAtAfter 호출 인자 캡처
 *    THEN   from이 현재 시각으로부터 약 24h 전(오차 ±1분 허용)
 *
 * 3. 데이터 없음 → 모두 0
 *    GIVEN  24h 이내 데이터 없음(0 반환)
 *    WHEN   getActivitySummary() 호출
 *    THEN   signupCount=0, loginCount=0, chatCreateCount=0
 *
 * 4. generateReport - 24h 채팅 데이터를 올바른 헤더/행으로 CSV 기록
 *    GIVEN  24h 이내 채팅 1건 존재
 *    WHEN   generateReport(response) 호출
 *    THEN   CsvUtil.writeCsvToResponse에 올바른 headers와 rows 전달
 */
class AnalyticsServiceTest {

    private val activityLogRepository: ActivityLogRepository = mockk()
    private val chatRepository: ChatRepository = mockk()

    private lateinit var analyticsService: AnalyticsService

    @BeforeEach
    fun setUp() {
        analyticsService = AnalyticsService(activityLogRepository, chatRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(CsvUtil)
    }

    // ── PLAN 1 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("24h 이내 SIGNUP=3, LOGIN=10, CHAT_CREATE=20 건 존재 시 카운트가 정확해야 한다")
    fun `getActivitySummary returns correct counts for 24h window`() {
        // GIVEN
        every {
            activityLogRepository.countByActionAndCreatedAtAfter(ActivityAction.SIGNUP, any())
        } returns 3L
        every {
            activityLogRepository.countByActionAndCreatedAtAfter(ActivityAction.LOGIN, any())
        } returns 10L
        every {
            activityLogRepository.countByActionAndCreatedAtAfter(ActivityAction.CHAT_CREATE, any())
        } returns 20L

        // WHEN
        val result = analyticsService.getActivitySummary()

        // THEN
        assertEquals(3L, result.signupCount)
        assertEquals(10L, result.loginCount)
        assertEquals(20L, result.chatCreateCount)
        assertTrue(result.from.isBefore(result.to), "from must be before to")
    }

    // ── PLAN 2 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countByActionAndCreatedAtAfter의 from 인자가 현재 시각 약 24h 전이어야 한다")
    fun `getActivitySummary calls repository with from approximately 24h ago`() {
        // GIVEN
        val fromSlot = slot<OffsetDateTime>()
        every {
            activityLogRepository.countByActionAndCreatedAtAfter(any(), capture(fromSlot))
        } returns 0L

        val expectedFrom = OffsetDateTime.now().minusHours(24)

        // WHEN
        analyticsService.getActivitySummary()

        // THEN — from 은 현재 기준 24h 전 ±1분 이내여야 한다
        val from = fromSlot.captured
        assertTrue(
            from.isAfter(expectedFrom.minusMinutes(1)) && from.isBefore(expectedFrom.plusMinutes(1)),
            "from ($from) should be approximately 24h before now (expected ~$expectedFrom)"
        )
    }

    // ── PLAN 3 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("24h 이내 데이터가 없으면 모든 카운트가 0이어야 한다")
    fun `getActivitySummary returns zero counts when no data exists`() {
        // GIVEN
        every {
            activityLogRepository.countByActionAndCreatedAtAfter(any(), any())
        } returns 0L

        // WHEN
        val result = analyticsService.getActivitySummary()

        // THEN
        assertEquals(0L, result.signupCount)
        assertEquals(0L, result.loginCount)
        assertEquals(0L, result.chatCreateCount)
    }

    // ── PLAN 4 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateReport는 24h 이내 채팅을 올바른 헤더와 행으로 CSV에 기록해야 한다")
    fun `generateReport writes csv with correct headers and rows`() {
        // GIVEN
        val user = User(
            id = UUID.randomUUID(),
            email = "user@example.com",
            password = "encoded",
            name = "테스트유저",
            role = Role.MEMBER
        )
        val thread = ChatThread(user = user)
        val chatId = UUID.randomUUID()
        val createdAt = OffsetDateTime.now().minusHours(1)
        val chat = Chat(
            id = chatId,
            thread = thread,
            user = user,
            question = "질문입니다",
            answer = "답변입니다",
            model = "gpt-5-nano",
            createdAt = createdAt
        )
        val response = mockk<HttpServletResponse>(relaxed = true)

        every { chatRepository.findByCreatedAtBetween(any(), any()) } returns listOf(chat)

        mockkObject(CsvUtil)
        val rowsSlot = slot<List<List<String>>>()
        val headersSlot = slot<List<String>>()
        every { CsvUtil.writeCsvToResponse(capture(rowsSlot), capture(headersSlot), response) } just Runs

        // WHEN
        analyticsService.generateReport(response)

        // THEN
        verify(exactly = 1) { chatRepository.findByCreatedAtBetween(any(), any()) }
        verify(exactly = 1) { CsvUtil.writeCsvToResponse(any(), any(), response) }

        val expectedHeaders = listOf(
            "chat_id", "thread_id", "user_id", "user_email", "user_name",
            "question", "answer", "model", "created_at"
        )
        assertEquals(expectedHeaders, headersSlot.captured)

        assertEquals(1, rowsSlot.captured.size)
        val row = rowsSlot.captured[0]
        assertEquals(chatId.toString(), row[0])
        assertEquals(thread.id.toString(), row[1])
        assertEquals(user.id.toString(), row[2])
        assertEquals("user@example.com", row[3])
        assertEquals("테스트유저", row[4])
        assertEquals("질문입니다", row[5])
        assertEquals("답변입니다", row[6])
        assertEquals("gpt-5-nano", row[7])
        assertEquals(createdAt.toString(), row[8])
    }
}
