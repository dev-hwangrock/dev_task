package com.chatbot.auth

import com.chatbot.analytics.ActivityLogRepository
import com.chatbot.auth.dto.LoginRequest
import com.chatbot.auth.dto.SignupRequest
import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.domain.analytics.ActivityLog
import com.chatbot.domain.user.Role
import com.chatbot.domain.user.User
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.global.security.JwtProvider
import com.chatbot.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.OffsetDateTime
import java.util.UUID

/**
 * TDD: RED → GREEN
 *
 * PLAN:
 * 1. 회원가입 성공
 *    GIVEN  이메일이 존재하지 않는 SignupRequest
 *    WHEN   signup(req) 호출
 *    THEN   User 저장, SIGNUP ActivityLog 저장, SignupResponse 반환
 *
 * 2. 회원가입 이메일 중복
 *    GIVEN  이미 존재하는 이메일의 SignupRequest
 *    WHEN   signup(req) 호출
 *    THEN   CustomException(DUPLICATE_EMAIL) throw
 *
 * 3. 로그인 성공
 *    GIVEN  존재하는 이메일, 올바른 비밀번호의 LoginRequest
 *    WHEN   login(req) 호출
 *    THEN   LOGIN ActivityLog 저장, TokenResponse(accessToken) 반환
 *
 * 4. 로그인 비밀번호 불일치
 *    GIVEN  존재하는 이메일, 잘못된 비밀번호의 LoginRequest
 *    WHEN   login(req) 호출
 *    THEN   CustomException(UNAUTHORIZED) throw
 */
class AuthServiceTest {

    private val userRepository: UserRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val jwtProvider: JwtProvider = mockk()
    private val activityLogRepository: ActivityLogRepository = mockk()

    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        authService = AuthService(
            userRepository = userRepository,
            passwordEncoder = passwordEncoder,
            jwtProvider = jwtProvider,
            activityLogRepository = activityLogRepository
        )
    }

    // PLAN 1: 회원가입 성공
    @Test
    @DisplayName("이메일 미중복 회원가입 성공 시 User와 SIGNUP 로그를 저장하고 SignupResponse를 반환한다")
    fun `signup success returns SignupResponse and saves user and activity log`() {
        // GIVEN
        val req = SignupRequest(
            email = "test@example.com",
            password = "password123",
            name = "테스트유저"
        )
        val encodedPassword = "encoded_password"
        val savedUser = User(
            id = UUID.randomUUID(),
            email = req.email,
            password = encodedPassword,
            name = req.name,
            role = Role.MEMBER,
            createdAt = OffsetDateTime.now()
        )
        val activityLogSlot = slot<ActivityLog>()

        every { userRepository.existsByEmail(req.email) } returns false
        every { passwordEncoder.encode(req.password) } returns encodedPassword
        every { userRepository.save(any()) } returns savedUser
        every { activityLogRepository.save(capture(activityLogSlot)) } answers { activityLogSlot.captured }

        // WHEN
        val result = authService.signup(req)

        // THEN
        assertNotNull(result)
        assertEquals(savedUser.id, result.id)
        assertEquals(req.email, result.email)
        assertEquals(req.name, result.name)
        assertEquals(Role.MEMBER.name, result.role)

        verify(exactly = 1) { userRepository.save(any()) }
        verify(exactly = 1) { activityLogRepository.save(any()) }
        assertEquals(ActivityAction.SIGNUP, activityLogSlot.captured.action)
        assertEquals(savedUser, activityLogSlot.captured.user)
    }

    // PLAN 2: 회원가입 이메일 중복
    @Test
    @DisplayName("이미 존재하는 이메일로 회원가입 시 DUPLICATE_EMAIL 예외가 발생한다")
    fun `signup with duplicate email throws CustomException DUPLICATE_EMAIL`() {
        // GIVEN
        val req = SignupRequest(
            email = "duplicate@example.com",
            password = "password123",
            name = "중복유저"
        )
        every { userRepository.existsByEmail(req.email) } returns true

        // WHEN & THEN
        val exception = assertThrows<CustomException> {
            authService.signup(req)
        }
        assertEquals(ErrorCode.DUPLICATE_EMAIL, exception.errorCode)

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { activityLogRepository.save(any()) }
    }

    // PLAN 3: 로그인 성공
    @Test
    @DisplayName("올바른 이메일과 비밀번호로 로그인 시 JWT 토큰을 포함한 TokenResponse를 반환한다")
    fun `login success returns TokenResponse with accessToken and saves LOGIN log`() {
        // GIVEN
        val req = LoginRequest(
            email = "test@example.com",
            password = "password123"
        )
        val existingUser = User(
            id = UUID.randomUUID(),
            email = req.email,
            password = "encoded_password",
            name = "테스트유저",
            role = Role.MEMBER,
            createdAt = OffsetDateTime.now()
        )
        val expectedToken = "jwt.token.value"
        val activityLogSlot = slot<ActivityLog>()

        every { userRepository.findByEmail(req.email) } returns existingUser
        every { passwordEncoder.matches(req.password, existingUser.password) } returns true
        every { jwtProvider.generateToken(existingUser.id!!, existingUser.role) } returns expectedToken
        every { activityLogRepository.save(capture(activityLogSlot)) } answers { activityLogSlot.captured }

        // WHEN
        val result = authService.login(req)

        // THEN
        assertNotNull(result)
        assertEquals(expectedToken, result.accessToken)

        verify(exactly = 1) { activityLogRepository.save(any()) }
        assertEquals(ActivityAction.LOGIN, activityLogSlot.captured.action)
        assertEquals(existingUser, activityLogSlot.captured.user)
    }

    // PLAN 4: 로그인 비밀번호 불일치
    @Test
    @DisplayName("비밀번호가 틀린 경우 UNAUTHORIZED 예외가 발생한다")
    fun `login with wrong password throws CustomException UNAUTHORIZED`() {
        // GIVEN
        val req = LoginRequest(
            email = "test@example.com",
            password = "wrong_password"
        )
        val existingUser = User(
            id = UUID.randomUUID(),
            email = req.email,
            password = "encoded_password",
            name = "테스트유저",
            role = Role.MEMBER,
            createdAt = OffsetDateTime.now()
        )

        every { userRepository.findByEmail(req.email) } returns existingUser
        every { passwordEncoder.matches(req.password, existingUser.password) } returns false

        // WHEN & THEN
        val exception = assertThrows<CustomException> {
            authService.login(req)
        }
        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode)

        verify(exactly = 0) { activityLogRepository.save(any()) }
        verify(exactly = 0) { jwtProvider.generateToken(any(), any()) }
    }

    // PLAN 4b: 로그인 존재하지 않는 이메일
    @Test
    @DisplayName("존재하지 않는 이메일로 로그인 시 UNAUTHORIZED 예외가 발생한다")
    fun `login with non-existing email throws CustomException UNAUTHORIZED`() {
        // GIVEN
        val req = LoginRequest(
            email = "nonexistent@example.com",
            password = "password123"
        )

        every { userRepository.findByEmail(req.email) } returns null

        // WHEN & THEN
        val exception = assertThrows<CustomException> {
            authService.login(req)
        }
        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode)

        verify(exactly = 0) { activityLogRepository.save(any()) }
        verify(exactly = 0) { jwtProvider.generateToken(any(), any()) }
    }
}
