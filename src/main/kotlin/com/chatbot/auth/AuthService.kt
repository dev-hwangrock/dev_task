package com.chatbot.auth

import com.chatbot.analytics.ActivityLogRepository
import com.chatbot.auth.dto.LoginRequest
import com.chatbot.auth.dto.SignupRequest
import com.chatbot.auth.dto.SignupResponse
import com.chatbot.auth.dto.TokenResponse
import com.chatbot.domain.analytics.ActivityAction
import com.chatbot.domain.analytics.ActivityLog
import com.chatbot.domain.user.User
import com.chatbot.global.exception.CustomException
import com.chatbot.global.exception.ErrorCode
import com.chatbot.global.security.JwtProvider
import com.chatbot.user.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
    private val activityLogRepository: ActivityLogRepository
) {

    @Transactional
    fun signup(req: SignupRequest): SignupResponse {
        if (userRepository.existsByEmail(req.email)) {
            throw CustomException(ErrorCode.DUPLICATE_EMAIL)
        }

        val user = User(
            email = req.email,
            password = passwordEncoder.encode(req.password),
            name = req.name
        )
        val saved = userRepository.save(user)

        activityLogRepository.save(
            ActivityLog(user = saved, action = ActivityAction.SIGNUP)
        )

        return SignupResponse(
            id = saved.id!!,
            email = saved.email,
            name = saved.name,
            role = saved.role.name,
            createdAt = saved.createdAt
        )
    }

    @Transactional
    fun login(req: LoginRequest): TokenResponse {
        val user = userRepository.findByEmail(req.email)
            ?: throw CustomException(ErrorCode.UNAUTHORIZED)

        if (!passwordEncoder.matches(req.password, user.password)) {
            throw CustomException(ErrorCode.UNAUTHORIZED)
        }

        activityLogRepository.save(
            ActivityLog(user = user, action = ActivityAction.LOGIN)
        )

        return TokenResponse(
            accessToken = jwtProvider.generateToken(user.id!!, user.role)
        )
    }
}
