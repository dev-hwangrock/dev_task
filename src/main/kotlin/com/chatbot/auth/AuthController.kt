package com.chatbot.auth

import com.chatbot.auth.dto.LoginRequest
import com.chatbot.auth.dto.SignupRequest
import com.chatbot.auth.dto.SignupResponse
import com.chatbot.auth.dto.TokenResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody req: SignupRequest): SignupResponse {
        return authService.signup(req)
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): TokenResponse {
        return authService.login(req)
    }
}
