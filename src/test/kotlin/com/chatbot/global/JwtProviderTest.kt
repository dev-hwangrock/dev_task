package com.chatbot.global

import com.chatbot.domain.user.Role
import com.chatbot.global.security.JwtProperties
import com.chatbot.global.security.JwtProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * TDD: RED → GREEN
 *
 * RED  단계: JwtProvider가 없을 때 컴파일/실행 실패
 * GREEN 단계: JwtProvider 구현 후 모든 케이스 통과
 *
 * secret: BASE64("test-secret-key-for-jwt-unit-test-1234567890") = 44바이트 > 32바이트(HMAC-SHA256 최소)
 */
class JwtProviderTest {

    private val testSecret = "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdW5pdC10ZXN0LTEyMzQ1Njc4OTA="
    private lateinit var jwtProvider: JwtProvider

    @BeforeEach
    fun setUp() {
        val properties = JwtProperties(secret = testSecret, expirationMs = 3_600_000L) // 1시간
        jwtProvider = JwtProvider(properties)
    }

    // PLAN:
    // GIVEN  유효한 userId(UUID), role(ADMIN)로 토큰 생성
    // WHEN   getUserId(token), getRole(token) 호출
    // THEN   생성 시 사용한 userId, role과 동일해야 한다
    @Test
    @DisplayName("토큰 생성 후 getUserId와 getRole이 원본 값과 일치해야 한다")
    fun `generateToken then getUserId and getRole return original values`() {
        val userId = UUID.randomUUID()
        val role = Role.ADMIN

        val token = jwtProvider.generateToken(userId, role)

        assertEquals(userId, jwtProvider.getUserId(token))
        assertEquals(role, jwtProvider.getRole(token))
    }

    // PLAN:
    // GIVEN  만료되지 않은 토큰
    // WHEN   validate(token) 호출
    // THEN   true 반환
    @Test
    @DisplayName("유효한 토큰의 validate는 true를 반환해야 한다")
    fun `validate returns true for a valid non-expired token`() {
        val token = jwtProvider.generateToken(UUID.randomUUID(), Role.MEMBER)

        assertTrue(jwtProvider.validate(token))
    }

    // PLAN:
    // GIVEN  정상 생성된 토큰의 마지막 5자리를 임의 문자로 교체(서명 변조)
    // WHEN   validate(tamperedToken) 호출
    // THEN   false 반환 (서명 불일치)
    @Test
    @DisplayName("서명이 변조된 토큰의 validate는 false를 반환해야 한다")
    fun `validate returns false for a tampered token`() {
        val token = jwtProvider.generateToken(UUID.randomUUID(), Role.MEMBER)
        val tamperedToken = token.dropLast(5) + "XXXXX"

        assertFalse(jwtProvider.validate(tamperedToken))
    }

    // PLAN:
    // GIVEN  expirationMs = -1000 (음수) 으로 이미 만료된 토큰 생성
    // WHEN   기본 jwtProvider.validate(expiredToken) 호출
    // THEN   false 반환 (ExpiredJwtException 포착)
    @Test
    @DisplayName("만료된 토큰의 validate는 false를 반환해야 한다")
    fun `validate returns false for an expired token`() {
        val expiredProperties = JwtProperties(secret = testSecret, expirationMs = -1_000L)
        val expiredProvider = JwtProvider(expiredProperties)
        val expiredToken = expiredProvider.generateToken(UUID.randomUUID(), Role.MEMBER)

        assertFalse(jwtProvider.validate(expiredToken))
    }
}
