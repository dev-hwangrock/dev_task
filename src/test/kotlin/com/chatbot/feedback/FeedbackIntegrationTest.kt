package com.chatbot.feedback

import com.chatbot.analytics.ActivityLogRepository
import com.chatbot.chat.ChatRepository
import com.chatbot.chat.ThreadRepository
import com.chatbot.domain.chat.Chat
import com.chatbot.domain.chat.ChatThread
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.global.security.JwtProvider
import com.chatbot.user.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * FeedbackIntegrationTest
 *
 * @SpringBootTest: 실제 앱 컨텍스트 로드
 * @AutoConfigureMockMvc: MockMvc 자동 설정
 * @ActiveProfiles("test"): application-test.yml (chatbot_test DB) 사용
 *
 * 테스트 DB 설정: application-test.yml 참조
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedbackIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var threadRepository: ThreadRepository

    @Autowired
    private lateinit var chatRepository: ChatRepository

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var activityLogRepository: ActivityLogRepository

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    private lateinit var memberUser: User
    private lateinit var adminUser: User
    private lateinit var chat: Chat
    private lateinit var memberToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        // 이전 테스트 데이터 정리 (FK 제약 순서 준수)
        activityLogRepository.deleteAll()
        feedbackRepository.deleteAll()
        chatRepository.deleteAll()
        threadRepository.deleteAll()
        userRepository.deleteAll()

        // 테스트 사용자 생성
        memberUser = userRepository.save(
            User(email = "member_${System.nanoTime()}@test.com", password = "encoded_pw", name = "Member", role = Role.MEMBER)
        )
        adminUser = userRepository.save(
            User(email = "admin_${System.nanoTime()}@test.com", password = "encoded_pw", name = "Admin", role = Role.ADMIN)
        )

        // 테스트 대화 생성 (member 소유)
        val thread = threadRepository.save(ChatThread(user = memberUser))
        chat = chatRepository.save(
            Chat(thread = thread, user = memberUser, question = "테스트 질문", answer = "테스트 답변", model = "gpt-5-nano")
        )

        // JWT 토큰 생성
        memberToken = jwtProvider.generateToken(memberUser.id!!, memberUser.role)
        adminToken = jwtProvider.generateToken(adminUser.id!!, adminUser.role)
    }

    // =========================================================
    // POST /api/v1/feedbacks
    // =========================================================

    // PLAN
    // GIVEN: 인증된 MEMBER, 본인 chat에 피드백 생성 요청
    // WHEN:  POST /api/v1/feedbacks
    // THEN:  201, FeedbackResponse(chatId, isPositive=true, status="PENDING")
    @Test
    @DisplayName("피드백 생성 성공 → 201")
    fun `POST feedback returns 201`() {
        val body = """{"chatId":"${chat.id}","isPositive":true}"""

        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.chatId").value(chat.id.toString()))
            .andExpect(jsonPath("$.userId").value(memberUser.id.toString()))
            .andExpect(jsonPath("$.isPositive").value(true))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    // PLAN
    // GIVEN: 동일 chatId로 첫 번째 피드백 생성 성공 후
    // WHEN:  동일 chatId로 두 번째 피드백 생성 요청
    // THEN:  409, code="DUPLICATE_FEEDBACK"
    @Test
    @DisplayName("중복 피드백 생성 → 409 DUPLICATE_FEEDBACK")
    fun `Duplicate feedback returns 409`() {
        val body = """{"chatId":"${chat.id}","isPositive":true}"""

        // 첫 번째 생성 (성공)
        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isCreated)

        // 두 번째 생성 (중복)
        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_FEEDBACK"))
    }

    // PLAN
    // GIVEN: MEMBER가 타인(adminUser) 소유 chat에 피드백 생성 시도
    // WHEN:  POST /api/v1/feedbacks
    // THEN:  403 FORBIDDEN
    @Test
    @DisplayName("MEMBER가 타인 대화에 피드백 생성 시 → 403")
    fun `MEMBER creating feedback for others chat returns 403`() {
        // adminUser 소유 chat 생성
        val adminThread = threadRepository.save(ChatThread(user = adminUser))
        val adminChat = chatRepository.save(
            Chat(thread = adminThread, user = adminUser, question = "Admin Q", answer = "Admin A", model = "gpt-5-nano")
        )

        val body = """{"chatId":"${adminChat.id}","isPositive":true}"""

        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isForbidden)
    }

    // PLAN
    // GIVEN: 존재하지 않는 chatId로 요청
    // WHEN:  POST /api/v1/feedbacks
    // THEN:  404 NOT_FOUND
    @Test
    @DisplayName("존재하지 않는 chatId로 피드백 생성 → 404")
    fun `Feedback creation with non-existent chatId returns 404`() {
        val body = """{"chatId":"${java.util.UUID.randomUUID()}","isPositive":true}"""

        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNotFound)
    }

    // =========================================================
    // GET /api/v1/feedbacks
    // =========================================================

    // PLAN
    // GIVEN: 피드백이 생성된 후
    // WHEN:  GET /api/v1/feedbacks?isPositive=true
    // THEN:  200, content[0].isPositive=true, totalElements=1
    @Test
    @DisplayName("isPositive 필터 조회 → 200, 필터 결과 반환")
    fun `GET feedbacks with isPositive filter returns filtered results`() {
        // 피드백 생성 (isPositive=true)
        val createBody = """{"chatId":"${chat.id}","isPositive":true}"""
        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        ).andExpect(status().isCreated)

        // isPositive=true 필터 조회
        mockMvc.perform(
            get("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .param("isPositive", "true")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content[0].isPositive").value(true))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    // PLAN
    // GIVEN: 피드백 생성 후
    // WHEN:  GET /api/v1/feedbacks?isPositive=false (존재하지 않는 필터)
    // THEN:  200, totalElements=0
    @Test
    @DisplayName("isPositive=false 필터 - 결과 없음 → 200, totalElements=0")
    fun `GET feedbacks with non-matching isPositive filter returns empty`() {
        val createBody = """{"chatId":"${chat.id}","isPositive":true}"""
        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .param("isPositive", "false")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    // PLAN
    // GIVEN: ADMIN 유저, 여러 사용자의 피드백 존재
    // WHEN:  GET /api/v1/feedbacks (필터 없음)
    // THEN:  200, 전체 피드백 반환
    @Test
    @DisplayName("ADMIN 전체 피드백 조회 → 200")
    fun `ADMIN can query all feedbacks`() {
        val createBody = """{"chatId":"${chat.id}","isPositive":true}"""
        mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/api/v1/feedbacks")
                .header("Authorization", "Bearer $adminToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    // =========================================================
    // PATCH /api/v1/feedbacks/{feedbackId}/status
    // =========================================================

    // PLAN
    // GIVEN: 인증된 MEMBER 유저
    // WHEN:  PATCH /api/v1/feedbacks/{id}/status
    // THEN:  403 FORBIDDEN (ADMIN 전용)
    @Test
    @DisplayName("비관리자 PATCH status → 403")
    fun `Non-admin PATCH status returns 403`() {
        // 피드백 생성 후 ID 추출
        val createBody = """{"chatId":"${chat.id}","isPositive":true}"""
        val createResult = mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        ).andExpect(status().isCreated)
            .andReturn()

        val feedbackId = objectMapper.readTree(createResult.response.contentAsString)["id"].asText()

        // MEMBER로 PATCH 시도 → 403
        mockMvc.perform(
            patch("/api/v1/feedbacks/$feedbackId/status")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"RESOLVED"}""")
        )
            .andExpect(status().isForbidden)
    }

    // PLAN
    // GIVEN: 인증된 ADMIN 유저, 존재하는 feedback
    // WHEN:  PATCH /api/v1/feedbacks/{id}/status {"status":"RESOLVED"}
    // THEN:  200, status="RESOLVED"
    @Test
    @DisplayName("ADMIN PATCH status 성공 → 200, status=RESOLVED")
    fun `Admin PATCH status returns 200 with updated status`() {
        // 피드백 생성
        val createBody = """{"chatId":"${chat.id}","isPositive":true}"""
        val createResult = mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        ).andExpect(status().isCreated)
            .andReturn()

        val feedbackId = objectMapper.readTree(createResult.response.contentAsString)["id"].asText()

        // ADMIN으로 PATCH → 200
        mockMvc.perform(
            patch("/api/v1/feedbacks/$feedbackId/status")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"RESOLVED"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
    }

    // PLAN
    // GIVEN: ADMIN 유저, 잘못된 status 값
    // WHEN:  PATCH /api/v1/feedbacks/{id}/status {"status":"INVALID_STATUS"}
    // THEN:  400 BAD_REQUEST
    @Test
    @DisplayName("잘못된 status 값 → 400")
    fun `Invalid status value returns 400`() {
        val createBody = """{"chatId":"${chat.id}","isPositive":true}"""
        val createResult = mockMvc.perform(
            post("/api/v1/feedbacks")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
        ).andExpect(status().isCreated)
            .andReturn()

        val feedbackId = objectMapper.readTree(createResult.response.contentAsString)["id"].asText()

        mockMvc.perform(
            patch("/api/v1/feedbacks/$feedbackId/status")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"INVALID_STATUS"}""")
        )
            .andExpect(status().isBadRequest)
    }

    // PLAN
    // GIVEN: JWT 없이 요청
    // WHEN:  POST /api/v1/feedbacks
    // THEN:  401 UNAUTHORIZED
    @Test
    @DisplayName("인증 없이 요청 → 401")
    fun `Request without authentication returns 401`() {
        val body = """{"chatId":"${chat.id}","isPositive":true}"""

        mockMvc.perform(
            post("/api/v1/feedbacks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isUnauthorized)
    }
}
