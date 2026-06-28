package com.chatbot.feedback

import com.chatbot.chat.ChatRepository
import com.chatbot.domain.chat.Chat
import com.chatbot.domain.chat.ChatThread
import com.chatbot.domain.feedback.Feedback
import com.chatbot.domain.feedback.FeedbackStatus
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.feedback.dto.FeedbackCreateRequest
import com.chatbot.feedback.dto.FeedbackStatusUpdateRequest
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.global.security.CustomUserDetails
import com.chatbot.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.util.Optional
import java.util.UUID

// TDD - RED phase: 이 테스트들이 먼저 실패하는 것을 확인한 뒤 GREEN 구현
class FeedbackServiceTest {

    private lateinit var feedbackRepository: FeedbackRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var userRepository: UserRepository
    private lateinit var feedbackService: FeedbackService

    private lateinit var memberUser: User
    private lateinit var otherUser: User
    private lateinit var adminUser: User
    private lateinit var memberThread: ChatThread
    private lateinit var chat: Chat
    private lateinit var memberDetails: CustomUserDetails
    private lateinit var adminDetails: CustomUserDetails

    @BeforeEach
    fun setUp() {
        feedbackRepository = mockk()
        chatRepository = mockk()
        userRepository = mockk()
        feedbackService = FeedbackService(feedbackRepository, chatRepository, userRepository)

        memberUser = User(
            id = UUID.randomUUID(),
            email = "member@test.com",
            password = "encoded_pw",
            name = "Member",
            role = Role.MEMBER
        )
        otherUser = User(
            id = UUID.randomUUID(),
            email = "other@test.com",
            password = "encoded_pw",
            name = "Other",
            role = Role.MEMBER
        )
        adminUser = User(
            id = UUID.randomUUID(),
            email = "admin@test.com",
            password = "encoded_pw",
            name = "Admin",
            role = Role.ADMIN
        )

        memberThread = ChatThread(id = UUID.randomUUID(), user =memberUser)
        chat = Chat(
            id = UUID.randomUUID(),
            thread = memberThread,
            user = memberUser,
            question = "테스트 질문",
            answer = "테스트 답변",
            model = "gpt-5-nano"
        )

        memberDetails = CustomUserDetails(
            id = memberUser.id!!,
            role = Role.MEMBER,
            email = memberUser.email,
            encodedPassword = memberUser.password
        )
        adminDetails = CustomUserDetails(
            id = adminUser.id!!,
            role = Role.ADMIN,
            email = adminUser.email,
            encodedPassword = adminUser.password
        )
    }

    // =========================================================
    // createFeedback
    // =========================================================

    // PLAN
    // GIVEN: MEMBER 유저, 본인이 소유한 chat
    // WHEN:  createFeedback 호출
    // THEN:  FeedbackResponse 반환, chatId/userId/isPositive 일치
    @Test
    @DisplayName("MEMBER가 본인 대화에 피드백 생성 성공")
    fun `MEMBER creates feedback for own chat successfully`() {
        val req = FeedbackCreateRequest(chatId = chat.id, isPositive = true)
        val savedFeedback = Feedback(id = UUID.randomUUID(), user =memberUser, chat = chat, isPositive = true)

        every { chatRepository.findById(chat.id!!) } returns Optional.of(chat)
        every { feedbackRepository.existsByUserIdAndChatId(memberUser.id!!, chat.id!!) } returns false
        every { userRepository.findById(memberUser.id!!) } returns Optional.of(memberUser)
        every { feedbackRepository.save(any()) } returns savedFeedback

        val result = feedbackService.createFeedback(memberDetails, req)

        assertNotNull(result)
        assertEquals(chat.id, result.chatId)
        assertEquals(memberUser.id, result.userId)
        assertEquals(true, result.isPositive)
        assertEquals("PENDING", result.status)
        verify { feedbackRepository.save(any()) }
    }

    // PLAN
    // GIVEN: MEMBER 유저, 동일한 chatId로 이미 피드백이 존재
    // WHEN:  createFeedback 재호출
    // THEN:  DUPLICATE_FEEDBACK(409) 예외
    @Test
    @DisplayName("동일 chat 재생성 시 DUPLICATE_FEEDBACK 예외")
    fun `Duplicate feedback throws DUPLICATE_FEEDBACK`() {
        val req = FeedbackCreateRequest(chatId = chat.id, isPositive = true)

        every { chatRepository.findById(chat.id!!) } returns Optional.of(chat)
        every { feedbackRepository.existsByUserIdAndChatId(memberUser.id!!, chat.id!!) } returns true

        val exception = assertThrows<CustomException> {
            feedbackService.createFeedback(memberDetails, req)
        }

        assertEquals(ErrorCode.DUPLICATE_FEEDBACK, exception.errorCode)
    }

