package com.chatbot.chat

import com.chatbot.ai.AiProvider
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.global.security.JwtProvider
import com.chatbot.user.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 통합 테스트: 실제 DB + @MockkBean AiProvider.
 *
 * 검증 대상:
 * 1. 비스트리밍 대화 생성 → 201
 * 2. 대화 목록 조회 → 스레드 그룹화
 * 3. 타인 스레드 삭제 → 403
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @MockkBean
    private lateinit var aiProvider: AiProvider

    private lateinit var testUser: User
    private lateinit var otherUser: User
    private lateinit var testToken: String
    private lateinit var otherToken: String

    @BeforeEach
    fun setUp() {
        // FK 제약을 고려한 순서로 전체 삭제
        jdbcTemplate.execute("TRUNCATE TABLE activity_logs, feedbacks, chats, threads, users CASCADE")

        testUser = userRepository.save(
            User(
                email = "test-${UUID.randomUUID()}@chat.com",
                password = "password",
                name = "TestUser",
                role = Role.MEMBER,
            ),
        )
        otherUser = userRepository.save(
            User(
                email = "other-${UUID.randomUUID()}@chat.com",
                password = "password",
                name = "OtherUser",
                role = Role.MEMBER,
            ),
        )
        testToken = jwtProvider.generateToken(testUser.id!!, testUser.role)
        otherToken = jwtProvider.generateToken(otherUser.id!!, otherUser.role)
    }

    // GIVEN: 인증된 사용자 + 유효한 대화 요청 (비스트리밍)
    // WHEN: POST /api/v1/chats
    // THEN: 201 Created + ChatResponse JSON
    @Test
    @DisplayName("비스트리밍 대화 생성 요청은 201을 반환한다")
    fun `POST chats - 비스트리밍으로 201 반환`() {
        every { aiProvider.complete(any(), any()) } returns "AI 테스트 답변"

        mockMvc.perform(
            post("/api/v1/chats")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"테스트 질문","isStreaming":false}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.question").value("테스트 질문"))
            .andExpect(jsonPath("$.answer").value("AI 테스트 답변"))
            .andExpect(jsonPath("$.threadId").isNotEmpty)
            .andExpect(jsonPath("$.model").value("gpt-5-nano"))
    }

    // GIVEN: 대화가 존재하는 사용자
    // WHEN: GET /api/v1/chats
    // THEN: 200 + 스레드 그룹화된 목록 (chats 배열 포함)
    @Test
    @DisplayName("대화 목록 조회는 스레드 단위로 그룹화된 결과를 반환한다")
    fun `GET chats - 스레드 그룹화 조회`() {
        every { aiProvider.complete(any(), any()) } returns "답변"

        // 대화 생성
        mockMvc.perform(
            post("/api/v1/chats")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"첫 번째 질문"}"""),
        ).andExpect(status().isCreated)

        // 목록 조회
        mockMvc.perform(
            get("/api/v1/chats")
                .header("Authorization", "Bearer $testToken"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content[0].threadId").isNotEmpty)
            .andExpect(jsonPath("$.content[0].chats").isArray)
            .andExpect(jsonPath("$.content[0].chats[0].question").value("첫 번째 질문"))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    // GIVEN: testUser의 스레드
    // WHEN: otherUser가 DELETE /api/v1/threads/{threadId}
    // THEN: 403 Forbidden
    @Test
    @DisplayName("타인의 스레드 삭제 시도는 403을 반환한다")
    fun `DELETE threads - 타인 스레드 삭제는 403`() {
        every { aiProvider.complete(any(), any()) } returns "답변"

        // testUser로 대화 생성 → threadId 추출
        val createResult = mockMvc.perform(
            post("/api/v1/chats")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"질문"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()

        val threadId = objectMapper.readTree(createResult.response.contentAsString)
            .get("threadId").asText()

        // otherUser로 삭제 시도 → 403
        mockMvc.perform(
            delete("/api/v1/threads/$threadId")
                .header("Authorization", "Bearer $otherToken"),
        )
            .andExpect(status().isForbidden)
    }

    // GIVEN: testUser의 스레드
    // WHEN: testUser가 DELETE /api/v1/threads/{threadId}
    // THEN: 204 No Content
    @Test
    @DisplayName("본인의 스레드 삭제는 204를 반환한다")
    fun `DELETE threads - 본인 스레드 삭제는 204`() {
        every { aiProvider.complete(any(), any()) } returns "답변"

        val createResult = mockMvc.perform(
            post("/api/v1/chats")
                .header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"질문"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()

        val threadId = objectMapper.readTree(createResult.response.contentAsString)
            .get("threadId").asText()

        mockMvc.perform(
            delete("/api/v1/threads/$threadId")
                .header("Authorization", "Bearer $testToken"),
        )
            .andExpect(status().isNoContent)
    }
}
