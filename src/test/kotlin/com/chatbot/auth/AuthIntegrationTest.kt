package com.chatbot.auth

import com.chatbot.auth.dto.LoginRequest
import com.chatbot.auth.dto.SignupRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/**
 * TDD: RED → GREEN (통합 테스트)
 *
 * PLAN:
 * 1. 회원가입 성공 → 201 Created + SignupResponse
 *    GIVEN  유효한 SignupRequest
 *    WHEN   POST /api/v1/auth/signup
 *    THEN   201 + id, email, name, role, createdAt 반환
 *
 * 2. 이메일 중복 재가입 → 409 Conflict
 *    GIVEN  이미 가입된 이메일로 동일 요청
 *    WHEN   POST /api/v1/auth/signup
 *    THEN   409 + DUPLICATE_EMAIL 에러 코드
 *
 * 3. 로그인 성공 → 200 OK + accessToken
 *    GIVEN  가입된 유저의 이메일/비밀번호
 *    WHEN   POST /api/v1/auth/login
 *    THEN   200 + accessToken 포함
 *
 * 4. 잘못된 비밀번호 로그인 → 401 Unauthorized
 *    GIVEN  가입된 유저 이메일, 틀린 비밀번호
 *    WHEN   POST /api/v1/auth/login
 *    THEN   401 + UNAUTHORIZED 에러 코드
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class AuthIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val signupUrl = "/api/v1/auth/signup"
    private val loginUrl = "/api/v1/auth/login"

    private val testEmail = "integration@example.com"
    private val testPassword = "password123"
    private val testName = "통합테스트유저"

    // PLAN 1: 회원가입 성공
    @Test
    @DisplayName("유효한 요청으로 회원가입 시 201과 SignupResponse를 반환한다")
    fun `signup with valid request returns 201 and SignupResponse`() {
        val req = SignupRequest(email = testEmail, password = testPassword, name = testName)

        mockMvc.post(signupUrl) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(req)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.email") { value(testEmail) }
            jsonPath("$.name") { value(testName) }
            jsonPath("$.role") { value("MEMBER") }
            jsonPath("$.id") { isNotEmpty() }
            jsonPath("$.createdAt") { isNotEmpty() }
        }
    }

    // PLAN 2: 이메일 중복 재가입
    @Test
    @DisplayName("이미 가입된 이메일로 재가입 시 409를 반환한다")
    fun `signup with duplicate email returns 409`() {
        val req = SignupRequest(email = testEmail, password = testPassword, name = testName)

        // 첫 번째 가입
        mockMvc.post(signupUrl) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(req)
        }.andExpect {
            status { isCreated() }
        }

        // 두 번째 가입 (중복)
        mockMvc.post(signupUrl) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(req)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DUPLICATE_EMAIL") }
        }
    }

    // PLAN 3: 로그인 성공
    @Test
    @DisplayName("올바른 자격증명으로 로그인 시 200과 accessToken을 반환한다")
    fun `login with valid credentials returns 200 and accessToken`() {
        // 사전 가입
        val signupReq = SignupRequest(email = testEmail, password = testPassword, name = testName)
        mockMvc.post(signupUrl) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(signupReq)
        }.andExpect { status { isCreated() } }

        // 로그인
        val loginReq = LoginRequest(email = testEmail, password = testPassword)
        mockMvc.post(loginUrl) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(loginReq)
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isNotEmpty() }
        }
    }

    // PLAN 4: 잘못된 비밀번호 로그인
    @Test
    @DisplayName("잘못된 비밀번호로 로그인 시 401을 반환한다")
    fun `login with wrong password returns 401`() {
        // 사전 가입
        val signupReq = SignupRequest(email = testEmail, password = testPassword, name = testName)
        mockMvc.post(signupUrl) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(signupReq)
        }.andExpect { status { isCreated() } }

        // 잘못된 비밀번호로 로그인
        val loginReq = LoginRequest(email = testEmail, password = "wrong_password_1")
        mockMvc.post(loginUrl) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(loginReq)
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }
}