    // PLAN
    // GIVEN: MEMBER 유저, 타인 chat에 대해 피드백 생성 시도
    // WHEN:  createFeedback 호출
    // THEN:  FORBIDDEN(403) 예외
    @Test
    @DisplayName("MEMBER가 타인 대화에 피드백 생성 시 FORBIDDEN 예외")
    fun `MEMBER creating feedback for others chat throws FORBIDDEN`() {
        val otherThread = ChatThread(id = UUID.randomUUID(), user =otherUser)
        val otherChat = Chat(
            id = UUID.randomUUID(),
            thread = otherThread,
            user = otherUser,
            question = "타인의 질문",
            answer = "타인의 답변",
            model = "gpt-5-nano"
        )
        val req = FeedbackCreateRequest(chatId = otherChat.id, isPositive = true)

        every { chatRepository.findById(otherChat.id!!) } returns Optional.of(otherChat)

        val exception = assertThrows<CustomException> {
            feedbackService.createFeedback(memberDetails, req)
        }

        assertEquals(ErrorCode.FORBIDDEN, exception.errorCode)
    }

    // PLAN
    // GIVEN: ADMIN 유저, 타인(memberUser) chat에 대해 피드백 생성 시도
    // WHEN:  createFeedback 호출
    // THEN:  성공 - ADMIN은 모든 대화에 피드백 허용
    @Test
    @DisplayName("ADMIN이 타인 대화에 피드백 생성 성공")
    fun `ADMIN can create feedback for any chat`() {
        val req = FeedbackCreateRequest(chatId = chat.id, isPositive = false)
        val savedFeedback = Feedback(id = UUID.randomUUID(), user =adminUser, chat = chat, isPositive = false)

        every { chatRepository.findById(chat.id!!) } returns Optional.of(chat)
        every { feedbackRepository.existsByUserIdAndChatId(adminUser.id!!, chat.id!!) } returns false
        every { userRepository.findById(adminUser.id!!) } returns Optional.of(adminUser)
        every { feedbackRepository.save(any()) } returns savedFeedback

        val result = feedbackService.createFeedback(adminDetails, req)

        assertNotNull(result)
        assertEquals(false, result.isPositive)
    }

    // PLAN
    // GIVEN: 존재하지 않는 chatId
    // WHEN:  createFeedback 호출
    // THEN:  NOT_FOUND(404) 예외
    @Test
    @DisplayName("존재하지 않는 chatId로 피드백 생성 시 NOT_FOUND 예외")
    fun `Creating feedback with non-existent chatId throws NOT_FOUND`() {
        val nonExistentChatId = UUID.randomUUID()
        val req = FeedbackCreateRequest(chatId = nonExistentChatId, isPositive = true)

        every { chatRepository.findById(nonExistentChatId) } returns Optional.empty()

        val exception = assertThrows<CustomException> {
            feedbackService.createFeedback(memberDetails, req)
        }

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode)
    }

    // PLAN
    // GIVEN: DB 유니크 제약 위반 (동시성 시나리오)
    // WHEN:  feedbackRepository.save() 에서 DataIntegrityViolationException 발생
    // THEN:  DUPLICATE_FEEDBACK(409) 예외로 매핑
    @Test
    @DisplayName("DataIntegrityViolationException 발생 시 DUPLICATE_FEEDBACK으로 매핑")
    fun `DataIntegrityViolationException is mapped to DUPLICATE_FEEDBACK`() {
        val req = FeedbackCreateRequest(chatId = chat.id, isPositive = true)

        every { chatRepository.findById(chat.id!!) } returns Optional.of(chat)
        every { feedbackRepository.existsByUserIdAndChatId(memberUser.id!!, chat.id!!) } returns false
        every { userRepository.findById(memberUser.id!!) } returns Optional.of(memberUser)
        every { feedbackRepository.save(any()) } throws DataIntegrityViolationException("unique constraint")

        val exception = assertThrows<CustomException> {
            feedbackService.createFeedback(memberDetails, req)
        }

        assertEquals(ErrorCode.DUPLICATE_FEEDBACK, exception.errorCode)
    }

    // =========================================================
    // getFeedbacks
    // =========================================================

    // PLAN
    // GIVEN: MEMBER 유저, isPositive 필터 없음
    // WHEN:  getFeedbacks 호출
    // THEN:  본인 피드백 목록 반환 (findByUserId 호출)
    @Test
    @DisplayName("MEMBER 피드백 전체 조회 (필터 없음)")
    fun `MEMBER gets own feedbacks without filter`() {
        val feedback = Feedback(id = UUID.randomUUID(), user =memberUser, chat = chat, isPositive = true)
        val page = PageImpl(listOf(feedback))

        every { feedbackRepository.findByUserId(memberUser.id!!, any()) } returns page

        val result = feedbackService.getFeedbacks(memberDetails, null, "desc", 0, 20)

        assertEquals(1, result.totalElements)
        verify { feedbackRepository.findByUserId(memberUser.id!!, any<Pageable>()) }
    }

    // PLAN
    // GIVEN: MEMBER 유저, isPositive = true 필터
    // WHEN:  getFeedbacks 호출
    // THEN:  findByUserIdAndIsPositive 호출
    @Test
    @DisplayName("MEMBER isPositive 필터 적용 조회")
    fun `MEMBER gets own feedbacks with isPositive filter`() {
        val feedback = Feedback(id = UUID.randomUUID(), user =memberUser, chat = chat, isPositive = true)
        val page = PageImpl(listOf(feedback))

        every { feedbackRepository.findByUserIdAndIsPositive(memberUser.id!!, true, any()) } returns page

        val result = feedbackService.getFeedbacks(memberDetails, true, "desc", 0, 20)

        assertEquals(1, result.totalElements)
        verify { feedbackRepository.findByUserIdAndIsPositive(memberUser.id!!, true, any<Pageable>()) }
    }

    // PLAN
    // GIVEN: ADMIN 유저, isPositive 필터 없음
    // WHEN:  getFeedbacks 호출
    // THEN:  전체 피드백 조회 (findAll 호출)
    @Test
    @DisplayName("ADMIN 전체 피드백 조회 (필터 없음)")
    fun `ADMIN gets all feedbacks without filter`() {
        val feedback = Feedback(id = UUID.randomUUID(), user =memberUser, chat = chat, isPositive = true)
        val page = PageImpl(listOf(feedback))

        every { feedbackRepository.findAll(any<Pageable>()) } returns page

        val result = feedbackService.getFeedbacks(adminDetails, null, "desc", 0, 20)

        assertEquals(1, result.totalElements)
        verify { feedbackRepository.findAll(any<Pageable>()) }
    }

    // PLAN
    // GIVEN: ADMIN 유저, isPositive = false 필터
    // WHEN:  getFeedbacks 호출
    // THEN:  findByIsPositive 호출
    @Test
    @DisplayName("ADMIN isPositive 필터 적용 전체 조회")
    fun `ADMIN gets all feedbacks with isPositive filter`() {
        val page = PageImpl(emptyList<Feedback>())

        every { feedbackRepository.findByIsPositive(false, any()) } returns page

        val result = feedbackService.getFeedbacks(adminDetails, false, "asc", 0, 10)

        assertEquals(0, result.totalElements)
        verify { feedbackRepository.findByIsPositive(false, any<Pageable>()) }
    }

    // =========================================================
    // updateStatus
    // =========================================================

    // PLAN
    // GIVEN: 존재하는 feedbackId, PENDING → RESOLVED 변경 요청
    // WHEN:  updateStatus 호출
    // THEN:  status = "RESOLVED" FeedbackResponse 반환
    @Test
    @DisplayName("updateStatus 정상 처리 - PENDING → RESOLVED")
    fun `updateStatus updates feedback status successfully`() {
        val feedback = Feedback(id = UUID.randomUUID(), user =memberUser, chat = chat, isPositive = true, status = FeedbackStatus.PENDING)
        val req = FeedbackStatusUpdateRequest(status = FeedbackStatus.RESOLVED)

        every { feedbackRepository.findById(feedback.id!!) } returns Optional.of(feedback)
        every { feedbackRepository.save(feedback) } returns feedback.also { it.status = FeedbackStatus.RESOLVED }

        val result = feedbackService.updateStatus(feedback.id!!, req)

        assertEquals("RESOLVED", result.status)
    }

    // PLAN
    // GIVEN: 존재하지 않는 feedbackId
    // WHEN:  updateStatus 호출
    // THEN:  NOT_FOUND(404) 예외
    @Test
    @DisplayName("존재하지 않는 feedbackId로 updateStatus 시 NOT_FOUND 예외")
    fun `updateStatus with non-existent feedbackId throws NOT_FOUND`() {
        val nonExistentId = UUID.randomUUID()
        val req = FeedbackStatusUpdateRequest(status = FeedbackStatus.RESOLVED)

        every { feedbackRepository.findById(nonExistentId) } returns Optional.empty()

        val exception = assertThrows<CustomException> {
            feedbackService.updateStatus(nonExistentId, req)
        }

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode)
    }
}
